package com.neo4j.gradle.wordpress

import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.logging.Logger
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.nio.file.Paths


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

class DocumentAttributesReader(val logger: Logger) {

  private val yaml = Yaml()

  /**
   * Get a list of documents with attributes (read from a YAML file).
   * The YAML file is generated in a pre-task.
   */
  fun get(sources: MutableList<ConfigurableFileTree>): List<DocumentAttributes> {
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
          fromFile(yamlFile, file)
        }
      }
  }

  private fun fromFile(yamlFile: File, file: File): DocumentAttributes? {
    logger.debug("Loading $yamlFile")
    val yamlFileAbsolutePath = yamlFile.absolutePath
    val fileName = file.name
    val attributes = yaml.load(FileInputStream(yamlFile)) as Map<*, *>
    val content = file.readText(Charsets.UTF_8)
    return fromMap(attributes, content, yamlFileAbsolutePath, fileName)
  }

  internal fun fromMap(attributes: Map<*, *>, content: String, yamlFileAbsolutePath: String, fileName: String): DocumentAttributes? {
    logger.debug("Document attributes in the YAML file: $attributes")
    val slug = getSlug(attributes, yamlFileAbsolutePath, fileName)
    val title = getTitle(attributes, yamlFileAbsolutePath, fileName)
    return if (slug != null && title != null) {
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
      DocumentAttributes(slug, title, uniqueTags, uniqueCategories, uniqueTaxonomies, excerpt, featuredMedia, author, content, parentPath)
    } else {
      null
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
      val tagsValue = author["tags"]
      val tags = if (tagsValue is List<*>) tagsValue.filterIsInstance<String>() else emptyList()
      return Author(author["name"] as String?, author["first_name"] as String?, author["last_name"] as String?, author["email"] as String?, tags)
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
