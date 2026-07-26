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

    /** Spent minus received. Can be negative in a month you were paid back. */
    fun netMinor(buckets: List<DayBucket>): Long =
        buckets.sumOf { it.spentMinor } - buckets.sumOf { it.receivedMinor }
}
