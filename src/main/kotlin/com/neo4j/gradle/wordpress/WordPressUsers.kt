package com.neo4j.gradle.wordpress

import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonException
import org.gradle.api.logging.Logger


class WordPressUsers(private val wordPressHttpClient: WordPressHttpClient, private val logger: Logger) {

  fun findUser(name: String): WordPressUser? {
    val url = wordPressHttpClient.baseUrlBuilder()
      .addPathSegment("users")
      .addQueryParameter("search", name)
      .addQueryParameter("page", "1")
      .addQueryParameter("per_page", "1")
      .build()
    return wordPressHttpClient.executeRequest(wordPressHttpClient.buildGetRequest(url)) { responseBody ->
      try {
        val users = wordPressHttpClient.parseJsonArray(responseBody)
        if (users.isNotEmpty()) {
          val user = users.first()
          if (user is JsonObject) {
              WordPressUser(user["slug"] as String, user["name"] as String, user["id"] as Int)
          } else {
            null
          }
        } else {
          null
        }
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response", e)
        null
      }
    }
  }
}

data class WordPressUser(val slug: String, val name: String, val id: Int)
