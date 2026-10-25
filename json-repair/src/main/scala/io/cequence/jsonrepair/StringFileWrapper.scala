package io.cequence.jsonrepair

import java.io.{File, RandomAccessFile}
import java.nio.charset.Charset
import scala.annotation.nowarn

/**
 * A wrapper for file handling that makes it behave like a string. This simplifies the code by
 * transforming file descriptor handling into string handling.
 *
 * The file is decoded once, as a whole, in the platform charset - the charset the standard
 * parse attempt of `JsonRepair.fromFile` reads it in - and indexed by CHARACTER. (Up to 1.1.2
 * it was read in byte chunks indexed by byte offset: a file with any multibyte character
 * failed with a `StringIndexOutOfBoundsException`, and a character split across two chunks was
 * decoded as two replacement characters. The chunks saved no memory either: the standard parse
 * attempt reads the whole file anyway, and the repaired value holds its content.)
 *
 * @param file
 *   The file to wrap
 * @param chunkLength
 *   No longer used (the file is decoded at once); kept for source compatibility
 */
class StringFileWrapper(
  file: File,
  @nowarn("msg=never used") chunkLength: Int = 0
) {

  private val content: String = {
    val fd = new RandomAccessFile(file, "r")
    try {
      val size = fd.length()
      if (size > Int.MaxValue - 8)
        throw new IllegalArgumentException(
          s"File too large to repair: $size bytes (at most 2 GiB are supported)."
        )
      val bytes = new Array[Byte](size.toInt)
      fd.readFully(bytes)
      new String(bytes, Charset.defaultCharset())
    } finally fd.close()
  }

  /**
   * Get the character at the specified index.
   *
   * @param index
   *   The index of the character to retrieve
   * @return
   *   The character at the specified index
   */
  def apply(index: Int): Char = content.charAt(index)

  /**
   * Get a slice of characters from the file.
   *
   * @param start
   *   The start index (inclusive)
   * @param end
   *   The end index (exclusive)
   * @return
   *   The substring from start to end
   */
  def slice(
    start: Int,
    end: Int
  ): String = content.substring(start, end)

  /**
   * Get the total length of the file.
   *
   * @return
   *   The total number of characters in the file
   */
  def length: Long = content.length.toLong

  /**
   * Close the file descriptor (a no-op: the file is closed once it is read).
   */
  def close(): Unit = ()
}
