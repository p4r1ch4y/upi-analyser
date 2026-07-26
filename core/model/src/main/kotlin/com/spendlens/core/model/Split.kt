package com.spendlens.core.model

/**
 * One person's share of a split payment.
 *
 * [settledAt] is null until they have actually paid you back, so "who still owes
 * me" is a property of the data rather than something the UI has to infer.
 */
data class SplitShare(
    val name: String,
    val amountMinor: Long,
    val isMe: Boolean = false,
    val settledAt: Long? = null
) {
    val isSettled: Boolean get() = settledAt != null
}

/**
 * A payment you made on behalf of several people.
 *
 * The invariant that matters: **the shares always sum to exactly the total.**
 * ₹1,000 three ways is 33333.33 paise each, and a split that rounds each share
 * independently either loses a paisa or invents one. Over a Goa trip that quietly
 * turns into "we're ₹4 out and nobody knows why", which is exactly the kind of
 * distrust that stops people using a money app.
 */
data class Split(
    val totalMinor: Long,
    val shares: List<SplitShare>
) {
    init {
        require(shares.sumOf { it.amountMinor } == totalMinor) {
            "shares sum to ${shares.sumOf { it.amountMinor }} but total is $totalMinor"
        }
    }

    val myShareMinor: Long get() = shares.filter { it.isMe }.sumOf { it.amountMinor }

    /** What other people still owe you. */
    val owedToMeMinor: Long
        get() = shares.filter { !it.isMe && !it.isSettled }.sumOf { it.amountMinor }

    val settledMinor: Long
        get() = shares.filter { !it.isMe && it.isSettled }.sumOf { it.amountMinor }

    val wayCount: Int get() = shares.size

    val isFullySettled: Boolean get() = shares.none { !it.isMe && !it.isSettled }

    companion object {

        /** You, plus everyone else. The first name is always the user. */
        const val ME = "You"

        /**
         * Splits [totalMinor] as evenly as integer paise allow.
         *
         * The remainder is handed out one minor unit at a time from the top, so
         * the shares still sum to the total exactly and the largest share is never
         * more than one paisa above the smallest. ₹1,000 three ways becomes
         * 33333.34 / 33333.33 / 33333.33 - not three lots of 33333.33 with a
         * paisa evaporated.
         *
         * @param names everyone sharing, including the user.
         * @param mePosition which name is the user.
         */
        fun evenly(
            totalMinor: Long,
            names: List<String>,
            mePosition: Int = 0
        ): Split {
            require(names.isNotEmpty()) { "a split needs at least one person" }
            require(totalMinor >= 0) { "a split total cannot be negative" }

            val count = names.size
            val base = totalMinor / count
            val remainder = (totalMinor % count).toInt()

            val shares = names.mapIndexed { index, name ->
                SplitShare(
                    name = name,
                    amountMinor = base + if (index < remainder) 1 else 0,
                    isMe = index == mePosition
                )
            }
            return Split(totalMinor, shares)
        }

        /** An even split among the user plus [otherCount] other people. */
        fun evenlyAmong(totalMinor: Long, otherCount: Int): Split {
            require(otherCount >= 1) { "splitting needs at least one other person" }
            val names = listOf(ME) + (1..otherCount).map { "Person $it" }
            return evenly(totalMinor, names)
        }
    }
}
