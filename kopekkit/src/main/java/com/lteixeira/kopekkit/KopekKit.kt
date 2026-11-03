package com.lteixeira.kopekkit

/** Point d'entrée public du SDK KopekKit. */
object KopekKit {
    /**
     * Version du SDK, alignée sur la version web (package.json).
     *
     * Volontairement `val` et non `const val` : une constante serait recopiée
     * dans le bytecode de l'appli à la compilation, et l'appli afficherait
     * l'ancienne version après une mise à jour du SDK.
     */
    val version: String = "0.1.0"
}
