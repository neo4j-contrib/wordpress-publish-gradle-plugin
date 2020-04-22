package com.neo4j.gradle.wordpress

import com.beust.klaxon.JsonArray
import com.beust.klaxon.Klaxon
import com.beust.klaxon.KlaxonException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.gradle.api.logging.Logger


class WordPressHttpClient(val connectionInfo: WordPressConnectionInfo, private val logger: Logger) {

  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val underlyingHttpClient = httpClient()
  private val klaxon = Klaxon()

  fun baseUrlBuilder(): HttpUrl.Builder {
    val builder = HttpUrl.Builder()
      .scheme(connectionInfo.scheme)
      .host(connectionInfo.host)
    if (connectionInfo.port != null) {
      builder.port(connectionInfo.port)
    }
    return builder
      .addPathSegment("wp-json")
      .addPathSegment("wp")
      .addPathSegment("v2")
  }

  /**
   * Execute a request that returns JSON.
   */
  fun <T> executeRequest(request: Request, mapper: (ResponseBody) -> T): T? {
    underlyingHttpClient.newCall(request).execute().use {
      if (it.isSuccessful) {
        it.body.use { responseBody ->
          if (responseBody != null) {
            val contentType = responseBody.contentType()
            if (contentType != null) {
              if (contentType.type == "application" && contentType.subtype == "json") {
                try {
                  return mapper(responseBody)
                } catch (e: KlaxonException) {
                  logger.error("Unable to parse the response", e)
                }
              } else {
                logger.warn("Content-Type must be application/json")
              }
            } else {
              logger.warn("Content-Type is undefined")
            }
          } else {
            logger.warn("Response is empty")
          }
        }
      } else {
        logger.warn("Request is unsuccessful - {request: $request, code: ${it.code}, message: ${it.message}, response: ${it.body?.string()}}")
      }
    }
    return null
  }

  fun <T> getRecursiveObjects(page: Int = 1, acc: List<T> = emptyList(), baseUrl: HttpUrl, mapper: (response: JsonArray<*>) -> List<T>): List<T> {
    val searchUrl = baseUrl
      .newBuilder()
      .addQueryParameter("page", page.toString())
      .addQueryParameter("per_page", "100")
      .build()
    val credential = Credentials.basic(connectionInfo.username, connectionInfo.password)
    val searchRequest = Request.Builder()
      .url(searchUrl)
      // force the header because WordPress returns a 400 instead of a 401 when the authentication fails...
      .header("Authorization", credential)
      .get()
      .build()
    val paginatedResult = underlyingHttpClient.newCall(searchRequest).execute().use { response ->
      val totalPages = response.header("X-WP-TotalPages", "1")?.toInt() ?: 1
      val hasNext = totalPages > page
      response.body.use {
        if (it != null) {
          PaginatedResult(mapper(klaxon.parseJsonArray(it.charStream())), hasNext)
        } else {
          PaginatedResult(emptyList(), hasNext)
        }
      }
    }
    return if (paginatedResult.hasNext) {
      getRecursiveObjects(page + 1, acc + paginatedResult.result, baseUrl, mapper = mapper)
    } else {
      acc + paginatedResult.result
    }
  }

  fun buildGetRequest(url: HttpUrl): Request {
    val credential = Credentials.basic(connectionInfo.username, connectionInfo.password)
    return Request.Builder()
      .url(url)
      // force the header because WordPress returns a 400 instead of a 401 when the authentication fails...
      .header("Authorization", credential)
      .get()
      .build()
  }

  fun buildPostRequest(url: HttpUrl, data: MutableMap<String, Any>): Request {
    return Request.Builder()
      .url(url)
      .post(klaxon.toJsonString(data).toRequestBody(jsonMediaType))
      .build()
  }

  fun parseJsonObject(responseBody: ResponseBody) = klaxon.parseJsonObject(responseBody.charStream())

  fun parseJsonArray(responseBody: ResponseBody) = klaxon.parseJsonArray(responseBody.charStream())

  private fun httpClient(): OkHttpClient {
    val client = OkHttpClient.Builder()
      .authenticator(object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
          if (responseCount(response) >= 3) {
            return null // unable to authenticate for the third time, we give up...
          }
          val credential = Credentials.basic(connectionInfo.username, connectionInfo.password)
          return response.request.newBuilder().header("Authorization", credential).build()
        }
      })
      .connectTimeout(connectionInfo.connectTimeout)
      .writeTimeout(connectionInfo.writeTimeout)
      .readTimeout(connectionInfo.readTimeout)
    return client.build()
  }

  private fun responseCount(response: Response): Int {
    var count = 1
    var res = response.priorResponse
    while (res != null) {
      count++
      res = res.priorResponse
    }
    return count
  }
}

data class PaginatedResult<T>(val result: List<T>, val hasNext: Boolean)
