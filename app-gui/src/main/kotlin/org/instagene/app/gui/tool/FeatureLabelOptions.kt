package org.instagene.app.gui.tool

import org.instagene.core.Feature

enum class FeatureLabelMode(val displayName: String) {
    ALL("All features"),
    VISIBLE("Visible features"),
}

internal object FeatureLabelOptions {
    fun text(feature: Feature): String = feature.name.trim().ifEmpty {
        feature.qualifiers["label"]?.firstOrNull()?.trim().orEmpty().ifEmpty {
            feature.qualifiers["gene"]?.firstOrNull()?.trim().orEmpty().ifEmpty {
                feature.type.trim().ifEmpty { "feature" }
            }
        }
    }

    fun include(feature: Feature, mode: FeatureLabelMode): Boolean =
        mode == FeatureLabelMode.ALL || feature.visible
}
