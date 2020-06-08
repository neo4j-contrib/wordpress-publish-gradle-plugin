package com.neo4j.gradle.wordpress

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.KlaxonException
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.yaml.snakeyaml.Yaml
import java.io.FileInputStream
import java.net.URL
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*


open class WordPressExtension(objects: ObjectFactory) {
  val scheme: Property<String> = objects.property()
  val host: Property<String> = objects.property()
  val port: Property<Int> = objects.property()
  val username: Property<String> = objects.property()
  val password: Property<String> = objects.property()
}

open class WordPressPlugin : Plugin<Project> {

  override fun apply(target: Project) {
    target.extensions.create("wordpress", WordPressExtension::class.java)
  }
}

data class Author(val name: String, val firstName: String, val lastName: String, val email: String)

data class TaxonomyValue(val id: Long, val slug: String, val name: String)

data class Taxonomy(val key: String, val values: List<String>)

data class DocumentAttributes(val slug: String,
                              val title: String,
                              val tags: Set<String>,
                              val categories: Set<String>,
                              val taxonomies: Set<Taxonomy>,
                              val excerpt: String?,
                              val featuredMedia: String?,
                              val author: Author?,
                              val content: String,
                              val parentPath: String?)

abstract class WordPressTask : DefaultTask() {
  @Input
  var scheme: String = "https"

  @Input
  var host: String = ""

  @Input
  @Optional
  val port: Property<Int> = project.objects.property()

  @Input
  var username: String = ""

  @Input
  var password: String = ""

  protected fun wordPressConnectionInfo(): WordPressConnectionInfo {
    val wordPressExtension = project.extensions.findByType(WordPressExtension::class.java)
    val hostValue = wordPressExtension?.host?.getOrElse(host) ?: host
    val schemeValue = wordPressExtension?.scheme?.getOrElse(scheme) ?: scheme
    val usernameValue = wordPressExtension?.username?.getOrElse(username) ?: username
    val passwordValue = wordPressExtension?.password?.getOrElse(password) ?: password
    val portValue = (wordPressExtension?.port ?: port).orNull
    return WordPressConnectionInfo(
      scheme = schemeValue,
      host = hostValue,
      port = portValue,
      username = usernameValue,
      password = passwordValue
    )
  }
}

open class WordPressUploadTask : WordPressTask() {

  @InputFiles
  var sources: MutableList<ConfigurableFileTree> = mutableListOf()

  @Input
  var type: String = ""

  @Input
  // publish, future, draft, pending, private
  var status: String = "draft"

  @Input
  var template: String = ""

  @TaskAction
  fun task() {
    if (type.isBlank()) {
      logger.error("The type is mandatory, aborting...")
      return
    }
    val wordPressUpload = WordPressUpload(
      documentType = WordPressDocumentType(type),
      documentStatus = status,
      documentTemplate = template,
      sources = sources,
      connectionInfo = wordPressConnectionInfo(),
      logger = logger
    )
    wordPressUpload.publish()
  }

  fun setSource(sources: FileCollection) {
    sources.forEach {
      this.sources.add(project.fileTree(it))
    }
  }

  fun setSource(source: String) {
    this.sources.add(project.fileTree(source))
  }

  fun setSource(vararg sources: String?) {
    sources.forEach {
      if (it != null) {
        this.sources.add(project.fileTree(it))
      }
    }
  }

  fun setSource(sources: List<String>) {
    sources.forEach {
      this.sources.add(project.fileTree(it))
    }
  }

  fun setSource(source: ConfigurableFileTree) {
    this.sources.add(source)
  }
}

data class WordPressConnectionInfo(val scheme: String,
                                   val host: String,
                                   val port: Int?,
                                   val username: String,
                                   val password: String,
                                   val connectTimeout: Duration = Duration.ofSeconds(10),
                                   val writeTimeout: Duration = Duration.ofSeconds(10),
                                   val readTimeout: Duration = Duration.ofSeconds(30))

data class WordPressDocumentType(val name: String) {
  val urlPath: String = when (name) {
    // type is singular but endpoint is plural for built-in types post and page.
    "post" -> "posts"
    "page" -> "pages"
    else -> name
  }
}

data class WordPressDocument(val id: Int,
                             val slug: String,
                             val type: WordPressDocumentType)

