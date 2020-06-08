package com.neo4j.gradle.wordpress

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.KlaxonException
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction


open class WordPressTaxonomySyncTask : WordPressTask() {

  @Input
  var values: MutableList<String> = mutableListOf()

  @Input
  var restBase: String = ""

  @TaskAction
  fun task() {
    if (restBase.isBlank()) {
      logger.error("The taxonomy rest_base is mandatory, aborting...")
      return
    }
    if (values.isEmpty()) {
      logger.quiet("No values to sync, skipping...")
      return
    }
    val httpClient = WordPressHttpClient(wordPressConnectionInfo(), logger)
    val wordPressTaxonomies = WordPressTaxonomies(
      wordPressHttpClient = httpClient,
      logger = logger
    )
    wordPressTaxonomies.sync(restBase, values)
  }
}

class WordPressTaxonomies(private val wordPressHttpClient: WordPressHttpClient, private val logger: Logger) {

  fun sync(taxonomyRestBase: String, values: List<String>) {
    val taxonomyEndpoint = TaxonomyEndpoint(taxonomyRestBase, taxonomyRestBase)
    val taxonomyReferences = getTaxonomyReferences(taxonomyEndpoint)
    val taxonomyReferenceSlugs = taxonomyReferences.map { it.slug }
    val missingTaxonomies = values.filter { !taxonomyReferenceSlugs.contains(it) }
    if (missingTaxonomies.isNotEmpty()) {
      logger.quiet("$missingTaxonomies are missing in $taxonomyRestBase, creating...")
      for (missingTaxonomy in missingTaxonomies) {
        createTaxonomy(taxonomyEndpoint, missingTaxonomy)
      }
    } else {
      logger.quiet("$taxonomyRestBase is up-to-date")
    }
  }

  fun getTaxonomyReferencesBySlug(documentType: WordPressDocumentType, taxonomySlugs: Set<String>): Map<String, Map<String, Int>> {
    // taxonomies cannot be assigned on a page
   return if (taxonomySlugs.isNotEmpty() && documentType.name !== "page") {
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

  private fun createTaxonomy(taxonomyEndpoint: TaxonomyEndpoint, value: String): TaxonomyValue? {
    val baseUrl = wordPressHttpClient.baseUrlBuilder()
      .addPathSegment(taxonomyEndpoint.endpoint)
      .build()
    val data = mutableMapOf<String, Any>(
      "name" to value,
      "slug" to value
    )
    return wordPressHttpClient.executeRequest(wordPressHttpClient.buildPostRequest(baseUrl, data)) { responseBody ->
      try {
        val json = wordPressHttpClient.parseJsonObject(responseBody)
        TaxonomyValue(json.long("id")!!, json.string("slug")!!, json.string("name")!!)
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response", e)
        null
      }
    }
  }
}

data class TaxonomyReference(val slug: String, val id: Int)

data class TaxonomyEndpoint(val slug: String, val endpoint: String)
