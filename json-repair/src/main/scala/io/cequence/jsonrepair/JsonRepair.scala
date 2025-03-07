package io.cequence.jsonrepair

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import java.io.{File, FileReader}
import scala.util.{Failure, Success, Try}
import play.api.libs.json.{
  JsArray,
  JsBoolean,
  JsNull,
  JsNumber,
  JsObject,
  JsString,
  JsValue,
  Json
}

/**
 * The main object/launcher for repairing malformed JSON strings or files.
 *
 * Main functionality:
 *   - `repair(jsonStr)`: Repair a JSON string
 *   - `repairJson(jsonStr, ...)`: Repair a JSON string with flags
 *   - `loads(jsonStr, ...)`: Parse and repair a JSON string into a JsValue
 *   - `fromFile(filePath, ...)`: Load and repair JSON from a file
 *
 * The repair process handles common JSON syntax errors such as:
 *   - Missing or mismatched brackets and braces
 *   - Missing quotation marks
 *   - Improperly formatted values
 *   - Incomplete arrays/objects
 */
object JsonRepair {

  private val logger = Logger(LoggerFactory.getLogger("JsonRepair"))

  /**
   * Repair a JSON string.
   *
   * @param jsonStr
   *   The JSON string to repair
   * @param skipJsonParse
   *   If true, skip trying to parse with the standard JSON parser first
   * @param logging
   *   If true, return a tuple with the repaired JSON and a log of repair actions
   * @param ensureAscii
   *   If true, ensure all non-ASCII characters are escaped
   * @return
   *   The repaired JSON string or object
   */
  def repairJson(
    jsonStr: String,
    skipJsonParse: Boolean = false,
    logging: Boolean = false,
    ensureAscii: Boolean = true,
    handleLiterals: Boolean = false
  ): String = {
    val jsValue = loads(jsonStr, skipJsonParse, logging, ensureAscii, handleLiterals)
    if (ensureAscii)
      Json.asciiStringify(jsValue)
    else
      Json.stringify(jsValue)
  }

  /**
   * Load and repair JSON from a string.
   *
   * @param jsonStr
   *   The JSON string to load and repair
   * @return
   *   The repaired JSON string
   */
  def repair(jsonStr: String): String =
    repairJson(jsonStr)

  /**
   * Repair a JSON string.
   *
   * @param jsonStr
   *   The JSON string to repair
   * @param returnObjects
   *   If true, return the parsed object instead of a JSON string
   * @param skipJsonParse
   *   If true, skip trying to parse with the standard JSON parser first
   * @param logging
   *   If true, return a tuple with the repaired JSON and a log of repair actions
   * @param ensureAscii
   *   If true, ensure all non-ASCII characters are escaped
   * @return
   *   The repaired JSON string or object
   */
  def loads(
    jsonStr: String,
    skipJsonParse: Boolean = false,
    logging: Boolean = false,
    ensureAscii: Boolean = true,
    handleLiterals: Boolean = false,
    convertNumbers: Boolean = false
  ): JsValue = {
    // Improved check for potential concatenated JSON
    val isConcatenatedJson = isConcatenatedJsonString(jsonStr)

    val result: Try[JsValue] = if (!skipJsonParse && !isConcatenatedJson) {
      // Try to parse with standard JSON parser first
      Try(Json.parse(jsonStr))
    } else
      Failure(new Exception("Failed to parse JSON or..."))

    result.getOrElse {
      val newResult = repairWithParser(
        jsonStr,
        logging,
        handleLiterals,
        convertNumbers
      )

      logAndReturn(newResult)
    }
  }

  private def logAndReturn[T](
    valueAndLogs: (T, List[Map[String, String]])
  ): T = {
    val (value, logs) = valueAndLogs

    logs.foreach(log => {
      val logStr = log.map { case (k, v) => s"$k: $v" }.mkString(", ")
      logger.info(s"Repair log: $logStr")
    })
    value
  }

