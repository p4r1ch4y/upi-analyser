package com.spendlens.core.parser

import com.spendlens.core.model.Source
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Opt-in harness: measures the parser against a real SMS backup.
 *
 *   ./gradlew :core:parser:test --tests '*CorpusHarness*' -Dspendlens.corpus=/path/to/sms.xml
 *
 * Skipped when the property is absent, so it never runs in CI and no personal
 * data is ever committed.
 */
class CorpusHarness {

    @Test
    fun report() {
        val path = System.getProperty("spendlens.corpus")
        assumeTrue("set -Dspendlens.corpus=<file> to run", path != null)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("sms")
        val parser = TemplateParser(BuiltInTemplates.all())

        val currency = Regex("""(?:₹|INR|Rs\.?)\s*[\d,]+(?:\.\d{1,2})?""", RegexOption.IGNORE_CASE)
        val verb = Regex(
            """\b(debited|credited|spent|paid|sent|received|withdrawn|transferred|deducted)\b""",
            RegexOption.IGNORE_CASE
        )

        var candidates = 0
        var matched = 0
        val misses = mutableListOf<Pair<String, String>>()
        val hitsByTemplate = mutableMapOf<String, Int>()
        val suspects = mutableListOf<String>()
        val samples = mutableMapOf<String, String>()

        val balance = Regex(
            """(?:bal(?:ance)?|avl\s*bal|aval\s*bal|available\s*bal(?:ance)?)[.:\s]*(?:INR|Rs\.?|₹)?\s*([\d,]+(?:\.\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )

        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as org.w3c.dom.Element
            val body = element.getAttribute("body") ?: continue
            val sender = element.getAttribute("address") ?: ""
            if (!currency.containsMatchIn(body) || !verb.containsMatchIn(body)) continue
            candidates++

            val txn = parser.parse(
                ParserInput(source = Source.SMS, sender = sender, body = body, timestamp = 0L)
            )
            if (txn != null) {
                matched++
                hitsByTemplate.merge(txn.templateId ?: "?", 1, Int::plus)
                samples.putIfAbsent(
                    txn.templateId ?: "?",
                    "amount=${txn.amountMinor} dir=${txn.direction} acc=${txn.accountTail} " +
                        "rrn=${txn.rrn} name=${txn.counterpartyNameRaw} vpa=${txn.counterpartyVpa}\n" +
                        "          << ${body.take(155)}"
                )

                val statedBalance = balance.find(body)?.groupValues?.get(1)?.replace(",", "")
                val balanceMinor = statedBalance?.toBigDecimalOrNull()?.movePointRight(2)?.toLong()
                if (balanceMinor != null && balanceMinor == txn.amountMinor) {
                    suspects += "[${txn.templateId}] amount=${txn.amountMinor} :: ${body.take(150)}"
                }
            } else {
                misses += sender to body
            }
        }

        println("=== CORPUS: $candidates candidates, $matched matched (${matched * 100 / maxOf(candidates, 1)}%) ===")
        println("--- hits by template ---")
        hitsByTemplate.entries.sortedByDescending { it.value }.forEach { println("  ${it.value}\t${it.key}") }

        // A closing balance is the classic false positive: it is a currency
        // amount, it sits in the same sentence, and reading it instead of the
        // payment silently corrupts the ledger. Flag any capture whose amount is
        // the number quoted as a balance.
        println("--- SAMPLE CAPTURES (one per template) ---")
        samples.entries.sortedBy { it.key }.forEach { (template, line) ->
            println("  $template")
            println("      $line")
        }

        println("--- SUSPECT: amount equals a stated balance (${suspects.size}) ---")
        suspects.take(15).forEach { println("  $it") }

        println("--- MISSES (${misses.size}) ---")
        // Collapse to shapes so the output is readable: digits and names blanked.
        val shapes = misses.groupBy { (_, body) ->
            body.replace(Regex("""\d"""), "#")
                .replace(Regex("""#+"""), "#")
                .take(110)
        }
        shapes.entries.sortedByDescending { it.value.size }.take(40).forEach { (shape, examples) ->
            println("  [${examples.size}] $shape")
            println("      e.g. ${examples.first().second.take(190)}")
        }
    }
}
