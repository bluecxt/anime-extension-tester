package dev.tester.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.concurrent.TimeUnit

/** Comment les requêtes de la suite courante sont relayées vers le solveur. */
enum class ProxyMode {
    /** Trafic direct : comportement par défaut. */
    OFF,

    /** Relais uniquement quand Cloudflare répond un défi (403/503 ou page interstitielle). */
    HALF,

    /** Toutes les pages passent par le solveur (site protégé en permanence). */
    FULL,
    ;

    companion object {
        fun parse(raw: String?): ProxyMode = when (raw?.trim()?.lowercase()) {
            "full" -> FULL
            "half" -> HALF
            "off", "" -> OFF
            null -> OFF
            else -> throw IllegalArgumentException("proxyMode inconnu: $raw (attendu: off, half ou full)")
        }
    }
}

/** Réglage courant, positionné par le runner au début de chaque suite. */
object ProxySettings {
    const val DEFAULT_ENDPOINT: String = "http://127.0.0.1:8191/v1"

    @Volatile
    var mode: ProxyMode = ProxyMode.OFF

    @Volatile
    var endpoint: String = DEFAULT_ENDPOINT
}

/**
 * Relaie les requêtes vers [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) : le défi
 * Cloudflare est résolu par un vrai navigateur, et le HTML déjà servi est renvoyé à l'extension.
 *
 * Le mode est décidé par la suite de tests (`proxyMode` dans le JSON) :
 * - `off` : aucun relais ;
 * - `half` : relais seulement sur un défi, après une première requête directe ;
 * - `full` : toutes les pages passent par le solveur.
 *
 * Les images, feuilles de style, scripts et segments vidéo ne sont jamais relayés : le solveur ne
 * renvoie que du texte, et ces ressources n'ont pas besoin d'être déchiffrées.
 */
class FlareSolverrInterceptor : Interceptor {
    private data class SolvedPage(val body: String, val status: Int, val contentType: String, val solvedAt: Long)

    /** Une page résolue coûte plusieurs secondes : on la réutilise un moment. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, SolvedPage>()

    private val json = Json { ignoreUnknownKeys = true }
    private val solverClient = OkHttpClient.Builder()
        .connectTimeout(SOLVER_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SOLVER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(SOLVER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(SOLVER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val mode = ProxySettings.mode
        if (mode == ProxyMode.OFF || !isRelayable(request)) {
            return chain.proceed(request)
        }
        if (mode == ProxyMode.FULL) {
            return solve(request) ?: chain.proceed(request)
        }
        val response = chain.proceed(request)
        if (!response.isChallenge()) {
            return response
        }
        val solved = solve(request) ?: return response
        response.close()
        return solved
    }

    /** Une requête ne vaut le relais que si elle ramène un document (page, JSON, flux HLS). */
    private fun isRelayable(request: Request): Boolean {
        if (request.method != "GET" && request.method != "POST") return false
        val path = request.url.encodedPath.lowercase()
        return ASSET_SUFFIXES.none { path.endsWith(it) }
    }

    /** Tells whether Cloudflare refused the answer instead of serving the page. */
    private fun Response.isChallenge(): Boolean {
        if (code != HTTP_FORBIDDEN && code != HTTP_UNAVAILABLE) return false
        if (header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
        val preview = runCatching { peekBody(PEEK_BYTES).string() }.getOrDefault("")
        return CHALLENGE_MARKERS.any { preview.contains(it, ignoreCase = true) }
    }

    private fun solve(request: Request): Response? {
        val cacheKey = "${request.method} ${request.url}"
        cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.solvedAt < SOLVED_CACHE_TTL_MS }?.let {
            println("  [solver] cache ${request.url} -> ${it.status}")
            return buildResponse(request, it.body, it.status, it.contentType)
        }

        val payload = buildJsonObject {
            put("cmd", if (request.method == "POST") "request.post" else "request.get")
            put("url", request.url.toString())
            put("maxTimeout", SOLVE_TIMEOUT_MS)
            if (request.method == "POST") {
                put("postData", request.body?.readAsString().orEmpty())
            }
        }

        val raw = runCatching {
            solverClient.newCall(
                Request.Builder()
                    .url(ProxySettings.endpoint)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            ).execute().use { it.body.string() }
        }.onFailure { println("  [solver] FlareSolverr injoignable: ${it.message}") }.getOrNull() ?: return null

        val solution = runCatching {
            json.parseToJsonElement(raw).jsonObject
                .also { root -> require(root["status"]?.jsonPrimitive?.contentOrNull == "ok") }
                .getValue("solution").jsonObject
        }.onFailure { println("  [solver] réponse inexploitable: ${raw.take(200)}") }.getOrNull() ?: return null

        val status = solution["status"]?.jsonPrimitive?.int ?: HTTP_OK
        val body = solution["response"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val contentType = solution["headers"]?.jsonObject
            ?.entries
            ?.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value?.jsonPrimitive?.contentOrNull
            ?: DEFAULT_CONTENT_TYPE

        println("  [solver] ${request.method} ${request.url} -> $status (${body.length} caractères)")
        cache[cacheKey] = SolvedPage(body, status, contentType, System.currentTimeMillis())
        return buildResponse(request, body, status, contentType)
    }

    private fun buildResponse(request: Request, body: String, status: Int, contentType: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("FlareSolverr")
        .body(body.toByteArray(Charsets.UTF_8).toResponseBody(contentType.toMediaType()))
        .build()

    private fun RequestBody.readAsString(): String = Buffer().also { writeTo(it) }.readUtf8()

    private companion object {
        const val SOLVER_CONNECT_TIMEOUT_SECONDS = 10L
        const val SOLVER_CALL_TIMEOUT_SECONDS = 150L
        const val SOLVE_TIMEOUT_MS = 90000L
        const val PEEK_BYTES = 4096L
        const val SOLVED_CACHE_TTL_MS = 120000L
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403
        const val HTTP_UNAVAILABLE = 503
        const val DEFAULT_CONTENT_TYPE = "text/html; charset=utf-8"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val CHALLENGE_MARKERS = listOf("Just a moment", "cf-browser-verification", "challenge-platform")
        val ASSET_SUFFIXES = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico",
            ".css", ".js", ".woff", ".woff2", ".ttf",
            ".mp4", ".m3u8", ".ts", ".vtt", ".ass", ".srt", ".aac", ".mp3",
        )
    }
}
