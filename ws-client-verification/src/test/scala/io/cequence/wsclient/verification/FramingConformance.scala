package io.cequence.wsclient.verification

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import cats.effect.IO
import cats.effect.unsafe.implicits.{global => ioRuntime}
import com.typesafe.config.ConfigFactory
import io.cequence.wsclient.domain.{CequenceWSException, CequenceWSFrameTooLongException}
import io.cequence.wsclient.stream.Fs2Interop._
import io.cequence.wsclient.stream.SourceInterop._
import io.cequence.wsclient.stream.StreamTransformers.DelimiterFramer
import io.cequence.wsclient.stream.TransformPublisher
import io.cequence.wsclient.stream.ZioInterop._
import io.cequence.wsclient.stream.{AsyncStream, ByteFraming, Bytes}

import java.nio.ByteBuffer
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.Flow
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Random
import scala.util.control.NonFatal

/**
 * Delimiter framing, through the STAGES that run the framers - what the Lean drivers
 * (`Framing/ExtractedDrivers.lean`) mirror: `ByteFraming.delimiter` on all four backends (the
 * neutral interpreter and the Akka, fs2 and ZIO compilers: every backend must produce the same
 * frames and the same ending, else the case reports the difference and fails) against the
 * extracted `ByteFraming.DelimiterFramer`, and the engines'
 * `StreamTransformers.DelimiterFramer` run by a real `TransformPublisher` (a Reactive Streams
 * publisher of the chunks, a collecting subscriber) against the extracted engine framer.
 *
 * Inputs are built from runs of two or three byte values, whole delimiters and delimiter
 * prefixes (the delimiter is drawn from the same bytes, so partial and overlapping matches are
 * frequent); 30% are 200 - 1200 bytes long, cut into chunks of up to 400 bytes - beyond the
 * framers' initial 256-byte buffer, so compaction and growth run. Limits range from 1 to past
 * the input length; empty chunks occur.
 */
object FramingConformance extends LeanConformance.Target {

  val name = "framing"

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private val pool: Array[Byte] = "abx\r\n".getBytes("UTF-8")

  def generate(random: Random): (String, String) = {
    val alphabet = random.shuffle(pool.toSeq).take(2 + random.nextInt(2)).toArray
    def pick(): Byte = alphabet(random.nextInt(alphabet.length))

    val delimiter = Array.fill(1 + random.nextInt(4))(pick())
    val long = random.nextInt(10) < 3
    val length = if (long) 200 + random.nextInt(1000) else random.nextInt(25)
    val input = {
      val tokens = Iterator.continually {
        random.nextInt(4) match {
          case 0 => delimiter
          case 1 => delimiter.take(1 + random.nextInt(delimiter.length))
          case _ => Array.fill(1 + random.nextInt(if (long) 60 else 4))(pick())
        }
      }
      tokens.flatten.take(length).toArray
    }
    val chunks = chunk(input, random, long)
    val max = random.nextInt(4) match {
      case 0 => 1 + random.nextInt(4)
      case 1 => 1 + random.nextInt(input.length + 4)
      case 2 => 1 + random.nextInt(80)
      case _ => 5000
    }
    val allowTruncation = random.nextBoolean()

    val line = (Seq(
      "framing",
      LeanConformance.hex(delimiter),
      max.toString,
      if (allowTruncation) "1" else "0",
      chunks.size.toString
    ) ++ chunks.map(LeanConformance.hex)).mkString(" ")
    val expected =
      s"${byteFraming(delimiter, max, allowTruncation, chunks)} | ${engineFramer(delimiter, max, chunks)}"
    (line, expected)
  }

  private def chunk(
    input: Array[Byte],
    random: Random,
    long: Boolean
  ): Seq[Array[Byte]] = {
    val chunks = Seq.newBuilder[Array[Byte]]
    var at = 0
    while (at < input.length) {
      if (random.nextInt(10) == 0) chunks += Array.emptyByteArray
      val size = 1 + random.nextInt(if (long) 400 else 6)
      chunks += input.slice(at, at + size)
      at += size
    }
    if (random.nextInt(10) == 0) chunks += Array.emptyByteArray
    chunks.result()
  }

  private def result(
    frames: Seq[Array[Byte]],
    ending: String
  ): String =
    (Seq(ending, frames.size.toString) ++ frames.map(LeanConformance.hex)).mkString(" ")

  private lazy val system: ActorSystem =
    ActorSystem(
      "framing-conformance",
      ConfigFactory.parseString("akka.daemonic = on").withFallback(ConfigFactory.load())
    )

  private def endingOf(e: Throwable): String = e match {
    case _: CequenceWSFrameTooLongException => "tooLong"
    case _: CequenceWSException             => "truncated"
    case other                              => s"UNEXPECTED ${other.getClass.getSimpleName}"
  }

  // the stage on one backend: the frames emitted, then how it ended
  private type Run = AsyncStream[Bytes] => (Vector[Array[Byte]], String)

