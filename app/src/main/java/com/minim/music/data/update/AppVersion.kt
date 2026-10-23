package com.minim.music.data.update

/**
 * v4.9.6: a semantic version (major.minor.patch, optional -prerelease, optional +build) for the
 * update checker. Pure Kotlin, so it's unit-tested.
 *
 * - A leading "v" or "V" is dropped ("v4.9.7" = "4.9.7"), and so is surrounding whitespace.
 * - "4.9" and "4" are read as 4.9.0 and 4.0.0, so a short tag still compares sensibly.
 * - Numbers compare as numbers: 4.9.10 is newer than 4.9.9.
 * - A prerelease is older than the same plain version (4.9.7-beta < 4.9.7), compared field by
 *   field as semver 2.0 says (numeric fields numerically, below text fields).
 * - Build metadata after "+" is ignored.
 * - Anything else (empty, letters in the numbers, leading zeros like 04, negative, 4 parts) is
 *   malformed: [parse] returns null and the checker treats the release as "no update".
 */
class AppVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String>
) : Comparable<AppVersion> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: AppVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // A version without a prerelease is newer than one with
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return other.preRelease.size.coerceAtMost(1) - preRelease.size.coerceAtMost(1)
        }
        val n = minOf(preRelease.size, other.preRelease.size)
        for (i in 0 until n) {
            val c = compareIdentifier(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0

    override fun hashCode(): Int = ((major * 31 + minor) * 31 + patch) * 31 + preRelease.hashCode()

    /** "4.9.7" or "4.9.7-beta.1", without the v. */
    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private const val MAX_PART = 1_000_000

        /** The parsed version, or null when [text] isn't a version. */
        fun parse(text: String?): AppVersion? {
            if (text == null) return null
            var s = text.trim()
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            if (s.isEmpty() || s.length > 64) return null
            s = s.substringBefore('+')
            val dash = s.indexOf('-')
            val core = if (dash >= 0) s.substring(0, dash) else s
            val pre = if (dash >= 0) s.substring(dash + 1) else null
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val nums = parts.map { numberOrNull(it) ?: return null }
            val preParts = if (pre == null) emptyList() else {
                if (pre.isEmpty()) return null
                val ids = pre.split('.')
                for (id in ids) {
                    if (id.isEmpty() || !id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }) return null
                }
                ids
            }
            return AppVersion(nums[0], nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 }, preParts)
        }

        /**
         * The installed app's version from its versionName. Debug builds are named "4.9.5-debug"
         * (versionNameSuffix); that suffix is the build type, not a prerelease, so it's dropped.
         */
        fun installed(versionName: String?): AppVersion? {
            val name = versionName?.trim() ?: return null
            return parse(name.removeSuffix("-debug").removeSuffix("-release"))
        }

        private fun numberOrNull(p: String): Int? {
            if (p.isEmpty() || p.length > 7 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            return p.toInt().takeIf { it <= MAX_PART }
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val an = a.all { it in '0'..'9' }
            val bn = b.all { it in '0'..'9' }
            return when {
                an && bn -> a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
                an -> -1
                bn -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
