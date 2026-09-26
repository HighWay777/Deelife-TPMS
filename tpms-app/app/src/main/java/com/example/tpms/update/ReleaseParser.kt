package com.example.tpms.update

import org.json.JSONObject

/** What the app needs to know about a newer release published on Gitea. */
data class UpdateInfo(
    /** From update.json; -1 when the release has no manifest (then [versionName] is compared). */
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 of the APK, or null when the release has no manifest. */
    val sha256: String?,
    val minSdk: Int,
    val pageUrl: String
)

data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class GiteaRelease(
    val tag: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>
)

/**
 * Optional `update.json` asset attached to each release:
 * `{"versionCode":12,"versionName":"1.12","apk":"Deelife-TPMS-v1.12.apk","sha256":"…","minSdk":21}`
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String?,
    val apk: String?,
    val sha256: String?,
    val minSdk: Int
)

/** Pure parsing / comparison logic, kept free of Android APIs so it is unit-testable. */
object ReleaseParser {

    fun parseRelease(json: String): GiteaRelease {
        val o = JSONObject(json)
        val assetsJson = o.optJSONArray("assets")
        val assets = buildList {
            if (assetsJson != null) {
                for (i in 0 until assetsJson.length()) {
                    val a = assetsJson.getJSONObject(i)
                    add(ReleaseAsset(
                        name = a.optString("name"),
                        url = a.optString("browser_download_url"),
                        size = a.optLong("size", -1L)
                    ))
                }
            }
        }
        return GiteaRelease(
            tag = o.optString("tag_name"),
            name = o.optString("name"),
            body = o.optString("body"),
            htmlUrl = o.optString("html_url"),
            assets = assets
        )
    }

    fun parseManifest(json: String): UpdateManifest {
        val o = JSONObject(json)
        return UpdateManifest(
            versionCode = o.optInt("versionCode", -1),
            versionName = o.optString("versionName").ifBlank { null },
            apk = o.optString("apk").ifBlank { null },
            sha256 = o.optString("sha256").ifBlank { null }?.lowercase(),
            minSdk = o.optInt("minSdk", 0)
        )
    }

    /** Combines a release and its (optional) manifest; null when the release carries no APK. */
    fun buildUpdateInfo(release: GiteaRelease, manifest: UpdateManifest?): UpdateInfo? {
        val apk = manifest?.apk?.let { name -> release.assets.firstOrNull { it.name == name } }
            ?: release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return null
        return UpdateInfo(
            versionCode = manifest?.versionCode ?: -1,
            versionName = manifest?.versionName ?: release.tag.trimStart('v', 'V'),
            title = release.name.ifBlank { release.tag },
            notes = release.body,
            apkUrl = apk.url,
            apkSize = apk.size,
            sha256 = manifest?.sha256,
            minSdk = manifest?.minSdk ?: 0,
            pageUrl = release.htmlUrl
        )
    }

    fun isNewer(info: UpdateInfo, currentCode: Int, currentName: String): Boolean =
        if (info.versionCode > 0) info.versionCode > currentCode
        else compareVersions(info.versionName, currentName) > 0

    /** Numeric, segment-wise comparison: "1.12" > "1.11", "1.2.1" > "1.2", "v1.3" == "1.3". */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(Regex("\\D+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0L }
        val pb = b.split(Regex("\\D+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0L }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0L }).compareTo(pb.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return 0
    }

    /** Release notes are Markdown on Gitea; show them as readable plain text. */
    fun plainNotes(markdown: String): String =
        markdown.lines().joinToString("\n") { line ->
            line.replace(Regex("^\\s*#{1,6}\\s*"), "")
                .replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
                .replace("**", "")
                .replace("`", "")
        }.replace(Regex("\n{3,}"), "\n\n").trim()
}
