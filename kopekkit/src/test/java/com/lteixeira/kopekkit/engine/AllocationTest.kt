package com.lteixeira.kopekkit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Port des cas de Kopek web (scripts/test-engine.ts) qui touchent le moteur de répartition :
 * mêmes entrées, mêmes chiffres attendus. Montants en centimes.
 */
class AllocationTest {
    private val rule =
        listOf(
            BucketInput("f", "Charges fixes", BucketKind.FIXED, 50.0),
            BucketInput("l", "Plaisir", BucketKind.LIFESTYLE, 30.0),
            BucketInput("s", "Épargne", BucketKind.SAVING, 20.0),
        )

    private val leoRule =
        listOf(
            BucketInput("ep", "Épargne", BucketKind.SAVING, 0.0, fixedAmount = 15000),
            BucketInput("f", "Charges", BucketKind.FIXED, 50.0),
            BucketInput("l", "Plaisir", BucketKind.LIFESTYLE, 30.0),
        )

    private val thirdRule =
        listOf(
            BucketInput("x", "X", BucketKind.LIFESTYLE, 33.3),
            BucketInput("y", "Y", BucketKind.SAVING, 33.3),
            BucketInput("z", "Z", BucketKind.FIXED, 33.3),
        )

    /** Montant d'une catégorie. Pas de `!!` : `first` lève une erreur claire si l'id n'existe pas. */
    private fun MonthAllocation.by(id: String): Long = buckets.first { it.input.id == id }.amount

    private fun allocate(
        net: Long,
        reference: Long,
        fixedCharges: Long,
        buckets: List<BucketInput>,
        surplusSavingsPct: Double = 70.0,
    ) = allocateMonth(net, reference, fixedCharges, buckets, surplusSavingsPct)

    // --- allocateMonth ---------------------------------------------------------------------------------------

    @Test
    fun `mois normal - charges au-dessus de 50 pour cent, on rogne le plaisir`() {
        val a = allocate(96100, 0, 65000, rule)
        assertEquals(65000L, a.by("f"))
        assertEquals(19220L, a.by("s"))
        assertEquals(96100L - 65000 - 19220, a.by("l"))
        assertEquals(0L, a.shortfall)
    }

    @Test
    fun `mois faible - plaisir et epargne a zero, manque de 250 euros`() {
        val a = allocate(40000, 96100, 65000, rule)
        assertEquals(0L, a.by("l"))
        assertEquals(0L, a.by("s"))
        assertEquals(25000L, a.shortfall)
    }

    @Test
    fun `mois fort - 70 pour cent du surplus en epargne`() {
        val a = allocate(300000, 150000, 65000, rule)
        assertEquals(97500L, a.by("f"))
        assertEquals(58500L, a.by("l"))
        assertEquals(144000L, a.by("s"))
        assertEquals(0L, a.unallocated)
    }

    @Test
    fun `sans categorie FIXED - les charges fixes remontent en manque`() {
        val noFixed =
            listOf(
                BucketInput("l", "Plaisir", BucketKind.LIFESTYLE, 60.0),
                BucketInput("s", "Épargne", BucketKind.SAVING, 40.0),
            )
        val a = allocate(100000, 0, 65000, noFixed)
        assertEquals(60000L, a.by("l"))
        assertEquals(40000L, a.by("s"))
        assertEquals(65000L, a.shortfall)
        assertEquals(0L, a.unallocated)
    }

    @Test
    fun `montant fixe - preleve d'abord, les pourcentages se partagent le reste`() {
        val fixedRule =
            listOf(
                BucketInput("f", "Charges fixes", BucketKind.FIXED, 50.0),
                BucketInput("dca", "DCA", BucketKind.SAVING, 0.0, fixedAmount = 20000),
                BucketInput("l", "Plaisir", BucketKind.LIFESTYLE, 50.0),
            )
        val a = allocate(100000, 0, 40000, fixedRule)
        assertEquals(20000L, a.by("dca"))
        assertEquals(40000L, a.by("f"))
        assertEquals(40000L, a.by("l"))
        // Un mois fort ne fait pas grossir le montant fixe.
        assertEquals(20000L, allocate(300000, 100000, 40000, fixedRule).by("dca"))
    }

