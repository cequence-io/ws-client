# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**WS Client** is a generic WebServices client library for Scala that provides an abstraction layer over Play WS implementation. The library enables type-safe HTTP requests with support for multiple Scala versions (2.12, 2.13, and 3).

The project includes:
- Core WS client interfaces and abstractions
- Play WS backend implementation
- Streaming support for large payloads
- JSON repair utility for fixing malformed JSON from LLMs

Current version: 0.7.3

## Build Commands

The project uses **SBT** as the build tool.

### Core Commands
- `sbt compile` - Compile all modules
- `sbt test` - Run all tests
- `sbt +compile` - Cross-compile for all Scala versions (2.12, 2.13, 3.2)
- `sbt +test` - Cross-test for all Scala versions

### Module-Specific Commands
- `sbt ws-client-core/compile` - Compile core module only
- `sbt ws-client-play/compile` - Compile Play WS implementation
- `sbt ws-client-play-stream/compile` - Compile streaming support
- `sbt json-repair/compile` - Compile JSON repair module
- `sbt json-repair/test` - Run JSON repair tests

### Code Quality
- `sbt scalafmt` - Format code with scalafmt
- `sbt scalafmtCheck` - Check code formatting
- `sbt scalafix` - Run scalafix
- `sbt coverage test coverageReport` - Generate test coverage report

### Publishing
- `sbt publishLocal` - Publish to local ivy repository
- `sbt publishSigned` - Publish signed artifacts to Sonatype

## Architecture

### Module Structure

The project is organized into 4 SBT modules:

1. **ws-client-core** - Core abstractions and interfaces
   - No concrete HTTP implementation
   - Defines `WSClient`, `WSClientEngine`, and `WSClientBase` traits
   - Contains domain models (`RichResponse`, `WsRequestContext`, `CequenceWSException`)
   - Provides service adapters (logging, retries, round-robin, parallel execution)
   - Uses Akka Streams for reactive data handling

2. **ws-client-play** - Play WS backend implementation
   - Depends on `ws-client-core`
   - Implements `PlayWSClientEngine` using Play WS standalone client
   - Handles HTTP methods: GET, POST, DELETE, PATCH, PUT
   - Supports multiple content types: JSON, multipart/form-data, URL-encoded, file uploads, streaming
   - Default timeouts: 120 seconds for both request and readout

3. **ws-client-play-stream** - Streaming extensions
   - Depends on `ws-client-core` and `ws-client-play`
   - Adds `PlayWSStreamClientEngine` for Server-Sent Events (SSE)
   - Uses Akka HTTP for JSON streaming over WebSockets
   - Streaming frame limit: 20,000 bytes (defaultMaxFrameLength)

4. **json-repair** - JSON repair utility
   - Independent module for fixing malformed JSON
   - Port of Python json_repair library
   - Main API: `JsonRepair.repair(String)`, `JsonRepair.loads(String)`, `JsonRepair.fromFile(File)`
   - 140+ tests ported from original Python library

### Key Architectural Patterns

#### Trait-Based Abstraction
The library uses a layered trait hierarchy:
```
WSClientBase (basic request handling)
  ↓
WSClient (HTTP method definitions with PEP/PT type parameters)
  ↓
WSClientEngine (URL construction, adds coreUrl and requestContext)
  ↓
PlayWSClientEngine (concrete Play WS implementation)
```

Type parameters `PEP` (endpoint) and `PT` (parameter type) allow subclasses to define their own endpoint and parameter types (commonly enums).

#### Service Adapters
The library provides composable service adapters in `io.cequence.wsclient.service.adapter`:
- `LogServiceAdapter` - Request/response logging
- `RoundRobinAdapter` - Load balancing across multiple services
- `RandomOrderAdapter` - Random service selection
- `ParallelTakeFirstAdapter` - Parallel execution, returns first response
- `PreServiceAdapter` - Execute action before each request
- `RepackExceptionsAdapter` - Transform exceptions

#### Rich Response Pattern
All HTTP methods have two variants:
- `execGET()` - Returns `Future[Response]`, throws on error
- `execGETRich()` - Returns `Future[RichResponse]`, includes status/headers even on failure

This allows callers to handle non-2xx responses gracefully without exceptions.

#### Request Context
`WsRequestContext` provides request-scoped configuration:
- Authentication headers
- Extra query parameters
- Custom timeouts via `Timeouts` case class

Use `PlayWSClientEngine.withContextFun()` to provide dynamic context per request.

### Cross-Version Compatibility

The build manages different dependency versions for Scala 2.12, 2.13, and 3.2:
- Play JSON: 2.8.2 (Scala 2.12/2.13), 2.10.0-RC6 (Scala 3.2)
- Akka Stream: 2.6.1 (Scala 2.12), 2.6.20 (Scala 2.13/3.2)
- Play WS: 2.1.11 (Scala 2.12/2.13), 2.2.0-M2 (Scala 3.2)

Scala 3 requires manual cross-version suffix handling (e.g., `akka-stream_2.13` for Scala 3).

## Testing

- Test files are located in `*/src/test/scala/`
- Primary test suite: `json-repair/src/test/scala/io/cequence/jsonrepair/JsonRepairSpec.scala`
- Run `sbt test` for all tests or `sbt <module>/test` for specific module

## Key Dependencies

- Play JSON - JSON serialization/deserialization
- Play WS Standalone - HTTP client
- Akka Streams - Reactive stream processing
- Akka HTTP - WebSocket and SSE support (streaming module)
- scala-logging + logback - Logging
- ScalaTest - Testing framework (test scope)

## Common Development Patterns

### Creating a WS Client
```scala
import io.cequence.wsclient.service.ws.PlayWSClientEngine
import akka.stream.Materializer
import scala.concurrent.ExecutionContext

implicit val materializer: Materializer = ???
implicit val ec: ExecutionContext = ???

val client = PlayWSClientEngine(
  coreUrl = "https://api.example.com",
  requestContext = WsRequestContext()
)
```

### Making Requests
```scala
// Simple GET
client.execGET(endPoint = "users", params = Seq("id" -> Some(123)))

// POST with JSON body
client.execPOST(
  endPoint = "users",
  bodyParams = Seq("name" -> Some(Json.toJson("John")))
)

// Rich response with error handling
client.execGETRich(
  endPoint = "users",
  acceptableStatusCodes = Seq(200, 404)
).map { response =>
  response.response match {
    case Some(resp) => // Success
    case None => // Handle non-acceptable status
  }
}
```

### Using Service Adapters
Service adapters are applied via the `ServiceBaseAdapters` trait pattern. Extend your service with this trait and compose adapters:

```scala
// Round-robin across multiple backends
val service = roundRobin(service1, service2, service3)

// Add logging
val loggedService = log(service, "MyService")
```

## Important Notes

- The library uses Play JSON (`JsValue`) as the JSON representation
- All async operations return `Future[T]`
- Timeouts are configurable per request via `WsRequestContext.explTimeouts`
- Error recovery is customizable via `recoverErrors` parameter in `PlayWSClientEngine`
- JSON repair is procedural (faithful port from Python) and may not be idiomatic Scala