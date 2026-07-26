package com.spendlens.data

import android.content.Context
import android.net.Uri
import com.spendlens.core.parser.CsvStatementParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a CSV statement the user picked through the Storage Access Framework, so
 * the app needs no storage permission and never sees a file the user did not
 * personally hand it.
 *
 * All the actual parsing lives in [CsvStatementParser], which is pure Kotlin and
 * unit-tested; this class only moves bytes.
 */
class CsvStatementImporter(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun read(uri: Uri, defaultCurrency: String = "INR"): CsvStatementParser.Outcome =
        withContext(io) {
            val lines = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readLines()
            } ?: return@withContext CsvStatementParser.Outcome(emptyList(), emptyList())

            CsvStatementParser.parse(lines, defaultCurrency)
        }
}
