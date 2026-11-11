package io.cequence.wsclient.stream

import java.nio.ByteBuffer
import java.nio.charset.{Charset, StandardCharsets}

/**
 * An immutable sequence of bytes - the element of binary streams (raw HTTP bodies, downloads).
 * `slice`, `take`, `drop` and `splitAt` are O(1) views; `++` copies. Equality is by content;
 * `toString` shows the length and a short hex prefix, never the whole (remote) content.
 *
 * Conversions to and from Akka / Pekko `ByteString` wrap without copying (see the interop
 * modules).
 */
final class Bytes private (
  private val array: Array[Byte],
  private val offset: Int,
  val length: Int
) {

  def apply(i: Int): Byte = {
    if (i < 0 || i >= length)
      throw new IndexOutOfBoundsException(s"Index $i out of bounds for length $length")
    array(offset + i)
  }

  def isEmpty: Boolean = length == 0

  def nonEmpty: Boolean = length > 0

  /**
   * The bytes `from` (inclusive) until `until` (exclusive), clamped to the bounds - a view.
   */
  def slice(
    from: Int,
    until: Int
  ): Bytes = {
    val start = math.min(math.max(from, 0), length)
    val end = math.min(math.max(until, start), length)
    if (start == 0 && end == length)
      this
    else
      new Bytes(array, offset + start, end - start)
  }

  def take(n: Int): Bytes = slice(0, n)

  def drop(n: Int): Bytes = slice(n, length)

  def splitAt(n: Int): (Bytes, Bytes) = (take(n), drop(n))

  /** A copy holding both. */
  def ++(that: Bytes): Bytes =
    if (that.isEmpty)
      this
    else if (isEmpty)
      that
    else {
      val joined = new Array[Byte](length + that.length)
      System.arraycopy(array, offset, joined, 0, length)
      System.arraycopy(that.array, that.offset, joined, length, that.length)
      new Bytes(joined, 0, joined.length)
    }

  /** The index of the first `b` at or after `from`, or -1. */
  def indexOf(
    b: Byte,
    from: Int = 0
  ): Int = {
    var i = math.max(from, 0)
    while (i < length) {
      if (array(offset + i) == b) return i
      i += 1
    }
    -1
  }

  /**
   * The index of the first occurrence of `that` at or after `from`, or -1 (`from` for an empty
   * `that`).
   */
  def indexOfSlice(
    that: Bytes,
    from: Int = 0
  ): Int = {
    val start = math.max(from, 0)
    if (that.isEmpty)
      math.min(start, length)
    else {
      val limit = length - that.length
      val first = that.array(that.offset)
      var i = start
      while (i <= limit) {
        if (array(offset + i) == first && regionMatches(i, that)) return i
        i += 1
      }
      -1
    }
  }

  def startsWith(that: Bytes): Boolean =
    that.length <= length && regionMatches(0, that)

  private def regionMatches(
    at: Int,
    that: Bytes
  ): Boolean = {
    var j = 0
    while (j < that.length) {
      if (array(offset + at + j) != that.array(that.offset + j)) return false
      j += 1
    }
    true
  }

  /** A copy. */
  def toArray: Array[Byte] = java.util.Arrays.copyOfRange(array, offset, offset + length)

  /** A read-only view (no copy). */
  def asByteBuffer: ByteBuffer =
    ByteBuffer.wrap(array, offset, length).slice().asReadOnlyBuffer()

  def utf8String: String = decodeString(StandardCharsets.UTF_8)

  def decodeString(charset: Charset): String = new String(array, offset, length, charset)

  /** The backing array, offset and length - for zero-copy conversions; never modify it. */
  private[wsclient] def unsafeArray: Array[Byte] = array
  private[wsclient] def unsafeOffset: Int = offset

  override def equals(other: Any): Boolean = other match {
    case that: Bytes =>
      (this eq that) || (length == that.length && regionMatches(0, that))
    case _ => false
  }

  override def hashCode(): Int = {
    var h = 1
    var i = 0
    while (i < length) {
      h = 31 * h + array(offset + i)
      i += 1
    }
    h
  }

  override def toString: String = {
    val shown = math.min(length, Bytes.ToStringPrefix)
    val hex = new StringBuilder(shown * 2)
    var i = 0
    while (i < shown) {
      val b = array(offset + i) & 0xff
      hex.append(Character.forDigit(b >>> 4, 16)).append(Character.forDigit(b & 0xf, 16))
      i += 1
    }
    val ellipsis = if (length > shown) "..." else ""
    s"Bytes($length bytes: $hex$ellipsis)"
  }
}

