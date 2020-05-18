package com.neo4j.gradle.wordpress

import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonException
import org.gradle.api.logging.Logger


class WordPressMedias(private val wordPressHttpClient: WordPressHttpClient, private val logger: Logger) {

  fun findMedia(slug: String): WordPressMedia? {
    val url = wordPressHttpClient.baseUrlBuilder()
      .addPathSegment("media")
      .addQueryParameter("slug", slug)
      .addQueryParameter("page", "1")
      .addQueryParameter("per_page", "1")
      .build()
    return wordPressHttpClient.executeRequest(wordPressHttpClient.buildGetRequest(url)) { responseBody ->
      try {
        val medias = wordPressHttpClient.parseJsonArray(responseBody)
        if (medias.isNotEmpty()) {
          val media = medias.first()
          if (media is JsonObject) {
            WordPressMedia(media["slug"] as String, (media["id"] as Number).toLong())
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

data class WordPressMedia(val slug: String, val id: Long)
