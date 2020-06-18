package com.neo4j.gradle.wordpress

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.test.*


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
    // Run the task
    val result = runTask(projectDir)
    val task = result.task(":wordPressUpload")
    assertEquals(TaskOutcome.SUCCESS, task?.outcome)
  }

  @Test
  fun `should create a new post`() {
    // Setup mock server to simulate WordPress
    val wordPressMockServer = WordPressMockServer()
    val server = wordPressMockServer.setup("00-intro-neo4j-about")
    try {
      server.start()
      // Setup the test build
      val post = PostWithMetadata(
        fileName = "test",
        htmlContent = """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>""",
        yamlContent = """
---
slug: 00-intro-neo4j-about
title: Introduction to Neo4j 4.0
""")
      val projectDir = WordPressProjectDir.setupUploadTask("test-minimal", listOf(post), server.port, server.hostName)
      // Run the task
      val result = runTask(projectDir)

      assertEquals(wordPressMockServer.dataReceived.size, 1)
      val postJson = wordPressMockServer.dataReceived.first()
      assertEquals(postJson["slug"] as String, "00-intro-neo4j-about")
      assertEquals(postJson["status"] as String, "private")
      assertEquals(postJson["title"] as String, "Introduction to Neo4j 4.0")
      assertEquals(postJson["content"] as String, """${post.htmlContent}
<!-- METADATA! {"digest":"53619184ec839e7ac785b827413fad5c"} !METADATA -->""")
      assertEquals(postJson["type"] as String, "post")
      val task = result.task(":wordPressUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `should send the excerpt when defined on a new post`() {
    // Setup mock server to simulate WordPress
    val wordPressMockServer = WordPressMockServer()
    val server = wordPressMockServer.setup("00-intro-neo4j-about")
    try {
      server.start()
      // Setup the test build
      val post = PostWithMetadata(
        fileName = "test",
        htmlContent = """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>""",
        yamlContent = """
---
slug: 00-intro-neo4j-about
title: Introduction to Neo4j 4.0
excerpt: Short introduction to Neo4j 4.0
""")
      val projectDir = WordPressProjectDir.setupUploadTask("test-excerpt", listOf(post), server.port, server.hostName)
      // Run the task
      val result = runTask(projectDir)

      assertEquals(wordPressMockServer.dataReceived.size, 1)
      val postJson = wordPressMockServer.dataReceived.first()
      assertEquals(postJson["slug"] as String, "00-intro-neo4j-about")
      assertEquals(postJson["status"] as String, "private")
      assertEquals(postJson["title"] as String, "Introduction to Neo4j 4.0")
      assertEquals(postJson["content"] as String, """${post.htmlContent}
<!-- METADATA! {"digest":"85c6dd36e09cdf01f01afeac1abcad25"} !METADATA -->""")
      assertEquals(postJson["type"] as String, "post")
      assertEquals((postJson["excerpt"] as JsonObject)["rendered"] as String, "Short introduction to Neo4j 4.0")
      val task = result.task(":wordPressUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `should send the featured media when found on a new post`() {
    // Setup mock server to simulate WordPress
    val wordPressMockServer = WordPressMockServer()
    val server = wordPressMockServer.setup("00-intro-neo4j-about")
    try {
      server.start()
      // Setup the test build
      val post = PostWithMetadata(
        fileName = "test",
        htmlContent = """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>""",
        yamlContent = """
---
slug: 00-intro-neo4j-about
title: Introduction to Neo4j 4.0
featured_media: neo4j-nodes-bottom
""")
      val projectDir = WordPressProjectDir.setupUploadTask("test-media", listOf(post), server.port, server.hostName)
      // Run the task
      val result = runTask(projectDir)

      assertEquals(wordPressMockServer.dataReceived.size, 1)
      val postJson = wordPressMockServer.dataReceived.first()
      assertEquals(postJson["slug"] as String, "00-intro-neo4j-about")
      assertEquals(postJson["status"] as String, "private")
      assertEquals(postJson["title"] as String, "Introduction to Neo4j 4.0")
      assertEquals(postJson["content"] as String, """${post.htmlContent}
<!-- METADATA! {"digest":"04c1c9d4df40d061f15d127f8211c68f"} !METADATA -->""")
      assertEquals(postJson["type"] as String, "post")
      assertEquals(postJson["featured_media"] as Number, 121795)
      val task = result.task(":wordPressUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `should not update an existing post when content has not changed`() {
    // Setup mock server to simulate WordPress
    val wordPressMockServer = WordPressMockServer()
    val server = wordPressMockServer.setup("01-neo4j-graph-database")
    try {
      server.start()
      // Setup the test build
      val post = PostWithMetadata(
        fileName = "test",
        htmlContent = """<section>
  <h2>Neo4j is a Graph Database</h2>
</section>""",
        yamlContent = """
---
slug: 01-neo4j-graph-database
title: Neo4j is a Graph Database
""")
      val projectDir = WordPressProjectDir.setupUploadTask("test-unchanged", listOf(post), server.port, server.hostName)
      // Run the task
      val result = runTask(projectDir)

      assertEquals(wordPressMockServer.dataReceived.size, 0)
      val task = result.task(":wordPressUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `should not send the featured media when not found on a new post`() {
    // Setup mock server to simulate WordPress
    val wordPressMockServer = WordPressMockServer()
    val server = wordPressMockServer.setup("00-intro-neo4j-about")
    try {
      server.start()
      // Setup the test build
      val post = PostWithMetadata(
        fileName = "test",
        htmlContent = """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>""",
        yamlContent = """
---
slug: 00-intro-neo4j-about
title: Introduction to Neo4j 4.0
featured_media: unexisting-media-slug
""")
      val projectDir = WordPressProjectDir.setupUploadTask("test-media", listOf(post), server.port, server.hostName)
      // Run the task
      val result = runTask(projectDir)

      assertEquals(wordPressMockServer.dataReceived.size, 1)
      val postJson = wordPressMockServer.dataReceived.first()
      assertEquals(postJson["slug"] as String, "00-intro-neo4j-about")
      assertEquals(postJson["status"] as String, "private")
      assertEquals(postJson["title"] as String, "Introduction to Neo4j 4.0")
      assertEquals(postJson["content"] as String, """${post.htmlContent}
<!-- METADATA! {"digest":"53619184ec839e7ac785b827413fad5c"} !METADATA -->""")
      assertEquals(postJson["type"] as String, "post")
      assertFalse(postJson.containsKey("featured_media"))
      val task = result.task(":wordPressUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `should slugify and resolve tag on a new post`() {
    // Setup mock server to simulate WordPress
    val wordPressMockServer = WordPressMockServer()
    val server = wordPressMockServer.setup("00-intro-neo4j-about")
    try {
      server.start()
      // Setup the test build
      val post = PostWithMetadata(
        fileName = "test",
        htmlContent = """<section>
  <h2>Introduction to Neo4j 4.0</h2>
</section>""",
        yamlContent = """
---
slug: 00-intro-neo4j-about
title: Introduction to Neo4j 4.0
tags:
  - etl
  - unexisting-tag
  - relational-database
  - relational graph
""")
      val projectDir = WordPressProjectDir.setupUploadTask("test-tags", listOf(post), server.port, server.hostName)
      // Run the task
      val result = runTask(projectDir)

      assertEquals(wordPressMockServer.dataReceived.size, 1)
      val postJson = wordPressMockServer.dataReceived.first()
      assertEquals(postJson["slug"] as String, "00-intro-neo4j-about")
      assertEquals(postJson["status"] as String, "private")
      assertEquals(postJson["title"] as String, "Introduction to Neo4j 4.0")
      assertEquals(postJson["content"] as String, """${post.htmlContent}
<!-- METADATA! {"digest":"40e5d6ec858a74f7a28ef09a9e5935fc"} !METADATA -->""")
      assertEquals(postJson["type"] as String, "post")
      assertNotNull(postJson["tags"])
      val tags = postJson["tags"] as JsonArray<*>
      assertTrue(tags.isNotEmpty())
      assertEquals(tags.size, 3)
      assertTrue(tags.contains(1))
      assertTrue(tags.contains(2))
      assertTrue(tags.contains(3))
      val task = result.task(":wordPressUpload")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  private fun runTask(projectDir: File): BuildResult {
    val runner = GradleRunner.create()
    runner.forwardOutput()
    runner.withPluginClasspath()
    runner.withArguments("wordPressUpload")
    runner.withProjectDir(projectDir)
    runner.withDebug(true)
    return runner.build()
  }
}
