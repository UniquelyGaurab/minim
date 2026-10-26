package com.minim.music.data.update

import com.minim.music.util.SafeRegex
import com.minim.music.data.greetings.MiniJson

/** v4.9.6: one downloadable file of a GitHub Release. */
data class ReleaseAsset(
    val name: String,
    val url: String,
    /** Bytes, or -1 when GitHub didn't say. */
    val size: Long,
    val contentType: String?,
    val state: String?
)

/** v4.9.6: the parts of a GitHub Release that the update checker uses. */
data class GitHubRelease(
    val tag: String,
    val version: AppVersion,
    val name: String,
    val body: String,
    val pageUrl: String?,
    val draft: Boolean,
    val preRelease: Boolean,
    val assets: List<ReleaseAsset>
)

/** v4.9.6: a release that's newer than the installed app and has an APK to download. */
data class AvailableUpdate(
    val version: AppVersion,
    val tag: String,
    val notes: String,
    val apk: ReleaseAsset,
    val pageUrl: String?
)

/**
 * v4.9.6: reads the JSON of `GET /repos/{owner}/{repo}/releases/latest`.
 * Defensive like the greetings parser: anything unexpected gives null, never an exception.
 */
object GitHubReleaseParser {

    fun parse(json: String?): GitHubRelease? {
        if (json.isNullOrBlank()) return null
        val parsed: Any? = try {
            MiniJson.parse(json)
        } catch (e: Exception) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        val root = parsed as? Map<*, *> ?: return null

        val tag = (root["tag_name"] as? String)?.trim().orEmpty()
        val version = AppVersion.parse(tag) ?: return null
        val assets = (root["assets"] as? List<*>).orEmpty().mapNotNull { parseAsset(it) }
        return GitHubRelease(
            tag = tag,
            version = version,
            name = (root["name"] as? String).orEmpty(),
            body = (root["body"] as? String).orEmpty(),
            pageUrl = (root["html_url"] as? String)?.takeIf { it.startsWith("https://") },
            draft = root["draft"] == true,
            preRelease = root["prerelease"] == true,
            assets = assets
        )
    }

    private fun parseAsset(raw: Any?): ReleaseAsset? {
        val m = raw as? Map<*, *> ?: return null
        val name = (m["name"] as? String)?.trim().orEmpty()
        val url = (m["browser_download_url"] as? String)?.trim().orEmpty()
        if (name.isEmpty() || url.isEmpty()) return null
        val size = when (val s = m["size"]) {
            is Long -> s
            is Double -> s.toLong()
            else -> -1L
        }
        return ReleaseAsset(
            name = name,
            url = url,
            size = if (size >= 0) size else -1L,
            contentType = m["content_type"] as? String,
            state = m["state"] as? String
        )
    }
}

/** v4.9.6: the update decisions, all pure so they're unit-tested. */
object UpdateRules {

    /** Never download anything bigger than this from a release (Minim is about 7 MB). */
    const val MAX_APK_BYTES = 150L * 1024L * 1024L

    private const val NOTES_MAX_LINES = 6
    private const val NOTES_MAX_CHARS = 360

    /**
     * The APK to download from [assets]: a finished upload whose name ends in .apk, from https,
     * and not absurdly big. When there are several, one whose name matches the installed build
     * ("debug" in the name for a debug install, no "debug" for a release install) wins; otherwise
     * the first. Today's releases have one APK, Minim-v4.9.x.apk.
     */
    fun pickApk(assets: List<ReleaseAsset>, installedIsDebug: Boolean): ReleaseAsset? {
        val apks = assets.filter { a ->
            a.name.endsWith(".apk", ignoreCase = true) &&
                a.url.startsWith("https://") &&
                (a.state == null || a.state == "uploaded") &&
                a.size != 0L && a.size <= MAX_APK_BYTES
        }
        if (apks.isEmpty()) return null
        return apks.firstOrNull { it.name.contains("debug", ignoreCase = true) == installedIsDebug }
            ?: apks.first()
    }

