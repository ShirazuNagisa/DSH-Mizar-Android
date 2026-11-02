package me.shirazu.mizar.kit

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Guards the kit against Java 10 APIs that Android only gained much later.
 *
 * `mizarkit` is a plain Kotlin/JVM module compiled against JDK 17, so nothing in
 * the Android lint pass looks at it. That is how `URLDecoder.decode(String,
 * Charset)` — a Java 10 overload that Android only added in API 33 — shipped and
 * crashed every pairing attempt on Android 8 through 12L with `NoSuchMethodError`.
 *
 * This is a targeted guard for that one family, not a substitute for compiling
 * the kit against `android.jar`. It reads the sources and rejects the `Charset`
 * overloads; the name overloads have existed since Android 1 and are what the
 * code must use.
 */
class AndroidApiCompatibilityTest {

    private fun sourceRoot(): File? {
        val candidates = listOf(
            File("src/main/kotlin"),
            File("../mizarkit/src/main/kotlin"),
        )
        return candidates.firstOrNull { it.isDirectory }
    }

    @Test
    fun `url codecs are called with a charset name, not a Charset`() {
        val root = sourceRoot()
        assumeTrue(root != null, "kit sources are not reachable from the test working directory")

        val offenders = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    val callsCodec = "URLDecoder.decode(" in line || "URLEncoder.encode(" in line
                    if (callsCodec && "Charsets." in line) {
                        "${file.name}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "these calls use the Java 10 Charset overload, which does not exist before " +
                "Android API 33 and fails with NoSuchMethodError on API 26:\n" +
                offenders.joinToString("\n"),
        )
    }
}
