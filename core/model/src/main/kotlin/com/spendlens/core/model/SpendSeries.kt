package com.spendlens.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One payment reduced to what it actually cost the user - their share when it was
 * split, the full amount otherwise.
 */
data class SpendEvent(
    val occurredAt: Long,
    val effectiveMinor: Long,
    val isCredit: Boolean
)

/** A calendar day's worth of spending. */
data class DayBucket(
    val date: LocalDate,
    val spentMinor: Long,
    val receivedMinor: Long,
    val count: Int
)

/**
 * A calendar month, in and out.
 *
 * Kept as its own type rather than reusing [DayBucket] with a first-of-month
 * date, because the two answer different questions and a month that pretends to
 * be a day invites someone to format it as one.
 */
data class MonthBucket(
    val year: Int,
    val month: Int,
    val spentMinor: Long,
    val receivedMinor: Long,
    val count: Int
) {
    val start: LocalDate get() = LocalDate.of(year, month, 1)
    val end: LocalDate get() = start.plusMonths(1).minusDays(1)

    /** Spent minus received. Negative in a month you were paid more than you spent. */
    val netMinor: Long get() = spentMinor - receivedMinor
}

/**
 * Turns a flat list of payments into the series a chart draws.
 *
 * Bucketing happens here rather than in SQL because a calendar day is a property
 * of the user's time zone, not of the stored epoch millis. `strftime` in SQLite
 * would need to be handed an offset, and would then put the wrong payments in the
 * wrong day either side of a DST change - the kind of bug that shows up once a
 * year and is never traced back.
 */
object SpendSeries {

    /**
     * Buckets [events] by calendar day, **including days with no payments**.
     *
     * The empty days are the point: a spend chart with the quiet days omitted
     * compresses the axis and makes a fortnight of nothing look like continuous
     * activity. Zero is data.
     */
    fun byDay(
        events: List<SpendEvent>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<DayBucket> {
        require(!to.isBefore(from)) { "range ends before it starts" }

        val grouped = events.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }

        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .map { date ->
                val onDay = grouped[date].orEmpty()
                DayBucket(
                    date = date,
                    spentMinor = onDay.filter { !it.isCredit }.sumOf { it.effectiveMinor },
                    receivedMinor = onDay.filter { it.isCredit }.sumOf { it.effectiveMinor },
                    count = onDay.size
                )
            }
            .toList()
    }

    /**
     * Buckets [events] by calendar month, **including months with no payments**.
     *
     * The empty months are the point, for the same reason the empty days are: a
     * year with three quiet months compressed out of it looks like a year of
     * steady spending, and the quiet months are usually the interesting ones.
     *
     * Ordered oldest first, so a chart drawn from it reads left to right.
     */
    fun byMonth(
        events: List<SpendEvent>,
        monthsBack: Int,
        endingIn: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<MonthBucket> {
        require(monthsBack > 0) { "a report over no months is not a report" }

        val grouped = events.groupBy {
            val date = Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate()
            date.year to date.monthValue
        }

        val firstMonth = endingIn.withDayOfMonth(1).minusMonths(monthsBack - 1L)
        return generateSequence(firstMonth) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(endingIn.withDayOfMonth(1)) }
            .map { month ->
                val inMonth = grouped[month.year to month.monthValue].orEmpty()
                MonthBucket(
                    year = month.year,
                    month = month.monthValue,
                    spentMinor = inMonth.filter { !it.isCredit }.sumOf { it.effectiveMinor },
                    receivedMinor = inMonth.filter { it.isCredit }.sumOf { it.effectiveMinor },
                    count = inMonth.size
                )
            }
            .toList()
    }

    /** Total spent across the buckets. Credits are not netted off - see [netMinor]. */
    fun totalSpentMinor(buckets: List<DayBucket>): Long = buckets.sumOf { it.spentMinor }

    /**
     * The side of the ledger a figure is about.
     *
     * Threaded through every summary below because the report flips wholesale
     * between the two, and a statistic that silently stays on the spending side
     * while the headline reads income is worse than one that is missing: it looks
     * like an answer.
     */
    fun amountOf(bucket: DayBucket, credits: Boolean): Long =
        if (credits) bucket.receivedMinor else bucket.spentMinor

    /**
     * Mean spend per day across the whole range, quiet days included.
     *
     * Averaging only over the days that had payments answers a different and much
     * less useful question - "what do I spend on a day I spend anything" - and
     * always reads high.
     */
    fun dailyAverageMinor(buckets: List<DayBucket>, credits: Boolean = false): Long =
        if (buckets.isEmpty()) 0L
        else buckets.sumOf { amountOf(it, credits) } / buckets.size

    fun busiestDay(buckets: List<DayBucket>, credits: Boolean = false): DayBucket? =
        buckets.maxByOrNull { amountOf(it, credits) }?.takeIf { amountOf(it, credits) > 0L }

    /**
     * The same span, immediately before [buckets].
     *
     * A total on its own answers nothing: ₹8,000 this month is only news against
     * what last month was. This is what turns the headline from a number into a
     * direction.
     */
    fun changeVsPrevious(
        current: List<DayBucket>,
        previous: List<DayBucket>,
        credits: Boolean = false
    ): Change? {
        if (current.isEmpty() || previous.isEmpty()) return null
        val now = current.sumOf { amountOf(it, credits) }
        val before = previous.sumOf { amountOf(it, credits) }
        if (before == 0L) return null
        return Change(nowMinor = now, beforeMinor = before)
    }

    data class Change(val nowMinor: Long, val beforeMinor: Long) {
        val deltaMinor: Long get() = nowMinor - beforeMinor
        /** Signed fraction: 0.23 means 23% more than the period before. */
        val fraction: Float get() = (deltaMinor.toDouble() / beforeMinor).toFloat()
        val isUp: Boolean get() = deltaMinor > 0

        /**
         * True once a percentage stops being readable.
         *
         * A month against an almost-empty one produces "9127% more", which is
         * arithmetically correct and tells the reader nothing - nobody holds a
         * ninety-one-fold increase in their head as a percentage. Past this the
         * UI says "×92" instead.
         */
        val isLarge: Boolean get() = beforeMinor > 0L && nowMinor / beforeMinor >= LARGE_MULTIPLE

        /** How many times over, for the cases [isLarge] covers. */
        val multiple: Long get() = if (beforeMinor <= 0L) 0L else nowMinor / beforeMinor
    }

    private const val LARGE_MULTIPLE = 10L

    /**
     * Days with no spending at all.
     *
     * A number people recognise in their own behaviour far more readily than an
     * average - "I spent nothing on 9 days this month" lands where "₹243/day"
     * does not.
     */
    fun spendFreeDays(buckets: List<DayBucket>, credits: Boolean = false): Int =
        buckets.count { amountOf(it, credits) == 0L }

    /**
     * Mean spend on the days money was actually spent.
     *
     * Reported *alongside* the all-days average rather than instead of it: the
     * two answer different questions, and the gap between them is itself the
     * interesting part.
     */
    fun averageOnSpendingDays(buckets: List<DayBucket>, credits: Boolean = false): Long {
        val active = buckets.filter { amountOf(it, credits) > 0L }
        return if (active.isEmpty()) 0L else active.sumOf { amountOf(it, credits) } / active.size
    }

    /** Spent minus received. Can be negative in a month you were paid back. */
    fun netMinor(buckets: List<DayBucket>): Long =
        buckets.sumOf { it.spentMinor } - buckets.sumOf { it.receivedMinor }
}
