package app.tvplayer.core.update

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A build published on GitHub that the app could update to. */
data class AvailableBuild(
    val build: Int,
    val apkUrl: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the APK, when GitHub reports one. */
    val sha256: String?,
    /**
     * The startup profile to install beside the APK, so Android compiles it for speed while installing:
     * the download address for each oldest Android version (API level) it suits.
     */
    val profiles: Map<Int, String> = emptyMap(),
) {
    /** The profile for a TV box on Android [sdk], if there is one. */
    fun profileFor(sdk: Int): String? = profiles.filterKeys { it <= sdk }.maxByOrNull { it.key }?.value
}

class UpdateException(message: String) : Exception(message)

/** Reads GitHub's "latest release" reply for the app's repository. */
object ReleaseParser {
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/EquitosFX/northlight/releases/latest"
    const val APK_NAME = "northlight.apk"

    private val json = Json { ignoreUnknownKeys = true }
    private val profilePattern = Regex("""northlight-(\d+)\.dm""")
    private val buildPattern = Regex("""build\s*[=#:]?\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): AvailableBuild {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw UpdateException("GitHub sent a reply the app couldn't read.")

        fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

        // The build number is in the release title ("Skyfall build 57"), with the notes as a fallback.
        val build = listOfNotNull(root.string("name"), root.string("body"))
            .firstNotNullOfOrNull { buildPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: throw UpdateException("The latest release doesn't say which build it is.")

        val asset = (root["assets"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.string("name") == APK_NAME }
            ?: throw UpdateException("The latest release has no $APK_NAME to download.")

        val url = asset.string("browser_download_url")?.takeIf { it.startsWith("https://") }
            ?: throw UpdateException("The latest release has no download address.")
        val size = (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0L
        val sha = asset.string("digest")
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.lowercase()
            ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

        // "northlight-31.dm": the profile for Android 12 (API 31) and later.
        val profiles = (root["assets"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .mapNotNull { item ->
                val sdk = profilePattern.matchEntire(item.string("name").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                val link = item.string("browser_download_url")?.takeIf { it.startsWith("https://") }
                if (sdk != null && link != null) sdk to link else null
            }
            .toMap()

        return AvailableBuild(build = build, apkUrl = url, sizeBytes = size, sha256 = sha, profiles = profiles)
    }

    /** Whether [available] is newer than the installed [currentBuild]. */
    fun isNewer(available: AvailableBuild, currentBuild: Long): Boolean = available.build > currentBuild
}

object FileCheck {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Checks a downloaded APK against what GitHub said it would be. Throws if it doesn't match. */
    fun verify(file: File, expected: AvailableBuild) {
        if (expected.sizeBytes > 0 && file.length() != expected.sizeBytes) {
            throw UpdateException("The download was incomplete (${file.length()} of ${expected.sizeBytes} bytes). Try again.")
        }
        val sha = expected.sha256 ?: return
        if (sha256(file) != sha) throw UpdateException("The download didn't match GitHub's checksum, so it wasn't installed. Try again.")
    }
}
