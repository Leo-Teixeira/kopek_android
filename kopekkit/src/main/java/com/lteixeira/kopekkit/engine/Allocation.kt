package com.lteixeira.kopekkit.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

// Moteur de répartition mensuelle, porté de Kopek web (src/lib/engine/allocation.ts).
// Fonctions pures, sans dépendance Android : testables isolément.
//
// Règle des types :
// - tous les montants sont des entiers en centimes (`Long`) ;
// - seuls les pourcentages (`percent`, `urssafRate`, `surplusSavingsPct`) sont des `Double`.
//
// Règle des arrondis (JS -> Kotlin) :
// - `Math.round` de JS arrondit les demi-valeurs vers +∞ (2,5 -> 3 ; -2,5 -> -2). `roundToLong()` de Kotlin
//   fait exactement pareil (« ties are rounded towards positive infinity »). Attention : `kotlin.math.round()`
//   arrondit les demi-valeurs au pair (2,5 -> 2), il ne faut donc PAS l'utiliser ici.
// - `Math.floor` / `Math.ceil` de JS -> `floor()` / `ceil()` de kotlin.math, puis `.toLong()`.

/** Type d'un revenu. Les noms des cas sont ceux de l'API web (sérialisés tels quels). */
enum class IncomeType { SALARY, SELF_EMPLOYED, THIRD_PARTY, OTHER }

/** Famille d'une catégorie de la règle de répartition. */
enum class BucketKind { FIXED, LIFESTYLE, SAVING, ENVELOPE }

/** Fréquence de paiement d'une charge récurrente. */
enum class ChargeFrequency { MONTHLY, QUARTERLY, YEARLY }

/** Nombre de paiements par an : un `when` exhaustif, le compilateur refuse un cas oublié. */
private fun ChargeFrequency.occurrencesPerYear(): Long =
    when (this) {
        ChargeFrequency.MONTHLY -> 12
        ChargeFrequency.QUARTERLY -> 4
        ChargeFrequency.YEARLY -> 1
    }

/** Ordre dans lequel on rogne pour payer les charges fixes quand le mois est faible. */
private val CUT_ORDER = listOf(BucketKind.LIFESTYLE, BucketKind.ENVELOPE, BucketKind.SAVING)

/** Équivalent de `Math.round` de JS : demi-valeurs vers +∞. */
private fun jsRound(value: Double): Long = value.roundToLong()

/** Arrondi au dixième, comme `Math.round(n * 10) / 10` en JS. */
private fun roundToTenth(value: Double): Double = jsRound(value * 10) / 10.0

// ---------------------------------------------------------------------------------------------------------------
// Charges
// ---------------------------------------------------------------------------------------------------------------

/**
 * Équivalent mensuel d'une charge selon sa fréquence (ex. une assurance payée une fois par an, lissée sur 12 mois).
 *
 * @param amount montant d'un paiement, en centimes.
 * @return le montant mensuel lissé, en centimes, arrondi comme `Math.round` de JS.
 */
fun monthlyEquivalent(amount: Long, frequency: ChargeFrequency): Long =
    jsRound((amount * frequency.occurrencesPerYear()).toDouble() / 12)

/** Une charge récurrente (loyer, assurance…), en centimes par paiement. */
data class RecurringCharge(
    val id: String,
    val label: String,
    val amount: Long,
    val frequency: ChargeFrequency,
    val active: Boolean,
)

/** Une dépense de charge fixe générée pour un mois. */
data class ChargeExpense(
    val userId: String,
    val month: String,
    val label: String,
    val amount: Long,
    val kind: BucketKind,
    val category: String,
    val recurringChargeId: String,
)

/**
 * Dépenses à créer pour un mois à partir des charges actives : une ligne par charge, à son équivalent mensuel.
 *
 * @param month mois au format « AAAA-MM ».
 */
