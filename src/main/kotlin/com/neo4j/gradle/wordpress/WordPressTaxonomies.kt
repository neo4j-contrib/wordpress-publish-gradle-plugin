package com.neo4j.gradle.wordpress

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonException
import org.gradle.api.logging.Logger


class WordPressTaxonomies(private val wordPressHttpClient: WordPressHttpClient, private val logger: Logger) {

  fun getTaxonomyReferencesBySlug(documentType: WordPressDocumentType, taxonomySlugs: Set<String>): Map<String, Map<String, Int>> {
    // taxonomies cannot be assigned on a page
   return if (documentType.name !== "page") {
      val taxonomyEndpoints = getTaxonomyEndpoints(documentType).map {
        it.slug to it
      }.toMap()
      taxonomySlugs.mapNotNull { taxonomySlug ->
        val taxonomyEndpoint = taxonomyEndpoints[taxonomySlug]
        if (taxonomyEndpoint == null) {
          logger.warn("Taxonomy $taxonomySlug does not exist, unable to set this taxonomy on posts")
          null
        } else {
          val taxonomyReferences = getTaxonomyReferences(taxonomyEndpoint)
          taxonomySlug to taxonomyReferences.map { it.slug to it.id }.toMap()
        }
      }.toMap()
    } else {
      emptyMap()
    }
  }

  private fun getTaxonomyReferences(taxonomyEndpoint: TaxonomyEndpoint): List<TaxonomyReference> {
    val baseUrl = wordPressHttpClient.baseUrlBuilder()
      .addPathSegment(taxonomyEndpoint.endpoint)
      .build()
    return wordPressHttpClient.getRecursiveObjects(baseUrl = baseUrl) { result ->
      result.mapNotNull {
        if (it is JsonObject) {
          TaxonomyReference(it["slug"] as String, it["id"] as Int)
        } else {
          null
        }
      }
    }
  }

  private fun getTaxonomyEndpoints(documentType: WordPressDocumentType): List<TaxonomyEndpoint> {
    val searchUrl = wordPressHttpClient.baseUrlBuilder()
      .addPathSegment("taxonomies")
      .build()
    val searchRequest = wordPressHttpClient.buildGetRequest(searchUrl)
    return wordPressHttpClient.executeRequest(searchRequest) { responseBody ->
      try {
        resolveTaxonomyEndpoints(wordPressHttpClient.parseJsonObject(responseBody), documentType)
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response", e)
        null
      }
    }.orEmpty()
  }

  private fun resolveTaxonomyEndpoints(taxonomies: JsonObject, documentType: WordPressDocumentType): List<TaxonomyEndpoint> {
    return taxonomies.keys.mapNotNull { key ->
      val taxonomyObject = taxonomies[key]
      if (taxonomyObject is JsonObject) {
        val types = taxonomyObject["types"]
        if (types is JsonArray<*> && types.value.contains(documentType.name)) {
          // remind: we are using the rest_base and not the slug!
          // most of the time the "rest_base" is equals to the "slug"
          // but for "categories" and "tags" it's better to use the "rest_base" because the name is consistent:
          // for instance, for "tags", rest_base is equals to "tags" while the slug is equals to "post_tag"!
          TaxonomyEndpoint(taxonomyObject.getValue("rest_base") as String, taxonomyObject.getValue("rest_base") as String)
        } else {
          null
        }
      } else {
        null
      }
    }
  }
}

data class TaxonomyReference(val slug: String, val id: Int)

data class TaxonomyEndpoint(val slug: String, val endpoint: String)
