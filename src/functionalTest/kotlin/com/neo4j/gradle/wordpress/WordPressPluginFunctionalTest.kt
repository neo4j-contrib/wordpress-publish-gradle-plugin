package com.neo4j.gradle.wordpress

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals


class WordPressPluginFunctionalTest {
  @Test
  fun `run task on empty directory`() {
    // Setup the test build
    val projectDir = File("build/functionalTest")
    projectDir.mkdirs()
    val emptyDir = File("build/functionalTest/empty")
    emptyDir.mkdirs()
    projectDir.resolve("settings.gradle").writeText("")
    projectDir.resolve("build.gradle").writeText("""
import com.neo4j.gradle.wordpress.WordPressUploadTask

plugins {
  id('com.neo4j.gradle.wordpress.WordPressPlugin')
}

wordpress {
  username = 'username'
  password = 'password'
  host = 'localhost'
  scheme = 'http'
}

task wordPressUpload(type: WordPressUploadTask) {
  source = "${projectDir}/empty"
  type = "post"
  status = "private"
}
""")

    // Run the build
    val runner = GradleRunner.create()
    runner.forwardOutput()
    runner.withPluginClasspath()
    runner.withArguments("wordPressUpload")
    runner.withProjectDir(projectDir)
    val result = runner.build()

    val task = result.task(":wordPressUpload")
    assertEquals(TaskOutcome.SUCCESS, task?.outcome)
  }

  @Test
  fun `should create a new post`() {
    // Setup mock server to simulate WordPress
    val server = MockWebServer()
    val klaxon = Klaxon()
    var postJson: JsonObject = JsonObject()
    val dispatcher: Dispatcher = object : Dispatcher() {
      @Throws(InterruptedException::class)
      override fun dispatch(request: RecordedRequest): MockResponse {
        when (request.path) {
          "/wp-json/wp/v2/posts?per_page=1&slug=00-intro-neo4j-about&status=publish%2Cfuture%2Cdraft%2Cpending%2Cprivate" -> {
            // returns an empty array, the post does not exist!
            return MockResponse()
              .setHeader("X-WP-Total", "0")
              .setHeader("X-WP-TotalPages", "1")
              .setHeader("Content-Type", "application/json")
              .setBody("[]")
              .setResponseCode(200)
          }
          "/wp-json/wp/v2/posts" -> {
            postJson = klaxon.parseJsonObject(StringReader(request.body.readUtf8()))
            return MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody("""{"id": 1}""")
              .setResponseCode(200)
          }
        }
        return MockResponse().setResponseCode(404)
      }
    }
    server.dispatcher = dispatcher
    try {
      server.start()
      // Setup the test build
      val projectDir = File("build/functionalTest")
      projectDir.mkdirs()
      val htmlDir = File("build/functionalTest/html")
      htmlDir.mkdirs()
      val htmlContent = """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>"""
      htmlDir.resolve("test.html").writeText(htmlContent)
      htmlDir.resolve("test.yml").writeText("""
---
slug: 00-intro-neo4j-about
title: Introduction to Neo4j 4.0
""".trimIndent())
      projectDir.resolve("settings.gradle").writeText("")
      projectDir.resolve("build.gradle").writeText("""
import com.neo4j.gradle.wordpress.WordPressUploadTask

plugins {
  id('com.neo4j.gradle.wordpress.WordPressPlugin')
}

wordpress {
  username = 'username'
  password = 'password'
  port = ${server.port}
  host = '${server.hostName}'
  scheme = 'http'
}

task wordPressUpload(type: WordPressUploadTask) {
  source = "html"
  type = "post"
  status = "private"
}
""")

      // Run the build
      val runner = GradleRunner.create()
      runner.forwardOutput()
      runner.withPluginClasspath()
      runner.withArguments(":wordPressUpload")
      runner.withDebug(true)
      runner.withProjectDir(projectDir)
      val result = runner.build()

      assertEquals(postJson["slug"] as String, "00-intro-neo4j-about")
      assertEquals(postJson["status"] as String, "private")
      assertEquals(postJson["title"] as String, "Introduction to Neo4j 4.0")
      assertEquals(postJson["content"] as String, htmlContent)
      assertEquals(postJson["type"] as String, "post")
      val task = result.task(":wordPressUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }
}