fun expensesFromCharges(charges: List<RecurringCharge>, userId: String, month: String): List<ChargeExpense> =
    charges
        .filter { it.active }
        .map { charge ->
            ChargeExpense(
                userId = userId,
                month = month,
                label = charge.label,
                amount = monthlyEquivalent(charge.amount, charge.frequency),
                kind = BucketKind.FIXED,
                category = "Charges fixes",
                recurringChargeId = charge.id,
            )
        }

// ---------------------------------------------------------------------------------------------------------------
// Revenus
// ---------------------------------------------------------------------------------------------------------------

/** Un revenu du mois, en centimes. */
data class Income(val type: IncomeType, val amount: Long)

/** Synthèse des revenus d'un mois, en centimes. */
data class IncomeSummary(
    /** Tous les revenus du mois, argent d'un tiers compris. */
    val gross: Long,
    /** Chiffre d'affaires de micro-entreprise. */
    val selfEmployed: Long,
    /** Provision de cotisations URSSAF. */
    val urssaf: Long,
    /** Ce qui est réellement à répartir : `gross - urssaf`. */
    val net: Long,
)

/**
 * Additionne les revenus du mois et provisionne l'URSSAF sur le chiffre d'affaires de micro-entreprise.
 *
 * @param urssafRate taux de cotisation en % (ex. 25.6).
 * @throws IllegalArgumentException si `urssafRate` est négatif.
 */
fun summarizeIncomes(incomes: List<Income>, urssafRate: Double): IncomeSummary {
    require(urssafRate >= 0) { "urssafRate doit être positif ou nul (reçu : $urssafRate)" }
    val gross = incomes.sumOf { it.amount }
    val selfEmployed = incomes.filter { it.type == IncomeType.SELF_EMPLOYED }.sumOf { it.amount }
    val urssaf = jsRound(selfEmployed * urssafRate / 100)
    return IncomeSummary(gross = gross, selfEmployed = selfEmployed, urssaf = urssaf, net = gross - urssaf)
}

/**
 * Revenu de référence : moyenne des mois précédents qui ont au moins un revenu, arrondie comme `Math.round` de JS.
 *
 * @param pastNets revenus nets des mois précédents, en centimes.
 * @return 0 si aucun mois n'a de revenu.
 */
fun referenceIncome(pastNets: List<Long>): Long {
    val months = pastNets.filter { it > 0 }
    if (months.isEmpty()) return 0
    // Division en Double : une division entière tronquerait (1,5 -> 1 au lieu de 2).
    return jsRound(months.sum().toDouble() / months.size)
}

/**
 * Revenu minimum pour que `fixedCharges` reste sous `fixedPct` % du revenu.
 *
 * @param fixedCharges charges fixes mensuelles, en centimes.
 * @param fixedPct part maximale des charges fixes, en %.
 * @return le revenu minimum en centimes, arrondi au centime supérieur ; 0 si `fixedPct` vaut 0 ou moins.
 */
fun minIncomeForFixedCharges(fixedCharges: Long, fixedPct: Double): Long =
    if (fixedPct > 0) ceil(fixedCharges * 100 / fixedPct).toLong() else 0

// ---------------------------------------------------------------------------------------------------------------
// Règle de répartition
// ---------------------------------------------------------------------------------------------------------------

/**
 * Une catégorie de la règle de répartition.
 *
 * @property percent part en % du revenu ; ignorée si `fixedAmount` est renseigné.
 * @property fixedAmount montant fixe mensuel en centimes, ou `null` pour une catégorie en pourcentage.
 */
data class BucketInput(
    val id: String,
    val name: String,
    val kind: BucketKind,
    val percent: Double,
    val fixedAmount: Long? = null,
)

/** Ce que répartit une règle, en % d'un mois de revenu (voir [ruleTotal]). */
data class RuleTotal(
    /** Somme des pourcentages, arrondie au dixième. */
    val percents: Double,
    /** Somme des montants fixes, en centimes. */
    val fixed: Long,
    /** Montants fixes rapportés au revenu, en %, ou `null` s'il y en a mais que le revenu est inconnu. */
    val fixedPercent: Double?,
    /** `percents + fixedPercent`, ou `null` quand `fixedPercent` l'est. Doit valoir 100. */
    val total: Double?,
)

