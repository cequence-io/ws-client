# JSON Repair for Scala

A Scala library to repair invalid JSON, commonly used to parse the output of LLMs.

This is a (faithful) Scala port of the Python [json_repair](https://github.com/mangiucugna/json_repair) library.
Acknowledgement to the original author [mangiucugna](https://github.com/mangiucugna) 🙏.
Note that we aimed to provide convert the original code to Scala "one-to-one" hence the code is in essence procedural
and might not be idiomatic Scala.

Note that all but one tests (around 140) from the original Python library have been ported to Scala and are passing! See [here](/src/test/scala/io/cequence/jsonrepair/JsonRepairSpec.scala)

## Features

### Fixing Common JSON Syntax Errors

* Missing or mismatched brackets and braces
* Missing quotation marks, improperly formatted values (true, false, null), and repairs corrupted key-value structures.

### Repairing Malformed JSON Arrays and Objects

* Incomplete or broken arrays/objects by adding necessary elements (e.g., commas, brackets) or default values (
  null, "").
* The library can process JSON that includes extra non-JSON characters like comments or improperly placed characters,
  cleaning them up while maintaining valid structure.

### Auto-Completion for Missing JSON Values

* Automatically completes missing values in JSON fields with reasonable defaults (like empty strings or null), ensuring
  validity.

## Installation

Add the following dependency to your build.sbt:

```scala
libraryDependencies += "io.cequence" %% "json-repair" % "0.7.0"
```

## Usage

### Basic Usage

```scala
import io.cequence.jsonrepair.JsonRepair

// Repair a JSON string
val repairedJson = JsonRepair.repair("""{"key": value, "key2": 1 "key3": null }""")
// Result: {"key": "value", "key2": 1, "key3": null}

// Parse and repair a JSON string, returning a JsValue
val jsValue = JsonRepair.loads("""{"key": value, "key2": 1 "key3": null }""")
// Result: JsObject with repaired values

// Load and repair JSON from a file
val jsValueFromFile = JsonRepair.fromFile("path/to/file.json")
```

### Advanced Usage

```scala
import io.cequence.jsonrepair.JsonRepair
import java.io.File

// Skip standard JSON parsing for performance
val jsValue = JsonRepair.loads(
  """{"key": value, "key2": 1 "key3": null }""",
  skipJsonParse = true
)

// With logging
val jsValue = JsonRepair.loads(
  """{"key": value, "key2": 1 "key3": null }""",
  logging = true
)

// Load from a file with custom chunk size
val jsValueFromFile = JsonRepair.load(
  new File("path/to/file.json"),
  chunkLength = 2000000
)
```

## How it works

This module will parse the JSON file following the BNF definition:

```
<json> ::= <primitive> | <container>

<primitive> ::= <number> | <string> | <boolean>
; Where:
; <number> is a valid real number expressed in one of a number of given formats
; <string> is a string of valid characters enclosed in quotes
; <boolean> is one of the literal strings 'true', 'false', or 'null' (unquoted)

<container> ::= <object> | <array>
<array> ::= '[' [ <json> *(', ' <json>) ] ']' ; A sequence of JSON values separated by commas
<object> ::= '{' [ <member> *(', ' <member>) ] '}' ; A sequence of 'members'
<member> ::= <string> ': ' <json> ; A pair consisting of a name, and a JSON value
```

If something is wrong (a missing parentheses or quotes for example) it will use a few simple heuristics to fix the JSON
string:

* Add the missing parentheses if the parser believes that the array or object should be closed
* Quote strings or add missing single quotes
* Adjust whitespaces and remove line breaks

## License

MIT License 