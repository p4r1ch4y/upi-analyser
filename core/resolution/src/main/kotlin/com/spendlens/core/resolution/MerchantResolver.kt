package com.spendlens.core.resolution

import com.spendlens.core.model.Vpa
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source

/**
 * 6-rung resolution ladder.
 * Never returns "Unknown" - always returns something meaningful.
 */
class MerchantResolver {
    
    data class Resolution(
        val displayName: String,
        val merchantId: String?,
        val categoryId: String?,
        val rung: Int,           // 1-6, which ladder rung resolved this
        val confidence: Float    // 0.0-1.0
    )

    /**
     * Resolve merchant name using the 6-rung ladder:
     * 1. User rules (highest priority)
     * 2. Notification display name
     * 3. VPA structure parsing
     * 4. Merchant directory
     * 5. Fuzzy match
     * 6. Raw VPA as display name (never "Unknown")
     */
    fun resolve(
        txn: RawTxn,
        userRules: List<VpaRule> = emptyList(),
        merchantDirectory: Map<String, MerchantInfo> = emptyMap(),
        historicalNames: List<Pair<String, String>> = emptyList()
    ): Resolution {
        
        // Rung 1: User rules
        txn.counterpartyVpa?.let { vpa ->
            val rule = userRules.find { it.matches(vpa) }
            if (rule != null) {
                return Resolution(
                    displayName = rule.merchantName,
                    merchantId = rule.merchantId,
                    categoryId = rule.categoryId,
                    rung = 1,
                    confidence = 1.0f
                )
            }
        }

        // Rung 2: a counterparty name the parser captured.
        //
        // Notifications and SMS both reach here, because in both cases the name
        // came out of a named capture group in a template that was written for
        // that exact sentence - it is a payee, not a guess. Gating this on
        // notifications alone threw away every name bank SMS does give up
        // ("sent via UPI to THE RICH TABLE", "transfer from RAM KUMAR", the
        // merchant on an autopay mandate) and fell through to the VPA, which
        // rendered a Google Play mandate as its 32-character hash.
        //
        // Statement imports are excluded: a CSV narration is raw bank text
        // ("UPI-SWIGGY-swiggy@ybl-..."), so it is better to let the VPA rules
        // have it first and keep the narration as the last resort.
        // A VPA-shaped "name" is not a display name - fall through to rung 3 so
        // the VPA structure rules get a chance at it.
        val rawName = txn.counterpartyNameRaw
        val nameIsTrustworthy = txn.source == Source.NOTIFICATION || txn.source == Source.SMS
        if (nameIsTrustworthy && !rawName.isNullOrBlank() && !rawName.contains("@")) {
            val cleanName = cleanNotificationName(rawName)
            if (cleanName.isNotBlank()) {
                return Resolution(
                    displayName = cleanName,
                    merchantId = null,
                    categoryId = null,
                    rung = 2,
                    confidence = 0.9f
                )
            }
        }

        // Rung 3: VPA structure parsing
        txn.counterpartyVpa?.let { vpaStr ->
            val vpa = Vpa.parse(vpaStr) ?: return@let null
            val pattern = vpa.detectPattern()
            val structureResolution = resolveFromStructure(vpa, pattern)
            if (structureResolution != null) return structureResolution
        }

        // Rung 4: Merchant directory
        txn.counterpartyVpa?.let { vpa ->
            merchantDirectory[vpa]?.let { info ->
                return Resolution(
                    displayName = info.canonicalName,
                    merchantId = info.id,
                    categoryId = info.categoryId,
                    rung = 4,
                    confidence = 0.95f
                )
            }
        }

        // Rung 5: Fuzzy match against historical
        txn.counterpartyVpa?.let { vpa ->
            val fuzzy = fuzzyMatch(vpa, historicalNames)
            if (fuzzy != null && fuzzy.second >= 0.85f) {
                return Resolution(
                    displayName = fuzzy.first,
                    merchantId = null,
                    categoryId = null,
                    rung = 5,
                    confidence = fuzzy.second
                )
            }
        }

        // Rung 6: last resort. Never "Unknown" - but never a lie either.
        //
        // This used to fall back to "Manual entry", which labelled several hundred
        // imported bank messages as something the user had typed in by hand. When
        // all that is known is which account the money left, say that; the account
        // tail is genuine information and is what the user will recognise.
        //
        // Measured against six months of one person's real ledger: 82% of rows
        // landed here, because most bank SMS never says who was paid. "Rs. 10.00
        // debited from Airtel Payments Bank a/c Txn ID 8159..." contains no payee
        // anywhere - no parser can extract a name the bank did not send.
        //
        // What those messages do say, 97% of the time, is which institution moved
        // the money. "Airtel Payments Bank ••2793" is true and recognisable;
        // "Bank message" is neither.
        val displayName = txn.counterpartyVpa?.let {
            Vpa.parse(it)?.displayName()
        }
            ?: txn.counterpartyNameRaw
            ?: institutionLabel(txn.institution, txn.accountTail)
            ?: txn.accountTail?.let { "Bank a/c ••$it" }
            ?: unlabelledFor(txn.source)

        return Resolution(
            displayName = displayName,
            merchantId = null,
            categoryId = "uncategorized",
            rung = 6,
            confidence = 0.3f
        )
    }

