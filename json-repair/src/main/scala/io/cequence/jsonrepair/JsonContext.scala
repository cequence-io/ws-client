package io.cequence.jsonrepair

/**
 * Enumeration of context values for JSON parsing.
 */
object ContextValues extends Enumeration {
  type ContextValue = Value
  val ObjectKey, ObjectValue, Array = Value
}

/**
 * Class to track the current context during JSON parsing.
 * This helps manage special cases like missing quotes in keys or values.
 */
class JsonContext {
  import ContextValues._
  
  private var contextStack: List[ContextValue] = Nil
  private var _current: Option[ContextValue] = None
  private var _empty: Boolean = true
  
  /**
   * Get the current context value.
   * @return The current context value, if any.
   */
  def current: Option[ContextValue] = _current

  def contains(value: ContextValue): Boolean = contextStack.contains(value)

  /**
   * Check if the context is empty.
   * @return True if the context is empty, false otherwise.
   */
  def empty: Boolean = _empty
  
  /**
   * Set a new context value.
   * @param value The context value to be added.
   */
  def set(value: ContextValue): Unit = {
    contextStack = value :: contextStack
    _current = Some(value)
    _empty = false
  }
  
  /**
   * Remove the most recent context value.
   */
  def reset(): Unit = {
    contextStack match {
      case Nil =>
        _current = None
        _empty = true
      case _ :: Nil =>
        contextStack = Nil
        _current = None
        _empty = true
      case _ :: tail =>
        contextStack = tail
        _current = Some(tail.head)
    }
  }
} 