package com.feri.aolon_cyberpunk.lyfta

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class LyftaSet(val kg: Double, val reps: Int)
data class RawSet(val kg: Double, val reps: Int, val completed: Boolean)

/**
 * Lyfta leaves every set's "completed" false in many finished workouts, so the flag only filters
 * when the workout marks at least one set completed; otherwise any set with reps counts.
 */
fun liftedSets(workout: List<List<RawSet>>): List<List<LyftaSet>> {
    val trustFlag = workout.any { sets -> sets.any { it.completed } }
    return workout.map { sets ->
        sets.filter { it.reps > 0 && (!trustFlag || it.completed) }.map { LyftaSet(it.kg, it.reps) }
    }
}
data class LyftaExercise(val name: String, val sets: List<LyftaSet>)
data class LyftaWorkout(val id: Long, val title: String, val performedAt: Long, val exercises: List<LyftaExercise>)

class LyftaException(message: String) : Exception(message)

/**
 * Read-only Lyfta access through its MCP server. OAuth 2.1 with PKCE and dynamic client
 * registration, so the APK carries no secret. Tokens stay in app-private prefs, never in Firebase.
 */
class LyftaClient(context: Context) {
    private val prefs = context.getSharedPreferences("lyfta", Context.MODE_PRIVATE)

    val connected get() = prefs.getString("refreshToken", null) != null || prefs.getString("accessToken", null) != null

    /** Registers this app once, stores a PKCE verifier, and returns the Lyfta sign-in page to open. */
    suspend fun authorizeUrl(): Uri = io {
        val clientId = prefs.getString("clientId", null) ?: register().also { prefs.edit().putString("clientId", it).apply() }
        val verifier = random(48)
        val state = random(16)
        prefs.edit().putString("verifier", verifier).putString("state", state).apply()
        Uri.parse("$BASE/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("resource", MCP)
            .build()
    }

    /** Exchanges the redirect's code for tokens; rejects a state that this app did not issue. */
    suspend fun finish(redirect: Uri) = io {
        redirect.getQueryParameter("error")?.let { throw LyftaException(redirect.getQueryParameter("error_description") ?: it) }
        if (redirect.getQueryParameter("state") != prefs.getString("state", null)) throw LyftaException("Sign-in expired. Try Connect again.")
        val code = redirect.getQueryParameter("code") ?: throw LyftaException("Lyfta returned no sign-in code.")
        saveTokens(form("$BASE/oauth/token", mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT,
            "client_id" to prefs.getString("clientId", "")!!,
            "code_verifier" to prefs.getString("verifier", "")!!,
            "resource" to MCP,
        )))
        prefs.edit().remove("verifier").remove("state").apply()
    }

    fun disconnect() {
        prefs.edit().remove("accessToken").remove("refreshToken").remove("expiresAt").apply()
    }

    suspend fun recentWorkouts(limit: Int = 20): List<LyftaWorkout> = io {
        val result = call("get_recent_workouts", JSONObject().put("limit", limit))
        val list = result.optJSONArray("workouts") ?: JSONArray()
        (0 until list.length()).map { list.getJSONObject(it).getLong("workoutId") }.map(::workout)
    }

    private fun workout(id: Long): LyftaWorkout {
        val w = call("get_workout", JSONObject().put("workoutId", id))
        val list = w.optJSONArray("exercises") ?: JSONArray()
        val exercises = (0 until list.length()).map { list.getJSONObject(it) }
        val raw = exercises.map { e ->
            val sets = e.optJSONArray("sets") ?: JSONArray()
            (0 until sets.length()).map { sets.getJSONObject(it) }.map { s ->
                RawSet(
                    kg = s.optDouble("weight").takeUnless { it.isNaN() } ?: 0.0,
                    reps = s.optDouble("reps").takeUnless { it.isNaN() }?.toInt() ?: 0,
                    completed = s.optBoolean("completed", false),
                )
            }
        }
        val lifted = liftedSets(raw)
        return LyftaWorkout(id, w.optString("title", "Lyfta workout"), parseTime(w.getString("performedAt")),
            exercises.mapIndexed { i, e -> LyftaExercise(e.getString("name"), lifted[i]) })
    }

