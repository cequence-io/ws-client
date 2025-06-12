package io.cequence.jsonrepair

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks.break

/**
 * Type alias for JSON return types
 */
object JsonTypes {
  type JsonValue = Any
  type JsonObject = Map[String, JsonValue]
}

/**
 * Parser for JSON strings with repair capabilities. This class implements a parser that
 * follows the BNF definition of JSON and can repair invalid JSON by using heuristics.
 *
 * @param jsonStr
 *   The JSON string to parse
 * @param logging
 *   Whether to log repair actions
 */
class JsonParser(
  private var jsonStr: Either[String, StringFileWrapper],
  private val logging: Boolean = false,
  private val handleLiterals: Boolean = false
) {
  import ContextValues._
  import JsonTypes._

  // Constants
  private val StringDelimiters = Seq('"', '\'', '"', '”')

  private val NumberChars =
    Set('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '-', 'e', 'E', '/', ',')

  private val CHAR_NONE = '\u0000'

  // Index is our iterator that will keep track of which character we are looking at right now
  private var index: Int = 0

  // This is used in the object member parsing to manage the special cases of missing quotes in key or value
  private val context = new JsonContext()

  // Logger for repair actions
  private val logger: ListBuffer[Map[String, String]] = if (logging) ListBuffer.empty else null

  /**
   * Log a repair action.
   *
   * @param message
   *   The message to log
   */
  private def log(message: String): Unit = {
    if (logging) {
      logger += Map("message" -> message)
    }
  }

  /**
   * Get the character at the current index.
   *
   * @return
   *   The character at the current index, or None if at the end of the string
   */
  private def getCharAt(offset: Int = 0): Option[Char] = {
    val i = index + offset
    if (i >= length) {
      None
    } else {
      jsonStr match {
        case Left(str) =>
          Some(str(i))
        case Right(fileWrapper) =>
          Some(fileWrapper(i))
      }
    }
  }

  /**
   * Get the length of the JSON string or file.
   *
   * @return
   *   The length of the JSON string or file
   */
  private def length: Int = {
    jsonStr match {
      case Left(str)          => str.length
      case Right(fileWrapper) => fileWrapper.length.toInt
    }
  }

  /**
   * Get a substring of the JSON string or file.
   *
   * @param start
   *   The start index (inclusive)
   * @param end
   *   The end index (exclusive)
   * @return
   *   The substring from start to end
   */
  private def substring(
    start: Int,
    end: Int
  ): String = {
    jsonStr match {
      case Left(str)          => str.substring(start, end)
      case Right(fileWrapper) => fileWrapper.slice(start, end)
    }
  }

  /**
   * Parse the JSON string or file.
   *
   * @return
   *   The parsed JSON value, or a tuple with the parsed JSON value and repair log if logging
   *   is enabled
   */
  def parse(): (JsonValue, List[Map[String, String]]) = {
    val json = parseJson()

    // Check if there's more JSON elements after the first one
    if (index < length) {
      log("The parser returned early, checking if there's more json elements")

      var jsonArray = List(json)
      var lastIndex = index

      while (index < length) {
        val j = parseJson()
        if (j.toString.nonEmpty) {
          jsonArray = jsonArray :+ j
        }
        if (index == lastIndex) {
          index += 1
        }
        lastIndex = index
      }

      val nonEmptyElements = jsonArray.filterNot(elem => elem == "")

      // If nothing extra was found, don't return an array
      val result = if (nonEmptyElements.size == 1) {
        log("There were no more elements, returning the element without the array")
        nonEmptyElements.head
      } else {
        nonEmptyElements
      }

      if (logging) {
        (result, logger.toList)
      } else {
        (result, List.empty)
      }
    } else {
      if (logging) {
        (json, logger.toList)
      } else {
        (json, List.empty)
      }
    }
  }

  /**
   * Parse a JSON value.
   *
   * @return
   *   The parsed JSON value
   */
  private def parseJson(): JsonValue = {
    while (true) {
      getCharAt() match {
        case None =>
          return ""

        // <object> starts with '{'
        case Some('{') =>
          index += 1
          return parseObject()

        // <array> starts with '['
        case Some('[') =>
          index += 1
          return parseArray()

        // Edge case: empty value at end of object
        case Some('}') if context.current.contains(ObjectValue) =>
          log("At the end of an object we found a key with missing value, skipping")
          return ""

        // <string> starts with a quote or alpha character in non-empty context
        // not self.context.empty and (char in self.STRING_DELIMITERS or char.isalpha()
        case Some(c) if !context.empty && (StringDelimiters.contains(c) || c.isLetter) =>
          return parseString()

        // <number> starts with digit or minus
        // not self.context.empty and (char.isdigit() or char == "-" or char == "."
        case Some(c) if !context.empty && (c.isDigit || c == '-' || c == '.') =>
          return parseNumber()

        // Handle comments
        case Some('#') | Some('/') =>
          return parseComment()

        // <boolean> or <null> starts with letter
        case Some(c) if c.isLetter && handleLiterals =>
          return parseLiteral()

        // Skip whitespace and other characters
        case Some(_) =>
          index += 1
      }
    }

    // This should never be reached
    ""
  }

  /**
   * Parses an object-like structure, closely mirroring the Python logic:
   *
   * parse_object -> { while current char != '}': skip whitespace if current char == ':', log
   * and skip it set context to OBJECT_KEY parse string as key handle duplicates if in array
   * context skip whitespace if current char == '}', continue skip whitespace if current char
   * != ':', log missing colon skip 1 char for colon set context to OBJECT_VALUE parse json
   * value reset context store key -> value if next char in [",", "'", "\""], skip it skip
   * whitespace skip final '}' return object map
   */
  def parseObject(): JsonObject = {
    var obj = Map.empty[String, JsonValue]

    // This condition mimics (self.get_char_at() or '}') != '}'
    // i.e., continue while current char is not '}' or is None
    scala.util.control.Breaks.breakable {
      while (getCharAt().getOrElse('}') != '}') {
        // Skip filler whitespace
        skipWhitespaces()

        // If the current character is ':' before we've found a key, treat it as an error but skip it
        if (getCharAt().contains(':')) {
          log("While parsing an object we found a : before a key, ignoring")
          index += 1
        }

        // We are now searching for the string key
        context.set(ContextValues.ObjectKey)

        // Save this index in case we detect a duplicate key
        var rollbackIndex = index
        var key = ""

        scala.util.control.Breaks.breakable {
          // <member> starts with a <string>
          while (getCharAt().isDefined) {
            // Update rollback index in case the key is empty
            rollbackIndex = index
            key = parseString().toString
            if (key.isEmpty) {
              // If the key was empty, skip more whitespace
              skipWhitespaces()
            }
            // Break out if we found a non-empty key OR if key is "" but next char is ':' or '}'
            val nextChar = getCharAt()
            if (
              key.nonEmpty || (key.isEmpty && (nextChar
                .contains(':') || nextChar.contains('}')))
            ) {
              // We can break the while loop here:
              // in Scala you can simulate a `break` by returning or using a boolean
              // but for brevity, just do a manual check:
              break()
            }
          }
        }

        // If we are in an ARRAY context and the key already exists, forcibly close the object
        if (context.contains(ContextValues.Array) && obj.contains(key)) {
          log(
            "While parsing an object we found a duplicate key, closing the object here and rolling back the index"
          )
          index = rollbackIndex - 1

          // Add an opening curly brace to the input string (like the Python code does)
          if (index + 1 <= length) {
            jsonStr = Left(substring(0, index + 1) + "{" + substring(index + 1, length))
          }

          // Then break out of the object parse
          break();
        }

        // Skip filler whitespaces
        skipWhitespaces()

        // If we see '}', continue the while loop (equivalent to `continue` in Python)
        if (getCharAt().getOrElse('}') == '}') {
          // Move on to the loop condition again
          // (in Python: `continue`)
          // We'll do it by skipping to the next iteration
          // Just do nothing; let the while re-check its condition
        } else {
          skipWhitespaces()

          // An extreme case of missing ':' after a key
          if (!getCharAt().contains(':')) {
            log("While parsing an object we missed a : after a key")
          }
          // Skip the colon if present
          index += 1

          // Now parse the value
          context.reset()
          context.set(ContextValues.ObjectValue)
          val value = parseJson()

          // Reset context since our job is done
          context.reset()

          obj = obj + (key -> value)

          // If next char is ',', '"' or '\'', skip it
          getCharAt() match {
            case Some(',') | Some('"') | Some('\'') => index += 1
            case _                                  =>
          }

          // Remove trailing spaces
          skipWhitespaces()
        }
      }
    }

    index += 1
    obj
  }

  /** Skip consecutive whitespace characters. */
  private def skipWhitespaces(): Unit = {
    while (getCharAt().exists(_.isWhitespace)) {
      index += 1
    }
  }

  def parseArray(): List[JsonValue] = {
    var arr = List.empty[JsonValue]

    // Enter ARRAY context (if your context logic requires it)
    context.set(ContextValues.Array)

    // Grab the initial character
    var charOpt = getCharAt()

    // while current char is defined and not ']' or '}'
    while (charOpt.isDefined && !Set(']', '}').contains(charOpt.get)) {
      // 1) Skip whitespace
      skipWhitespacesAt()

      // 2) Parse next JSON value
      val value = parseJson() // could be "", "...", or a valid JsonValue

      // 3) Check for corner cases
      value match {
        case null =>
          // index += 1
          arr = arr :+ null

        case "" =>
          // "It is possible that parse_json() returns nothing valid, so we increase by 1"
          index += 1

        case s: String if s == "..." =>
          // If the returned value was literally the string "..."
          // Check if the last consumed character was '.'
          if ((index - 1) >= 0 && getCharAt(-1).contains('.')) {
            log("While parsing an array, found a stray '...'; ignoring it")
          } else {
            // If the last character isn't '.', treat "..." as a legitimate string
            arr = arr :+ s
          }

        case parsedValue: JsonValue =>
          // If parseJson() returned a valid value (including possibly an empty string-literal)
          arr = arr :+ parsedValue
      }

      // 4) After reading a value, skip over any whitespace or commas before the next item
      charOpt = getCharAt()
      while (charOpt.isDefined && (charOpt.get.isWhitespace || charOpt.contains(','))) {
        index += 1
        charOpt = getCharAt()
      }
    }

    // If we stopped because we saw '}' or ran out of input, but not a ']',
    // log that we missed a proper closing bracket
    if (charOpt.isDefined && charOpt.get != ']') {
      log("While parsing an array we missed the closing ], ignoring it")
    }

    // Move past the closing bracket if it exists (or anything else if the bracket is missing)
    index += 1

    // Reset context after finishing
    context.reset()

    arr
  }

  /**
   * Scala version of the big Python parse_string() method. Returns either:
   *   - String
   *   - Boolean
   *   - null (representing Python's None)
   *
   * Reflects the Python code's flow and corner-case handling.
   */
  def parseString(): Any = {
    // PART I
    // Check if the current char is a comment trigger (# or /).
    getCharAt() match {
      case Some('#') | Some('/') =>
        // Python calls parse_comment() and returns that result immediately.
        return parseComment()
      case _ =>
      // Not a comment, proceed.
    }

    // We attempt to find a valid left delimiter or detect missing quotes.
    // We'll track these flags as in Python:
    var missing_quotes = false
    var doubled_quotes = false
    var lstring_delimiter: Char = '"'
    var rstring_delimiter: Char = '"'

    // skip any leading junk until we find a potential delimiter or alphanumeric
    var charOpt = getCharAt()

    while (
      charOpt.isDefined &&
      !StringDelimiters.contains(charOpt.get) &&
      !charOpt.get.isLetterOrDigit
    ) {
      index += 1
      charOpt = getCharAt()
    }

    if (charOpt.isEmpty) {
      // No more characters => empty string
      return ""
    }

    var char: Char = charOpt.get

    // Decide actual delimiters
    if (char == '\'') {
      lstring_delimiter = '\''
      rstring_delimiter = '\''
    } else if (char == '“') {
      lstring_delimiter = '“'
      rstring_delimiter = '”'
    } else if (char.isLetterOrDigit) {
      // Possibly boolean, null, or a missing left-quote scenario
      // Python checks if t/f/n and context != OBJECT_KEY => parse boolean/null
      if (
        (char == 't' || char == 'f' || char == 'n')
        && !context.current.contains(ContextValues.ObjectKey)
      ) {
        val value = parseBooleanOrNull()
        if (value.isDefined) {
          return value.get // could be true/false/null
        }
      }
      log(
        "While parsing a string, found a literal instead of a quote => missing_quotes = true"
      )
      missing_quotes = true
    }

    if (!missing_quotes) {
      // We consume the left quote if it's not "missing"
      index += 1
    }

    // PART II

    // Check if the *next* character is also a delimiter => potential doubled quotes
    getCharAt() match {
      case Some(nextChar) if StringDelimiters.contains(nextChar) =>
        if (nextChar == lstring_delimiter) {
          // This might be a scenario of `""...` or `''...`
          // See Python code that tries to detect empty key or doubled quotes
          if (
            context.current.contains(ContextValues.ObjectKey) && getCharAt(1).contains(':')
          ) {
            // This is an empty key
            index += 1 // skip the second quote
            return ""
          }

          // Check if the next character is also the left delimiter
          if (getCharAt(1).contains(lstring_delimiter)) {
            // There's something fishy about this, we found doubled quotes and then again quotes
            log(
              "While parsing a string, we found a doubled quote and then a quote again, ignoring it"
            )
            return ""
          }

          // Find the next right delimiter
          val i = skipToCharacter(rstring_delimiter, offset = 1)
          val nextC = getCharAt(i)
          // If nextC is the same quote, we treat it as "doubled quotes"
          if (nextC.isDefined && getCharAt(i + 1).contains(rstring_delimiter)) {
            log("While parsing a string, found a valid starting doubled quote")
            doubled_quotes = true
            index += 1
          } else {
            // Additional checks or partial repairs
            // (mirroring the Python logic of deciding if there's a "mistake" or "ignore it")
            // i2 is an offset
            val i2 = skipWhitespacesAt(startOffset = 1, moveMainIndex = false)
            val next2 = getCharAt(i2)
            if (next2.exists(ch => StringDelimiters.contains(ch) || ch == '{' || ch == '[')) {
              log("Found a doubled quote but also another delimiter afterwards, ignoring.")
              index += 1
              return ""
            } else if (!Set(',', ']', '}').contains(next2.getOrElse('X'))) {
              log("Found a doubled quote but it was a mistake => removing one quote.")
              index += 1
            }
          }
        } else {
          // nextChar is a different type of quote than the left one, do a check
          // i is an offset
          val i = skipToCharacter(rstring_delimiter, offset = 1)
          val nextC = getCharAt(i)
          if (nextC.isEmpty) {
            log("Found a quote but it never repeats => ignoring.")
            return ""
          }
        }

      case _ =>
        // no second quote scenario
    }

    // PART III

    // Now we accumulate the string, stopping if we find rstring_delimiter or special cases.
    val sb = new StringBuilder

    char = getCharAt().getOrElse(CHAR_NONE)
    var unmatched_delimiter = false

    scala.util.control.Breaks.breakable {
      while (char != CHAR_NONE && char != rstring_delimiter) {
        // PART III.1
        // Check for missing-quotes scenario in OBJECT_KEY => stop if we hit ':'
        if (
          missing_quotes && context.current.contains(ContextValues.ObjectKey) &&
          (char == ':' || char.isWhitespace)
        ) {
          log(
            "Found ':' while parsing a string missing the left delimiter in object key context => stop."
          )
          // Don't consume the colon
          // return sb.toString
          break()
        }

        // Check if we are in OBJECT_VALUE context and the next char is ',' or '}' => possible missing right quote
        if (
          context.current.contains(ContextValues.ObjectValue) &&
          (char == ',' || char == '}')
        ) {

          // Flag to track if we're missing the closing delimiter
          var rStringDelimiterMissing = true

          // Check if this is a case where the closing comma is NOT missing instead
          // i is an offset
          val i = skipToCharacter(rstring_delimiter, offset = 1)
          getCharAt(i) match {
            case Some(_) =>
              // Found a delimiter, check what follows after whitespace
              // afterDelimiterIndex is an offset
              val afterDelimiterIndex =
                skipWhitespacesAt(startOffset = i + 1, moveMainIndex = false)

              getCharAt(afterDelimiterIndex) match {
                case None | Some(',') | Some('}') =>
                  // If it's followed by nothing, comma, or closing brace, delimiter isn't missing
                  rStringDelimiterMissing = false

                case _ =>
                  // Could be garbage at end of string, check for another opening quote
                  // nextQuoteIndex is an offset
                  val nextQuoteIndex = skipToCharacter(lstring_delimiter, afterDelimiterIndex)

                  // If we have doubled quotes, need to look for another one
                  val finalQuoteIndex = if (doubled_quotes) {
                    skipToCharacter(lstring_delimiter, offset = nextQuoteIndex)
                  } else {
                    nextQuoteIndex
                  }

                  getCharAt(finalQuoteIndex) match {
                    case None =>
                      // No more quotes found
                      rStringDelimiterMissing = false

                    case Some(_) =>
                      // Found another quote, check if it's followed by a colon (e.g. "lorem, "ipsum": sic")
                      val afterQuoteIndex =
                        skipWhitespacesAt(
                          startOffset = finalQuoteIndex + 1,
                          moveMainIndex = false
                        )
                      if (getCharAt(afterQuoteIndex).exists(_ != ':')) {
                        rStringDelimiterMissing = false
                      }
                  }
              }

            case None =>
              // No closing quote found, check if this might be systemic missing delimiters
              val colonIndex = skipToCharacter(':', offset = 1)
              getCharAt(colonIndex) match {
                case Some(_) =>
                // Found a colon - likely systemic issue with missing quotes
                // Break out (handled by calling code)

                case None =>
                  // No colon found, check if we're at end of object
                  // afterWhitespace is an offset
                  val afterWhitespace =
                    skipWhitespacesAt(startOffset = 1, moveMainIndex = false)
                  val closingBraceIndex = skipToCharacter('}', afterWhitespace)

                  if (closingBraceIndex - afterWhitespace > 1) {
                    // Not immediately before closing brace
                    rStringDelimiterMissing = false
                  } else {
                    // Check if there's an unmatched opening brace in accumulated string
                    getCharAt(closingBraceIndex).foreach { _ =>
                      // breakable
                      scala.util.control.Breaks.breakable {
                        // Look for unmatched braces
                        var foundMatchingBrace = false
                        sb.toString.reverse.foreach { c =>
                          if (c == '{') {
                            // Found opening brace - this is part of the string
                            rStringDelimiterMissing = false
                            foundMatchingBrace = true
                          } else if (c == '}') {
                            foundMatchingBrace = true
                          }
                          if (foundMatchingBrace) {
                            break()
                          }
                        }
                      }
                    }
                  }
              }
          }

          if (rStringDelimiterMissing) {
            log(
              "While parsing a string missing the left delimiter in object value context, we found a , or } and we couldn't determine that a right delimiter was present. Stopping here"
            )
            break()
          }
        }

        // PART III.2
        // If we are in array context and see a ']', we might also break
        if (char == ']' && context.contains(ContextValues.Array)) {
          val i = skipToCharacter(rstring_delimiter)
          if (getCharAt(i).isEmpty) {
            // No matching delimiter => break
            break()
          }
        }

        sb.append(char)
        index += 1
        char = getCharAt().getOrElse(CHAR_NONE)

        // Check for backslash escapes
        if (char != CHAR_NONE && sb.nonEmpty && sb.last == '\\') {
          // This is a special case, if people use real strings this might happen
          log("Found a stray escape sequence, normalizing it")
          if (Set(rstring_delimiter, 't', 'n', 'r', 'b', '\\').contains(char)) {
            val escMap = Map(
              't' -> '\t',
              'n' -> '\n',
              'r' -> '\r',
              'b' -> '\b',
              '\\' -> '\\'
            )

            // sb.deleteCharAt(sb.length - 1)
            sb.append(escMap.getOrElse(char, char))
            index += 1
            char = getCharAt().getOrElse(CHAR_NONE)
          }
        }

        // If we are in object key context and encounter a colon, it could be a missing right quote
        if (
          char == ':' && !missing_quotes && context.current.contains(ContextValues.ObjectKey)
        ) {
          // Ok now we need to check if this is followed by a value like "..."
          val i = skipToCharacter(lstring_delimiter, 1)
          getCharAt(i) match {
            case Some(nextC) =>
              val i2 = i + 1
              // found the first delimiter
              val i3 = skipToCharacter(rstring_delimiter, i2)
              if (getCharAt(i3).isDefined) {
                val i4 = i3 + 1
                // Skip spaces
                val afterWhitespace = skipWhitespacesAt(i4, moveMainIndex = false)

                val nextC2 = getCharAt(afterWhitespace)
                if (nextC2.isDefined && Set(',', '}').contains(nextC2.get)) {
                  // This pattern suggests a missing right quote in the key
                  log(
                    "While parsing a string missing the right delimiter in object key context, we found a :, stopping here"
                  )
                  break()
                }
              }

            case None =>
              // No opening delimiter found after colon, assume missing right quote
              log(
                "While parsing a string missing the right delimiter in object key context, we found a :, stopping here"
              )
              break()
          }
        }

        // PART III.3
        //  ChatGPT sometimes forget to quote stuff in html tags or markdown, so we do this whole thing here
        if (char == rstring_delimiter) {
          // Special case here, in case of double quotes one after another
          if (doubled_quotes && getCharAt(1).contains(rstring_delimiter)) {
            log("While parsing a string, we found a doubled quote, ignoring it")
            index += 1
          } else if (
            missing_quotes &&
            context.current.contains(ContextValues.ObjectValue)
          ) {
            // In case of missing starting quote, check if the delimiter is the end or beginning of a key
            var i = 1
            var nextC = getCharAt(i)

            // Look ahead until we find a string delimiter or run out of input
            while (
              nextC.isDefined &&
              !Set(rstring_delimiter, lstring_delimiter).contains(nextC.get)
            ) {
              i += 1
              nextC = getCharAt(i)
            }

            if (nextC.isDefined) {
              // Found a quote, check if it's followed by a colon (indicating a key)
              i += 1
              // Found a delimiter, verify it's followed by a colon after whitespace
              i = skipWhitespacesAt(i, moveMainIndex = false)
              nextC = getCharAt(i)

              if (nextC.contains(':')) {
                // Reset the cursor - this quote belongs to the next key
                index -= 1
                char = getCharAt().getOrElse(CHAR_NONE)
                log(
                  "In a string with missing quotes and object value context, I found a delimiter but it turns out it was the beginning of the next key. Stopping here."
                )
                break();
              }
            }
          } else if (unmatched_delimiter) {
            unmatched_delimiter = false
            sb.append(char)
            index += 1
            char = getCharAt().getOrElse(CHAR_NONE)
          } else {
            // Check if this is a quote that should be treated as content
            var i = 1
            var nextC = getCharAt(i)
            var checkCommaInObjectValue = true

            scala.util.control.Breaks.breakable {
              // Look ahead to see if this quote is followed by another quote or special characters
              while (
                nextC.isDefined && !Set(rstring_delimiter, lstring_delimiter).contains(
                  nextC.get
                )
              ) {
                // If we find alphanumeric characters, this might be a quoted section within the string
                if (checkCommaInObjectValue && nextC.exists(_.isLetterOrDigit)) {
                  checkCommaInObjectValue = false
                }

                // Check for special characters that would indicate this quote is not a closing delimiter
                if (
                  (context.contains(ContextValues.ObjectKey) && (nextC.contains(':') || nextC
                    .contains('}'))) ||

                  (context.contains(ContextValues.ObjectValue) && nextC.contains(',')) ||

                  (context.contains(ContextValues.Array) && Set(']', ',').contains(
                    nextC.getOrElse(' ')
                  )) ||

                  (checkCommaInObjectValue && context.current.contains(
                    ContextValues.ObjectValue
                  ) && nextC.contains(','))
                ) {
                  break()
                }

                i += 1
                nextC = getCharAt(i)
              }
            }

            // If we stopped for a comma in object_value context, let's check if find a "} at the end of the string
            if (nextC.contains(',') && context.current.contains(ContextValues.ObjectValue)) {
              i += 1
              val delimiterIndex = skipToCharacter(rstring_delimiter, i)
              nextC = getCharAt(delimiterIndex)

              // If we found another delimiter, check what follows
              if (nextC.isDefined) {
                val afterDelimiterIndex =
                  skipWhitespacesAt(delimiterIndex + 1, moveMainIndex = false)
                nextC = getCharAt(afterDelimiterIndex)

                if (nextC.contains('}')) {
                  // OK this is valid then
                  log(
                    "While parsing a string, we misplaced a quote that would have closed the string but has a different meaning here since this is the last element of the object, ignoring it"
                  )
                  unmatched_delimiter = !unmatched_delimiter
                  sb.append(char)
                  index += 1
                  char = getCharAt().getOrElse(CHAR_NONE)
                }
              }
            } else if (
              nextC.contains(rstring_delimiter) && getCharAt(i - 1).exists(_ != '\\')
            ) {
              // Check if there's only whitespace between the quotes
              val onlyWhitespace =
                (1 until i).forall(j => getCharAt(j).exists(_.isWhitespace))

              if (onlyWhitespace) {
                break()
              }

              if (context.current.contains(ContextValues.ObjectValue)) {
                // Check if this is a key by looking for a colon
                // this is an offset
                val afterDelimiterIndex = skipToCharacter(rstring_delimiter, i + 1) + 1
                var colonFound = false
                var j = afterDelimiterIndex

                scala.util.control.Breaks.breakable {
                  while (getCharAt(j).isDefined && !colonFound) {
                    val c = getCharAt(j).get
                    if (c == ':') {
                      colonFound = true
                    } else if (
                      Set(',', ']', '}', rstring_delimiter).contains(c) && getCharAt(j - 1)
                        .exists(_ != '\\')
                    ) {
                      break()
                    }
                    j += 1
                  }
                }

                if (!colonFound) {
                  log(
                    "While parsing a string, we a misplaced quote that would have closed the string but has a different meaning here, ignoring it"
                  )
                  unmatched_delimiter = !unmatched_delimiter
                  sb.append(char)
                  index += 1
                  char = getCharAt().getOrElse(CHAR_NONE)
                }
              } else if (context.contains(ContextValues.Array)) {
                // In array context, this is likely a quoted section within the string
                log(
                  "While parsing a string in Array context, we detected a quoted section that would have closed the string but has a different meaning here, ignoring it"
                )
                unmatched_delimiter = !unmatched_delimiter
                sb.append(char)
                index += 1
                char = getCharAt().getOrElse(CHAR_NONE)
              }
            }
          }
        }
      } // end while loop
    } // end breakable

    // PART IV

    // Handle extreme corner case where we have missing quotes in OBJECT_KEY context
    if (
      char != CHAR_NONE &&
      missing_quotes &&
      context.current.contains(ContextValues.ObjectKey) &&
      char.isWhitespace
    ) {
      log(
        "While parsing a string, handling an extreme corner case in which the LLM added a comment instead of valid string, invalidate the string and return an empty value"
      )
      skipWhitespacesAt()
      if (!Set(':', ',').contains(char)) {
        return ""
      }
    }

    // A fallout of the previous special case in the while loop,
    // we need to update the index only if we had a closing quote
    if (char != rstring_delimiter) {
      log("While parsing a string, we missed the closing quote, ignoring")
      // Equivalent to Python's rstrip()
      val trimmed = sb.toString().replaceAll("\\s+$", "")
      sb.clear()
      sb.append(trimmed)
    } else {
      index += 1
    }

    // Clean the whitespaces for some corner cases
    if (missing_quotes || (sb.nonEmpty && sb.last == '\n')) {
      val trimmed = sb.toString().replaceAll("\\s+$", "")
      sb.clear()
      sb.append(trimmed)
    }

    return sb.toString()
  }

  def skipToCharacter(
    character: Char,
    offset: Int = 0
  ): Int = {
    var currentOffset = offset

    while (true) {
      val pos = index + currentOffset
      // If we're beyond the end of the string, return offset.
      if (pos >= length) {
        return currentOffset
      }

      val c = getCharAt(currentOffset)

      if (c.isEmpty || c.get == character) {
        // We found the character, but check if it's escaped.
        if (currentOffset > 0 && getCharAt(currentOffset - 1).contains('\\')) {
          // It's escaped, so keep going.
          currentOffset += 1
        } else {
          // Found an unescaped match or we've run out of input
          return currentOffset
        }
      } else {
        // Keep searching
        currentOffset += 1
      }
    }

    // This line will never be reached, but required for compiler
    currentOffset
  }

  /**
   * Iterates over consecutive whitespace characters starting from `index + startOffset`.
   *
   * @param startOffset
   *   the offset relative to `index` at which we begin skipping whitespace
   * @param moveMainIndex
   *   if true, increment the global `index` as we skip whitespace; if false, only increment
   *   the local offset
   * @return
   *   the new offset (relative to the original `index`) after skipping whitespace
   */
  def skipWhitespacesAt(
    startOffset: Int = 0,
    moveMainIndex: Boolean = true
  ): Int = {
    var offset = startOffset

    // Attempt to read the character at index + offset
    def currentChar: Option[Char] = {
      val pos = index + offset
      if (pos >= 0 && pos < length) getCharAt(offset)
      else None
    }

    // If we're already out-of-bounds, just return the given offset
    var charOpt = currentChar
    if (charOpt.isEmpty) {
      return offset
    }

    // Skip whitespace
    var char = charOpt.get
    while (char.isWhitespace) {
      if (moveMainIndex) {
        index += 1
      } else {
        offset += 1
      }

      charOpt = currentChar
      if (charOpt.isEmpty) {
        return offset
      }
      char = charOpt.get
    }

    offset
  }

  /**
   * Attempts to parse one of the unquoted literals "true", "false", or "null". If successful,
   * returns the corresponding Scala value (true, false, or null). If not, resets `index` and
   * returns "".
   */
  private def parseBooleanOrNull(): Option[Any] = {
    val startingIndex = index
    var char = getCharAt().getOrElse(CHAR_NONE).toLower

    // Try to match each literal
    val literals = Seq(
      ("true", Some(true)),
      ("false", Some(false)),
      ("null", Some(null))
    )

    var result: Option[Any] = None

    scala.util.control.Breaks.breakable {
      for ((word, scalaValue) <- literals) {
        var i = 0
        // Attempt to match the entire literal character-by-character
        while (i < word.length && char == word.charAt(i)) {
          i += 1
          index += 1
          char = getCharAt().getOrElse(CHAR_NONE).toLower
        }

        if (i == word.length) {
          // Successfully matched the literal, store the value and break out of the loop
          result = scalaValue
          break
        }
      }
    }

    if (result.isEmpty) {
      // Reset index and char before trying the next literal
      index = startingIndex
    }

    result
  }

  /**
   * Parse a JSON number.
   *
   * @return
   *   The parsed JSON number
   */
  private def parseNumber(): JsonValue = {
    val startIndex = index
    val isArray = context.current.contains(Array)

    scala.util.control.Breaks.breakable {
      while (index < length) {
        getCharAt() match {
          case Some(c) if NumberChars.contains(c) && (!isArray || c != ',') =>
            index += 1
          case _ =>
            break()
        }
      }
    }

    var numStr = substring(startIndex, index)

    // Roll back one character if the number ends with invalid character
    if (numStr.nonEmpty && "-eE/,".contains(numStr.last)) {
      numStr = numStr.init
      index -= 1
    }

    try {
      if (numStr.contains(',')) {
        numStr
      } else if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
        numStr.toDouble
      } else if (numStr == "-") {
        // If there is a stray "-", parse the next value
        parseJson()
      } else {
        numStr.toLong
      }
    } catch {
      case _: NumberFormatException =>
        numStr
    }
  }

  /**
   * Parse a JSON literal (true, false, null).
   *
   * @return
   *   The parsed JSON literal
   */
  private def parseLiteral(): Any = {
    val startIndex = index

    scala.util.control.Breaks.breakable {
      while (index < length) {
        getCharAt() match {
          case Some(c) if c.isLetter =>
            index += 1
          case _ =>
            break()
        }
      }
    }

    val literal = substring(startIndex, index).toLowerCase

    literal match {
      case "true" | "True" | "TRUE"    => true
      case "false" | "False" | "FALSE" => false
      case "null" | "Null" | "NULL"    => null
      case _ =>
        log(s"Unknown literal: $literal, treating as string")
        literal
    }
  }

  /**
   * Parse a code-like comment and return an empty string once the comment is skipped. Matches
   * the Python logic:
   *   - # line comment
   *   - // line comment
   *   - /* block comment */
   *
   * Comments are skipped over (not returned in the final JSON).
   */
  def parseComment(): String = {
    // If we're out of characters, just return.
    val currentOpt = getCharAt()
    if (currentOpt.isEmpty) return ""

    // Build up possible termination characters
    val terminationChars = mutable.Set('\n', '\r')
    if (context.contains(ContextValues.Array)) {
      terminationChars += ']'
    }
    if (context.contains(ContextValues.ObjectValue)) {
      terminationChars += '}'
    }
    if (context.contains(ContextValues.ObjectKey)) {
      terminationChars += ':'
    }

    val currentChar = currentOpt.get

    // 1. Line comment starting with '#'
    if (currentChar == '#') {
      val sb = new StringBuilder
      // Accumulate until we hit a termination character or end of input
      while (index < length) {
        val c = getCharAt().getOrElse(CHAR_NONE) // safe default
        if (terminationChars.contains(c)) {
          log(s"Found line comment: $sb")
          return ""
        }
        sb.append(c)
        index += 1
      }
      // If we exhaust the input
      log(s"Found line comment: $sb")
      return ""
    }

    // 2. Comments starting with '/'
    else if (currentChar == '/') {
      // Look ahead to see if it's '//' or '/*'
      val nextCharOpt = getCharAt(1)

      nextCharOpt match {
        // 2a. Line comment starting with '//'
        case Some('/') =>
          // Skip both slashes
          index += 2
          val sb = new StringBuilder("//")
          // Accumulate until termination char or end
          while (index < length) {
            val c = getCharAt().getOrElse(CHAR_NONE)
            if (terminationChars.contains(c)) {
              log(s"Found line comment: $sb")
              return ""
            }
            sb.append(c)
            index += 1
          }
          log(s"Found line comment: $sb")
          return ""

        // 2b. Block comment starting with '/*'
        case Some('*') =>
          // Skip '/*'
          index += 2
          val sb = new StringBuilder("/*")
          // Read until we find '*/' or end of input
          while (index < length) {
            val c = getCharAt().getOrElse(CHAR_NONE)
            sb.append(c)
            index += 1
            // Check if we just ended the comment
            if (sb.endsWith("*/")) {
              log(s"Found block comment: $sb")
              return ""
            }
          }
          // If we reach here, block comment was never closed
          log("Reached end-of-string while parsing block comment; unclosed block comment.")
          return ""

        // 2c. Not a recognized comment pattern; skip the single '/'
        case _ =>
          index += 1
          return ""
      }
    }

    // 3. If it's not # or /, just skip one char and move on.
    else {
      index += 1
      return ""
    }
  }
}
