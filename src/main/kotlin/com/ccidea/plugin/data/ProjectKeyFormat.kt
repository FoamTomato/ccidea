package com.ccidea.plugin.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Claude Code stores its session JSONL files under
 *   ~/.claude/projects/<slug>/<sessionId>.jsonl
 * where <slug> is the absolute project path with each '/' replaced by '-' and a leading '-'.
 *
 * The replacement is lossy when the original path contains '-' segments (e.g.
 * /Users/foam/code/ccidea-plug → -Users-foam-code-ccidea-plug, indistinguishable from
 * /Users/foam/code/ccidea/plug). To recover the real directory we probe the filesystem,
 * grouping consecutive '-' tokens into either a separator or part of a name.
 *
 * If the resolved directory is a git working tree, prefer "<repoName> (<branch>)" as the
 * display label; otherwise fall back to the last path segment of the resolved directory,
 * or — when nothing resolves — the last token of the slug.
 */
object ProjectKeyFormat {

    data class Resolved(
        val display: String,
        val fullPath: String,
        val branch: String?
    )

    private val cache = ConcurrentHashMap<String, Resolved>()

    fun resolve(rawSlug: String): Resolved {
        if (rawSlug.isBlank()) return Resolved("(unknown)", "", null)
        cache[rawSlug]?.let { return it }
        val tokens = rawSlug.trimStart('-').split('-').filter { it.isNotBlank() }
        val realDir = probeRealPath(tokens)
        val resolved = if (realDir != null) {
            val gitTop = findGitToplevel(realDir)
            if (gitTop != null) {
                val branch = readGitBranch(gitTop)
                val name = gitTop.fileName?.toString() ?: realDir.fileName.toString()
                val display = if (branch != null) "$name ($branch)" else name
                Resolved(display, realDir.toString(), branch)
            } else {
                Resolved(realDir.fileName?.toString() ?: fallbackName(tokens), realDir.toString(), null)
            }
        } else {
            Resolved(fallbackName(tokens), reconstructPath(rawSlug), null)
        }
        cache[rawSlug] = resolved
        return resolved
    }

    /** Short label suitable for table cells. */
    fun shortName(rawSlug: String): String = resolve(rawSlug).display

    /** Tooltip text — real disk path when resolvable, otherwise the naive reconstruction. */
    fun reconstructPath(rawSlug: String): String {
        val cached = cache[rawSlug]
        if (cached != null && cached.fullPath.isNotEmpty()) return cached.fullPath
        val trimmed = rawSlug.trimStart('-')
        return "/" + trimmed.replace('-', '/')
    }

    private fun fallbackName(tokens: List<String>): String =
        tokens.lastOrNull() ?: "(unknown)"

    /**
     * Walk tokens left-to-right. At each step, the next directory entry can be one or more
     * consecutive tokens joined by '-' (since the original directory name might contain '-').
     * Greedy match: prefer the longest joined candidate that exists on disk; fall back to
     * shorter joins. Returns null when no full path resolves.
     */
    private fun probeRealPath(tokens: List<String>): Path? {
        if (tokens.isEmpty()) return null
        return walk(Paths.get("/"), tokens, 0)
    }

    private fun walk(current: Path, tokens: List<String>, idx: Int): Path? {
        if (idx >= tokens.size) {
            return if (Files.isDirectory(current)) current else null
        }
        // Try greedy: combine as many remaining tokens as possible into one segment.
        for (take in (tokens.size - idx) downTo 1) {
            val name = tokens.subList(idx, idx + take).joinToString("-")
            val next = current.resolve(name)
            if (Files.exists(next)) {
                val res = walk(next, tokens, idx + take)
                if (res != null) return res
            }
        }
        return null
    }

    private fun findGitToplevel(dir: Path): Path? {
        var p: Path? = dir
        while (p != null) {
            if (Files.isDirectory(p.resolve(".git")) || Files.isRegularFile(p.resolve(".git"))) return p
            p = p.parent
        }
        return null
    }

    private fun readGitBranch(repoRoot: Path): String? = runCatching {
        val gitPath = repoRoot.resolve(".git")
        val headFile = when {
            Files.isDirectory(gitPath) -> gitPath.resolve("HEAD")
            Files.isRegularFile(gitPath) -> {
                // git worktree: ".git" is a file with "gitdir: <path>"
                val line = Files.readString(gitPath).trim()
                val gitDir = line.removePrefix("gitdir:").trim()
                Paths.get(gitDir).resolve("HEAD")
            }
            else -> return@runCatching null
        }
        if (!Files.isRegularFile(headFile)) return@runCatching null
        val head = Files.readString(headFile).trim()
        when {
            head.startsWith("ref: refs/heads/") -> head.removePrefix("ref: refs/heads/")
            head.startsWith("ref: ") -> head.removePrefix("ref: ").substringAfterLast('/')
            head.length >= 7 -> head.take(7) // detached HEAD → short sha
            else -> null
        }
    }.getOrNull()
}
