package dev.ryuya.galaxybar

import kotlin.jvm.JvmStatic

/* Compound status indicators own their background, foreground colors and internal clipping. */
object StatusBarElements {
    @JvmStatic
    fun isDedicatedComposeBattery(
        sdk: Int,
        className: String,
        parentResource: String,
        nativeBatterySibling: Boolean,
    ): Boolean {
        return sdk == 37 &&
            className == "androidx.compose.ui.platform.ComposeView" &&
            parentResource == "system_icons" &&
            nativeBatterySibling
    }

    @JvmStatic
    fun isNetworkSpeedText(
        sdk: Int,
        className: String,
        parentClass: String,
        resourceName: String,
    ): Boolean {
        return sdk == 37 &&
            className == "com.android.systemui.statusbar.phone.SwitchableDoubleShadowTextView" &&
            parentClass == "com.android.systemui.statusbar.policy.NetspeedView" &&
            resourceName == "network_speed_contentview"
    }

    @JvmStatic
    fun isCompoundIndicator(className: String, resourceName: String): Boolean {
        return className.contains("OngoingChip") ||
            resourceName.contains("capsule") ||
            resourceName == "samsung_ongoing_cast_chip"
    }
}