  private val backends: Vector[(String, Run)] = Vector(
    "neutral" -> { stream =>
      val frames = Vector.newBuilder[Array[Byte]]
      val ending =
        try {
          Await.result(stream.runForeach(frame => frames += frame.toArray), 30.seconds)
          "completed"
        } catch { case NonFatal(e) => endingOf(e) }
      (frames.result(), ending)
    },
    "akka" -> { stream =>
      val frames = Vector.newBuilder[Array[Byte]]
      val ending =
        try {
          Await.result(
            stream.toSource.runWith(Sink.foreach[Bytes](frame => frames += frame.toArray))(
              akka.stream.Materializer.matFromSystem(system)
            ),
            30.seconds
          )
          "completed"
        } catch { case NonFatal(e) => endingOf(e) }
      (frames.result(), ending)
    },
    "fs2" -> { stream =>
      val frames = Vector.newBuilder[Array[Byte]]
      val ending =
        try {
          val drained =
            stream.toFs2[IO].evalMap(frame => IO { frames += frame.toArray; () }).compile.drain
          if (drained.unsafeRunTimed(30.seconds).isDefined) "completed" else "HANG"
        } catch { case NonFatal(e) => endingOf(e) }
      (frames.result(), ending)
    },
    "zio" -> { stream =>
      val frames = Vector.newBuilder[Array[Byte]]
      val effect = stream.toZStream
        .runForeach(frame => zio.ZIO.succeed { frames += frame.toArray; () })
        .timeout(zio.Duration.fromScala(30.seconds))
      val ending =
        zio.Unsafe.unsafe(implicit unsafe => zio.Runtime.default.unsafe.run(effect)) match {
          case zio.Exit.Success(Some(_)) => "completed"
          case zio.Exit.Success(None)    => "HANG"
          case zio.Exit.Failure(cause) =>
            cause.failureOption
              .map(endingOf)
              .getOrElse(s"DEFECT ${cause.prettyPrint.take(80)}")
        }
      (frames.result(), ending)
    }
  )

  // The neutral interpreter, fs2 and ZIO must agree exactly. Akka's stateful stage is
  // fail-fast (the sanctioned difference of the operator spec): when the run FAILS, frames the
  // stage still holds are dropped, so its frames are a prefix of the others' - with the same
  // ending - and when it completes they are identical. The neutral outcome is what is reported.
  private def byteFraming(
    delimiter: Array[Byte],
    max: Int,
    allowTruncation: Boolean,
    chunks: Seq[Array[Byte]]
  ): String = {
    def stream =
      AsyncStream
        .fromIterable(chunks.map(Bytes(_)).toList)
        .via(ByteFraming.delimiter(Bytes(delimiter), max, allowTruncation))
    val outcomes = backends.map { case (name, run) => name -> run(stream) }
    val (_, (neutralFrames, neutralEnding)) = outcomes.head
    def same(o: (Vector[Array[Byte]], String)): Boolean =
      o._2 == neutralEnding && o._1.map(_.toSeq) == neutralFrames.map(_.toSeq)
    def prefixOf(o: (Vector[Array[Byte]], String)): Boolean =
      o._2 == neutralEnding && o._1.size <= neutralFrames.size &&
        o._1.map(_.toSeq) == neutralFrames.take(o._1.size).map(_.toSeq)
    val ok = outcomes.tail.forall {
      case ("akka", o) => if (neutralEnding == "completed") same(o) else prefixOf(o)
      case (_, o)      => same(o)
    }
    if (ok) result(neutralFrames, neutralEnding)
    else
      s"BACKENDS DIFFER ${outcomes.map { case (n, (f, e)) => s"$n: ${result(f, e).take(120)}" }.mkString("; ")}"
  }

  // a Reactive Streams publisher of the chunks: emits on demand, re-entrancy safe
  private final class ChunkPublisher(chunks: Seq[ByteBuffer])
      extends Flow.Publisher[ByteBuffer] {
    override def subscribe(subscriber: Flow.Subscriber[_ >: ByteBuffer]): Unit = {
      val iterator = chunks.iterator
      var demand = 0L
      var emitting = false
      var finished = false
      subscriber.onSubscribe(new Flow.Subscription {
        override def request(n: Long): Unit = {
          val drain = synchronized {
            demand += n
            if (emitting) false
            else { emitting = true; true }
          }
          if (drain) {
            var go = true
            while (go) {
              val next = synchronized {
                if (finished) None
                else if (!iterator.hasNext) { finished = true; Some(None) }
                else if (demand > 0) { demand -= 1; Some(Some(iterator.next())) }
                else { emitting = false; None }
              }
              next match {
                case None             => go = false
                case Some(None)       => subscriber.onComplete(); go = false
                case Some(Some(item)) => subscriber.onNext(item)
              }
            }
          }
        }
        override def cancel(): Unit = synchronized { finished = true }
      })
    }
  }

  // the engines' framer, run by the real TransformPublisher
  private def engineFramer(
    delimiter: Array[Byte],
    max: Int,
    chunks: Seq[Array[Byte]]
  ): String = {
    val publisher = new TransformPublisher[ByteBuffer, ByteBuffer](
      new ChunkPublisher(chunks.map(ByteBuffer.wrap)),
      () => new DelimiterFramer(delimiter, max)
    )
    val frames = Vector.newBuilder[Array[Byte]]
    val done = new CountDownLatch(1)
    @volatile var ending = "HANG"
    publisher.subscribe(new Flow.Subscriber[ByteBuffer] {
      private var subscription: Flow.Subscription = _
      override def onSubscribe(s: Flow.Subscription): Unit = {
        subscription = s
        s.request(1)
      }
      override def onNext(buffer: ByteBuffer): Unit = {
        val array = new Array[Byte](buffer.remaining())
        buffer.get(array)
        frames += array
        subscription.request(1)
      }
      override def onError(t: Throwable): Unit = { ending = endingOf(t); done.countDown() }
      override def onComplete(): Unit = { ending = "completed"; done.countDown() }
    })
    if (!done.await(30, TimeUnit.SECONDS)) ending = "HANG"
    result(frames.result(), ending)
  }
}
