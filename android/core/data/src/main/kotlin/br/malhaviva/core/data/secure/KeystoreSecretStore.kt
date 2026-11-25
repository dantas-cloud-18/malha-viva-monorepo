package br.malhaviva.core.data.secure

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [SecretStore] com chave AES-256-GCM no Android Keystore (nao exportavel; StrongBox/TEE quando disponivel).
 * O ciphertext fica em SharedPreferences privado, excluido de backup e transferencia (data_extraction_rules).
 * Falha fechada: chave invalidada ou blob adulterado => entrada removida e get devolve null, sem crash.
 * Nao testado em aparelho nesta fase (depende do Keystore real); a cripto pura esta em [AesGcmCodec].
 */
class KeystoreSecretStore(
    context: Context,
    prefsName: String = DEFAULT_PREFS,
    private val keyAlias: String = DEFAULT_ALIAS,
) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Synchronized
    override fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        // Falha fechada: qualquer problema de chave ou blob apaga a entrada e devolve null.
        return decryptOrNull(name, stored) ?: run {
            prefs.edit().remove(name).apply()
            null
        }
    }

    @Synchronized
    override fun put(
        name: String,
        value: String,
    ) {
        val blob =
            try {
                encrypt(name, value)
            } catch (_: GeneralSecurityException) {
                // Chave invalidada: descarta a chave e todo ciphertext antigo (ilegivel) e tenta uma vez.
                resetKey()
                encrypt(name, value)
            }
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    override fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun encrypt(
        name: String,
        value: String,
    ): ByteArray = AesGcmCodec(loadOrCreateKey()).encrypt(value.toByteArray(Charsets.UTF_8), name.toByteArray())

    private fun decryptOrNull(
        name: String,
        stored: String,
    ): String? =
        try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            String(AesGcmCodec(loadOrCreateKey()).decrypt(blob, name.toByteArray()), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun loadOrCreateKey(): SecretKey {
        val existing = keyStore().getKey(keyAlias, null) as? SecretKey
        return existing ?: generateKey()
    }

    private fun resetKey() {
        keyStore().deleteEntry(keyAlias)
        prefs.edit().clear().apply()
    }

    private fun generateKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKey(strongBox = true)
            } catch (_: ProviderException) {
                // StrongBox indisponivel neste aparelho: cai para TEE/software.
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val builder =
            KeyGenParameterSpec
                .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(builder.build()) }
            .generateKey()
    }

    private companion object {
        const val DEFAULT_PREFS = "mv_secure_store"
        const val DEFAULT_ALIAS = "mv_secret_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_BITS = 256
    }
}
