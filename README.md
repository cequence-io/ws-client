# WS Client (Cequence)
[![version](https://img.shields.io/badge/version-0.7.1-green.svg)](https://cequence.io) [![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](https://opensource.org/licenses/MIT) [![Twitter Follow](https://img.shields.io/twitter/follow/cequence_io?style=social)](https://twitter.com/0xbnd)

This repository contains a simple and efficient Web Service client implemented in Scala. The client is designed to interact with RESTful web services, making it easy to send requests, handle responses, and manage errors.

**🔥 New**: as a part of this suite we provide [json-repair](./json-repair/README.md) library that can be used to fix common JSON syntax errors, repair malformed JSON objects and arrays.

## Installation 🚀

The currently supported Scala versions are **2.12, 2.13**, and **3**.

To install the library, add the following dependency to your *build.sbt*

```
"io.cequence" %% "ws-client-play"" % "0.7.1"
```

or to *pom.xml* (if you use maven)

```
<dependency>
    <groupId>io.cequence</groupId>
    <artifactId>ws-client-play_2.12</artifactId>
    <version>0.7.1</version>
</dependency>
```

## License ⚖️

This library is available and published as open source under the terms of the [MIT License](https://opensource.org/licenses/MIT).