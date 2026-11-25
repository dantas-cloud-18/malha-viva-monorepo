package br.malhaviva.feature.auth

// stand-by: decisão do produto; reativar trocando para true
internal const val GENDER_ENABLED = false

/** Genero opcional e autodeclarado; `null` (nao informado) e diferente de [PreferNotToSay]. So estatistica agregada. */
enum class Gender(
    val wire: String,
) {
    Male("male"),
    Female("female"),
    NonBinary("non_binary"),
    PreferNotToSay("prefer_not_to_say"),
    ;

    companion object {
        fun fromWire(value: String?): Gender? = entries.firstOrNull { it.wire == value }
    }
}
