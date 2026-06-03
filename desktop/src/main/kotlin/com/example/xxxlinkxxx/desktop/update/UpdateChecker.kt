package com.example.xxxlinkxxx.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Polls GitHub Releases for newer XxxLink builds and (if approved by the
 * user) downloads the .msi asset to {user.home}\.xxxlink\updates\ and
 * spawns the system installer. Mirrors the Android self-update flow in
 * MainActivity (findLatestRelease / checkForUpdates).
 *
 * Version comparison: parses "1.15.8" / "1.15.8-beta" semver-ish into
 * an Int triple; the build-time CURRENT_VERSION is hardcoded so we can
 * stay an offline-friendly single JAR.
 *
 * Skips entirely when the GitHub repo isn't reachable or no .msi asset
 * is present on the latest release — desktop releases are MSI-only.
 */
object UpdateChecker {
    private const val GITHUB_OWNER = "nykleforse"
    private const val GITHUB_REPO = "xxxlinxxx"
    private const val DESKTOP_ASSET_SUFFIX = ".msi"
    const val CURRENT_VERSION = "1.15.8"

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    suspend fun checkLatest(includeBeta: Boolean = true): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val url = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases" +
                "?per_page=20"
            val resp = http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (resp.statusCode() !in 200..299) return@withContext null
            val arr = JSONArray(resp.body())
            var best: ReleaseCandidate? = null
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val tag = obj.optString("tag_name").trimStart('v')
                if (tag.isEmpty()) continue
                val prerelease = obj.optBoolean("prerelease", false)
                if (prerelease && !includeBeta) continue
                val assets = obj.optJSONArray("assets") ?: continue
                val msi = (0 until assets.length()).asSequence()
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(DESKTOP_ASSET_SUFFIX, true) }
                    ?: continue
                val candidate = ReleaseCandidate(
                    version = tag,
                    msiName = msi.getString("name"),
                    msiUrl = msi.getString("browser_download_url"),
                    body = obj.optString("body"),
                    prerelease = prerelease,
                )
                if (best == null || compareVersions(candidate.version, best!!.version) > 0) {
                    best = candidate
                }
            }
            val winner = best ?: return@withContext null
            val cmp = compareVersions(winner.version, CURRENT_VERSION)
            if (cmp <= 0) return@withContext null
            UpdateInfo(winner.version, winner.msiName, winner.msiUrl, winner.body, winner.prerelease)
        }

    /**
     * Downloads the MSI to user-local cache and returns the file. Caller
     * launches the installer (e.g. via Desktop.getDesktop().open(file))
     * after confirming with the user.
     */
    suspend fun downloadMsi(info: UpdateInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            val dir = File(File(System.getProperty("user.home"), ".xxxlink"), "updates")
            dir.mkdirs()
            val target = File(dir, info.msiName)
            val req = HttpRequest.newBuilder(URI.create(info.msiUrl))
                .header("Accept", "application/octet-stream")
                .GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() !in 200..299) error("Download HTTP ${resp.statusCode()}")
            val total = resp.headers().firstValueAsLong("Content-Length").orElse(-1)
            var got = 0L
            target.outputStream().use { out ->
                resp.body().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        onProgress(got, total)
                    }
                }
            }
            target
        }

    private fun compareVersions(a: String, b: String): Int {
        val aParts = parseSemver(a)
        val bParts = parseSemver(b)
        for (i in 0 until 4) {
            val diff = aParts[i] - bParts[i]
            if (diff != 0) return diff
        }
        return 0
    }

    /**
     * Returns (major, minor, patch, prerelease-rank). "1.15.8-beta" →
     * (1,15,8,0). "1.15.8" → (1,15,8,1). Stable is always > beta of
     * same triple.
     */
    private fun parseSemver(s: String): IntArray {
        val cleaned = s.trimStart('v')
        val dashIdx = cleaned.indexOf('-')
        val base = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
        val isBeta = dashIdx >= 0
        val parts = base.split('.').mapNotNull { it.toIntOrNull() }
        val mj = parts.getOrNull(0) ?: 0
        val mn = parts.getOrNull(1) ?: 0
        val pt = parts.getOrNull(2) ?: 0
        return intArrayOf(mj, mn, pt, if (isBeta) 0 else 1)
    }

    private data class ReleaseCandidate(
        val version: String,
        val msiName: String,
        val msiUrl: String,
        val body: String,
        val prerelease: Boolean,
    )

    data class UpdateInfo(
        val version: String,
        val msiName: String,
        val msiUrl: String,
        val body: String,
        val prerelease: Boolean,
    )
}
