package com.spendlens.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the SQLCipher passphrase for the on-device ledger.
 *
 * The passphrase itself is 32 random bytes generated on first launch. It is
 * never stored in the clear: it is sealed with an AES-256-GCM key that lives in
 * the Android Keystore (hardware-backed where the device offers it) and can not
 * be exported. Only the wrapped blob and its IV land in SharedPreferences, so a
 * filesystem-level copy of the app's data directory is not enough to open the
 * database.
 *
 * No key escrow, no derived-from-device-id shortcuts: losing the Keystore entry
 * (uninstall, factory reset) means the ledger is unreadable, which is the
 * intended failure mode. Backups carry their own passphrase-derived key.
 */
object DatabasePassphrase {

    private const val PREFS = "spendlens_secure"
    private const val PREF_WRAPPED = "db_passphrase_wrapped"
    private const val PREF_IV = "db_passphrase_iv"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "spendlens_db_wrapping_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PASSPHRASE_BYTES = 32

    fun getOrCreate(context: Context): ByteArray {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val wrapped = prefs.getString(PREF_WRAPPED, null)
        val iv = prefs.getString(PREF_IV, null)
        if (wrapped != null && iv != null) {
            return unwrap(decode(wrapped), decode(iv))
        }

        val passphrase = ByteArray(PASSPHRASE_BYTES).also {
            java.security.SecureRandom().nextBytes(it)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey())
        }
        val ciphertext = cipher.doFinal(passphrase)

        prefs.edit()
            .putString(PREF_WRAPPED, encode(ciphertext))
            .putString(PREF_IV, encode(cipher.iv))
            .commit()

        return passphrase
    }

    private fun unwrap(ciphertext: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(ciphertext)
        }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // The notification listener has to open the ledger while the
                // device is locked, so this key cannot require user auth.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
