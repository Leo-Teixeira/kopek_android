package com.lteixeira.kopekkit.engine

import kotlin.math.ceil
import kotlin.math.roundToInt

enum class IncomeType {
    SALARY,
    SELF_EMPLOYED,
    THIRD_PARTY,
    OTHER,
}

enum class BucketKind {
    FIXED,
    LIFESTYLE,
    SAVING,
    ENVELOPPE,
}

enum class ChargeFrequency {
    MONTHLY,
    QUARTERLY,
    YEARLY,
}

data class IncomeSummary(
    val gross: Double,
    val selfEmployed: Double,
    val ursaff: Long,
    val net: Double,
)

data class RuleTotal(
    val percents: Double,
    val fixed: Long,
    val fixedPercent: Double,
    val total: Long?,
)

data class BucketInput(
    val id: String,
    val name: String,
    val kind: BucketKind,
    val percent: Double,
    val fixedAmount: Long?,
)

data class BucketResult(
    val input: BucketInput,
    val base: Long,
    val fromSurplus: Long,
    val adjustement: Long,
    val amount: Long,
)

data class ExpenseComparison(
    val kind: BucketKind,
    val objective: Long,
    val actual: Long,
    val delta: Long,
)

enum class AllBucketKinds(
    val bucketKind: Boolean,
) {
    FIXED(true),
    LIFESTYLE(true),
    SAVING(true),
    ENVELOPPE(true),
}

val OCCURRENCES_PER_YEAR: Map<ChargeFrequency, Long> =
    mapOf(
        ChargeFrequency.MONTHLY to 12,
        ChargeFrequency.QUARTERLY to 4,
        ChargeFrequency.YEARLY to 1,
    )

data class MonthAllocation(
    val available: Long,
    val reference: Long,
    val base: Long,
    val surplus: Long,
    val fixedCharges: Long,
    val fixedMargin: Long, // reste dans les buckets FIXED après les charges fixes (courses, essence…)
    val shortfall: Long, // manque à prendre sur le matelas de sécurité
    val unallocated: Long, // ce qui reste sans catégorie en pourcentage pour le recevoir (aucune, ou toutes à 0 %)
    val buckets: List<BucketResult>,
)

val cutOrder: List<BucketKind> =
    listOf(BucketKind.LIFESTYLE, BucketKind.ENVELOPPE, BucketKind.SAVING)

data class Charges(
    val id: String,
    val label: String,
    val amount: Long,
    val frequency: ChargeFrequency,
    val active: Boolean,
)

data class Expense(
    val userId: String,
    val month: String,
    val label: String,
    val amount: Double,
    val kind: String,
    val category: String,
    val recurringChargeId: String,
)

data class Incomes(
    val type: IncomeType,
    val amount: Double,
)

fun monthlyEquivalent(
    amount: Long,
    frequency: ChargeFrequency,
): Double =
    OCCURRENCES_PER_YEAR[frequency]
        ?.let { ((amount * it).toDouble() / 12).roundToInt() }
        ?.toDouble() ?: 0.0

fun expensesFromCharges(
    charges: List<Charges>,
    userId: String,
    month: String,
): List<Expense> =
    charges
        .filter { it.active }
        .map { charge ->
            Expense(
                userId = userId,
                month = month,
                label = charge.label,
                amount = monthlyEquivalent(charge.amount, charge.frequency),
                kind = "FIXED",
                category = "Charges fixes",
                recurringChargeId = charge.id,
            )
        }

fun summarizeIncomes(
    incomes: List<Incomes>,
    ursaffRate: Long,
): IncomeSummary {
    var gross: Double = 0.0
    var selfEmployed: Double = 0.0
    for (i in incomes) {
        gross += i.amount
        if (i.type == IncomeType.SELF_EMPLOYED) selfEmployed += i.amount
    }
    var ursaff = Math.round((selfEmployed * ursaffRate).toDouble() / 100)
    var net = gross - ursaff
    return IncomeSummary(gross, selfEmployed, ursaff, net)
}

