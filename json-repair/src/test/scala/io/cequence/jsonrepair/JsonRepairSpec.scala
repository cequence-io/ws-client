package io.cequence.jsonrepair

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json._

class JsonRepairSpec extends AnyFlatSpec with Matchers {

  "JsonRepair" should "handle basic valid types correctly" in {
    JsonRepair.repairJson("True") shouldBe "\"\""
    JsonRepair.repairJson("False") shouldBe "\"\""
    JsonRepair.repairJson("Null") shouldBe "\"\""
    JsonRepair.repairJson("1") shouldBe "1"
    JsonRepair.repairJson("[]") shouldBe "[]"
    JsonRepair.repairJson("[1, 2, 3, 4]") shouldBe "[1,2,3,4]"
    JsonRepair.repairJson("{}") shouldBe "{}"
    JsonRepair.repairJson(
      """{ "key": "value", "key2": 1, "key3": True }"""
    ) shouldBe """{"key":"value","key2":1,"key3":true}"""
    // extra tests not in the original code
    JsonRepair.repairJson("True", handleLiterals = true) shouldBe "true"
    JsonRepair.repairJson("False", handleLiterals = true) shouldBe "false"
    JsonRepair.repairJson("Null", handleLiterals = true) shouldBe "null"
  }

  it should "handle basic invalid types correctly" in {
    JsonRepair.repairJson("true") shouldBe "true"
    JsonRepair.repairJson("false") shouldBe "false"
    JsonRepair.repairJson("null") shouldBe "null"
    JsonRepair.repairJson("1.2") shouldBe "1.2"
    JsonRepair.repairJson("[") shouldBe "[]"
    JsonRepair.repairJson("[1, 2, 3, 4") shouldBe "[1,2,3,4]"
    JsonRepair.repairJson("{") shouldBe "{}"
    JsonRepair.repairJson(
      """{ "key": value, "key2": 1 "key3": null }"""
    ) shouldBe """{"key":"value","key2":1,"key3":null}"""
  }

  it should "handle valid JSON correctly" in {
    JsonRepair.repairJson(
      """{"name": "John", "age": 30, "city": "New York"}"""
    ) shouldBe """{"name":"John","age":30,"city":"New York"}"""

    JsonRepair.repairJson(
      """{"employees":["John", "Anna", "Peter"]} """
    ) shouldBe """{"employees":["John","Anna","Peter"]}"""

    JsonRepair.repairJson("""{"key": "value:value"}""") shouldBe """{"key":"value:value"}"""

    JsonRepair.repairJson(
      """{"text": "The quick brown fox,"}"""
    ) shouldBe """{"text":"The quick brown fox,"}"""

    JsonRepair.repairJson(
      """{"text": "The quick brown fox won't jump"}"""
    ) shouldBe """{"text":"The quick brown fox won't jump"}"""

    JsonRepair.repairJson("""{"key": """) shouldBe """{"key":""}"""

    JsonRepair.repairJson(
      """{"key1": {"key2": [1, 2, 3]}}"""
    ) shouldBe """{"key1":{"key2":[1,2,3]}}"""

    JsonRepair.repairJson(
      """{"key": 12345678901234567890}"""
    ) shouldBe """{"key":12345678901234567890}"""

    JsonRepair.repairJson(
      """{"key": "value\u263A"}""",
      ensureAscii = false
    ) shouldBe """{"key":"value\u263A"}"""

    JsonRepair.repairJson(
      """{"key": "value\\nvalue"}"""
    ) shouldBe """{"key":"value\\nvalue"}"""
  }

  it should "handle bracket edge cases correctly" in {
    JsonRepair.repairJson("[{]") shouldBe "[{}]"
    JsonRepair.repairJson("   {  }   ") shouldBe "{}"
    JsonRepair.repairJson("[") shouldBe "[]"
    JsonRepair.repairJson("]") shouldBe "\"\""
    JsonRepair.repairJson("{") shouldBe "{}"
    JsonRepair.repairJson("}") shouldBe "\"\""
    JsonRepair.repairJson("{\"") shouldBe "{}"
    JsonRepair.repairJson("[\"") shouldBe "[]"
    JsonRepair.repairJson("{foo: [}") shouldBe "{\"foo\":[]}"
  }

