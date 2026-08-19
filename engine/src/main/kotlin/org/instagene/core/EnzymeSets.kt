package org.instagene.core

data class EnzymeSetDefinition(
    val name: String,
    val enzymeNames: List<String>,
    val description: String = "",
)

/** Built-in and generated restriction-enzyme sets, including supplier and cutter groupings. */
object EnzymeSetCatalog {
    val COMMON_CLONING = EnzymeSetDefinition(
        "Common cloning",
        listOf("BamHI", "BglII", "EcoRI", "EcoRV", "HindIII", "KpnI", "NcoI", "NdeI", "NheI", "NotI", "PstI", "SalI", "SmaI", "SpeI", "XbaI", "XhoI"),
        "Frequently used enzymes for routine plasmid construction.",
    )
    val RARE_CUTTERS = EnzymeSetDefinition(
        "Rare cutters",
        Enzymes.ALL.filter { it.siteLength >= 8 }.map { it.name },
        "Recognition sites of eight or more bases.",
    )
    val BLUNT_CUTTERS = EnzymeSetDefinition(
        "Blunt cutters",
        Enzymes.ALL.filter { it.endType == EndType.BLUNT }.map { it.name },
        "Enzymes that leave blunt ends.",
    )
    val FIVE_PRIME_CUTTERS = EnzymeSetDefinition(
        "5' overhang cutters",
        Enzymes.ALL.filter { it.endType == EndType.FIVE_PRIME_OVERHANG }.map { it.name },
        "Enzymes that leave 5' cohesive ends.",
    )
    val PREDEFINED = listOf(COMMON_CLONING, RARE_CUTTERS, BLUNT_CUTTERS, FIVE_PRIME_CUTTERS)
}