  /**
   * Repair a JSON string.
   *
   * @param jsonStr
   *   The JSON string to repair
   * @param logging
   *   If true, return a tuple with the repaired JSON and a log of repair actions
   * @param ensureAscii
   *   If true, ensure all non-ASCII characters are escaped
   * @return
   *   The repaired JSON string or object
   */
  def repairJsonAsValue(
    jsonStr: String,
    logging: Boolean = false,
    handleLiterals: Boolean = false
  ): Any = {
    val parser = new JsonParser(Left(jsonStr), logging, handleLiterals)
    val result = parser.parse()
    logAndReturn(result)
  }

  /**
   * Checks if a string appears to contain concatenated JSON objects or arrays.
   *
   * @param jsonStr
   *   The JSON string to check
   * @return
   *   True if the string appears to contain concatenated JSON
   */
  private def isConcatenatedJsonString(jsonStr: String): Boolean = {
    jsonStr.trim.nonEmpty && {
      val trimmed = jsonStr.trim

      // Check for multiple JSON objects/arrays by looking for patterns like }{, ][, }[, or ]{
      trimmed.contains("}{") || trimmed.contains("][") ||
      trimmed.contains("}[") || trimmed.contains("]{") ||
      // Count opening and closing braces/brackets as before
      {
        val firstChar = trimmed.head
        val lastChar = trimmed.last

        val openBraces = trimmed.count(_ == '{')
        val closeBraces = trimmed.count(_ == '}')
        val openBrackets = trimmed.count(_ == '[')
        val closeBrackets = trimmed.count(_ == ']')

        (openBraces > 1 && closeBraces > 1 && openBraces == closeBraces) ||
        (openBrackets > 1 && closeBrackets > 1 && openBrackets == closeBrackets) ||
        (firstChar == '{' && lastChar == '}' && openBraces > 1 && closeBraces > 1) ||
        (firstChar == '[' && lastChar == ']' && openBrackets > 1 && closeBrackets > 1)
      }
    }
  }

  /**
   * Repair a JSON string using our custom parser.
   *
   * @param jsonStr
   *   The JSON string to repair
   * @param logging
   *   If true, return a tuple with the repaired JSON and a log of repair actions
   * @return
   *   The repaired JSON string or object
   */
  private def repairWithParser(
    jsonStr: String,
    logging: Boolean,
    handleLiterals: Boolean,
    convertNumbers: Boolean = false
  ): (JsValue, List[Map[String, String]]) = {
    val parser = new JsonParser(Left(jsonStr), logging, handleLiterals)
    val (value, log) = parser.parse()
    (convertToJsValue(value, convertNumbers), log)
  }

  /**
   * Load and repair JSON from a file using our custom parser.
   *
   * @param file
   *   The file to load and repair
   * @param logging
   *   If true, return a tuple with the repaired JSON and a log of repair actions
   * @param chunkLength
   *   The length of each chunk to read from the file
   * @return
   *   The repaired JSON object
   */
  private def loadWithParser(
    file: File,
    logging: Boolean,
    chunkLength: Int,
    handleLiterals: Boolean,
    convertNumbers: Boolean = false
  ): (JsValue, List[Map[String, String]]) = {
    val fileWrapper = new StringFileWrapper(file, chunkLength)
    val parser = new JsonParser(Right(fileWrapper), logging, handleLiterals)

    try {
      val (result, log) = parser.parse()
      (convertToJsValue(result, convertNumbers), log)
    } finally {
      fileWrapper.close()
    }
  }

