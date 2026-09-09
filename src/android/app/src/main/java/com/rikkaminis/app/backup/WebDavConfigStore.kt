package com.rikkaminis.app.backup

import android.content.Context
import android.content.SharedPreferences
import com.rikkaminis.app.util.EncryptedPrefsFactory

/**
 * Persists the WebDAV server configuration in EncryptedSharedPreferences
 * (AES256-GCM), unlike rikkahub which keeps the password in plaintext
 * DataStore. Mirrors the app's [com.rikkaminis.app.data.repository.EnvVarRepository]
 * storage pattern: metadata + secret live in the same encrypted file, read
 * back decrypted only for the duration of a request.
 */
class WebDavConfigStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        EncryptedPrefsFactory.safeCreate(context, PREFS_NAME)
    }

    /** The configured server, or null when never saved. */
    fun load(): WebDavConfig? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        return WebDavConfig(
            url = url,
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            path = prefs.getString(KEY_PATH, WebDavConfig.DEFAULT_BACKUP_DIR)
                ?: WebDavConfig.DEFAULT_BACKUP_DIR,
        )
    }

    /** Save the server settings. A blank [WebDavConfig.password] CLEARS the
     *  stored password — the config dialog pre-fills the stored value, so a
     *  blank only happens when the user deliberately clears it (e.g. to
     *  switch servers or remove credentials). */
    fun save(config: WebDavConfig) {
        prefs.edit()
            .putString(KEY_URL, config.url.trim())
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_PATH, config.path.trim().ifBlank { WebDavConfig.DEFAULT_BACKUP_DIR })
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "webdav_config"
        private const val KEY_URL = "url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_PATH = "path"
    }
}
