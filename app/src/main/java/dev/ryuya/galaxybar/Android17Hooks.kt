package dev.ryuya.galaxybar

import kotlin.jvm.JvmStatic

/* Android 17 is reviewed independently; never fall back to Android 16 on a mismatch. */
object Android17Hooks {
    const val LAUNCHER_VERSION: Long = 1800705053L
    const val TASK_SCENE_VIEW: String = "com.honeyspace.common.taskscene.TaskSceneView"
    const val TASK_SCENE_DATA: String = "com.honeyspace.common.taskscene.TaskSceneData"
    const val BUILD: String = "CP2A.260605.016.S948QOPU1BZI9"
    const val CANDIDATE_FIRMWARE: String = "S948QOPU1BZI9"

    @JvmStatic
    fun supports(
        sdk: Int,
        manufacturer: String?,
        model: String?,
        device: String?,
        display: String?,
    ): Boolean {
        // BZI9 contracts were reviewed against ROM and matching installed APKs.
        // Each feature still resolves its full runtime contract before changing behavior.
        return sdk == 37 &&
            "samsung".equals(manufacturer, ignoreCase = true) &&
            "SM-S948Q" == model &&
            "m3q" == device &&
            Android17Hooks.BUILD == display
    }
}
