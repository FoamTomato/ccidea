package com.ccidea.plugin.data

/**
 * Claude Code stores its session JSONL files under
 *   ~/.claude/projects/<slug>/<sessionId>.jsonl
 * where <slug> is the absolute project path with each '/' replaced by '-' and a leading '-'
 * (e.g. /Users/foam/code/python-boss-start  →  -Users-foam-code-python-boss-start).
 *
 * The raw slug is unreadable in tables. This helper extracts a short, human-friendly name
 * by taking the last non-empty path segment and trimming Users-<homedir> noise.
 */
object ProjectKeyFormat {

    private val WHITELIST_PREFIXES = listOf(
        "Users-foam-", "Users-", "home-", "opt-", "var-", "tmp-"
    )

    fun shortName(rawSlug: String): String {
        if (rawSlug.isBlank()) return "(unknown)"
        // Strip leading '-' and split. Empty segments (from consecutive '-' i.e. nested '/')
        // are dropped — they don't carry name information.
        val segments = rawSlug.trimStart('-').split('-').filter { it.isNotBlank() }
        if (segments.isEmpty()) return "(unknown)"

        // Strip generic prefixes that almost every macOS path starts with so the result
        // focuses on the meaningful tail (e.g. "Users", "foam", "home", "var").
        val noiseHead = setOf("Users", "home", "var", "tmp", "opt", "Volumes", "private", "mnt")
        var meaningful = segments.toMutableList()
        // Drop up to first 2 leading noise segments (covers "Users-foam-...").
        var stripped = 0
        while (stripped < 2 && meaningful.size > 1 && meaningful.first() in noiseHead) {
            meaningful.removeAt(0); stripped++
        }
        // After "Users", the next segment is almost always the username. Drop it too if
        // the rest is non-empty so users see "yiya/backend" not "foam/yiya/backend".
        if (stripped > 0 && meaningful.size > 1 && meaningful.first().length <= 16) {
            meaningful.removeAt(0)
        }
        if (meaningful.isEmpty()) meaningful = segments.toMutableList()

        // Show last 2 segments joined with '/' so the project context is clear.
        return if (meaningful.size >= 2) "${meaningful[meaningful.size - 2]}/${meaningful.last()}"
               else meaningful.last()
    }

    /** Full path string reconstructed from the slug. Useful for tooltips. */
    fun reconstructPath(rawSlug: String): String {
        val trimmed = rawSlug.trimStart('-')
        return "/" + trimmed.replace('-', '/')
    }
}
