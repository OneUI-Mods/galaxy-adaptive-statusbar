package dev.ryuya.galaxybar;

/** Compound status indicators own their background, foreground colors and internal clipping. */
final class StatusBarElements {
  static boolean isCompoundIndicator(String className, String resourceName) {
    return className.contains("OngoingChip")
        || resourceName.contains("capsule")
        || resourceName.equals("samsung_ongoing_cast_chip");
  }
}
