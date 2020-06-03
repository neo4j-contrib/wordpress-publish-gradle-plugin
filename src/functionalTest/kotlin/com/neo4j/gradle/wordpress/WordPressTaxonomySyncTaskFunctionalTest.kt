package com.neo4j.gradle.wordpress

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class WordPressTaxonomySyncTaskFunctionalTest {
  @Test
  fun `run task with empty values`() {
    // Setup the test build
    val projectDir = File("build/syncTaxonomyFunctionalTest")
    projectDir.mkdirs()
    val emptyDir = File("build/syncTaxonomyFunctionalTest/empty")
    emptyDir.mkdirs()
    projectDir.resolve("settings.gradle").writeText("")
    projectDir.resolve("build.gradle").writeText("""
import com.neo4j.gradle.wordpress.WordPressTaxonomySyncTask

plugins {
  id('com.neo4j.gradle.wordpress.WordPressPlugin')
}

wordpress {
  username = 'username'
  password = 'password'
  host = 'localhost'
  scheme = 'http'
}

task wordPressTaxonomySync(type: WordPressTaxonomySyncTask) {
  values = []
  restBase = "neo4j_version"
}
""")
    // Run the task
    val result = runTask(projectDir)
    val task = result.task(":wordPressTaxonomySync")
    assertEquals(TaskOutcome.SUCCESS, task?.outcome)
  }

  @Test
  fun `should create a new taxonomy value`() {
    // Setup mock server to simulate WordPress
    val wordPressMockServer = WordPressMockServer()
    val server = wordPressMockServer.setup("")
    try {
      server.start()
      // Setup the test build
      val projectDir = File("build/syncTaxonomyFunctionalTest")
      projectDir.mkdirs()
      val dir = File("build/syncTaxonomyFunctionalTest/create")
      dir.mkdirs()
      projectDir.resolve("settings.gradle").writeText("")
      projectDir.resolve("build.gradle").writeText("""
import com.neo4j.gradle.wordpress.WordPressTaxonomySyncTask

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

task wordPressTaxonomySync(type: WordPressTaxonomySyncTask) {
  values = ["1-9", "3-0", "2-3"]
  restBase = "neo4j_version"
}
""")

      // Run the task
      val result = runTask(projectDir)

      assertEquals(wordPressMockServer.dataReceived.size, 1)
      val taxonomyJson = wordPressMockServer.dataReceived.first()
      assertEquals(taxonomyJson["slug"] as String, "3-0")
      assertEquals(taxonomyJson["name"] as String, "3-0")
      val task = result.task(":wordPressTaxonomySync")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  fun `should create multiple new taxonomy values`() {
    // Setup mock server to simulate WordPress
    val wordPressMockServer = WordPressMockServer()
    val server = wordPressMockServer.setup("")
    try {
      server.start()
      // Setup the test build
      val projectDir = File("build/syncTaxonomyFunctionalTest")
      projectDir.mkdirs()
      val dir = File("build/syncTaxonomyFunctionalTest/create")
      dir.mkdirs()
      projectDir.resolve("settings.gradle").writeText("")
      projectDir.resolve("build.gradle").writeText("""
import com.neo4j.gradle.wordpress.WordPressTaxonomySyncTask

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

task wordPressTaxonomySync(type: WordPressTaxonomySyncTask) {
  values = ["1-9", "3-0", "2-3", "4-1"]
  restBase = "neo4j_version"
}
""")

      // Run the task
      val result = runTask(projectDir)

      assertEquals(wordPressMockServer.dataReceived.size, 2)
      val firstTaxonomyJson = wordPressMockServer.dataReceived[0]
      assertEquals(firstTaxonomyJson["slug"] as String, "3-0")
      assertEquals(firstTaxonomyJson["name"] as String, "3-0")
      val secondTaxonomyJson = wordPressMockServer.dataReceived[1]
      assertEquals(secondTaxonomyJson["slug"] as String, "4-1")
      assertEquals(secondTaxonomyJson["name"] as String, "4-1")
      val task = result.task(":wordPressTaxonomySync")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `should not create a new taxonomy value`() {
    // Setup mock server to simulate WordPress
    val wordPressMockServer = WordPressMockServer()
    val server = wordPressMockServer.setup("")
    try {
      server.start()
      // Setup the test build
      val projectDir = File("build/syncTaxonomyFunctionalTest")
      projectDir.mkdirs()
      val dir = File("build/syncTaxonomyFunctionalTest/create")
      dir.mkdirs()
      projectDir.resolve("settings.gradle").writeText("")
      projectDir.resolve("build.gradle").writeText("""
import com.neo4j.gradle.wordpress.WordPressTaxonomySyncTask

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

task wordPressTaxonomySync(type: WordPressTaxonomySyncTask) {
  values = ["1-9", "2-1", "2-3"]
  restBase = "neo4j_version"
}
""")

      // Run the task
      val result = runTask(projectDir)

      assertEquals(wordPressMockServer.dataReceived.size, 0)
      val task = result.task(":wordPressTaxonomySync")
      assertEquals(TaskOutcome.SUCCESS, task?.outcome)
    } finally {
      server.shutdown()
    }
  }

  private fun runTask(projectDir: File): BuildResult {
    val runner = GradleRunner.create()
    runner.forwardOutput()
    runner.withPluginClasspath()
    runner.withArguments("wordPressTaxonomySync")
    runner.withProjectDir(projectDir)
    runner.withDebug(true)
    return runner.build()
  }
}