/**
 * Ce que la règle répartit, en % d'un mois de revenu `income` (en pratique le revenu moyen) : les pourcentages plus
 * les montants fixes rapportés à ce revenu (150 € sur 750 € = 20 %). Sans revenu connu, les montants fixes ne se
 * convertissent pas : `total` est `null` s'il y en a.
 *
 * @param income revenu de référence, en centimes.
 */
fun ruleTotal(buckets: List<BucketInput>, income: Long): RuleTotal {
    val percents = roundToTenth(buckets.filter { it.fixedAmount == null }.sumOf { it.percent })
    val fixed = buckets.sumOf { it.fixedAmount ?: 0L }
    val fixedPercent =
        when {
            fixed == 0L -> 0.0
            income > 0 -> roundToTenth(fixed * 100.0 / income)
            else -> null
        }
    val total = fixedPercent?.let { roundToTenth(percents + it) }
    return RuleTotal(percents = percents, fixed = fixed, fixedPercent = fixedPercent, total = total)
}

/**
 * Part réelle de chaque catégorie dans un mois de revenu `income`, en % (même calcul que [allocateMonth] hors
 * surplus et charges fixes) : les montants fixes d'abord, puis le reste partagé au prorata des pourcentages. Sans
 * revenu connu, les montants fixes comptent pour 0 et les pourcentages se partagent 100 %.
 *
 * @return une map `id -> part en %`, dans l'ordre des catégories.
 */