  /**
   * Convert a parsed JSON value to a Play JSON value.
   *
   * @param value
   *   The parsed JSON value
   * @return
   *   The Play JSON value
   */
  private def convertToJsValue(
    value: Any,
    convertNumbers: Boolean = false
  ): JsValue = {
    value match {
      case null       => JsNull
      case b: Boolean => JsBoolean(b)
      case i: Int     => JsNumber(i)
      case l: Long    => JsNumber(l)
      case d: Double  => JsNumber(d)
      case f: Float   => JsNumber(f.toDouble)
      case s: String  =>
        // First check for boolean or null literals that might be strings
        s.toLowerCase.trim match {
          case "true"  => JsBoolean(true)
          case "false" => JsBoolean(false)
          case "null"  => JsNull
          case _       =>
            // Check if the string contains mixed quotes that need to be cleaned
            val cleanedString = if (s.contains("'") || s.contains("\"")) {
              // Handle mixed quotes more robustly
              if (
                (s.startsWith("\"") && s.endsWith("\"")) ||
                (s.startsWith("'") && s.endsWith("'"))
              ) {
                s.substring(1, s.length - 1)
              } else {
                s.replace("'", "").replace("\\\"", "\"")
              }
            } else {
              s
            }

            // Check if the string is a number with mixed quotes
            if (
              convertNumbers &&
              cleanedString.forall(c =>
                c.isDigit || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E' || c.isWhitespace
              )
            ) {
              val trimmed = cleanedString.trim
              try {
                JsNumber(BigDecimal(trimmed))
              } catch {
                case _: NumberFormatException => JsString(cleanedString)
              }
            } else {
              JsString(cleanedString)
            }
        }
      case m: Map[_, _] =>
        JsObject(m.asInstanceOf[Map[String, Any]].map { case (k, v) =>
          // Clean key from quotes if needed
          val cleanKey =
            if (
              (k.startsWith("\"") && k.endsWith("\"")) ||
              (k.startsWith("'") && k.endsWith("'"))
            ) {
              k.substring(1, k.length - 1)
            } else if (k.contains("'") || k.contains("\"")) {
              // Handle mixed quotes in keys more thoroughly
              k.replace("'", "").replace("\\\"", "\"")
            } else {
              k
            }
          cleanKey -> convertToJsValue(v, convertNumbers)
        })
      case l: Seq[_] =>
        JsArray(l.map(convertToJsValue(_, convertNumbers)))
      case jsValue: JsValue => jsValue
      case _                => JsNull
    }
  }

  /**
   * Load and repair JSON from a file.
   *
   * @param file
   *   The file to load and repair
   * @param skipJsonParse
   *   If true, skip trying to parse with the standard JSON parser first
   * @param logging
   *   If true, return a tuple with the repaired JSON and a log of repair actions
   * @param chunkLength
   *   The length of each chunk to read from the file
   * @return
   *   The repaired JSON object
   */
  private def load(
    file: File,
    skipJsonParse: Boolean = false,
    logging: Boolean = false,
    chunkLength: Int = 0,
    handleLiterals: Boolean = false
  ): (JsValue, List[Map[String, String]]) = {
    if (!file.exists()) {
      throw new java.io.FileNotFoundException(s"File not found: ${file.getPath}")
    }

    if (!skipJsonParse) {
      // Try to parse with standard JSON parser first
      Try {
        val reader = new FileReader(file)
        try {
          // Read the file content as a string and then parse it
          val content = new java.io.BufferedReader(reader)
            .lines()
            .collect(java.util.stream.Collectors.joining("\n"))
          Json.parse(content)
        } finally {
          reader.close()
        }
      } match {
        case Success(jsValue: JsValue) =>
          (jsValue, List.empty)

        case Failure(_) =>
          // If standard parsing fails, use our custom parser
          loadWithParser(file, logging, chunkLength, handleLiterals)
      }
    } else {
      // Skip standard parsing and use our custom parser directly
      loadWithParser(file, logging, chunkLength, handleLiterals)
    }
  }

  /**
   * Load and repair JSON from a file path.
   *
   * @param filePath
   *   The path to the file to load and repair
   * @param skipJsonParse
   *   If true, skip trying to parse with the standard JSON parser first
   * @param logging
   *   If true, return a tuple with the repaired JSON and a log of repair actions
   * @param chunkLength
   *   The length of each chunk to read from the file
   * @return
   *   The repaired JSON object
   */
  def fromFile(
    filePath: String,
    skipJsonParse: Boolean = false,
    logging: Boolean = false,
    chunkLength: Int = 0
  ): JsValue = {
    val (value, logs) = load(new File(filePath), skipJsonParse, logging, chunkLength)
    value
  }
}
