package com.spendlens.core.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * How often a budget starts over.
 *
 * Two, not five. A budget people keep is one they can check against a feeling
 * they already have - "this week" and "this month" are both feelings; "every 17
 * days" is arithmetic homework.
 */
enum class BudgetPeriod { WEEKLY, MONTHLY }

/**
 * What a budget is drawn around.
 *
 * There is no category model in this app, deliberately, so a budget hangs off the
 * two groupings the ledger can actually stand behind: a tag the user chose, or a
 * merchant name the payment itself carried. [TOTAL] is the third and the default,
 * because most people's first budget is not "₹4,000 on food" - it is "less than
 * last month".
 */
enum class BudgetScope { TOTAL, TAG, MERCHANT }

/**
 * A limit the user set for themselves.
 *
 * [anchorAt] is the instant the budget was created, and it fixes the day the
 * period turns over rather than assuming the 1st. Salaries in India land on the
 * 1st, the 7th and the 25th depending on the employer, and a "monthly" budget
 * that resets on a day the money does not arrive is a budget that is always wrong
 * for the first week.
 */
data class Budget(
    val id: String,
    val name: String,
    val scope: BudgetScope,
    /** Tag name or merchant display name. Null - and only null - when [scope] is TOTAL. */
    val scopeValue: String?,
    val period: BudgetPeriod,
    val limitMinor: Long,
    val currency: String,
    val anchorAt: Long
) {
    init {
        require(limitMinor > 0) { "A budget of zero is not a budget" }
        require((scope == BudgetScope.TOTAL) == (scopeValue == null)) {
            "scopeValue is required for $scope and forbidden for TOTAL"
        }
    }
}

/**
 * The window a budget is currently being measured over.
 *
 * Computed from the anchor rather than from the calendar so that a monthly budget
 * set on the 25th runs the 25th to the 24th, every month, without drifting. The
 * naive `plusMonths` on the previous start does drift: 31 Jan + 1 month is 28
 * Feb, and the month after that is 28 Mar, so a budget anchored on the 31st
 * quietly walks backwards to the 28th and stays there.
 */
object BudgetWindow {

    /** The period covering [today], as an inclusive pair of dates. */
    fun of(period: BudgetPeriod, anchor: LocalDate, today: LocalDate): Pair<LocalDate, LocalDate> =
        when (period) {
            BudgetPeriod.WEEKLY -> weekly(anchor, today)
            BudgetPeriod.MONTHLY -> monthly(anchor, today)
        }

    private fun weekly(anchor: LocalDate, today: LocalDate): Pair<LocalDate, LocalDate> {
        // Days back to the most recent occurrence of the anchor's weekday, today
        // included. Kotlin's rem can be negative, so it is normalised.
        val back = Math.floorMod(
            today.dayOfWeek.value - anchor.dayOfWeek.value,
            DAYS_IN_WEEK
        ).toLong()
        val start = today.minusDays(back)
        return start to start.plusDays(DAYS_IN_WEEK - 1L)
    }

    private fun monthly(anchor: LocalDate, today: LocalDate): Pair<LocalDate, LocalDate> {
        val day = anchor.dayOfMonth
        val thisMonth = startIn(YearMonth.from(today), day)
        val start = if (thisMonth.isAfter(today)) {
            startIn(YearMonth.from(today).minusMonths(1), day)
        } else {
            thisMonth
        }
        val next = startIn(YearMonth.from(start).plusMonths(1), day)
        return start to next.minusDays(1)
    }

    /** The anchor day in [month], pulled back to the last day of a short month. */
    private fun startIn(month: YearMonth, dayOfMonth: Int): LocalDate =
        month.atDay(minOf(dayOfMonth, month.lengthOfMonth()))

    private const val DAYS_IN_WEEK = 7
}

/**
 * A budget, its window, and what has actually been spent against it.
 *
 * The whole point of the derived figures here is that a bare "₹4,120 of ₹8,000"
 * is not yet advice. Half the limit gone means nothing until you know whether
 * half the month has gone with it - which is what [paceFraction] and
 * [projectedMinor] supply, and what makes this screen able to answer "where
 * should I spend less" rather than only "what did I spend".
 */
data class BudgetProgress(
    val budget: Budget,
    val spentMinor: Long,
    val paymentCount: Int,
    val start: LocalDate,
    val end: LocalDate,
    val today: LocalDate
) {
    val limitMinor: Long get() = budget.limitMinor

    /** Negative once the limit is passed, which is the number people want to see. */
    val remainingMinor: Long get() = limitMinor - spentMinor

    val isOver: Boolean get() = spentMinor > limitMinor

    /** Days in the whole period. */
    val totalDays: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()

    /** Days used up, today included - you cannot un-spend the rest of today. */
    val elapsedDays: Int
        get() = (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceIn(1, totalDays)

    /** Whole days after today. Zero on the last day of the period. */
    val daysLeft: Int get() = totalDays - elapsedDays

    /** How much of the limit is spent, 0..1 for the bar's fill. */
    val fraction: Float
        get() = (spentMinor.toDouble() / limitMinor).toFloat().coerceIn(0f, 1f)

    /**
     * Where the fill *would* be if the limit were spread evenly over the period.
     *
     * Drawn as a single thin marker on the bar. It is the difference between the
     * fill and this mark that carries the judgement, so neither needs a colour.
     */
    val paceFraction: Float get() = (elapsedDays.toDouble() / totalDays).toFloat()

    /** What the period ends at if the rest of it looks like the part already spent. */
    val projectedMinor: Long get() = spentMinor * totalDays / elapsedDays

    val isOnPace: Boolean get() = projectedMinor <= limitMinor

    /**
     * What is left to spend per remaining day, today included, to finish under.
     *
     * Zero once the limit is gone: the honest answer then is "nothing", not a
     * negative daily allowance nobody can act on.
     */
    val dailyAllowanceMinor: Long
        get() = if (remainingMinor <= 0L) 0L else remainingMinor / (daysLeft + 1)

    /** What was actually spent per day so far, for the comparison against the above. */
    val dailyRateMinor: Long get() = spentMinor / elapsedDays

    /**
     * How this budget is doing, as one of four states the UI can speak plainly
     * about. Ordered by how much attention each deserves.
     */
    val state: State
        get() = when {
            isOver -> State.OVER
            !isOnPace -> State.AHEAD_OF_PACE
            spentMinor == 0L -> State.UNTOUCHED
            else -> State.ON_TRACK
        }

    enum class State { OVER, AHEAD_OF_PACE, ON_TRACK, UNTOUCHED }

    companion object {
        /** The window for [budget] as of [today], in the caller's zone. */
        fun windowOf(budget: Budget, anchorDate: LocalDate, today: LocalDate) =
            BudgetWindow.of(budget.period, anchorDate, today)
    }
}
