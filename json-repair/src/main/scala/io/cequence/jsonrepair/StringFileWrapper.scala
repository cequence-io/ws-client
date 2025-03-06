package io.cequence.jsonrepair

import java.io.{File, RandomAccessFile}
import scala.collection.mutable

/**
 * A wrapper for file handling that makes it behave like a string.
 * This simplifies the code by transforming file descriptor handling into string handling.
 *
 * @param file The file to wrap
 * @param chunkLength The length of each chunk to read from the file (in bytes)
 */
class StringFileWrapper(file: File, chunkLength: Int = 0) {
  private val fd = new RandomAccessFile(file, "r")
  private var _length: Long = -1
  
  // Buffers are chunks of strings that are read from the file
  // and kept in memory to keep reads low
  private val buffers: mutable.Map[Int, String] = mutable.Map.empty
  
  // Default chunk length is 1MB if not specified or too small
  private val bufferLength: Int = if (chunkLength < 2) 1000000 else chunkLength
  
  /**
   * Retrieve or load a buffer chunk from the file.
   *
   * @param index The index of the buffer chunk to retrieve
   * @return The buffer chunk at the specified index
   */
  private def getBuffer(index: Int): String = {
    buffers.getOrElseUpdate(index, {
      fd.seek(index.toLong * bufferLength)
      val bytes = new Array[Byte](bufferLength)
      val bytesRead = fd.read(bytes)
      
      // If we read less than the buffer size, trim the array
      val result = if (bytesRead < bufferLength) {
        new String(bytes.take(bytesRead))
      } else {
        new String(bytes)
      }
      
      // Save memory by keeping max 2MB buffer chunks and min 2 chunks
      if (buffers.size > math.max(2, 2000000 / bufferLength)) {
        val oldestKey = buffers.keys.min
        if (oldestKey != index) {
          buffers.remove(oldestKey)
        }
      }
      
      result
    })
  }
  
  /**
   * Get the character at the specified index.
   *
   * @param index The index of the character to retrieve
   * @return The character at the specified index
   */
  def apply(index: Int): Char = {
    val bufferIndex = index / bufferLength
    getBuffer(bufferIndex)(index % bufferLength)
  }
  
  /**
   * Get a slice of characters from the file.
   *
   * @param start The start index (inclusive)
   * @param end The end index (exclusive)
   * @return The substring from start to end
   */
  def slice(start: Int, end: Int): String = {
    val bufferStart = start / bufferLength
    val bufferEnd = end / bufferLength
    
    if (bufferStart == bufferEnd) {
      getBuffer(bufferStart).substring(start % bufferLength, end % bufferLength)
    } else {
      val startSlice = getBuffer(bufferStart).substring(start % bufferLength)
      val endSlice = getBuffer(bufferEnd).substring(0, end % bufferLength)
      val middleSlices = (bufferStart + 1 until bufferEnd).map(getBuffer)
      
      startSlice + middleSlices.mkString + endSlice
    }
  }
  
  /**
   * Get the total length of the file.
   *
   * @return The total number of characters in the file
   */
  def length: Long = {
    if (_length < 0) {
      val currentPosition = fd.getFilePointer
      fd.seek(fd.length())
      _length = fd.getFilePointer
      fd.seek(currentPosition)
    }
    _length
  }
  
  /**
   * Close the file descriptor.
   */
  def close(): Unit = {
    fd.close()
  }
} 