object Bytes {

  private val ToStringPrefix = 8

  /**
   * The default bound of a collected download: 256 MiB (as the engines' decompression limit).
   */
  val DefaultMaxBytes: Long = 256L * 1024 * 1024

  /** Operations on byte streams. */
  implicit final class BytesStreamOps(private val stream: AsyncStream[Bytes]) extends AnyVal {

    /**
     * Runs the stream and collects its bytes - at most `maxBytes`: a longer stream is
     * cancelled and fails with a [[io.cequence.wsclient.domain.CequenceWSException]]; a
     * negative `maxBytes` fails the future with an `IllegalArgumentException`.
     */
    def runToBytes(
      maxBytes: Long = DefaultMaxBytes
    )(
      implicit ec: scala.concurrent.ExecutionContext
    ): scala.concurrent.Future[Bytes] =
      if (maxBytes < 0)
        scala.concurrent.Future.failed(
          new IllegalArgumentException(s"maxBytes must not be negative: $maxBytes")
        )
      else
        collect(maxBytes)

    private def collect(
      maxBytes: Long
    )(
      implicit ec: scala.concurrent.ExecutionContext
    ): scala.concurrent.Future[Bytes] =
      stream
        .stateful(() => new Builder)(
          (
            builder,
            chunk
          ) =>
            if (builder.length.toLong + chunk.length > maxBytes)
              throw new io.cequence.wsclient.domain.CequenceWSException(
                s"The response body exceeds the limit of $maxBytes bytes"
              )
            else
              AsyncStream.Step(builder += chunk),
          builder => builder.result() :: Nil
        )
        .runHead
  }

  val empty: Bytes = new Bytes(Array.emptyByteArray, 0, 0)

  /** A copy of `a`. */
  def apply(a: Array[Byte]): Bytes =
    if (a.length == 0)
      empty
    else
      new Bytes(a.clone(), 0, a.length)

  /** Wraps `a` WITHOUT copying - the caller must never modify the wrapped range afterwards. */
  def unsafeWrap(
    a: Array[Byte],
    off: Int,
    len: Int
  ): Bytes = {
    if (off < 0 || len < 0 || off > a.length - len)
      throw new IndexOutOfBoundsException(
        s"Range [$off, ${off.toLong + len}) out of bounds for length ${a.length}"
      )
    if (len == 0)
      empty
    else
      new Bytes(a, off, len)
  }

  def unsafeWrap(a: Array[Byte]): Bytes = unsafeWrap(a, 0, a.length)

  /** A copy of the buffer's remaining bytes; the buffer's position is not changed. */
  def fromByteBuffer(b: ByteBuffer): Bytes = {
    val copy = new Array[Byte](b.remaining())
    b.duplicate().get(copy)
    if (copy.length == 0) empty else new Bytes(copy, 0, copy.length)
  }

  def utf8(s: String): Bytes = unsafeWrap(s.getBytes(StandardCharsets.UTF_8))

  def concat(parts: Seq[Bytes]): Bytes = {
    val builder = new Builder
    parts.foreach(builder += _)
    builder.result()
  }

  /** Accumulates `Bytes` into one (amortized linear). */
  final class Builder {
    private var buffer = new Array[Byte](64)
    private var size = 0

    def +=(b: Bytes): this.type = {
      if (b.nonEmpty) {
        ensureCapacity(size.toLong + b.length)
        System.arraycopy(b.array, b.offset, buffer, size, b.length)
        size += b.length
      }
      this
    }

    def length: Int = size

    // a full buffer is handed over as is: a later append grows (copies) it first
    def result(): Bytes =
      if (size == 0) empty
      else if (size == buffer.length) new Bytes(buffer, 0, size)
      else new Bytes(java.util.Arrays.copyOf(buffer, size), 0, size)

    private def ensureCapacity(needed: Long): Unit =
      if (needed > buffer.length) {
        if (needed > Int.MaxValue - 8)
          throw new IllegalStateException(
            s"Bytes.Builder: $needed bytes exceed the array limit"
          )
        val grown = math.max(needed, math.min(buffer.length.toLong * 2, Int.MaxValue - 8L))
        buffer = java.util.Arrays.copyOf(buffer, grown.toInt)
      }
  }
}