fun ruleTotal(
    buckets: List<BucketInput>,
    income: Long,
): RuleTotal {
    fun round(n: Double): Long = Math.round(n * 10) / 10.toLong()
    var percents = round(buckets.filter { it.fixedAmount == null }.sumOf { it.percent })
    val fixed = buckets.sumOf { it.fixedAmount ?: 0L }
    var fixedPercent =
        when {
            fixed.toInt() == 0 -> 0
            income > 0 -> round(((fixed * 100) / income).toDouble())
            else -> null
        }
    var total = if (fixedPercent == null) null else round((percents + fixedPercent).toDouble())
    return RuleTotal(percents.toDouble(), fixed, fixedPercent?.toDouble() ?: 0.0, total)
}

fun effectiveShares(
    buckets: List<BucketInput>,
    income: Long,
): Map<String, Double> {
    val fixedTotal = buckets.sumOf { it.fixedAmount ?: 0L }

    val weights =
        buckets.sumOf {
            if (it.fixedAmount == null) it.percent else 0.0
        }

    val fixedShare =
        if (income > 0) {
            ((fixedTotal.toDouble() * 100) / income).coerceAtMost(100.0)
        } else {
            0.0
        }

    return buckets.associate { b ->
        val share =
            if (b.fixedAmount != null) {
                if (fixedTotal > 0) {
                    (fixedShare * b.fixedAmount) / fixedTotal
                } else {
                    0.0
                }
            } else {
                if (weights > 0) {
                    ((100 - fixedShare) * b.percent) / weights
                } else {
                    0.0
                }
            }

        b.id to share
    }
}

fun minIncomeForFixedCharges(
    fixedCharges: Double,
    fixedPct: Double,
): Double = if (fixedPct > 0) ceil(((fixedCharges * 100) / fixedPct)) else 0.0

fun referenceIncome(pastNets: List<Long>): Long {
    val months = pastNets.filter { it > 0 }
    if (months.isEmpty()) {
        return 0
    } else {
        return Math.round((months.sumOf { it ?: 0L } / months.count()).toDouble())
    }
}

data class Expenses(
    val kind: BucketKind,
    val amount: Long,
)

fun summarizeExpenses(
    expenses: List<Expenses>,
    buckets: List<BucketResult>,
): List<ExpenseComparison> {
    val kinds = BucketKind.entries

    return kinds
        .map { kind ->

            val objective =
                buckets
                    .filter { it.input.kind == kind }
                    .sumOf { it.amount }

            val actual =
                expenses
                    .filter { it.kind == kind }
                    .sumOf { it.amount }

            ExpenseComparison(
                kind = kind,
                objective = objective,
                actual = actual,
                delta = objective - actual,
            )
        }.filter { it.objective > 0 || it.actual > 0 }
}

private fun spread(
    total: Long,
    weights: List<Double>,
): List<Long> {
    if (total <= 0 || weights.isEmpty()) {
        return weights.map { 0L }
    }

    val sum = weights.sumOf { it }

    if (sum <= 0) {
        return weights.map { 0L }
    }

    val shares =
        weights
            .map { weight ->
                (total * weight / sum).toLong()
            }.toMutableList()

    var rest = total - shares.sumOf { it }
    var i = 0

    while (rest > 0) {
        shares[i]++
        rest--
        i = (i + 1) % shares.size
    }

    return shares
}

data class MonthAllocationInput(
    val net: Long,
    val reference: Long,
    val fixedCharges: Long,
    val buckets: List<BucketInput>,
    val surplusSavingsPct: Double,
)