    /** One JSON-RPC tools/call; refreshes the token once on 401. */
    private fun call(tool: String, args: JSONObject, retried: Boolean = false): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", JSONObject().put("name", tool).put("arguments", args))
        val conn = open(MCP, "application/json", body.toString(), "Bearer ${token()}")
        if (conn.responseCode == 401 && !retried) {
            refresh()
            return call(tool, args, retried = true)
        }
        if (conn.responseCode == 401) throw LyftaException("Lyfta sign-in expired. Connect again.")
        val text = read(conn)
        // Streamable HTTP may answer as server-sent events; the JSON-RPC message is the data line.
        val json = JSONObject(if (text.trimStart().startsWith("{")) text else text.lines().last { it.startsWith("data:") }.removePrefix("data:"))
        json.optJSONObject("error")?.let { throw LyftaException(it.optString("message", "Lyfta error")) }
        val result = json.getJSONObject("result")
        if (result.optBoolean("isError")) throw LyftaException(result.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: "Lyfta error")
        return result.optJSONObject("structuredContent")
            ?: JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    // Lyfta rotates refresh tokens, so two refreshes at once would burn the token; one at a time.
    @Synchronized
    private fun token(): String {
        val access = prefs.getString("accessToken", null)
        if (access != null && System.currentTimeMillis() < prefs.getLong("expiresAt", 0)) return access
        refresh()
        return prefs.getString("accessToken", null)!!
    }

    @Synchronized
    private fun refresh() {
        val refresh = prefs.getString("refreshToken", null) ?: throw LyftaException("Lyfta sign-in expired. Connect again.")
        saveTokens(form("$BASE/oauth/token", mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to prefs.getString("clientId", "")!!,
            "resource" to MCP,
        )))
    }

    private fun saveTokens(t: JSONObject) {
        // Expire a minute early so a request never starts with a token about to lapse.
        val expiresAt = System.currentTimeMillis() + (t.optLong("expires_in", 3600) - 60) * 1000
        prefs.edit()
            .putString("accessToken", t.getString("access_token"))
            .putString("refreshToken", t.optString("refresh_token").ifEmpty { prefs.getString("refreshToken", null) })
            .putLong("expiresAt", expiresAt)
            .apply()
    }

    private fun register(): String {
        val body = JSONObject()
            .put("client_name", "Aolon Cyberpunk")
            .put("redirect_uris", JSONArray().put(REDIRECT))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
            .put("scope", SCOPE)
        val conn = open("$BASE/oauth/register", "application/json", body.toString())
        return JSONObject(read(conn)).getString("client_id")
    }

    private fun form(url: String, fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return JSONObject(read(open(url, "application/x-www-form-urlencoded", body)))
    }

    private fun open(url: String, type: String, body: String, auth: String? = null) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", type)
            setRequestProperty("Accept", "application/json, text/event-stream")
            auth?.let { setRequestProperty("Authorization", it) }
            outputStream.use { it.write(body.toByteArray()) }
        }

    private fun read(conn: HttpURLConnection): String {
        val ok = conn.responseCode in 200..299
        val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (!ok) {
            val reason = runCatching { JSONObject(text).let { it.optString("error_description").ifEmpty { it.optString("error") } } }.getOrNull()
            throw LyftaException("Lyfta ${conn.responseCode}: ${reason?.ifEmpty { null } ?: "request failed"}")
        }
        return text
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val BASE = "https://api.lyftadev.com/v2"
        private const val MCP = "$BASE/mcp"
        private const val SCOPE = "training:read"
        // Underscores are not legal in URI schemes, so the package name can't be used directly.
        const val REDIRECT = "aoloncyberpunk://lyfta/callback"

        private fun random(bytes: Int) = b64(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
        private fun b64(data: ByteArray) = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        /** Lyfta dates are UTC; accepts full timestamps or a bare date. */
        fun parseTime(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { java.time.LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrElse { LocalDate.parse(value.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    }
}
