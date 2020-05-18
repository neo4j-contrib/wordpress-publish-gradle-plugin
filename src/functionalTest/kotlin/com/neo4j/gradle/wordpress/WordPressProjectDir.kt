package com.neo4j.gradle.wordpress

import java.io.File

data class PostWithMetadata(val fileName: String, val htmlContent: String, val yamlContent: String)

object WordPressProjectDir {
  fun setupUploadTask(dirName: String, posts: List<PostWithMetadata>, port: Int, hostName: String): File {
    // Setup the test build
    val projectDir = File("build/functionalTest")
    projectDir.mkdirs()
    val htmlDir = File("build/functionalTest/$dirName")
    htmlDir.mkdirs()
    for (post in posts) {
      htmlDir.resolve("${post.fileName}.html").writeText(post.htmlContent)
      htmlDir.resolve("${post.fileName}.yml").writeText(post.yamlContent.trimIndent())
    }
    projectDir.resolve("settings.gradle").writeText("")
    projectDir.resolve("build.gradle").writeText("""
import com.neo4j.gradle.wordpress.WordPressUploadTask

plugins {
  id('com.neo4j.gradle.wordpress.WordPressPlugin')
}

wordpress {
  username = 'username'
  password = 'password'
  port = $port
  host = '$hostName'
  scheme = 'http'
}

task wordPressUpload(type: WordPressUploadTask) {
  source = "$dirName"
  type = "post"
  status = "private"
}
""")
    return projectDir
  }
}
