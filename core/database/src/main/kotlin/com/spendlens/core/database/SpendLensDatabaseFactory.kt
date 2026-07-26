package com.spendlens.core.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Builds the one and only [SpendLensDatabase], backed by SQLCipher.
 *
 * The whole file is encrypted at the page level with AES-256; there is no
 * plaintext-on-disk mode and no fallback to the framework SQLite driver, because
 * a silent fallback would leave a plaintext ledger on the device.
 */
object SpendLensDatabaseFactory {

    const val DATABASE_NAME: String = "spendlens.db"

    @Volatile
    private var nativeLoaded = false

    /**
     * @param passphrase raw key bytes. The caller owns them; they are zeroed by
     *   SQLCipher after the helper is built, so do not reuse the array.
     */
    fun createDriver(context: Context, passphrase: ByteArray): SqlDriver {
        loadNativeLibrary()
        return AndroidSqliteDriver(
            schema = SpendLensDatabase.Schema,
            context = context,
            name = DATABASE_NAME,
            factory = SupportOpenHelperFactory(passphrase)
        )
    }

    fun create(context: Context, passphrase: ByteArray): SpendLensDatabase =
        SpendLensDatabase(createDriver(context, passphrase))

    private fun loadNativeLibrary() {
        if (nativeLoaded) return
        synchronized(this) {
            if (nativeLoaded) return
            System.loadLibrary("sqlcipher")
            nativeLoaded = true
        }
    }
}
