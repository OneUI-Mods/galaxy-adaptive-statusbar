package dev.ryuya.galaxybar;

/** Selects a single version profile; unsupported versions never inherit another profile. */
final class HookCompatibility {
  enum Platform {
    ANDROID_16,
    ANDROID_17,
    UNSUPPORTED
  }

  @androidx.annotation.ChecksSdkIntAtLeast(api = 36)
  static boolean isCurrentSupported() {
    return supports(
        android.os.Build.VERSION.SDK_INT,
        android.os.Build.MANUFACTURER,
        android.os.Build.MODEL,
        android.os.Build.DEVICE,
        android.os.Build.DISPLAY);
  }

  static boolean matchesLauncherVersion(int sdk, long version) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> version == Android16Hooks.LAUNCHER_VERSION;
      case ANDROID_17 -> version == Android17Hooks.LAUNCHER_VERSION;
      case UNSUPPORTED -> false;
    };
  }

  static String taskSceneView(int sdk) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> Android16Hooks.TASK_SCENE_VIEW;
      case ANDROID_17 -> Android17Hooks.TASK_SCENE_VIEW;
      case UNSUPPORTED -> throw new UnsupportedOperationException("Unreviewed task scene platform");
    };
  }

  static String taskSceneData(int sdk) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> Android16Hooks.TASK_SCENE_DATA;
      case ANDROID_17 -> Android17Hooks.TASK_SCENE_DATA;
      case UNSUPPORTED -> throw new UnsupportedOperationException("Unreviewed task scene platform");
    };
  }

  static String[] playerFinishMethods(int sdk) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> new String[] {"end", "finish"};
      // Android 17 removed end(); finish still consumes the native completion callback.
      case ANDROID_17 -> new String[] {"finish"};
      case UNSUPPORTED -> throw new UnsupportedOperationException("Unreviewed animation platform");
    };
  }

  static Platform platform(int sdk) {
    return switch (sdk) {
      case 36 -> Platform.ANDROID_16;
      case 37 -> Platform.ANDROID_17;
      default -> Platform.UNSUPPORTED;
    };
  }

  static boolean supports(
      int sdk, String manufacturer, String model, String device, String display) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> Android16Hooks.supports(sdk, manufacturer, model, device, display);
      case ANDROID_17 -> Android17Hooks.supports(sdk, manufacturer, model, device, display);
      case UNSUPPORTED -> false;
    };
  }
}
