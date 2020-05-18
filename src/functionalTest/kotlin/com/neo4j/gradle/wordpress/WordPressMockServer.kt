package com.neo4j.gradle.wordpress

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.StringReader


class WordPressMockServer {

  // Setup mock server to simulate WordPress
  val server = MockWebServer()
  val klaxon = Klaxon()
  var postJson = JsonObject()

  fun setup(slug: String): MockWebServer {
    // Setup mock server to simulate WordPress
    val dispatcher: Dispatcher = object : Dispatcher() {
      @Throws(InterruptedException::class)
      override fun dispatch(request: RecordedRequest): MockResponse {
        when (request.path) {
          "/wp-json/wp/v2/media?slug=neo4j-nodes-bottom&page=1&per_page=1" -> {
            // returns an empty array, the post does not exist!
            return MockResponse()
              .setHeader("X-WP-Total", "1")
              .setHeader("X-WP-TotalPages", "1")
              .setHeader("Content-Type", "application/json")
              .setBody("[{\"id\": 121795, \"slug\": \"neo4j-nodes-bottom\"}]")
              .setResponseCode(200)
          }
          "/wp-json/wp/v2/posts?per_page=1&slug=$slug&status=publish%2Cfuture%2Cdraft%2Cpending%2Cprivate" -> {
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
    return server
  }
}
