package dev.ryuya.galaxybar

/** Preference values are stable across app updates and unknown values retain the old behavior. */
internal enum class ColorGrouping(val preference: String) {
    CHARACTERS("characters"),
    PARTS("parts");

    companion object {
        const val KEY = "color_grouping"

        fun fromPreference(value: String?): ColorGrouping =
            entries.firstOrNull { it.preference == value } ?: CHARACTERS
    }
}
