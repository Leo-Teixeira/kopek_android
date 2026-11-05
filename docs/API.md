# API publique de KopekKit

Référence commune aux deux SDK (Android et iOS). Ce fichier est identique
dans `kopek_android` et `kopek_ios` : si les deux API divergent, ça se voit ici.

| Symbole | Kotlin (Android) | Swift (iOS) | Depuis |
|---|---|---|---|
| Version du SDK | `KopekKit.version: String` | `KopekKit.version: String` | 0.1.0 |
| Moteur : équivalent mensuel d'une charge | `monthlyEquivalent(amount: Long, frequency: ChargeFrequency): Long` | `monthlyEquivalent(of:frequency:) -> Int` | 0.1.0 |
| Moteur : charges -> dépenses du mois | `expensesFromCharges(charges, userId, month): List<ChargeExpense>` | `expensesFromCharges(_:userId:month:) -> [ChargeExpense]` | 0.1.0 |
| Moteur : synthèse des revenus | `summarizeIncomes(incomes, urssafRate: Double): IncomeSummary` | `summarizeIncomes(_:urssafRate:) throws(EngineError) -> IncomeSummary` | 0.1.0 |
| Moteur : revenu de référence | `referenceIncome(pastNets: List<Long>): Long` | `referenceIncome(pastNets:) -> Int` | 0.1.0 |
| Moteur : revenu minimum | `minIncomeForFixedCharges(fixedCharges: Long, fixedPct: Double): Long` | `minIncomeForFixedCharges(_:fixedPct:) -> Int` | 0.1.0 |
| Moteur : total d'une règle | `ruleTotal(buckets, income: Long): RuleTotal` | `ruleTotal(of:income:) -> RuleTotal` | 0.1.0 |
| Moteur : parts réelles | `effectiveShares(buckets, income: Long): Map<String, Double>` | `effectiveShares(of:income:) -> [BucketShare]` | 0.1.0 |
| Moteur : répartition du mois | `allocateMonth(net, reference, fixedCharges, buckets, surplusSavingsPct): MonthAllocation` | `allocateMonth(net:reference:fixedCharges:buckets:surplusSavingsPct:) throws(EngineError) -> MonthAllocation` | 0.1.0 |
| Moteur : dépenses contre objectif | `summarizeExpenses(expenses, buckets): List<ExpenseComparison>` | `summarizeExpenses(_:buckets:) -> [ExpenseComparison]` | 0.1.0 |

Tous les montants sont des entiers en centimes (`Long` / `Int` 64 bits) ; seuls les pourcentages sont des `Double`.
Une entrée invalide lève `IllegalArgumentException` (Kotlin, `require`) ou `EngineError` (Swift, `throws(EngineError)`).
