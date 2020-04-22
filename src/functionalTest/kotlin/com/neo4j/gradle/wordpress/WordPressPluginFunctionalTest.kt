package com.neo4j.gradle.wordpress

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class WordPressPluginFunctionalTest {
    @Test
    fun canRunTaskOnEmptyDirectory() {
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
}