    /**
     * The update to offer, or null: only for a published (not draft, not prerelease) release that's
     * newer than [installedVersionName] and has an APK. A version that can't be read is never offered.
     */
    fun availableUpdate(installedVersionName: String?, release: GitHubRelease?): AvailableUpdate? {
        if (release == null || release.draft || release.preRelease || release.version.isPreRelease) return null
        val installed = AppVersion.installed(installedVersionName) ?: return null
        if (release.version <= installed) return null
        val debug = installedVersionName?.trim()?.endsWith("-debug") == true
        val apk = pickApk(release.assets, debug) ?: return null
        return AvailableUpdate(
            version = release.version,
            tag = release.tag,
            notes = shortNotes(release.body),
            apk = apk,
            pageUrl = release.pageUrl
        )
    }

    /**
     * The release body made short and plain for the pop-up: Markdown headings, bold, code marks and
     * link targets removed, bullets as "•", blank lines dropped, at most [NOTES_MAX_LINES] lines and
     * about [NOTES_MAX_CHARS] characters (ending in "…" when cut).
     */
    fun shortNotes(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { cleanLine(it) }
            .filter { it.isNotEmpty() }
        val out = StringBuilder()
        var count = 0
        var cut = false
        for (line in lines) {
            if (count == NOTES_MAX_LINES) { cut = true; break }
            val room = NOTES_MAX_CHARS - out.length - (if (out.isEmpty()) 0 else 1)
            if (room <= 0) { cut = true; break }
            if (out.isNotEmpty()) out.append('\n')
            if (line.length > room) {
                out.append(trimToWord(line, room))
                cut = true
                break
            }
            out.append(line)
            count++
        }
        var text = out.toString().trimEnd()
        if (cut && !text.endsWith("…")) text = text.trimEnd('.', ',', ';', ':', ' ') + "…"
        return text
    }

    /** What a downloaded APK turned out to be, checked before the installer opens. */
    enum class ArchiveCheck { OK, BROKEN, OTHER_APP, OLDER }

    /**
     * The downloaded file must be Minim with the same package name as the installed app (a debug
     * install only takes a debug build) and must not be an older build, which Android would refuse.
     * [archivePackage] null = Android couldn't read the file.
     */
    fun checkArchive(
        archivePackage: String?,
        archiveVersionCode: Long,
        installedPackage: String,
        installedVersionCode: Long
    ): ArchiveCheck = when {
        archivePackage.isNullOrEmpty() -> ArchiveCheck.BROKEN
        archivePackage != installedPackage -> ArchiveCheck.OTHER_APP
        archiveVersionCode < installedVersionCode -> ArchiveCheck.OLDER
        else -> ArchiveCheck.OK
    }

    /** The file name the APK of [version] is saved under. */
    fun apkFileName(version: AppVersion): String = "Minim-$version.apk"

    /** Old downloads to delete: saved APKs for versions the app already has (or newer junk names). */
    fun isStaleDownload(fileName: String, installed: AppVersion?): Boolean {
        if (fileName.endsWith(".part")) return true
        if (!fileName.startsWith("Minim-") || !fileName.endsWith(".apk")) return true
        val v = AppVersion.parse(fileName.removePrefix("Minim-").removeSuffix(".apk")) ?: return true
        return installed != null && v <= installed
    }

    private val linkRegex = SafeRegex.of("""!?\[([^\]]*)\]\([^)]*\)""")
    private val htmlTagRegex = SafeRegex.of("""<[^>]{1,200}>""")

    private fun cleanLine(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.matches(SafeRegex.of("""^([-*_]\s*){3,}$"""))) return "" // horizontal rule
        if (s.startsWith("```")) return ""
        s = s.replace(SafeRegex.of("""^#{1,6}\s*"""), "")
        s = s.replace(SafeRegex.of("""^>\s?"""), "")
        var bullet = false
        SafeRegex.of("""^([-*+]|\d{1,3}[.)])\s+""").find(s)?.let {
            s = s.substring(it.range.last + 1)
            bullet = true
        }
        s = linkRegex.replace(s) { it.groupValues[1] }
        s = htmlTagRegex.replace(s, "")
        s = s.replace("**", "").replace("__", "").replace("`", "")
        s = s.replace(SafeRegex.of("""\s+"""), " ").trim()
        if (s.isEmpty()) return ""
        return if (bullet) "• $s" else s
    }

    private fun trimToWord(line: String, max: Int): String {
        if (max <= 1) return ""
        val hard = line.substring(0, max - 1)
        val space = hard.lastIndexOf(' ')
        return (if (space > max / 2) hard.substring(0, space) else hard).trimEnd() + "…"
    }
}