  it should "handle general edge cases correctly" in {
    JsonRepair.repairJson("\"") shouldBe "\"\""
    JsonRepair.repairJson("\n") shouldBe "\"\""
    JsonRepair.repairJson(" ") shouldBe "\"\""
    JsonRepair.repairJson("[[1\n\n]") shouldBe "[[1]]"
    JsonRepair.repairJson("string") shouldBe "\"\""
    JsonRepair.repairJson("stringbeforeobject {}") shouldBe "{}"
  }

  it should "handle mixed data types correctly" in {
    JsonRepair.repairJson(
      "  {\"key\": true, \"key2\": false, \"key3\": null}"
    ) shouldBe "{\"key\":true,\"key2\":false,\"key3\":null}"
    JsonRepair.repairJson(
      "{\"key\": TRUE, \"key2\": FALSE, \"key3\": Null}   "
    ) shouldBe "{\"key\":true,\"key2\":false,\"key3\":null}"
  }

  it should "handle missing and mixed quotes correctly" in {
    JsonRepair.repairJson(
      "{'key': 'string', 'key2': false, \"key3\": null, \"key4\": unquoted}"
    ) shouldBe "{\"key\":\"string\",\"key2\":false,\"key3\":null,\"key4\":\"unquoted\"}"

    JsonRepair.repairJson(
      "{\"name\": \"John\", \"age\": 30, \"city\": \"New York"
    ) shouldBe "{\"name\":\"John\",\"age\":30,\"city\":\"New York\"}"

    JsonRepair.repairJson(
      "{\"name\": \"John\", \"age\": 30, city: \"New York\"}"
    ) shouldBe "{\"name\":\"John\",\"age\":30,\"city\":\"New York\"}"

    JsonRepair.repairJson(
      "{\"name\": \"John\", \"age\": 30, \"city\": New York}"
    ) shouldBe "{\"name\":\"John\",\"age\":30,\"city\":\"New York\"}"

    JsonRepair.repairJson(
      "{\"name\": John, \"age\": 30, \"city\": \"New York\"}"
    ) shouldBe "{\"name\":\"John\",\"age\":30,\"city\":\"New York\"}"

    JsonRepair.repairJson(
      "{\"slanted_delimiter\": \"value\"}"
    ) shouldBe "{\"slanted_delimiter\":\"value\"}"

    JsonRepair.repairJson(
      "{\"name\": \"John\", \"age\": 30, \"city\": \"New"
    ) shouldBe "{\"name\":\"John\",\"age\":30,\"city\":\"New\"}"

    JsonRepair.repairJson(
      "{\"name\": \"John\", \"age\": 30, \"city\": \"New York, \"gender\": \"male\"}"
    ) shouldBe "{\"name\":\"John\",\"age\":30,\"city\":\"New York\",\"gender\":\"male\"}"

    JsonRepair.repairJson(
      "[{\"key\": \"value\", COMMENT \"notes\": \"lorem \"ipsum\", sic.\" }]"
    ) shouldBe "[{\"key\":\"value\",\"notes\":\"lorem \\\"ipsum\\\", sic.\"}]"

    JsonRepair.repairJson("{\"key\": \"\"value\"}") shouldBe "{\"key\":\"value\"}"

    JsonRepair.repairJson(
      "{\"key\": \"value\", 5: \"value\"}"
    ) shouldBe "{\"key\":\"value\",\"5\":\"value\"}"

    JsonRepair.repairJson("{\"foo\": \"\\\"bar\\\"\"") shouldBe "{\"foo\":\"\\\"bar\\\"\"}"

    JsonRepair.repairJson("{\"\" key\":\"val\"") shouldBe "{\" key\":\"val\"}"

    JsonRepair.repairJson(
      "{\"key\": value \"key2\" : \"value2\" "
    ) shouldBe "{\"key\":\"value\",\"key2\":\"value2\"}"

    JsonRepair.repairJson(
      "{\"key\": \"lorem ipsum ... \"sic \" tamet. ...}"
    ) shouldBe "{\"key\":\"lorem ipsum ... \\\"sic \\\" tamet. ...\"}"

    JsonRepair.repairJson("{\"key\": value , }") shouldBe "{\"key\":\"value\"}"

    JsonRepair.repairJson(
      "{\"comment\": \"lorem, \"ipsum\" sic \"tamet\". To improve\"}"
    ) shouldBe "{\"comment\":\"lorem, \\\"ipsum\\\" sic \\\"tamet\\\". To improve\"}"

    JsonRepair.repairJson(
      "{\"key\": \"v\\\"alu\\\"e\"} key:"
    ) shouldBe "{\"key\":\"v\\\"alu\\\"e\"}"
  }

