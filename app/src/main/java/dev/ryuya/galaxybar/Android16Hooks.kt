package dev.ryuya.galaxybar

import kotlin.jvm.JvmStatic

/* Frozen Android 16 admission rules; hook sources are recorded in docs/compatibility. */
object Android16Hooks {
    const val LAUNCHER_VERSION: Long = 1750305027L
    const val TASK_SCENE_VIEW: String = "com.honeyspace.ui.common.taskScene.TaskSceneView"
    const val TASK_SCENE_DATA: String = "com.honeyspace.ui.common.taskScene.TaskSceneData"
    const val BUILD: String = "BP4A.251205.006.S948QOPS1AZHL"

    @JvmStatic
    fun supports(
        sdk: Int,
        manufacturer: String?,
        model: String?,
        device: String?,
        display: String?,
    ): Boolean {
        return sdk == 36 && "m3q" == device && Android16Hooks.BUILD == display
    }
}
