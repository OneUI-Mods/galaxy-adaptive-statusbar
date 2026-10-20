package dev.ryuya.galaxybar;

/** Frozen Android 16 admission rules; hook sources are recorded in docs/compatibility. */
final class Android16Hooks {
  static final long LAUNCHER_VERSION = 1750305027L;
  static final String TASK_SCENE_VIEW = "com.honeyspace.ui.common.taskScene.TaskSceneView";
  static final String TASK_SCENE_DATA = "com.honeyspace.ui.common.taskScene.TaskSceneData";
  static final String BUILD = "BP4A.251205.006.S948QOPS1AZHL";

  static boolean supports(
      int sdk, String manufacturer, String model, String device, String display) {
    return sdk == 36 && "m3q".equals(device) && BUILD.equals(display);
  }
}