  it should "handle array edge cases correctly" in {
    JsonRepair.repairJson("[1, 2, 3,") shouldBe "[1,2,3]"
    JsonRepair.repairJson("[1, 2, 3, ...]") shouldBe "[1,2,3]"
    JsonRepair.repairJson("[1, 2, ... , 3]") shouldBe "[1,2,3]"
    JsonRepair.repairJson("[1, 2, '...', 3]") shouldBe "[1,2,\"...\",3]"
    JsonRepair.repairJson("[true, false, null, ...]") shouldBe "[true,false,null]"
    JsonRepair.repairJson("[\"a\" \"b\" \"c\" 1") shouldBe "[\"a\",\"b\",\"c\",1]"
    JsonRepair.repairJson(
      "{\"employees\":[\"John\", \"Anna\","
    ) shouldBe "{\"employees\":[\"John\",\"Anna\"]}"
    JsonRepair.repairJson(
      "{\"employees\":[\"John\", \"Anna\", \"Peter"
    ) shouldBe "{\"employees\":[\"John\",\"Anna\",\"Peter\"]}"
    JsonRepair.repairJson(
      "{\"key1\": {\"key2\": [1, 2, 3"
    ) shouldBe "{\"key1\":{\"key2\":[1,2,3]}}"
    JsonRepair.repairJson("{\"key\": [\"value]}") shouldBe "{\"key\":[\"value\"]}"
    JsonRepair.repairJson("[\"lorem \"ipsum\" sic\"]") shouldBe "[\"lorem \\\"ipsum\\\" sic\"]"
    JsonRepair.repairJson(
      "{\"key1\": [\"value1\", \"value2\"}, \"key2\": [\"value3\", \"value4\"]}"
    ) shouldBe "{\"key1\":[\"value1\",\"value2\"],\"key2\":[\"value3\",\"value4\"]}"
    JsonRepair.repairJson(
      "[ \"value\", /* comment */ \"value2\" ]"
    ) shouldBe "[\"value\",\"value2\"]"
    JsonRepair.repairJson(
      "{\"key\": [\"value\" \"value1\" \"value2\"]}"
    ) shouldBe "{\"key\":[\"value\",\"value1\",\"value2\"]}"
    JsonRepair.repairJson(
      "{\"key\": [\"lorem \"ipsum\" dolor \"sit\" amet, \"consectetur\" \", \"lorem \"ipsum\" dolor\", \"lorem\"]}"
    ) shouldBe "{\"key\":[\"lorem \\\"ipsum\\\" dolor \\\"sit\\\" amet, \\\"consectetur\\\" \",\"lorem \\\"ipsum\\\" dolor\",\"lorem\"]}"
  }

  it should "handle escaping correctly" in {
    JsonRepair.repairJson("'\"'") shouldBe "\"\""
    JsonRepair.repairJson(
      "{\"key\": 'string\"\n\t\\le'"
    ) shouldBe "{\"key\":\"string\\\"\\n\\t\\\\le\"}"
    JsonRepair.repairJson(
      "{\"real_content\": \"Some string: Some other string \t Some string <a href=\\\"https://domain.com\\\">Some link</a>\""
    ) shouldBe "{\"real_content\":\"Some string: Some other string \\t Some string <a href=\\\"https://domain.com\\\">Some link</a>\"}"
    JsonRepair.repairJson("{\"key_1\n\": \"value\"}") shouldBe "{\"key_1\":\"value\"}"
    JsonRepair.repairJson("{\"key\t_\": \"value\"}") shouldBe "{\"key\\t_\":\"value\"}"
  }

