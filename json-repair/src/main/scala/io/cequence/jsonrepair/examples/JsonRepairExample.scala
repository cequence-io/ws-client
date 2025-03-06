package io.cequence.jsonrepair.examples

import io.cequence.jsonrepair.JsonRepair
import play.api.libs.json._

object JsonRepairExample extends App {
  // Example 1: Repair a simple JSON string with a missing comma
  val invalidJson1 = """{"name": "John" "age": 30}"""
  val repairedJson1 = JsonRepair.repair(invalidJson1)
  println("Example 1 - Invalid JSON:")
  println(invalidJson1)
  println("Repaired JSON:")
  println(repairedJson1)
  println()

  // Example 2: Repair a JSON string with a missing and trailing comma
  val invalidJson2 = """{"items": [1, 2, 3,], "status" "ok",}"""
  val repairedJson2 = JsonRepair.repairJson(invalidJson2, logging = true)
  println("Example 2 - Invalid JSON with trailing commas:")
  println(invalidJson2)
  println("Repaired JSON:")
  println(repairedJson2)
  println()

  // Example 3: Repair a JSON string with single quotes
  val invalidJson3 = """{'name': 'Alice', 'details': {'city': 'New York', 'zip': '10001'}}"""
  val repairedJson3 = JsonRepair.repairJson(invalidJson3, logging = true)
  println("Example 3 - JSON with single quotes:")
  println(invalidJson3)
  println("Repaired JSON:")
  println(repairedJson3)
  println()

  // Example 4: Repair and parse into a Play JSON value
  val invalidJson4 = """{"user": "Bob" "active": true "scores": [85, 90, 92]}"""
  val jsValue = JsonRepair.loads(invalidJson4, logging = true)

  println("Example 4 - Invalid JSON:")
  println(invalidJson4)

  println("Parsed as Play JSON:")
  println(Json.prettyPrint(jsValue))

  // Access values from the repaired JSON
  val username = (jsValue \ "user").as[String]

  // Check if active is a boolean or string
  val isActive = (jsValue \ "active").validate[Boolean].getOrElse {
    (jsValue \ "active").as[String].toLowerCase == "true"
  }

  val scores = (jsValue \ "scores").as[Seq[Int]]

  println(s"Username: $username")
  println(s"Active: $isActive")
  println(s"Scores: ${scores.mkString(", ")}")

  val json = JsonRepair.fromFile("/path/to/json.json")
  println(json)
  // println("\nRepair logs:")
  // logs.foreach(log => println(s"- $log"))
}
