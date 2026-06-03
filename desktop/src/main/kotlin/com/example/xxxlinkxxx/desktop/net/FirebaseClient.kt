package com.example.xxxlinkxxx.desktop.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Firebase REST client for desktop. No Android SDK, no Admin SDK — just
 * the public REST endpoints that the Web SDK uses, so we can talk to
 * the same xxxlinkxxx-81cbf project the phone client uses.
 *
 *  - Anonymous auth via Identity Toolkit signUp endpoint.
 *  - ID token refresh via securetoken.googleapis.com.
 *  - Firestore documents via firestore.googleapis.com/v1/...
 *  - Callable functions via {region}-{project}.cloudfunctions.net/{name}
 *
 * Listeners are polled (REST has no streaming; gRPC listen is a separate
 * protocol). For chat polling at ~5 s the latency matches the Android
 * MESSAGE_POLL_MS interval.
 */
class FirebaseClient(
    private val apiKey: String,
    private val projectId: String,
    private val region: String = "us-central1",
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    @Volatile private var idToken: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var uid: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0L

    val currentUid: String? get() = uid
    val currentIdToken: String? get() = idToken

    /** Identity Toolkit signUp with no email/password → anonymous account. */
    suspend fun signInAnonymously(): AuthResult = withContext(Dispatchers.IO) {
        val url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey"
        val body = """{"returnSecureToken":true}"""
        val resp = postJson(url, body, authorized = false)
        val obj = JSONObject(resp)
        idToken = obj.getString("idToken")
        refreshToken = obj.getString("refreshToken")
        uid = obj.getString("localId")
        val expiresIn = obj.getString("expiresIn").toLong()
        tokenExpiresAtMs = System.currentTimeMillis() + expiresIn * 1000 - 60_000
        AuthResult(uid!!, idToken!!)
    }

    /** Refresh the ID token before it expires. */
    suspend fun refreshIdTokenIfNeeded() {
        val now = System.currentTimeMillis()
        if (idToken != null && now < tokenExpiresAtMs) return
        val refresh = refreshToken ?: error("No refresh token; sign in first")
        withContext(Dispatchers.IO) {
            val url = "https://securetoken.googleapis.com/v1/token?key=$apiKey"
            val body = "grant_type=refresh_token&refresh_token=" +
                URLEncoder.encode(refresh, Charsets.UTF_8)
            val resp = postForm(url, body)
            val obj = JSONObject(resp)
            idToken = obj.getString("id_token")
            refreshToken = obj.getString("refresh_token")
            tokenExpiresAtMs = System.currentTimeMillis() +
                obj.getString("expires_in").toLong() * 1000 - 60_000
        }
    }

    // ── Firestore documents ──────────────────────────────────────────────────

    private fun docUrl(path: String): String =
        "https://firestore.googleapis.com/v1/projects/$projectId" +
            "/databases/(default)/documents/$path"

    suspend fun firestoreGet(path: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        refreshIdTokenIfNeeded()
        val resp = sendAuthorized(
            HttpRequest.newBuilder(URI.create(docUrl(path))).GET().build()
        )
        if (resp.statusCode() == 404) return@withContext null
        ensureOk(resp)
        val obj = JSONObject(resp.body())
        decodeDoc(obj)
    }

    suspend fun firestoreSet(
        path: String,
        fields: Map<String, Any?>,
        merge: Boolean = true,
    ): Unit = withContext(Dispatchers.IO) {
        refreshIdTokenIfNeeded()
        val payload = JSONObject().apply {
            put("fields", encodeFields(fields))
        }
        val maskQuery = if (merge && fields.isNotEmpty()) {
            "?" + fields.keys.joinToString("&") {
                "updateMask.fieldPaths=" + URLEncoder.encode(it, Charsets.UTF_8)
            }
        } else ""
        val req = HttpRequest.newBuilder(URI.create(docUrl(path) + maskQuery))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        val resp = sendAuthorized(req)
        ensureOk(resp)
    }

    suspend fun firestoreDelete(path: String): Unit = withContext(Dispatchers.IO) {
        refreshIdTokenIfNeeded()
        val req = HttpRequest.newBuilder(URI.create(docUrl(path)))
            .DELETE()
            .build()
        val resp = sendAuthorized(req)
        if (resp.statusCode() != 404) ensureOk(resp)
    }

    /** RunQuery over a collection with simple equality filters. */
    suspend fun firestoreQuery(
        collection: String,
        equalityFilters: Map<String, String> = emptyMap(),
        limit: Int = 100,
    ): List<Pair<String, Map<String, Any?>>> = withContext(Dispatchers.IO) {
        refreshIdTokenIfNeeded()
        val url = "https://firestore.googleapis.com/v1/projects/$projectId" +
            "/databases/(default)/documents:runQuery"
        val filters = equalityFilters.map { (k, v) ->
            JSONObject().put(
                "fieldFilter", JSONObject()
                    .put("field", JSONObject().put("fieldPath", k))
                    .put("op", "EQUAL")
                    .put("value", JSONObject().put("stringValue", v))
            )
        }
        val where = when {
            filters.isEmpty() -> null
            filters.size == 1 -> filters.first()
            else -> JSONObject().put(
                "compositeFilter", JSONObject()
                    .put("op", "AND")
                    .put("filters", JSONArray(filters))
            )
        }
        val structured = JSONObject().apply {
            put("from", JSONArray().put(JSONObject().put("collectionId", collection)))
            put("limit", limit)
            if (where != null) put("where", where)
        }
        val body = JSONObject().put("structuredQuery", structured).toString()
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = sendAuthorized(req)
        ensureOk(resp)
        val arr = JSONArray(resp.body())
        val out = mutableListOf<Pair<String, Map<String, Any?>>>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (!item.has("document")) continue
            val doc = item.getJSONObject("document")
            val name = doc.getString("name")
            val id = name.substringAfterLast('/')
            val fields = decodeDoc(doc) ?: emptyMap()
            out.add(id to fields)
        }
        out
    }

    /** Cloud Functions callable shaped per https://firebase.google.com/docs/functions/callable-reference. */
    suspend fun callFunction(name: String, data: Map<String, Any?>): JSONObject =
        withContext(Dispatchers.IO) {
            refreshIdTokenIfNeeded()
            val url = "https://$region-$projectId.cloudfunctions.net/$name"
            val body = JSONObject().put("data", encodeCallable(data)).toString()
            val req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = sendAuthorized(req)
            ensureOk(resp)
            val outer = JSONObject(resp.body())
            if (outer.has("error")) error("Function error: ${outer.opt("error")}")
            outer.optJSONObject("result") ?: JSONObject()
        }

    // ── HTTP plumbing ────────────────────────────────────────────────────────

    private fun sendAuthorized(req: HttpRequest): HttpResponse<String> {
        val authed = HttpRequest.newBuilder(req, { _, _ -> true })
            .header("Authorization", "Bearer ${idToken ?: error("Not signed in")}")
            .build()
        return http.send(authed, HttpResponse.BodyHandlers.ofString())
    }

    private fun postJson(url: String, body: String, authorized: Boolean): String {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (authorized) {
            builder.header("Authorization", "Bearer ${idToken ?: error("Not signed in")}")
        }
        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        ensureOk(resp)
        return resp.body()
    }

    private fun postForm(url: String, body: String): String {
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        ensureOk(resp)
        return resp.body()
    }

    private fun ensureOk(resp: HttpResponse<String>) {
        if (resp.statusCode() !in 200..299) {
            error("HTTP ${resp.statusCode()}: ${resp.body().take(500)}")
        }
    }

    // ── Firestore typed-value codec ──────────────────────────────────────────

    private fun encodeFields(map: Map<String, Any?>): JSONObject {
        val out = JSONObject()
        map.forEach { (k, v) -> out.put(k, encodeValue(v)) }
        return out
    }

    private fun encodeValue(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("nullValue", JSONObject.NULL)
        is Boolean -> JSONObject().put("booleanValue", v)
        is Int -> JSONObject().put("integerValue", v.toString())
        is Long -> JSONObject().put("integerValue", v.toString())
        is Double -> JSONObject().put("doubleValue", v)
        is String -> JSONObject().put("stringValue", v)
        is List<*> -> JSONObject().put(
            "arrayValue", JSONObject().put("values", JSONArray(v.map { encodeValue(it) }))
        )
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val typed = v as Map<String, Any?>
            JSONObject().put("mapValue", JSONObject().put("fields", encodeFields(typed)))
        }
        else -> error("Unsupported Firestore value type: ${v::class}")
    }

    /** Callable format is plain JSON, not Firestore-typed. */
    private fun encodeCallable(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val out = JSONObject()
            v.forEach { (k, vv) -> out.put(k.toString(), encodeCallable(vv)) }
            out
        }
        is List<*> -> JSONArray(v.map { encodeCallable(it) })
        else -> v
    }

    private fun decodeDoc(doc: JSONObject): Map<String, Any?>? {
        val fields = doc.optJSONObject("fields") ?: return emptyMap()
        val out = mutableMapOf<String, Any?>()
        fields.keys().forEach { k -> out[k] = decodeValue(fields.getJSONObject(k)) }
        return out
    }

    private fun decodeValue(v: JSONObject): Any? = when {
        v.has("stringValue") -> v.getString("stringValue")
        v.has("integerValue") -> v.getString("integerValue").toLong()
        v.has("doubleValue") -> v.getDouble("doubleValue")
        v.has("booleanValue") -> v.getBoolean("booleanValue")
        v.has("nullValue") -> null
        v.has("timestampValue") -> v.getString("timestampValue")
        v.has("mapValue") -> {
            val inner = v.getJSONObject("mapValue").optJSONObject("fields") ?: JSONObject()
            val map = mutableMapOf<String, Any?>()
            inner.keys().forEach { k -> map[k] = decodeValue(inner.getJSONObject(k)) }
            map
        }
        v.has("arrayValue") -> {
            val items = v.getJSONObject("arrayValue").optJSONArray("values") ?: JSONArray()
            (0 until items.length()).map { decodeValue(items.getJSONObject(it)) }
        }
        else -> null
    }

    data class AuthResult(val uid: String, val idToken: String)
}