  it should "handle object edge cases correctly" in {
    JsonRepair.repairJson("{       ") shouldBe "{}"
    JsonRepair.repairJson("{\"\":\"value\"") shouldBe "{\"\":\"value\"}"
    JsonRepair.repairJson(
      "{\"value_1\": true, COMMENT \"value_2\": \"data\"}"
    ) shouldBe "{\"value_1\":true,\"value_2\":\"data\"}"
    JsonRepair.repairJson(
      "{\"value_1\": true, SHOULD_NOT_EXIST \"value_2\": \"data\" AAAA }"
    ) shouldBe "{\"value_1\":true,\"value_2\":\"data\"}"
    JsonRepair.repairJson(
      "{\"\" : true, \"key2\": \"value2\"}"
    ) shouldBe "{\"\":true,\"key2\":\"value2\"}"
    JsonRepair.repairJson(
      "{\"\"answer\"\":[{\"\"traits\"\":''Female aged 60+'',\"\"answer1\"\":\"\"5\"\"}]}"
    ) shouldBe "{\"answer\":[{\"traits\":\"Female aged 60+\",\"answer1\":\"5\"}]}"
    JsonRepair.repairJson(
      "{ \"words\": abcdef\", \"numbers\": 12345\", \"words2\": ghijkl\" }"
    ) shouldBe "{\"words\":\"abcdef\",\"numbers\":12345,\"words2\":\"ghijkl\"}"
    JsonRepair.repairJson(
      "{\"number\": 1,\"reason\": \"According...\"\"ans\": \"YES\"}"
    ) shouldBe "{\"number\":1,\"reason\":\"According...\",\"ans\":\"YES\"}"
// TODO    JsonRepair.repairJson("""{ "a" : "{ b": {} }" }""") shouldBe """{"a":"{ b"}"""
    JsonRepair.repairJson("{\"b\": \"xxxxx\" true}") shouldBe "{\"b\":\"xxxxx\"}"
    JsonRepair.repairJson(
      "{\"key\": \"Lorem \"ipsum\" s,\"}"
    ) shouldBe "{\"key\":\"Lorem \\\"ipsum\\\" s,\"}"
    JsonRepair.repairJson(
      "{\"lorem\": ipsum, sic, datum.\",}"
    ) shouldBe "{\"lorem\":\"ipsum, sic, datum.\"}"
    JsonRepair.repairJson(
      "{\"lorem\": sic tamet. \"ipsum\": sic tamet, quick brown fox. \"sic\": ipsum}"
    ) shouldBe "{\"lorem\":\"sic tamet.\",\"ipsum\":\"sic tamet\",\"sic\":\"ipsum\"}"
    JsonRepair.repairJson(
      "{\"lorem_ipsum\": \"sic tamet, quick brown fox. }"
    ) shouldBe "{\"lorem_ipsum\":\"sic tamet, quick brown fox.\"}"
    JsonRepair.repairJson(
      "{\"key\":value, \" key2\":\"value2\" }"
    ) shouldBe "{\"key\":\"value\",\" key2\":\"value2\"}"
    JsonRepair.repairJson(
      "{\"key\":value \"key2\":\"value2\" }"
    ) shouldBe "{\"key\":\"value\",\"key2\":\"value2\"}"
    JsonRepair.repairJson(
      "{'text': 'words{words in brackets}more words'}"
    ) shouldBe "{\"text\":\"words{words in brackets}more words\"}"
    JsonRepair.repairJson(
      "{text:words{words in brackets}}"
    ) shouldBe "{\"text\":\"words{words in brackets}\"}"
    JsonRepair.repairJson(
      "{text:words{words in brackets}m}"
    ) shouldBe "{\"text\":\"words{words in brackets}m\"}"
    JsonRepair.repairJson(
      "{\"key\": \"value, value2\"```"
    ) shouldBe "{\"key\":\"value, value2\"}"
    JsonRepair.repairJson(
      "{key:value,key2:value2}"
    ) shouldBe "{\"key\":\"value\",\"key2\":\"value2\"}"
    JsonRepair.repairJson("{\"key:\"value\"}") shouldBe "{\"key\":\"value\"}"
    JsonRepair.repairJson("{\"key:value}") shouldBe "{\"key\":\"value\"}"
    JsonRepair.repairJson(
      "[{\"lorem\": {\"ipsum\": \"sic\"}, \"\"\"\" \"lorem\": {\"ipsum\": \"sic\"}]"
    ) shouldBe "[{\"lorem\":{\"ipsum\":\"sic\"}},{\"lorem\":{\"ipsum\":\"sic\"}}]"
    JsonRepair.repairJson(
      "{ \"key\": { \"key2\": \"value2\" // comment }, \"key3\": \"value3\" }"
    ) shouldBe "{\"key\":{\"key2\":\"value2\"},\"key3\":\"value3\"}"
    JsonRepair.repairJson(
      "{ \"key\": { \"key2\": \"value2\" # comment }, \"key3\": \"value3\" }"
    ) shouldBe "{\"key\":{\"key2\":\"value2\"},\"key3\":\"value3\"}"
    JsonRepair.repairJson(
      "{ \"key\": { \"key2\": \"value2\" /* comment */ }, \"key3\": \"value3\" }"
    ) shouldBe "{\"key\":{\"key2\":\"value2\"},\"key3\":\"value3\"}"
  }