    @Test
    fun `montant fixe - rogne comme les autres quand le mois est faible`() {
        val weakFixedRule =
            listOf(
                BucketInput("f", "Charges fixes", BucketKind.FIXED, 100.0),
                BucketInput("dca", "DCA", BucketKind.SAVING, 0.0, fixedAmount = 20000),
            )
        val a = allocate(50000, 0, 60000, weakFixedRule)
        assertEquals(0L, a.by("dca"))
        assertEquals(10000L, a.shortfall)
    }

    @Test
    fun `montants fixes trop gros - partages au prorata de ce qui existe`() {
        val overcommitted =
            listOf(
                BucketInput("dca", "DCA", BucketKind.SAVING, 0.0, fixedAmount = 80000),
                BucketInput("vac", "Vacances", BucketKind.ENVELOPE, 0.0, fixedAmount = 80000),
            )
        val a = allocate(100000, 0, 0, overcommitted)
        assertEquals(100000L, a.by("dca") + a.by("vac"))
        assertEquals(a.by("dca"), a.by("vac"))
    }

    @Test
    fun `mois fort - une epargne a 0 pour cent ne recoit rien du surplus`() {
        val zeroSaver =
            listOf(
                BucketInput("s", "Épargne", BucketKind.SAVING, 0.0),
                BucketInput("l", "Plaisir", BucketKind.LIFESTYLE, 100.0),
            )
        val a = allocate(200000, 100000, 0, zeroSaver)
        assertEquals(0L, a.by("s"))
        assertEquals(200000L, a.by("l"))
    }

    @Test
    fun `regle qui ne fait pas 100 pour cent - tout est reparti sans centime perdu`() {
        val decimalRule = listOf(BucketInput("x", "X", BucketKind.LIFESTYLE, 0.7))
        assertEquals(11000L, allocate(11000, 0, 0, decimalRule, surplusSavingsPct = 0.0).by("x"))
    }

    @Test
    fun `spread ne perd aucun centime - 3001 centimes en trois tiers`() {
        val a = allocate(3001, 0, 0, thirdRule, surplusSavingsPct = 0.0)
        assertEquals(3001L, a.by("x") + a.by("y") + a.by("z"))
        assertEquals(0L, a.unallocated)
    }

    @Test
    fun `regle de leo - 150 euros fixes plus 80 pour cent`() {
        val a = allocate(75000, 0, 0, leoRule)
        assertEquals(listOf(15000L, 37500L, 22500L), listOf(a.by("ep"), a.by("f"), a.by("l")))
        assertEquals(0L, a.unallocated)
        val b = allocate(100000, 0, 0, leoRule)
        assertEquals(85000L, b.by("f") + b.by("l"))
        assertEquals(0L, b.unallocated)
    }

    @Test
    fun `regle de leo - mois faible, le fixe puis le plaisir sont rognes`() {
        val a = allocate(60000, 0, 50000, leoRule)
        assertEquals(50000L, a.by("f"))
        assertEquals(60000L, a.by("f") + a.by("l") + a.by("ep"))
        assertEquals(0L, a.shortfall)
        val b = allocate(40000, 0, 50000, leoRule)
        assertEquals(listOf(40000L, 0L, 0L), listOf(b.by("f"), b.by("l"), b.by("ep")))
        assertEquals(10000L, b.shortfall)
    }

    @Test
    fun `mois fort avec epargne et regle differente de 100 pour cent`() {
        val strongRule =
            listOf(
                BucketInput("f", "Charges", BucketKind.FIXED, 40.0),
                BucketInput("s", "Épargne", BucketKind.SAVING, 20.0),
                BucketInput("dca", "DCA", BucketKind.SAVING, 0.0, fixedAmount = 10000),
            )
        val a = allocate(150000, 100000, 0, strongRule)
        assertEquals(10000L, a.by("dca"))
        assertEquals(150000L, a.by("f") + a.by("s") + a.by("dca"))
        assertEquals(30000L + 35000 + 5000, a.by("s"))
        assertEquals(0L, a.unallocated)
    }

    // --- Entrées invalides -----------------------------------------------------------------------------------

