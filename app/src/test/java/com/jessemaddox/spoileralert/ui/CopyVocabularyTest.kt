package com.jessemaddox.spoileralert.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the Hide/Hiding vocabulary: user-facing copy must not reintroduce retired jargon
 * (shield, vault, arm/armed/arming, sealed, intercepted, block/blocking, caught, protect/protection).
 * Scans strings.xml and a curated set of pure copy holders — not the whole codebase — reading
 * source as text the way TeamsJsonTest reads assets. Only quoted string literals are scanned;
 * comments, interpolations, and token-like keys/ids are skipped so internal identifiers stay exempt.
 * DebugScreen and other non-copy surfaces are intentionally out of scope.
 */
class CopyVocabularyTest {
    private val banned = Regex(
        "\\b(shields?|vaults?|arm|armed|arming|arms|seal|sealed|seals|sealing" +
            "|intercept|intercepts|intercepted|intercepting|block|blocks|blocked|blocking" +
            "|caught|protect|protects|protected|protecting|protection)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val separators = charArrayOf('-', '.', '_', ':', '/')

    private val kotlinCopyFiles = listOf(
        "src/main/java/com/jessemaddox/spoileralert/service/SessionEndCopy.kt",
        "src/main/java/com/jessemaddox/spoileralert/service/ProtectionNotificationPolicy.kt",
        "src/main/java/com/jessemaddox/spoileralert/SpoilerAlertApp.kt",
    )

    @Test fun `strings xml uses no retired jargon`() {
        val xml = File("src/main/res/values/strings.xml").readText()
        val offenders = Regex("<string[^>]*>([\\s\\S]*?)</string>").findAll(xml)
            .map { it.groupValues[1] }
            .filter { banned.containsMatchIn(it) }
            .toList()
        assertTrue("Retired jargon in strings.xml: $offenders", offenders.isEmpty())
    }

    @Test fun `pure copy objects use no retired jargon`() {
        val offenders = kotlinCopyFiles.flatMap { path ->
            literalsOf(File(path).readText())
                .filter { banned.containsMatchIn(it) }
                .map { "$path :: \"$it\"" }
        }
        assertTrue("Retired jargon in copy literals: $offenders", offenders.isEmpty())
    }

    /** Quoted literals with comments/interpolations removed and token-like keys skipped. */
    private fun literalsOf(source: String): List<String> {
        val noComments = source
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        return Regex("\"([^\"\\\\]|\\\\.)*\"").findAll(noComments)
            .map { it.value.trim('"') }
            .map {
                it.replace(Regex("\\$\\{[^}]*}"), " ")
                    .replace(Regex("\\$[A-Za-z_][A-Za-z0-9_]*"), " ")
                    .trim()
            }
            .filterNot { it.isEmpty() }
            .filterNot { token -> token.none(Char::isWhitespace) && token.any { it in separators } }
            .toList()
    }
}