  it should "handle number edge cases correctly" in {
    JsonRepair.repairJson(
      " - { \"test_key\": [\"test_value\", \"test_value2\"] }"
    ) shouldBe "{\"test_key\":[\"test_value\",\"test_value2\"]}"
    JsonRepair.repairJson("{\"key\": 1/3}") shouldBe "{\"key\":\"1/3\"}"
    JsonRepair.repairJson("{\"key\": .25}") shouldBe "{\"key\":0.25}"
    JsonRepair.repairJson(
      "{\"here\": \"now\", \"key\": 1/3, \"foo\": \"bar\"}"
    ) shouldBe "{\"here\":\"now\",\"key\":\"1/3\",\"foo\":\"bar\"}"
    JsonRepair.repairJson("{\"key\": 12345/67890}") shouldBe "{\"key\":\"12345/67890\"}"
    JsonRepair.repairJson("[105,12") shouldBe "[105,12]"
    JsonRepair.repairJson("{\"key\", 105,12,") shouldBe "{\"key\":\"105,12\"}"
    JsonRepair.repairJson(
      "{\"key\": 1/3, \"foo\": \"bar\"}"
    ) shouldBe "{\"key\":\"1/3\",\"foo\":\"bar\"}"
    JsonRepair.repairJson("{\"key\": 10-20}") shouldBe "{\"key\":\"10-20\"}"
    JsonRepair.repairJson("{\"key\": 1.1.1}") shouldBe "{\"key\":\"1.1.1\"}"
    JsonRepair.repairJson("[- ") shouldBe "[]"
  }

  it should "handle markdown content correctly" in {
    JsonRepair.repairJson(
      "{ \"content\": \"[LINK](\\\"https://google.com\\\")\" }"
    ) shouldBe "{\"content\":\"[LINK](\\\"https://google.com\\\")\"}"
    JsonRepair.repairJson("{ \"content\": \"[LINK](\" }") shouldBe "{\"content\":\"[LINK](\"}"
    JsonRepair.repairJson(
      "{ \"content\": \"[LINK](\", \"key\": true }"
    ) shouldBe "{\"content\":\"[LINK](\",\"key\":true}"
  }

  it should "handle leading and trailing characters correctly" in {
    JsonRepair.repairJson("````{ \"key\": \"value\" }```") shouldBe "{\"key\":\"value\"}"
    JsonRepair.repairJson(
      """{    "a": "",    "b": [ { "c": 1} ] \n}```"""
    ) shouldBe "{\"a\":\"\",\"b\":[{\"c\":1}]}"
    JsonRepair.repairJson(
      "Based on the information extracted, here is the filled JSON output: ```json { 'a': 'b' } ```"
    ) shouldBe "{\"a\":\"b\"}"
    JsonRepair.repairJson("""
                       The next 64 elements are:
                       ```json
                       { "key": "value" }
                       ```""") shouldBe "{\"key\":\"value\"}"
  }

