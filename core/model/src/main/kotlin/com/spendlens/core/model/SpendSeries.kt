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

    /** Total spent across the buckets. Credits are not netted off - see [netMinor]. */
    fun totalSpentMinor(buckets: List<DayBucket>): Long = buckets.sumOf { it.spentMinor }

    /**
     * Mean spend per day across the whole range, quiet days included.
     *
     * Averaging only over the days that had payments answers a different and much
     * less useful question - "what do I spend on a day I spend anything" - and
     * always reads high.
     */
    fun dailyAverageMinor(buckets: List<DayBucket>): Long =
        if (buckets.isEmpty()) 0L else totalSpentMinor(buckets) / buckets.size

    fun busiestDay(buckets: List<DayBucket>): DayBucket? = buckets.maxByOrNull { it.spentMinor }

    /**
     * The same span, immediately before [buckets].
     *
     * A total on its own answers nothing: ₹8,000 this month is only news against
     * what last month was. This is what turns the headline from a number into a
     * direction.
     */
    fun changeVsPrevious(current: List<DayBucket>, previous: List<DayBucket>): Change? {
        if (current.isEmpty() || previous.isEmpty()) return null
        val now = totalSpentMinor(current)
        val before = totalSpentMinor(previous)
        if (before == 0L) return null
        return Change(nowMinor = now, beforeMinor = before)
    }

    data class Change(val nowMinor: Long, val beforeMinor: Long) {
        val deltaMinor: Long get() = nowMinor - beforeMinor
        /** Signed fraction: 0.23 means 23% more than the period before. */
        val fraction: Float get() = (deltaMinor.toDouble() / beforeMinor).toFloat()
        val isUp: Boolean get() = deltaMinor > 0
    }

    /**
     * Days with no spending at all.
     *
     * A number people recognise in their own behaviour far more readily than an
     * average - "I spent nothing on 9 days this month" lands where "₹243/day"
     * does not.
     */
    fun spendFreeDays(buckets: List<DayBucket>): Int = buckets.count { it.spentMinor == 0L }

    /**
     * Mean spend on the days money was actually spent.
     *
     * Reported *alongside* the all-days average rather than instead of it: the
     * two answer different questions, and the gap between them is itself the
     * interesting part.
     */
    fun averageOnSpendingDays(buckets: List<DayBucket>): Long {
        val active = buckets.filter { it.spentMinor > 0L }
        return if (active.isEmpty()) 0L else active.sumOf { it.spentMinor } / active.size
    }

    /** Spent minus received. Can be negative in a month you were paid back. */
    fun netMinor(buckets: List<DayBucket>): Long =
        buckets.sumOf { it.spentMinor } - buckets.sumOf { it.receivedMinor }
}