fun effectiveShares(buckets: List<BucketInput>, income: Long): Map<String, Double> {
    val fixedTotal = buckets.sumOf { it.fixedAmount ?: 0L }
    val weights = buckets.filter { it.fixedAmount == null }.sumOf { it.percent }
    val fixedShare = if (income > 0) (fixedTotal * 100.0 / income).coerceAtMost(100.0) else 0.0
    return buckets.associate { bucket ->
        val fixedAmount = bucket.fixedAmount
        val share =
            when {
                fixedAmount != null -> if (fixedTotal > 0) fixedShare * fixedAmount / fixedTotal else 0.0
                weights > 0 -> (100 - fixedShare) * bucket.percent / weights
                else -> 0.0
            }
        bucket.id to share
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Répartition du mois
// ---------------------------------------------------------------------------------------------------------------

/** Résultat d'une catégorie après répartition, en centimes. */
data class BucketResult(
    val input: BucketInput,
    /** Part issue du revenu « normal ». */
    val base: Long,
    /** Bonus d'un mois plus fort que la référence. */
    val fromSurplus: Long,
    /** +/- pour couvrir les charges fixes. */
    val adjustment: Long,
    /** Montant final conseillé. */
    val amount: Long,
)

/** Résultat de [allocateMonth], en centimes. */
data class MonthAllocation(
    val available: Long,
    val reference: Long,
    val base: Long,
    val surplus: Long,
    val fixedCharges: Long,
    /** Reste dans les catégories FIXED après les charges fixes (courses, essence…). */
    val fixedMargin: Long,
    /** Manque à prendre sur le matelas de sécurité. */
    val shortfall: Long,
    /** Ce qui reste sans catégorie en pourcentage pour le recevoir (aucune, ou toutes à 0 %). */
    val unallocated: Long,
    val buckets: List<BucketResult>,
)

/**
 * Répartit `total` proportionnellement aux poids, en centimes entiers, sans perte d'arrondi : chaque part est
 * arrondie vers le bas, puis les centimes restants sont distribués un par un depuis le début.
 */
private fun spread(total: Long, weights: List<Double>): List<Long> {
    if (total <= 0 || weights.isEmpty()) return weights.map { 0L }
    val sum = weights.sum()
    if (sum <= 0) return weights.map { 0L }
    val shares = weights.map { floor(total * it / sum).toLong() }.toMutableList()
    var rest = total - shares.sum()
    var i = 0
    while (rest > 0) {
        shares[i]++
        rest--
        i = (i + 1) % shares.size
    }
    return shares
}

/**
 * Répartit le revenu net d'un mois entre les catégories de la règle.
 *
 * 1. Mois fort (`net > reference`) : la référence est répartie selon la règle, le surplus à part
 *    (`surplusSavingsPct` % vers l'épargne et les enveloppes en pourcentage, le reste au prorata).
 * 2. Les montants fixes sont prélevés d'abord, les pourcentages se partagent le reste.
 * 3. Mois faible : les charges fixes sont incompressibles, on rogne plaisir, puis enveloppes, puis épargne.
 *
 * @param net revenu net du mois, en centimes (un négatif compte pour 0).
 * @param reference revenu de référence (voir [referenceIncome]), en centimes ; 0 si inconnu.
 * @param fixedCharges charges fixes du mois, en centimes.
 * @param surplusSavingsPct part du surplus envoyée à l'épargne, en % (0 à 100).
 * @throws IllegalArgumentException si un pourcentage est négatif, un montant fixe négatif, ou si `surplusSavingsPct`
 *   sort de 0..100.
 */
fun allocateMonth(
    net: Long,
    reference: Long,
    fixedCharges: Long,
    buckets: List<BucketInput>,
    surplusSavingsPct: Double,
): MonthAllocation {
    require(surplusSavingsPct in 0.0..100.0) { "surplusSavingsPct doit être entre 0 et 100 (reçu : $surplusSavingsPct)" }
    for (b in buckets) {
        require(b.percent >= 0) { "percent de « ${b.id} » doit être positif ou nul (reçu : ${b.percent})" }
        require((b.fixedAmount ?: 0L) >= 0) { "fixedAmount de « ${b.id} » doit être positif ou nul" }
    }

    val available = net.coerceAtLeast(0L)

    // 1. Mois fort : on répartit la référence selon la règle, le surplus est traité à part.
    val base = if (reference > 0 && available > reference) reference else available
    val surplus = available - base

    // État modifiable LOCAL à la fonction : on ne renvoie qu'une liste en lecture seule.
    val res = buckets.map { BucketResult(it, base = 0, fromSurplus = 0, adjustment = 0, amount = 0) }.toMutableList()

    val fixedIndexes = res.indices.filter { res[it].input.fixedAmount != null }
    val flexibleIndexes = res.indices.filter { res[it].input.fixedAmount == null }
    val totalFixedAmount = fixedIndexes.sumOf { res[it].input.fixedAmount ?: 0L }

    if (totalFixedAmount > base) {
        // Les montants fixes à eux seuls dépassent l'argent disponible : on les réduit proportionnellement.
        spread(base, fixedIndexes.map { (res[it].input.fixedAmount ?: 0L).toDouble() }).forEachIndexed { i, s ->
            val index = fixedIndexes[i]
            res[index] = res[index].copy(base = s)
        }
    } else {
        for (index in fixedIndexes) res[index] = res[index].copy(base = res[index].input.fixedAmount ?: 0L)
        spread(base - totalFixedAmount, flexibleIndexes.map { res[it].input.percent }).forEachIndexed { i, s ->
            val index = flexibleIndexes[i]
            res[index] = res[index].copy(base = s)
        }
    }

    if (surplus > 0) {
        // Un montant fixe ne grossit pas les mois forts : seules les catégories en pourcentage profitent du surplus.
        val saverIndexes =
            flexibleIndexes.filter {
                val b = res[it].input
                (b.kind == BucketKind.SAVING || b.kind == BucketKind.ENVELOPE) && b.percent > 0
            }
        val toSavers = if (saverIndexes.isNotEmpty()) jsRound(surplus * surplusSavingsPct / 100) else 0L
        spread(toSavers, saverIndexes.map { res[it].input.percent }).forEachIndexed { i, s ->
            val index = saverIndexes[i]
            res[index] = res[index].copy(fromSurplus = res[index].fromSurplus + s)
        }
        spread(surplus - toSavers, flexibleIndexes.map { res[it].input.percent }).forEachIndexed { i, s ->
            val index = flexibleIndexes[i]
            res[index] = res[index].copy(fromSurplus = res[index].fromSurplus + s)
        }
    }
    for (i in res.indices) res[i] = res[i].copy(amount = res[i].base + res[i].fromSurplus)

    // 2. Mois faible : les charges fixes sont incompressibles, on rogne plaisir -> enveloppes -> épargne.
    // Sans catégorie FIXED, on ne devine pas où rogner : les charges fixes deviennent un manque direct.
    val fixedKindIndexes = res.indices.filter { res[it].input.kind == BucketKind.FIXED }
    val fixedBudget = fixedKindIndexes.sumOf { res[it].amount }
    var gap = if (fixedKindIndexes.isNotEmpty()) fixedCharges - fixedBudget else fixedCharges
    var covered = 0L

    if (gap > 0 && fixedKindIndexes.isNotEmpty()) {
        for (kind in CUT_ORDER) {
            if (gap <= 0) break
            val group = res.indices.filter { res[it].input.kind == kind && res[it].amount > 0 }
            val pool = group.sumOf { res[it].amount }
            val take = minOf(pool, gap)
            spread(take, group.map { res[it].amount.toDouble() }).forEachIndexed { i, s ->
                val index = group[i]
                res[index] = res[index].copy(adjustment = res[index].adjustment - s, amount = res[index].amount - s)
            }
            gap -= take
            covered += take
        }
        // `b.percent || 1` en JS : un poids de 1 pour une catégorie FIXED à 0 %.
        val fixedWeights = fixedKindIndexes.map { res[it].input.percent.takeIf { p -> p != 0.0 } ?: 1.0 }
        spread(covered, fixedWeights).forEachIndexed { i, s ->
            val index = fixedKindIndexes[i]
            res[index] = res[index].copy(adjustment = res[index].adjustment + s, amount = res[index].amount + s)
        }
    }

    val fixedAfter = fixedKindIndexes.sumOf { res[it].amount }
    val allocated = res.sumOf { it.amount }

    return MonthAllocation(
        available = available,
        reference = reference,
        base = base,
        surplus = surplus,
        fixedCharges = fixedCharges,
        fixedMargin = if (fixedKindIndexes.isNotEmpty()) (fixedAfter - fixedCharges).coerceAtLeast(0L) else 0L,
        shortfall = gap.coerceAtLeast(0L),
        unallocated = (available - allocated).coerceAtLeast(0L),
        buckets = res.toList(),
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Dépenses réelles
// ---------------------------------------------------------------------------------------------------------------

/** Une dépense réelle du mois, réduite à ce dont la comparaison a besoin. */
data class ExpenseInput(val kind: BucketKind, val amount: Long)

/** Objectif de la règle contre dépenses réelles pour une famille de catégories, en centimes. */
data class ExpenseComparison(
    val kind: BucketKind,
    val objective: Long,
    val actual: Long,
    /** `objective - actual` : positif s'il reste de la marge. */
    val delta: Long,
)

/**
 * Compare les dépenses réelles du mois aux montants de la règle de répartition, par famille de catégories.
 *
 * @return une ligne par [BucketKind] qui a un objectif ou des dépenses, dans l'ordre de l'enum.
 */
fun summarizeExpenses(expenses: List<ExpenseInput>, buckets: List<BucketResult>): List<ExpenseComparison> =
    BucketKind.entries
        .map { kind ->
            val objective = buckets.filter { it.input.kind == kind }.sumOf { it.amount }
            val actual = expenses.filter { it.kind == kind }.sumOf { it.amount }
            ExpenseComparison(kind = kind, objective = objective, actual = actual, delta = objective - actual)
        }.filter { it.objective > 0 || it.actual > 0 }