  it should "handle multiple JSONs correctly" in {
    JsonRepair.repairJson("[]{}") shouldBe "[[],{}]"
    JsonRepair.repairJson("{}[]{}") shouldBe "[{},[],{}]"
    JsonRepair.repairJson(
      "{\"key\":\"value\"}[1,2,3,true]"
    ) shouldBe "[{\"key\":\"value\"},[1,2,3,true]]"
    JsonRepair.repairJson(
      "lorem ```json {\"key\":\"value\"} ``` ipsum ```json [1,2,3,true] ``` 42"
    ) shouldBe "[{\"key\":\"value\"},[1,2,3,true]]"
  }

  it should "handle return_objects parameter correctly" in {
    JsonRepair.repairJsonAsValue("[]") shouldBe Seq()
    JsonRepair.repairJsonAsValue("{}") shouldBe Map()
    JsonRepair.repairJsonAsValue(
      """{"key": true, "key2": false, "key3": null}"""
    ) shouldBe Map("key" -> true, "key2" -> false, "key3" -> null)
    JsonRepair.repairJsonAsValue(
      """{"name": "John", "age": 30, "city": "New York"}"""
    ) shouldBe Map(
      "name" -> "John",
      "age" -> 30,
      "city" -> "New York"
    )
    JsonRepair.repairJsonAsValue("[1, 2, 3, 4]") shouldBe Seq(1, 2, 3, 4)
    JsonRepair.repairJsonAsValue("""{"employees":["John", "Anna", "Peter"]} """) shouldBe Map(
      "employees" -> Seq("John", "Anna", "Peter")
    )
    JsonRepair.repairJsonAsValue("""
{
  "resourceType": "Bundle",
  "id": "1",
  "type": "collection",
  "entry": [
    {
      "resource": {
        "resourceType": "Patient",
        "id": "1",
        "name": [
          {"use": "official", "family": "Corwin", "given": ["Keisha", "Sunny"], "prefix": ["Mrs."]},
          {"use": "maiden", "family": "Goodwin", "given": ["Keisha", "Sunny"], "prefix": ["Mrs."]}
        ]
      }
    }
  ]
}
""") shouldBe Map(
      "resourceType" -> "Bundle",
      "id" -> "1",
      "type" -> "collection",
      "entry" -> Seq(
        Map(
          "resource" -> Map(
            "resourceType" -> "Patient",
            "id" -> "1",
            "name" -> Seq(
              Map(
                "use" -> "official",
                "family" -> "Corwin",
                "given" -> Seq("Keisha", "Sunny"),
                "prefix" -> Seq("Mrs.")
              ),
              Map(
                "use" -> "maiden",
                "family" -> "Goodwin",
                "given" -> Seq("Keisha", "Sunny"),
                "prefix" -> Seq("Mrs.")
              )
            )
          )
        )
      )
    )
    JsonRepair.repairJsonAsValue(
      """{"html": "<h3 id="aaa">Waarom meer dan 200 Technical Experts - "Passie voor techniek"?</h3>"}"""
    ) shouldBe Map(
      "html" -> """<h3 id="aaa">Waarom meer dan 200 Technical Experts - "Passie voor techniek"?</h3>"""
    )
//    JsonRepair.repairJsonAsValue("""
//        [
//            {
//                "foo": "Foo bar baz",
//                "tag": "#foo-bar-baz"
//            },
//            {
//                "foo": "foo bar "foobar" foo bar baz.",
//                "tag": "#foo-bar-foobar"
//            }
//        ]
//        """) shouldBe Seq(
//          Map("foo" -> "Foo bar baz", "tag" -> "#foo-bar-baz"),
//          Map("foo" -> """foo bar "foobar" foo bar baz.""", "tag" -> "#foo-bar-foobar")
//        )
  }

