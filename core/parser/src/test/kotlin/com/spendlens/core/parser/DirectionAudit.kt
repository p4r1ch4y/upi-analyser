package com.spendlens.core.parser

import com.spendlens.core.model.Direction
import com.spendlens.core.model.Source
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Opt-in harness: is money in ever filed as money out?
 *
 *   ./gradlew :core:parser:test --tests '*DirectionAudit*' \
 *       -Dspendlens.corpus=/path/to/sms.xml
 *
 * [CorpusHarness] answers "how much does the parser read". This answers the
 * separate and more dangerous question: of what it *does* read, how much does it
 * read backwards. A missed message leaves a gap the user can see. A credit filed
 * as a debit is a number that looks right and is wrong by twice the amount, in a
 * total nobody re-derives by hand.
 *
 * The check is deliberately independent of the templates. Each message is scored
 * on its own words - "credited to your account" against "debited from your
 * account" - and only messages where that evidence is unambiguous are judged, so
 * the audit never grades a template against itself.
 *
 * Skipped when the property is absent, so it never runs in CI and no personal
 * data is ever committed.
 */
class DirectionAudit {

    /** Words that only appear when money arrived. */
    private val creditWords = Regex(
        """\b(credited|received|deposited|refunded|refund|cashback|added\s+to)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Words that only appear when money left. */
    private val debitWords = Regex(
        """\b(debited|spent|withdrawn|deducted|paid\s+to|sent\s+to|purchase)\b""",
        RegexOption.IGNORE_CASE
    )

    @Test
    fun report() {
        val path = System.getProperty("spendlens.corpus")
        assumeTrue("set -Dspendlens.corpus=<file> to run", path != null)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("sms")
        val parser = TemplateParser(BuiltInTemplates.all())

        var read = 0
        var judged = 0
        var agreed = 0
        val backwards = mutableListOf<Triple<String, Direction, String>>()
        val byDirection = mutableMapOf<Direction, Int>()

        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as org.w3c.dom.Element
            val body = element.getAttribute("body") ?: continue
            val sender = element.getAttribute("address") ?: ""

            val txn = parser.parse(
                ParserInput(source = Source.SMS, sender = sender, body = body, timestamp = 0L)
            ) ?: continue

            read++
            byDirection.merge(txn.direction, 1, Int::plus)

            val saysCredit = creditWords.containsMatchIn(body)
            val saysDebit = debitWords.containsMatchIn(body)
            // Only unambiguous messages are graded. One that says both - "refund
            // credited against the amount debited" - is a genuinely hard case and
            // scoring it either way would be noise.
            if (saysCredit == saysDebit) continue

            judged++
            val expected = if (saysCredit) Direction.CREDIT else Direction.DEBIT
            if (txn.direction == expected) {
                agreed++
            } else {
                backwards += Triple(txn.templateId ?: "?", txn.direction, body.take(150))
            }
        }

        val percent = if (judged == 0) 0.0 else agreed * 100.0 / judged
        println("=== direction audit ===")
        println("messages read by the parser : $read")
        println("  filed as DEBIT            : ${byDirection[Direction.DEBIT] ?: 0}")
        println("  filed as CREDIT           : ${byDirection[Direction.CREDIT] ?: 0}")
        println("gradable (words unambiguous): $judged")
        println("agreed with the message     : $agreed  (${"%.2f".format(percent)}%)")
        println("read backwards              : ${backwards.size}")

        if (backwards.isNotEmpty()) {
            println()
            println("--- filed against what the message says ---")
            backwards.groupBy { it.first }.forEach { (template, rows) ->
                println("[$template] ${rows.size}")
                rows.take(3).forEach { println("   filed ${it.second}: ${it.third}") }
            }
        }
    }
}