    @Test
    fun `entrees invalides refusees par require`() {
        assertThrows(IllegalArgumentException::class.java) { allocate(100000, 0, 0, rule, surplusSavingsPct = 120.0) }
        assertThrows(IllegalArgumentException::class.java) { allocate(100000, 0, 0, rule, surplusSavingsPct = -1.0) }
        val negative = listOf(BucketInput("l", "Plaisir", BucketKind.LIFESTYLE, -10.0))
        assertThrows(IllegalArgumentException::class.java) { allocate(100000, 0, 0, negative) }
        assertThrows(IllegalArgumentException::class.java) { summarizeIncomes(emptyList(), -1.0) }
    }

    // --- Revenus, charges, dépenses --------------------------------------------------------------------------

    @Test
    fun `revenus - URSSAF sur le CA, tous les revenus comptes`() {
        val s =
            summarizeIncomes(
                listOf(
                    Income(IncomeType.SALARY, 96100),
                    Income(IncomeType.SELF_EMPLOYED, 100000),
                    Income(IncomeType.THIRD_PARTY, 5000),
                ),
                urssafRate = 25.6,
            )
        assertEquals(25600L, s.urssaf)
        assertEquals(96100L + 100000 + 5000, s.gross)
        assertEquals(96100L + 100000 + 5000 - 25600, s.net)
    }

    @Test
    fun `revenu de reference - moyenne des mois avec revenu`() {
        assertEquals(150000L, referenceIncome(listOf(0, 100000, 200000)))
        assertEquals(0L, referenceIncome(listOf(0, 0)))
        // Arrondi comme Math.round : 1,5 -> 2 (une division entière donnerait 1).
        assertEquals(2L, referenceIncome(listOf(1, 2)))
    }

    @Test
    fun `revenu minimum pour les charges fixes`() {
        assertEquals(130000L, minIncomeForFixedCharges(65000, 50.0))
        assertEquals(0L, minIncomeForFixedCharges(65000, 0.0))
    }

    @Test
    fun `equivalent mensuel selon la frequence`() {
        assertEquals(6000L, monthlyEquivalent(6000, ChargeFrequency.MONTHLY))
        assertEquals(2000L, monthlyEquivalent(6000, ChargeFrequency.QUARTERLY))
        assertEquals(10000L, monthlyEquivalent(120000, ChargeFrequency.YEARLY))
    }

    @Test
    fun `depenses reelles contre objectif de la regle`() {
        val a = allocate(96100, 0, 65000, rule)
        val cmp =
            summarizeExpenses(
                listOf(ExpenseInput(BucketKind.LIFESTYLE, 8000), ExpenseInput(BucketKind.FIXED, 65000)),
                a.buckets,
            )
        val lifestyle = cmp.first { it.kind == BucketKind.LIFESTYLE }
        assertEquals(a.by("l"), lifestyle.objective)
        assertEquals(8000L, lifestyle.actual)
        assertEquals(a.by("l") - 8000, lifestyle.delta)
    }

    // --- ruleTotal et effectiveShares ------------------------------------------------------------------------

    @Test
    fun `total de la regle`() {
        assertEquals(RuleTotal(percents = 80.0, fixed = 15000, fixedPercent = 20.0, total = 100.0), ruleTotal(leoRule, 75000))
        val noIncome = ruleTotal(leoRule, 0)
        assertEquals(RuleTotal(percents = 80.0, fixed = 15000, fixedPercent = null, total = null), noIncome)
        assertNull(noIncome.total)
        assertEquals(99.9, ruleTotal(thirdRule, 0).total ?: Double.NaN, 0.0)
        val twoFixed =
            listOf(
                BucketInput("a", "A", BucketKind.FIXED, 33.33),
                BucketInput("b", "B", BucketKind.FIXED, 66.67),
            )
        assertEquals(100.0, ruleTotal(twoFixed, 0).total ?: Double.NaN, 0.0)
    }

    @Test
    fun `parts reelles - memes chiffres que le moteur un mois moyen`() {
        val shares = effectiveShares(leoRule, 100000)
        assertEquals(listOf(15.0, 53.125, 31.875), listOf(shares["ep"], shares["f"], shares["l"]))
        // Pas de revenu : les pourcentages se partagent tout.
        assertEquals(listOf(0.0, 62.5, 37.5), effectiveShares(leoRule, 0).values.toList())
    }
}
