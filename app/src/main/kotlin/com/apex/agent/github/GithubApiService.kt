package com.apex.agent.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

@Singleton
class GithubApiService @Inject constructor(
    private val tokenManager: GithubTokenManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    private fun authHeader(): String {
        val token = tokenManager.getToken()
            ?: throw GithubApiException("未连接 GitHub，请先配置 Token")
        return "Bearer $token"
    }

    suspend fun getCurrentUser(): GithubUser = apiCall("/user")

    suspend fun listRepos(username: String? = null, type: String = "owner", sort: String = "updated", perPage: Int = 30): List<GithubRepo> {
        val user = username ?: tokenManager.getUsername() ?: "me"
        val path = if (user == "me") "/user/repos" else "/users/$user/repos"
        return apiCall("$path?type=$type&sort=$sort&per_page=$perPage")
    }

    suspend fun getFileContent(owner: String, repo: String, path: String, branch: String? = null): GithubFileContent {
        val ref = if (branch != null) "?ref=$branch" else ""
        return apiCall("/repos/$owner/$repo/contents/$path$ref")
    }

    suspend fun createOrUpdateFile(owner: String, repo: String, path: String, content: String, message: String, branch: String? = null, sha: String? = null): GithubCommitResult {
        val body = buildJsonObject {
            put("message", message)
            put("content", java.util.Base64.getEncoder().encodeToString(content.toByteArray()))
            if (branch != null) put("branch", branch)
            if (sha != null) put("sha", sha)
        }
        return apiCall("/repos/$owner/$repo/contents/$path", "PUT", body.toString())
    }

    suspend fun listIssues(owner: String, repo: String, state: String = "open", perPage: Int = 20): List<GithubIssue> =
        apiCall("/repos/$owner/$repo/issues?state=$state&per_page=$perPage")

    suspend fun createIssue(owner: String, repo: String, title: String, body: String, labels: List<String> = emptyList()): GithubIssue {
        val reqBody = buildJsonObject {
            put("title", title)
            put("body", body)
            if (labels.isNotEmpty()) putJsonArray("labels") { labels.forEach { add(it) } }
        }
        return apiCall("/repos/$owner/$repo/issues", "POST", reqBody.toString())
    }

    suspend fun listBranches(owner: String, repo: String): List<GithubBranch> =
        apiCall("/repos/$owner/$repo/branches")

    suspend fun searchCode(query: String, repo: String? = null, perPage: Int = 10): GithubSearchResult {
        val repoFilter = if (repo != null) "+repo:$repo" else ""
        return apiCall("/search/code?q=$query$repoFilter&per_page=$perPage")
    }

    private suspend inline fun <reified T> apiCall(path: String, method: String = "GET", body: String? = null): T =
        withContext(Dispatchers.IO) {
            val url = "https://api.github.com$path"
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", authHeader())
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "ApexAgent/1.0")

            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMediaType))
                "PUT" -> builder.put((body ?: "{}").toRequestBody(jsonMediaType))
                "DELETE" -> builder.delete()
            }

            AppLogger.instance.info(
                LogCategory.NETWORK, "GithubApi", "$method $path",
                tags = arrayOf("http", method.lowercase(), "request")
            )

            val response = client.newCall(builder.build()).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val msg = try {
                    Json.parseToJsonElement(responseBody).jsonObject["message"]?.jsonPrimitive?.content ?: responseBody.take(200)
                } catch (e: Exception) { responseBody.take(200) }
                AppLogger.instance.error(
                    LogCategory.NETWORK, "GithubApi", "HTTP ${response.code} $method $path: $msg",
                    tags = arrayOf("http", method.lowercase(), "error", "code:${response.code}")
                )
                throw GithubApiException("GitHub API ${response.code}: $msg")
            }

            AppLogger.instance.debug(
                LogCategory.NETWORK, "GithubApi", "HTTP ${response.code} $method $path (${responseBody.length} bytes)",
                tags = arrayOf("http", method.lowercase(), "response")
            )
            json.decodeFromString<T>(responseBody)
        }
}

class GithubApiException(message: String) : Exception(message)

@Serializable data class GithubUser(val login: String, val name: String? = null, val public_repos: Int = 0)
@Serializable data class GithubRepo(val name: String, val full_name: String, val description: String? = null, val private: Boolean = false, val html_url: String = "", val default_branch: String = "main", val language: String? = null, val stargazers_count: Int = 0)
@Serializable data class GithubFileContent(val name: String, val path: String, val sha: String, val size: Long = 0, val content: String? = null, val encoding: String? = null) {
    fun decoded(): String { if (content == null || encoding != "base64") return ""; return String(java.util.Base64.getDecoder().decode(content.replace("\n", ""))) }
}
@Serializable data class GithubCommitResult(val commit: GithubCommitInfo? = null)
@Serializable data class GithubCommitInfo(val sha: String = "", val message: String = "")
@Serializable data class GithubIssue(val number: Int, val title: String, val body: String? = null, val state: String = "open", val html_url: String = "")
@Serializable data class GithubBranch(val name: String, val commit: GithubBranchCommit? = null)
@Serializable data class GithubBranchCommit(val sha: String = "")
@Serializable data class GithubSearchResult(val total_count: Int = 0, val items: List<GithubSearchItem> = emptyList())
@Serializable data class GithubSearchItem(val name: String = "", val path: String = "", val html_url: String = "", val repository: GithubRepo? = null)
