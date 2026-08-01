package com.spendlens.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.spendlens.core.database.Budgets
import com.spendlens.core.database.SpendLensDatabase
import com.spendlens.core.model.Budget
import com.spendlens.core.model.BudgetPeriod
import com.spendlens.core.model.BudgetProgress
import com.spendlens.core.model.BudgetScope
import com.spendlens.core.model.BudgetWindow
import com.spendlens.core.model.TxnId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Budgets, and what has been spent against them.
 *
 * Every figure a budget reports is measured with the same rules as the charts
 * above it: the user's own share of a split rather than the gross, and failed
 * payments shown but never counted. A budget that counted differently from the
 * breakdown it was created from would be the fastest way to make someone
 * distrust both numbers.
 */
class BudgetRepository(
    private val database: SpendLensDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val queries get() = database.spendLensQueries

    fun budgets(): Flow<List<Budget>> =
        queries.selectBudgets().asFlow().mapToList(io).map { rows -> rows.mapNotNull { it.toModel() } }

    suspend fun budgetsOnce(): List<Budget> = withContext(io) {
        queries.selectBudgets().executeAsList().mapNotNull { it.toModel() }
    }

    /**
     * Saves a budget, creating it if [id] is null.
     *
     * The anchor is only set on creation. Editing a limit mid-month must not
     * silently restart the period under the user - they would see the month's
     * spend reset to zero and reasonably conclude the app had lost it.
     */
    suspend fun save(
        id: String?,
        name: String,
        scope: BudgetScope,
        scopeValue: String?,
        period: BudgetPeriod,
        limitMinor: Long,
        currency: String,
        anchorAt: Long? = null
    ): Budget = withContext(io) {
        val timestamp = now()
        val existing = id?.let { key -> queries.selectBudgets().executeAsList().firstOrNull { it.id == key } }
        val budget = Budget(
            id = id ?: TxnId.generate(timestamp).value,
            name = name.trim(),
            scope = scope,
            scopeValue = scopeValue?.trim()?.takeIf { it.isNotEmpty() },
            period = period,
            limitMinor = limitMinor,
            currency = currency,
            anchorAt = anchorAt ?: existing?.anchor_at ?: timestamp
        )
        queries.upsertBudget(
            id = budget.id,
            name = budget.name,
            scope = budget.scope.name,
            scope_value = budget.scopeValue,
            period = budget.period.name,
            amount_minor = budget.limitMinor,
            currency = budget.currency,
            anchor_at = budget.anchorAt,
            created_at = existing?.created_at ?: timestamp
        )
        budget
    }

    suspend fun delete(id: String) = withContext(io) {
        queries.deleteBudget(id)
    }

    /** Every budget with its current window and what has been spent inside it. */
    suspend fun progress(today: LocalDate = LocalDate.now(zone)): List<BudgetProgress> =
        withContext(io) { budgetsOnce().map { progressFor(it, today) } }

    suspend fun progressFor(
        budget: Budget,
        today: LocalDate = LocalDate.now(zone)
    ): BudgetProgress = withContext(io) {
        val anchor = Instant.ofEpochMilli(budget.anchorAt).atZone(zone).toLocalDate()
        val (start, end) = BudgetWindow.of(budget.period, anchor, today)
        val since = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val (spent, count) = when (budget.scope) {
            BudgetScope.TOTAL -> queries.selectSpendTotalBetween(since, until)
                .executeAsOne().let { it.effective_minor to it.txn_count }
            BudgetScope.MERCHANT -> queries.selectSpendForMerchantBetween(since, until, budget.scopeValue!!)
                .executeAsOne().let { it.effective_minor to it.txn_count }
            BudgetScope.TAG -> queries.selectSpendForTagBetween(since, until, budget.scopeValue!!)
                .executeAsOne().let { it.effective_minor to it.txn_count }
        }

        BudgetProgress(
            budget = budget,
            spentMinor = spent ?: 0L,
            paymentCount = count.toInt(),
            start = start,
            end = end,
            today = today
        )
    }

    /**
     * What this scope actually cost over the last whole period, as a starting
     * suggestion for a new budget.
     *
     * A blank amount field is the reason budget features go unused: nobody knows
     * what a reasonable number is for themselves until they are shown one, and
     * the only honest source for that is what they already did.
     */
    suspend fun lastPeriodSpendMinor(
        scope: BudgetScope,
        scopeValue: String?,
        period: BudgetPeriod,
        today: LocalDate = LocalDate.now(zone)
    ): Long = withContext(io) {
        val (start, _) = BudgetWindow.of(period, today, today)
        val previousEnd = start.minusDays(1)
        val (previousStart, _) = BudgetWindow.of(period, today, previousEnd)
        val since = previousStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = start.atStartOfDay(zone).toInstant().toEpochMilli()

        when (scope) {
            BudgetScope.TOTAL -> queries.selectSpendTotalBetween(since, until).executeAsOne().effective_minor
            BudgetScope.MERCHANT -> scopeValue?.let {
                queries.selectSpendForMerchantBetween(since, until, it).executeAsOne().effective_minor
            }
            BudgetScope.TAG -> scopeValue?.let {
                queries.selectSpendForTagBetween(since, until, it).executeAsOne().effective_minor
            }
        } ?: 0L
    }

    private companion object {
        /**
         * A stored row the code no longer understands is dropped rather than
         * coerced. Guessing MONTHLY for an unreadable period would show the user
         * a limit measured over a window they never chose.
         */
        fun Budgets.toModel(): Budget? {
            val scopeKind = runCatching { BudgetScope.valueOf(scope) }.getOrNull() ?: return null
            val periodKind = runCatching { BudgetPeriod.valueOf(period) }.getOrNull() ?: return null
            if ((scopeKind == BudgetScope.TOTAL) != (scope_value == null)) return null
            if (amount_minor <= 0L) return null
            return Budget(
                id = id,
                name = name,
                scope = scopeKind,
                scopeValue = scope_value,
                period = periodKind,
                limitMinor = amount_minor,
                currency = currency,
                anchorAt = anchor_at
            )
        }
    }
}
