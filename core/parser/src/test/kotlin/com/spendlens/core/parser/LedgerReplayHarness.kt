package com.spendlens.core.parser

import com.spendlens.core.model.Source
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays an exported ledger's source messages through the current parser and
 * reports how many rows would now carry a usable label.
 *
 *   ./gradlew :core:parser:test --tests '*LedgerReplay*' \
 *     -Dspendlens.ledger=/path/to/spendlens-export.csv
 *
 * Skipped without the property, so no personal data is ever needed to build.
 */
class LedgerReplayHarness {

    @Test
    fun report() {
        val path = System.getProperty("spendlens.ledger")
        assumeTrue("set -Dspendlens.ledger=<csv> to run", path != null)

        val rows = readCsv(File(path))
        val parser = TemplateParser(BuiltInTemplates.all())

        var parsed = 0
        var named = 0
        var institutionOnly = 0
        var unlabelled = 0

        for (row in rows) {
            val body = row["Source message"].orEmpty().ifBlank { continue }
            val raw = parser.parse(
                ParserInput(source = Source.SMS, sender = "UNKNOWN", body = body, timestamp = 0L)
            ) ?: continue
            parsed++
            when {
                raw.counterpartyNameRaw != null || raw.counterpartyVpa != null -> named++
                raw.institution != null -> institutionOnly++
                else -> unlabelled++
            }
        }

        println("=== LEDGER REPLAY: ${rows.size} rows, $parsed parsed ===")
        println("  payee named by the message : $named")
        println("  bank named, payee not      : $institutionOnly")
        println("  nothing identifiable       : $unlabelled")
        val identifiable = named + institutionOnly
        println("  -> rows with a real label  : $identifiable (${identifiable * 100 / maxOf(parsed, 1)}%)")
    }

    /** Minimal RFC-4180 reader — the export quotes narrations containing commas. */
    private fun readCsv(file: File): List<Map<String, String>> {
        val text = file.readText()
        val rows = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && inQuotes && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { cells.add(cell.toString()); cell.clear() }
                (c == '\n') && !inQuotes -> {
                    cells.add(cell.toString().removeSuffix("\r")); cell.clear()
                    rows.add(cells); cells = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || cells.isNotEmpty()) { cells.add(cell.toString()); rows.add(cells) }
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        return rows.drop(1).filter { it.size >= header.size }.map { header.zip(it).toMap() }
    }
}