internal class WordPressUpload(val documentType: WordPressDocumentType,
                               val documentStatus: String,
                               val documentTemplate: String,
                               val sources: MutableList<ConfigurableFileTree>,
                               val connectionInfo: WordPressConnectionInfo,
                               val logger: Logger) {

  private val yaml = Yaml()
  private val klaxon = Klaxon()
  private val httpClient = WordPressHttpClient(connectionInfo, logger)

  fun publish(): Boolean {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    formatter.format(Date())
    val date = formatter.format(Date())
    val documentsWithAttributes = getDocumentsWithAttributes()
    if (documentsWithAttributes.isEmpty()) {
      logger.info("No file to upload")
      return false
    }
    val slugs = documentsWithAttributes.map { it.slug }
    val searchUrl = httpClient.baseUrlBuilder()
      .addPathSegment(documentType.urlPath)
      .addQueryParameter("per_page", slugs.size.toString())
      .addQueryParameter("slug", slugs.joinToString(","))
      .addQueryParameter("status", "publish,future,draft,pending,private")
      .build()
    val searchRequest = httpClient.buildGetRequest(searchUrl)
    val wordPressDocumentsBySlug = httpClient.executeRequest(searchRequest) { responseBody ->
      try {
        val jsonArray = klaxon.parseJsonArray(responseBody.charStream())
        jsonArray.value.mapNotNull { item ->
          if (item is JsonObject) {
            val slug = item.string("slug")!!
            slug to WordPressDocument(item.int("id")!!, slug, documentType)
          } else {
            null
          }
        }.toMap()
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response", e)
        null
      }
    } ?: return false
    val parentIdsByPath = mutableMapOf<String, WordPressDocument?>()
    val taxonomyReferencesBySlug = if (documentType.name !== "page") {
      val taxonomySlugs = documentsWithAttributes.flatMap {
        it.taxonomies.map { taxonomy -> taxonomy.key }
      }.toMutableSet()
      if (documentsWithAttributes.find { it.tags.isNotEmpty() } != null) {
        taxonomySlugs.add("tags")
      }
      if (documentsWithAttributes.find { it.categories.isNotEmpty() } != null) {
        taxonomySlugs.add("categories")
      }
      WordPressTaxonomies(httpClient, logger).getTaxonomyReferencesBySlug(documentType, taxonomySlugs)
    } else {
      emptyMap()
    }
    val usersService = WordPressUsers(httpClient, logger)
    val mediasService = WordPressMedias(httpClient, logger)
    val wordPressAuthorsCache = mutableMapOf<String, WordPressUser?>()
    for (documentAttributes in documentsWithAttributes) {
      val data = mutableMapOf<String, Any>(
        "date_gmt" to date,
        "slug" to documentAttributes.slug,
        "status" to documentStatus,
        "title" to documentAttributes.title,
        "content" to documentAttributes.content,
        "type" to documentType.name
      )
      // excerpt
      val excerpt = documentAttributes.excerpt
      if (excerpt != null) {
        data["excerpt"] = mapOf(
          "rendered" to excerpt,
          "protected" to false
        )
      }
      // taxonomies
      for (taxonomy in documentAttributes.taxonomies) {
        val values = taxonomy.values.mapNotNull { value ->
          val taxonomyReference = taxonomyReferencesBySlug[taxonomy.key]?.get(value)
          if (taxonomyReference == null) {
            logger.warn("Unable to resolve taxonomy id for ${taxonomy.key}/$value on post ${documentAttributes.slug}")
            null
          } else {
            taxonomyReference
          }
        }
        data[taxonomy.key] = values
      }
      // categories
      val categoryIds = documentAttributes.categories.mapNotNull { category ->
        val taxonomyReference = taxonomyReferencesBySlug["categories"]?.get(category)
        if (taxonomyReference == null) {
          logger.warn("Unable to resolve category $category on post ${documentAttributes.slug}")
          // remind: should we automatically create the missing category?
          null
        } else {
          taxonomyReference
        }
      }
      if (categoryIds.isNotEmpty()) {
        data["categories"] = categoryIds
      }
      // tags
      val tagIds = documentAttributes.tags.mapNotNull { tag ->
        val taxonomyReference = taxonomyReferencesBySlug["tags"]?.get(tag)
        if (taxonomyReference == null) {
          logger.warn("Unable to resolve tag $tag on post ${documentAttributes.slug}")
          // remind: should we automatically create the missing tag?
          null
        } else {
          taxonomyReference
        }
      }
      if (tagIds.isNotEmpty()) {
        data["tags"] = tagIds
      }
      val parentPath = documentAttributes.parentPath
      if (documentType == WordPressDocumentType("page") && parentPath != null) {
        val parentPage = if (parentIdsByPath.containsKey(parentPath)) {
          parentIdsByPath[parentPath]
        } else {
          val parentPage = findParentPage(parentPath)
          parentIdsByPath[parentPath] = parentPage
          parentPage
        }
        if (parentPage == null) {
          logger.warn("No page found for path: $parentPath, unable to publish ${documentAttributes.slug} to WordPress")
          continue
        }
        data["parent"] = parentPage.id
      }
      if (documentTemplate.isNotBlank()) {
        data["template"] = documentTemplate
      }
      // author
      val documentAuthor = documentAttributes.author
      if (documentAuthor != null) {
        val authorName = documentAuthor.name
        val wordPressAuthor = if (wordPressAuthorsCache.contains(authorName)) {
          wordPressAuthorsCache[authorName]
        } else {
          val result = usersService.findUser(authorName)
          wordPressAuthorsCache[authorName] = result
          result
        }
        if (wordPressAuthor == null) {
          logger.info("Unable to find the author for email $authorName, using the default author")
        } else {
          data["author"] = wordPressAuthor.id
        }
      }
      // featured media
      val featuredMedia = documentAttributes.featuredMedia
      if (featuredMedia != null) {
        val wordPressMedia = mediasService.findMedia(featuredMedia)
        if (wordPressMedia == null) {
          logger.info("Unable to find the featured media for slug $featuredMedia, ignoring")
        } else {
          data["featured_media"] = wordPressMedia.id
        }
      }
      val wordPressDocument = wordPressDocumentsBySlug[documentAttributes.slug]
      if (wordPressDocument != null) {
        // document already exists on WordPress, updating...
        updateDocument(data, wordPressDocument)
      } else {
        // document does not exist on WordPress, creating...
        createDocument(data)
      }
    }
    return true
  }

  private fun findParentPage(parentPath: String): WordPressDocument? {
    // slug is the last part of the path
    // example:
    // - path: "/docs/labs/"
    // - slug: "labs"
    // the path must _not_ contain the complete URL (ie. "https://neo4j.com/docs/labs")
    val parentSlug = parentPath
      .removeSuffix("/")
      .split("/")
      .last { it.isNotEmpty() }
    val searchParentUrl = httpClient.baseUrlBuilder()
      .addPathSegment(documentType.urlPath)
      .addQueryParameter("per_page", "10")
      .addQueryParameter("slug", parentSlug)
      .addQueryParameter("status", "publish,future,draft,pending,private")
      .build()
    val searchParentRequest = httpClient.buildGetRequest(searchParentUrl)
    return httpClient.executeRequest(searchParentRequest) { responseBody ->
      try {
        val jsonArray = httpClient.parseJsonArray(responseBody)
        val documents = jsonArray.value.mapNotNull { item ->
          if (item is JsonObject) {
            val link = item.string("link")
            // extract the path part from the URL
            val linkPath = URL(link).path.removeSuffix("/")
            val parentLinkPath = parentPath.removeSuffix("/")
            if (linkPath == parentLinkPath) {
              val slug = item.string("slug")!!
              WordPressDocument(item.int("id")!!, slug, documentType)
            } else {
              null
            }
          } else {
            null
          }
        }
        documents.firstOrNull()
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response", e)
        null
      }
    }
  }

  private fun updateDocument(data: MutableMap<String, Any>, wordPressDocument: WordPressDocument): Boolean {
    data["id"] = wordPressDocument.id
    val url = httpClient.baseUrlBuilder()
      .addPathSegment(documentType.urlPath)
      .addPathSegment(wordPressDocument.id.toString())
      .build()
    logger.debug("POST $url")
    val updateRequest = httpClient.buildPostRequest(url, data)
    return httpClient.executeRequest(updateRequest) { responseBody ->
      try {
        val jsonObject = httpClient.parseJsonObject(responseBody)
        val id = jsonObject.int("id")!!
        logger.quiet("Successfully updated the ${documentType.name.toLowerCase()} with id: $id and slug: ${data["slug"]}")
        true
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response for the ${documentType.name.toLowerCase()} with slug: ${data["slug"]}", e)
        false
      }
    } ?: false
  }

  private fun createDocument(data: MutableMap<String, Any>): Boolean {
    val url = httpClient.baseUrlBuilder()
      .addPathSegment(documentType.urlPath)
      .build()
    logger.debug("POST $url")
    val createRequest = httpClient.buildPostRequest(url, data)
    return httpClient.executeRequest(createRequest) { responseBody ->
      try {
        val jsonObject = httpClient.parseJsonObject(responseBody)
        val id = jsonObject.int("id")!!
        logger.quiet("Successfully created a new ${documentType.name.toLowerCase()} with id: $id and slug: ${data["slug"]}")
        true
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response for the new ${documentType.name.toLowerCase()} with slug: ${data["slug"]}", e)
        false
      }
    } ?: false
  }

  /**
   * Get a list of documents with attributes (read from a YAML file).
   * The YAML file is generated in a pre-task.
   */
  private fun getDocumentsWithAttributes(): List<DocumentAttributes> {
    return sources
      .flatten()
      .filter { it.extension == "html" }
      .mapNotNull { file ->
        val yamlFile = Paths.get(file.toPath().parent.toString(), "${file.nameWithoutExtension}.yml").toFile()
        val fileName = file.name
        val yamlFileAbsolutePath = yamlFile.absolutePath
        if (!yamlFile.exists()) {
          logger.warn("Missing YAML file: $yamlFileAbsolutePath, unable to publish $fileName to WordPress")
          null
        } else {
          logger.debug("Loading $yamlFile")
          val attributes = yaml.load(FileInputStream(yamlFile)) as Map<*, *>
          logger.debug("Document attributes in the YAML file: $attributes")
          val slug = getSlug(attributes, yamlFileAbsolutePath, fileName)
          val title = getTitle(attributes, yamlFileAbsolutePath, fileName)
          if (slug != null && title != null) {
            // The terms assigned to the object in the post_tag taxonomy.
            val tags = getTags(attributes)
            val taxonomies = getTaxonomies(attributes)
            val categories = getCategories(attributes)
            val parentPath = getParentPath(attributes)
            val taxonomiesByKey = taxonomies.groupBy { it.key }
            val tagsAsTaxonomies = taxonomiesByKey["tags"]
            val uniqueTags = if (tagsAsTaxonomies != null) {
              tags.toSet() + tagsAsTaxonomies.flatMap { it.values }.toSet()
            } else {
              tags.toSet()
            }
            val categoriesAsTaxonomies = taxonomiesByKey["categories"]
            val uniqueCategories = if (categoriesAsTaxonomies != null) {
              categories.toSet() + categoriesAsTaxonomies.flatMap { it.values }.toSet()
            } else {
              categories.toSet()
            }
            val uniqueTaxonomies = taxonomies.filter { it.key != "categories" && it.key != "tags" }.toSet()
            val author = getAuthor(attributes)
            val excerpt = getExcerpt(attributes)
            val featuredMedia = getFeaturedMedia(attributes)
            DocumentAttributes(slug, title, uniqueTags, uniqueCategories, uniqueTaxonomies, excerpt, featuredMedia, author, file.readText(Charsets.UTF_8), parentPath)
          } else {
            null
          }
        }
      }
  }

  private fun getMandatoryString(attributes: Map<*, *>, name: String, yamlFilePath: String, fileName: String): String? {
    val value = attributes[name]
    if (value == null) {
      logger.warn("No $name found in: $yamlFilePath, unable to publish $fileName to WordPress")
      return null
    }
    if (value !is String) {
      logger.warn("$name must be a String in: $yamlFilePath, unable to publish $fileName to WordPress")
      return null
    }
    if (value.isBlank()) {
      logger.warn("$name must not be blank in: $yamlFilePath, unable to publish $fileName to WordPress")
      return null
    }
    return value
  }

  private fun getTaxonomies(attributes: Map<*, *>): List<Taxonomy> {
    val value = attributes["taxonomies"] ?: return listOf()
    return if (value is List<*>) {
      value.mapNotNull { info ->
        if (info is Map<*, *>) {
          @Suppress("UNCHECKED_CAST")
          Taxonomy(info["key"] as String, slugify(info["values"] as List<String>))
        } else {
          null
        }
      }
    } else {
      listOf()
    }
  }

  private fun getCategories(attributes: Map<*, *>): List<String> {
    val value = attributes["categories"] ?: return listOf()
    if (value is List<*>) {
      return slugify(value.filterIsInstance<String>())
    }
    return listOf()
  }

  private fun getTags(attributes: Map<*, *>): List<String> {
    val value = attributes["tags"] ?: return listOf()
    if (value is List<*>) {
      return slugify(value.filterIsInstance<String>())
    }
    return listOf()
  }

  private fun getParentPath(attributes: Map<*, *>): String? {
    val name = "parent_path"
    val value = attributes[name] ?: return null
    if (value !is String) {
      return null
    }
    if (value.isBlank()) {
      return null
    }
    return value
  }

  private fun getAuthor(attributes: Map<*, *>): Author? {
    val author = attributes["author"]
    if (author is Map<*, *>) {
      return Author(author["name"] as String, author["first_name"] as String, author["last_name"] as String, author["email"] as String)
    }
    return null
  }

  private fun getExcerpt(attributes: Map<*, *>): String? {
    return when (val excerpt = attributes["excerpt"]) {
      is String -> excerpt
      else -> null
    }
  }

  private fun getFeaturedMedia(attributes: Map<*, *>): String? {
    return when (val featuredMedia = attributes["featured_media"]) {
      is String -> slugify(featuredMedia)
      else -> null
    }
  }

  private fun getTitle(attributes: Map<*, *>, yamlFilePath: String, fileName: String): String? {
    return getMandatoryString(attributes, "title", yamlFilePath, fileName)
  }

  private fun getSlug(attributes: Map<*, *>, yamlFilePath: String, fileName: String): String? {
    return getMandatoryString(attributes, "slug", yamlFilePath, fileName)
  }

  private val spaceRegex = Regex("\\s")
  private fun slugify(values: List<String>) = values.map { slugify(it) }
  private fun slugify(value: String) = value.replace(spaceRegex, "-")
}
