package com.smapifan.androidmodder.service

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * Loads locale-specific UI strings from `messages_<locale>.properties`.
 * Falls back to English if the requested locale is not available.
 *
 * Usage:
 * ```
 * val i18n = I18nService()                     // uses system locale
 * val i18n = I18nService(Locale.ENGLISH)       // forced locale
 * println(i18n.get("app.title"))
 * println(i18n.format("app.catalog.title", 14, 9, 10))
 * ```
 */
class I18nService(private val locale: Locale = Locale.getDefault()) {

    private val bundle: ResourceBundle = try {
        ResourceBundle.getBundle("messages", locale)
    } catch (_: MissingResourceException) {
        ResourceBundle.getBundle("messages", Locale.ENGLISH)
    }

    /** Returns the raw message string for [key]. */
    fun get(key: String): String = try {
        bundle.getString(key)
    } catch (_: MissingResourceException) {
        "[$key]"
    }

    /** Returns the message for [key] with [args] substituted via [MessageFormat]. */
    fun format(key: String, vararg args: Any): String =
        MessageFormat.format(get(key), *args)
}
