package dev.ryuya.galaxybar

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import kotlin.jvm.JvmStatic

/* Selects a single version profile; unsupported versions never inherit another profile. */
object HookCompatibility {
    @JvmStatic
    @ChecksSdkIntAtLeast(api = 36)
    fun isCurrentSupported(): Boolean {
        return HookCompatibility.supports(
            Build.VERSION.SDK_INT,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
            Build.DISPLAY,
        )
    }

    @JvmStatic
    fun matchesLauncherVersion(sdk: Int, version: Long): Boolean {
        return when (HookCompatibility.platform(sdk)) {
            HookCompatibility.Platform.ANDROID_16 -> version == Android16Hooks.LAUNCHER_VERSION
            HookCompatibility.Platform.ANDROID_17 -> version == Android17Hooks.LAUNCHER_VERSION
            HookCompatibility.Platform.UNSUPPORTED -> false
        }
    }

    @JvmStatic
    fun taskSceneView(sdk: Int): String? {
        return when (HookCompatibility.platform(sdk)) {
            HookCompatibility.Platform.ANDROID_16 -> Android16Hooks.TASK_SCENE_VIEW
            HookCompatibility.Platform.ANDROID_17 -> Android17Hooks.TASK_SCENE_VIEW
            HookCompatibility.Platform.UNSUPPORTED ->
                throw UnsupportedOperationException("Unreviewed task scene platform")
        }
    }

    @JvmStatic
    fun taskSceneData(sdk: Int): String? {
        return when (HookCompatibility.platform(sdk)) {
            HookCompatibility.Platform.ANDROID_16 -> Android16Hooks.TASK_SCENE_DATA
            HookCompatibility.Platform.ANDROID_17 -> Android17Hooks.TASK_SCENE_DATA
            HookCompatibility.Platform.UNSUPPORTED ->
                throw UnsupportedOperationException("Unreviewed task scene platform")
        }
    }

    @JvmStatic
    fun playerFinishMethods(sdk: Int): Array<String> {
        return when (HookCompatibility.platform(sdk)) {
            HookCompatibility.Platform.ANDROID_16 -> arrayOf<String>("end", "finish")
            HookCompatibility.Platform.ANDROID_17 -> arrayOf<String>("finish")
            HookCompatibility.Platform.UNSUPPORTED ->
                throw UnsupportedOperationException("Unreviewed animation platform")
        }
    }

    @JvmStatic
    fun platform(sdk: Int): HookCompatibility.Platform {
        return when (sdk) {
            36 -> HookCompatibility.Platform.ANDROID_16
            37 -> HookCompatibility.Platform.ANDROID_17
            else -> HookCompatibility.Platform.UNSUPPORTED
        }
    }

    @JvmStatic
    fun supports(
        sdk: Int,
        manufacturer: String?,
        model: String?,
        device: String?,
        display: String?,
    ): Boolean {
        return when (HookCompatibility.platform(sdk)) {
            HookCompatibility.Platform.ANDROID_16 ->
                Android16Hooks.supports(sdk, manufacturer, model, device, display)
            HookCompatibility.Platform.ANDROID_17 ->
                Android17Hooks.supports(sdk, manufacturer, model, device, display)
            HookCompatibility.Platform.UNSUPPORTED -> false
        }
    }

    enum class Platform {
        ANDROID_16,
        ANDROID_17,
        UNSUPPORTED,
    }
}
