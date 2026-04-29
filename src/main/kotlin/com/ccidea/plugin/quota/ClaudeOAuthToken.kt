package com.ccidea.plugin.quota

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Reads Claude Code's OAuth access token from the OS-native credential store.
 *
 * - macOS: tries `~/.claude/.credentials.json` first (some installs land tokens
 *   in a flat file); otherwise reads the Keychain entry "Claude Code-credentials".
 * - Other platforms: not implemented yet — returns null and the caller silently
 *   falls back to local quota estimation.
 *
 * Credential JSON shape: { "claudeAiOauth": { "accessToken": "sk-ant-oat01-...", ... } }
 */
object ClaudeOAuthToken {
    private val log = Logger.getInstance(ClaudeOAuthToken::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(): String? {
        readFromFile()?.let { return it }
        if (SystemInfo.isMac) return readFromMacKeychain()
        return null
    }

    private fun readFromFile(): String? = runCatching {
        val home = System.getProperty("user.home") ?: return@runCatching null
        val path = Paths.get(home, ".claude", ".credentials.json")
        if (!Files.isRegularFile(path)) return@runCatching null
        parseAccessToken(Files.readString(path))
    }.getOrNull()

    private fun readFromMacKeychain(): String? {
        val user = System.getProperty("user.name") ?: return null
        return runCatching {
            val proc = ProcessBuilder(
                "/usr/bin/security",
                "find-generic-password",
                "-a", user,
                "-s", "Claude Code-credentials",
                "-w"
            ).redirectErrorStream(false).start()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@runCatching null
            }
            if (proc.exitValue() != 0) return@runCatching null
            val raw = proc.inputStream.bufferedReader().readText().trim()
            parseAccessToken(raw)
        }.onFailure { log.debug("Keychain read failed", it) }.getOrNull()
    }

    private fun parseAccessToken(raw: String): String? {
        if (raw.isBlank()) return null
        val tok = json.parseToJsonElement(raw).jsonObject["claudeAiOauth"]?.jsonObject
            ?.get("accessToken")?.jsonPrimitive?.content
        return tok?.takeIf { it.isNotBlank() }
    }
}
