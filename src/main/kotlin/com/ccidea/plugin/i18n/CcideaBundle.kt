package com.ccidea.plugin.i18n

import com.ccidea.plugin.settings.CcideaSettings
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * Locale-controlled string lookup.  Reads `uiLanguage` from [CcideaSettings] and
 * picks the matching ResourceBundle (`messages/CcideaBundle[_zh_CN].properties`).
 *
 * Falls back to the user's system locale when the setting is "system", and
 * falls back to the default bundle when a key is missing.
 */
object CcideaBundle {
    private const val BASE = "messages.CcideaBundle"

    fun message(key: String, vararg args: Any?): String {
        val raw = lookup(key) ?: return key
        return if (args.isEmpty()) raw else MessageFormat.format(raw, *args)
    }

    private fun lookup(key: String): String? {
        val locale = currentLocale()
        return try {
            ResourceBundle.getBundle(BASE, locale, javaClass.classLoader).getString(key)
        } catch (_: MissingResourceException) {
            try {
                ResourceBundle.getBundle(BASE, Locale.ROOT, javaClass.classLoader).getString(key)
            } catch (_: MissingResourceException) { null }
        }
    }

    private fun currentLocale(): Locale = when (CcideaSettings.getInstance().state.uiLanguage) {
        "en" -> Locale.ENGLISH
        "zh" -> Locale.SIMPLIFIED_CHINESE
        else -> Locale.getDefault()
    }
}

/** Idiomatic shortcut. */
fun ccideaMsg(key: String, vararg args: Any?): String = CcideaBundle.message(key, *args)