    /** "Airtel Payments Bank ••2793", or just the bank when no account is known. */
    private fun institutionLabel(institution: String?, accountTail: String?): String? {
        val name = institution ?: return null
        return if (accountTail != null) "$name ••$accountTail" else name
    }

    /** Honest name for a payment whose counterparty nothing could recover. */
    private fun unlabelledFor(source: Source): String = when (source) {
        Source.SMS -> "Bank message"
        Source.NOTIFICATION -> "Payment"
        Source.STATEMENT -> "Statement entry"
        Source.MANUAL -> "Manual entry"
    }

    private fun resolveFromStructure(vpa: Vpa, pattern: Vpa.VpaPattern): Resolution? {
        return when (pattern) {
            Vpa.VpaPattern.PERSON_PHONE -> Resolution(
                displayName = vpa.displayName(),
                merchantId = null,
                categoryId = "people",
                rung = 3,
                confidence = 0.8f
            )
            Vpa.VpaPattern.NAMED_MERCHANT -> Resolution(
                displayName = vpa.displayName(),
                merchantId = null,
                categoryId = null,
                rung = 3,
                confidence = 0.7f
            )
            else -> null
        }
    }

    private fun cleanNotificationName(raw: String): String {
        return raw
            .replace(Regex("\\s*-\\s*Payment.*"), "")
            .replace(Regex("\\s*\\|.*"), "")
            .trim()
    }

    private fun fuzzyMatch(
        vpa: String, 
        historical: List<Pair<String, String>>
    ): Pair<String, Float>? {
        if (historical.isEmpty()) return null
        
        // Simple Levenshtein-based fuzzy matching
        val matches = historical.map { (historicalVpa, name) ->
            val similarity = levenshteinSimilarity(vpa, historicalVpa)
            name to similarity
        }
        
        return matches.maxByOrNull { it.second }
    }

    private fun levenshteinSimilarity(s1: String, s2: String): Float {
        val distance = levenshteinDistance(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        return if (maxLen == 0) 1.0f else 1.0f - (distance.toFloat() / maxLen)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[len1][len2]
    }
}

data class VpaRule(
    val id: String,
    val pattern: String,
    val matchType: MatchType,
    val merchantName: String,
    val merchantId: String?,
    val categoryId: String?,
    val priority: Int = 0
) {
    enum class MatchType { EXACT, PREFIX, REGEX }

    fun matches(vpa: String): Boolean {
        return when (matchType) {
            MatchType.EXACT -> vpa == pattern
            MatchType.PREFIX -> vpa.startsWith(pattern)
            MatchType.REGEX -> Regex(pattern).matches(vpa)
        }
    }
}

data class MerchantInfo(
    val id: String,
    val canonicalName: String,
    val categoryId: String?
)
