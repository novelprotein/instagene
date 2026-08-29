package org.instagene.app.gui.tool

import org.instagene.core.Feature

enum class FeatureLabelMode(val displayName: String) {
    ALL("All features"),
    VISIBLE("Visible features"),
}

data class FeatureLabelChoice(
    val id: String,
    val displayName: String,
    val mode: FeatureLabelMode,
    val featureType: String? = null,
)

internal object FeatureLabelOptions {
    fun choices(features: List<Feature>): List<FeatureLabelChoice> {
        val types = features.map { it.type.trim() }.filter { it.isNotEmpty() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        return buildList {
            add(FeatureLabelChoice("all", FeatureLabelMode.ALL.displayName, FeatureLabelMode.ALL))
            add(FeatureLabelChoice("visible", FeatureLabelMode.VISIBLE.displayName, FeatureLabelMode.VISIBLE))
            types.forEach { type -> add(FeatureLabelChoice("type:$type", type, FeatureLabelMode.ALL, type)) }
        }
    }

    fun text(feature: Feature): String = feature.name.trim().ifEmpty {
        feature.qualifiers["label"]?.firstOrNull()?.trim().orEmpty().ifEmpty {
            feature.qualifiers["gene"]?.firstOrNull()?.trim().orEmpty().ifEmpty {
                feature.type.trim().ifEmpty { "feature" }
            }
        }
    }

    fun include(feature: Feature, choice: FeatureLabelChoice): Boolean = when (choice.mode) {
        FeatureLabelMode.ALL -> choice.featureType == null || feature.type.trim().equals(choice.featureType, ignoreCase = true)
        FeatureLabelMode.VISIBLE -> feature.visible
    }

    fun include(feature: Feature, mode: FeatureLabelMode): Boolean =
        include(feature, FeatureLabelChoice(mode.name.lowercase(), mode.displayName, mode))
}
