package app.quickerlink.data

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface PreferenceWriteResult {
    data object Success : PreferenceWriteResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : PreferenceWriteResult
}

interface QuickerPreferences {
    fun loadConnection(): StoredConnection

    fun saveConnection(connection: StoredConnection): PreferenceWriteResult

    fun clearRememberedPassword(): PreferenceWriteResult

    fun loadActions(): List<SavedAction>

    fun saveActions(actions: List<SavedAction>)

    fun loadFeatureSettings(): FeatureSettings

    fun saveFeatureSettings(settings: FeatureSettings): PreferenceWriteResult
}

class AppPreferences(context: Context) : QuickerPreferences {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        if (preferences.contains(LEGACY_KEY_CLIPBOARD_SYNC)) {
            preferences.edit { remove(LEGACY_KEY_CLIPBOARD_SYNC) }
        }
    }

    override fun loadConnection(): StoredConnection {
        // New installations default to seamless reconnect; an explicit opt-out is persisted as false.
        val rememberPassword = preferences.getBoolean(KEY_REMEMBER_PASSWORD, true)
        return StoredConnection(
            ipAddress = preferences.getString(KEY_IP_ADDRESS, "").orEmpty(),
            port = preferences.getInt(KEY_PORT, 668),
            rememberPassword = rememberPassword,
            password = if (rememberPassword) decrypt(preferences.getString(KEY_PASSWORD, null)).orEmpty() else "",
            requiresPassword = preferences.getBoolean(KEY_REQUIRES_PASSWORD, false),
            serviceActionId = preferences.getString(KEY_SERVICE_ACTION_ID, null),
        )
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun saveConnection(connection: StoredConnection): PreferenceWriteResult {
        val encryptedPassword = if (connection.rememberPassword && connection.password.isNotEmpty()) {
            try {
                encrypt(connection.password)
            } catch (error: Exception) {
                return PreferenceWriteResult.Failure("无法加密保存验证码", error)
            }
        } else {
            null
        }

        val saved = try {
            preferences.edit().apply {
                putString(KEY_IP_ADDRESS, connection.ipAddress)
                putInt(KEY_PORT, connection.port)
                remove(LEGACY_KEY_SECURE)
                putBoolean(KEY_REMEMBER_PASSWORD, connection.rememberPassword)
                putBoolean(KEY_REQUIRES_PASSWORD, connection.requiresPassword)
                connection.serviceActionId?.let { putString(KEY_SERVICE_ACTION_ID, it) }
                    ?: remove(KEY_SERVICE_ACTION_ID)
                if (encryptedPassword != null) {
                    putString(KEY_PASSWORD, encryptedPassword)
                } else {
                    remove(KEY_PASSWORD)
                }
            }.commit()
        } catch (error: Exception) {
            return PreferenceWriteResult.Failure("无法保存连接设置", error)
        }

        return if (saved) {
            PreferenceWriteResult.Success
        } else {
            PreferenceWriteResult.Failure("无法保存连接设置")
        }
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun clearRememberedPassword(): PreferenceWriteResult {
        val saved = try {
            preferences.edit()
                .putBoolean(KEY_REMEMBER_PASSWORD, false)
                .remove(KEY_PASSWORD)
                .commit()
        } catch (error: Exception) {
            return PreferenceWriteResult.Failure("无法删除已保存的验证码", error)
        }

        return if (saved) {
            PreferenceWriteResult.Success
        } else {
            PreferenceWriteResult.Failure("无法删除已保存的验证码")
        }
    }

    override fun loadActions(): List<SavedAction> {
        val json = preferences.getString(KEY_ACTIONS, null) ?: return emptyList()
        return decodeSavedActions(json, gson)
    }

    override fun saveActions(actions: List<SavedAction>) {
        preferences.edit { putString(KEY_ACTIONS, gson.toJson(actions)) }
    }

    override fun loadFeatureSettings(): FeatureSettings = FeatureSettings(
        backgroundConnectionEnabled = preferences.getBoolean(
            KEY_BACKGROUND_CONNECTION,
            DEFAULT_BACKGROUND_CONNECTION_ENABLED,
        ),
        receiptCueEnabled = preferences.getBoolean(
            KEY_RECEIPT_CUE,
            DEFAULT_RECEIPT_CUE_ENABLED,
        ),
    )

    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun saveFeatureSettings(settings: FeatureSettings): PreferenceWriteResult {
        val saved = try {
            preferences.edit()
                .putBoolean(KEY_BACKGROUND_CONNECTION, settings.backgroundConnectionEnabled)
                .putBoolean(KEY_RECEIPT_CUE, settings.receiptCueEnabled)
                .commit()
        } catch (error: Exception) {
            return PreferenceWriteResult.Failure("无法保存增强功能设置", error)
        }

        return if (saved) {
            PreferenceWriteResult.Success
        } else {
            PreferenceWriteResult.Failure("无法保存增强功能设置")
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$iv:$payload"
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val parts = value.split(':', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "quicker_link"
        const val KEY_IP_ADDRESS = "ip_address"
        const val KEY_PORT = "port"
        const val LEGACY_KEY_SECURE = "secure"
        const val KEY_REMEMBER_PASSWORD = "remember_password"
        const val KEY_REQUIRES_PASSWORD = "requires_password"
        const val KEY_SERVICE_ACTION_ID = "service_action_id"
        const val KEY_PASSWORD = "password_v1"
        const val KEY_ACTIONS = "saved_actions_v1"
        const val KEY_BACKGROUND_CONNECTION = "background_connection_v1"
        const val KEY_RECEIPT_CUE = "receipt_cue_v1"
        const val LEGACY_KEY_CLIPBOARD_SYNC = "clipboard_sync_v1"
        const val KEY_ALIAS = "quicker_link_connection_password_v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal fun decodeSavedActions(
    json: String,
    gson: Gson = Gson(),
): List<SavedAction> = runCatching {
    val type = object : TypeToken<List<SavedAction>>() {}.type
    gson.fromJson<List<SavedAction>>(json, type).orEmpty().map { action ->
        // Gson bypasses Kotlin default arguments, so an older stored object yields null here.
        action.copy(parameterChoices = action.parameterChoices.orEmpty())
    }
}.getOrDefault(emptyList())