  // it should "handle skipJsonLoads parameter correctly" in {
  //   JsonRepair.repairJson("""{"key": true, "key2": false, "key3": null}""", skipJsonLoads = true) shouldBe """{"key": true, "key2": false, "key3": null}"""
  //   JsonRepair.repairJson("""{"key": true, "key2": false, "key3": null}""", returnObjects = true, skipJsonLoads = true) shouldBe Map("key" -> true, "key2" -> false, "key3" -> null)
  //   JsonRepair.repairJson("""{"key": true, "key2": false, "key3": }""", skipJsonLoads = true) shouldBe """{"key": true, "key2": false, "key3": ""}"""
  //   JsonRepair.loads("""{"key": true, "key2": false, "key3": }""", skipJsonLoads = true) shouldBe Map("key" -> true, "key2" -> false, "key3" -> "")
  // }

  it should "handle file operations correctly" in {
    val path = java.nio.file.Paths
      .get(getClass.getResource("/").toURI)
      .getParent
      .resolve("test-classes")

    // Test with a file containing valid JSON
    val tempFile1 = java.io.File.createTempFile("test", ".json")
    try {
      val writer = new java.io.FileWriter(tempFile1)
      writer.write("""{"key":"value"}""")
      writer.close()

      JsonRepair.fromFile(tempFile1.getAbsolutePath) shouldBe Json.obj("key" -> "value")
      JsonRepair.fromFile(tempFile1.getAbsolutePath, chunkLength = 2) shouldBe Json.obj(
        "key" -> "value"
      )
    } finally {
      tempFile1.delete()
    }
  }

  //   // Test with a file containing invalid JSON that needs repair
  //   val tempFile2 = java.io.File.createTempFile("test", ".json")
  //   try {
  //     val writer = new java.io.FileWriter(tempFile2)
  //     writer.write("{key:value}")
  //     writer.close()

  //     val (result, logs) = JsonRepair.fromFile(filename = tempFile2.getAbsolutePath, logging = true)
  //     result shouldBe Map("key" -> "value")
  //     logs.length should be > 0

  //     val (result2, logs2) = JsonRepair.fromFile(filename = tempFile2.getAbsolutePath, logging = true, chunkLength = 2)
  //     result2 shouldBe Map("key" -> "value")
  //     logs2.length should be > 0
  //   } finally {
  //     tempFile2.delete()
  //   }

  //   // Test with a very large file
  //   val tempFile3 = java.io.File.createTempFile("test", ".json")
  //   try {
  //     val writer = new java.io.FileWriter(tempFile3)
  //     writer.write("x" * 1024 * 10) // 10KB of 'x' characters
  //     writer.close()

  //     val (result, logs) = JsonRepair.fromFile(filename = tempFile3.getAbsolutePath, logging = true)
  //     result shouldBe ""
  //     logs shouldBe Seq()
  //   } finally {
  //     tempFile3.delete()
  //   }
  // }

  it should "handle ensure_ascii parameter correctly" in {
    JsonRepair.repairJson(
      """{'test_中国人_ascii':'统一码'}""",
      ensureAscii = false
    ) shouldBe """{"test_中国人_ascii":"统一码"}"""
  }

  // extra "real world" tests not in the original code
  it should "handle JSON with inner quotes" in {
    JsonRepair.repairJson(
      """{
        |  "invoiceNumber": "FA/222/2024",
        |  "invoiceDate": "2022-11-11",
        |  "lineItems": [
        |    {
        |      "itemNumber": 1,
        |      "description": "Wniosek o zmianę pozwolenia na ... „Something Polska" ... - zamówienie nr x /zlec. 1111/",
        |      "pricePerUnit": 500.00
        |    }
        |  ]
        |},""".stripMargin,
      ensureAscii = false
    ) shouldBe """{"invoiceNumber":"FA/222/2024","invoiceDate":"2022-11-11","lineItems":[{"itemNumber":1,"description":"Wniosek o zmianę pozwolenia na ... „Something Polska\" ... - zamówienie nr x /zlec. 1111/\"","pricePerUnit":500}]}"""
  }
}