fun allocateMonth(input: MonthAllocationInput): MonthAllocation {
    val reference = input.reference
    val fixedCharges = input.fixedCharges
    val surplusSavingsPct = input.surplusSavingsPct

    val available = input.net.coerceAtLeast(0L)

    // 1. Mois fort
    val base =
        if (reference > 0 && available > reference) {
            reference
        } else {
            available
        }

    val surplus = available - base

    val res =
        input.buckets
            .map { b ->
                BucketResult(
                    input = b,
                    base = 0L,
                    fromSurplus = 0L,
                    adjustement = 0L,
                    amount = 0L,
                )
            }.toMutableList()

    val fixedIndexes =
        res.indices.filter {
            res[it].input.fixedAmount != null
        }

    val flexibleIndexes =
        res.indices.filter {
            res[it].input.fixedAmount == null
        }

    val totalFixedAmount =
        fixedIndexes.sumOf {
            res[it].input.fixedAmount ?: 0L
        }

    if (totalFixedAmount > base) {
        spread(
            base,
            fixedIndexes.map {
                (res[it].input.fixedAmount ?: 0L).toDouble()
            },
        ).forEachIndexed { i, s ->
            val index = fixedIndexes[i]
            res[index] = res[index].copy(base = s)
        }

        for (i in res.indices) {
            if (res[i].input.fixedAmount == null) {
                res[i] = res[i].copy(base = 0L)
            }
        }
    } else {
        for (i in fixedIndexes) {
            res[i] =
                res[i].copy(
                    base = res[i].input.fixedAmount ?: 0L,
                )
        }

        spread(
            base - totalFixedAmount,
            flexibleIndexes.map { res[it].input.percent },
        ).forEachIndexed { i, s ->
            val index = flexibleIndexes[i]
            res[index] = res[index].copy(base = s)
        }
    }

    if (surplus > 0) {
        val saversIndexes =
            flexibleIndexes.filter {
                val b = res[it].input
                (
                    b.kind == BucketKind.SAVING ||
                        b.kind == BucketKind.ENVELOPPE
                ) &&
                    b.percent > 0
            }

        val toSavers =
            if (saversIndexes.isNotEmpty()) {
                kotlin.math
                    .round(
                        (surplus.toDouble() * surplusSavingsPct) / 100,
                    ).toLong()
            } else {
                0L
            }

        spread(
            toSavers,
            saversIndexes.map { res[it].input.percent },
        ).forEachIndexed { i, s ->
            val index = saversIndexes[i]
            res[index] =
                res[index].copy(
                    fromSurplus = res[index].fromSurplus + s,
                )
        }

        spread(
            surplus - toSavers,
            flexibleIndexes.map { res[it].input.percent },
        ).forEachIndexed { i, s ->
            val index = flexibleIndexes[i]
            res[index] =
                res[index].copy(
                    fromSurplus = res[index].fromSurplus + s,
                )
        }
    }

    for (i in res.indices) {
        res[i] =
            res[i].copy(
                amount = res[i].base + res[i].fromSurplus,
            )
    }

    // 2. Mois faible
    val fixedIndexesForCharges =
        res.indices.filter {
            res[it].input.kind == BucketKind.FIXED
        }

    val fixedBudget =
        fixedIndexesForCharges.sumOf {
            res[it].amount
        }

    var gap =
        if (fixedIndexesForCharges.isNotEmpty()) {
            fixedCharges - fixedBudget
        } else {
            fixedCharges
        }

    var covered = 0L

    if (gap > 0 && fixedIndexesForCharges.isNotEmpty()) {
        for (kind in cutOrder) {
            if (gap <= 0) break

            val groupIndexes =
                res.indices.filter {
                    res[it].input.kind == kind && res[it].amount > 0
                }

            val pool =
                groupIndexes.sumOf {
                    res[it].amount
                }

            val take = minOf(pool, gap)

            spread(
                take,
                groupIndexes.map { res[it].amount.toDouble() },
            ).forEachIndexed { i, s ->
                val index = groupIndexes[i]

                res[index] =
                    res[index].copy(
                        adjustement = res[index].adjustement - s,
                        amount = res[index].amount - s,
                    )
            }

            gap -= take
            covered += take
        }

        spread(
            covered,
            fixedIndexesForCharges.map {
                if (res[it].input.percent != 0.0) {
                    res[it].input.percent
                } else {
                    1.0
                }
            },
        ).forEachIndexed { i, s ->
            val index = fixedIndexesForCharges[i]

            res[index] =
                res[index].copy(
                    adjustement = res[index].adjustement + s,
                    amount = res[index].amount + s,
                )
        }
    }

    val shortfall = gap.coerceAtLeast(0L)

    val fixedAfter =
        fixedIndexesForCharges.sumOf {
            res[it].amount
        }

    val fixedMargin =
        if (fixedIndexesForCharges.isNotEmpty()) {
            (fixedAfter - fixedCharges).coerceAtLeast(0L)
        } else {
            0L
        }

    val allocated = res.sumOf { it.amount }

    return MonthAllocation(
        available = available,
        reference = reference,
        base = base,
        surplus = surplus,
        fixedCharges = fixedCharges,
        fixedMargin = fixedMargin,
        shortfall = shortfall,
        unallocated = (available - allocated).coerceAtLeast(0L),
        buckets = res,
    )
}
