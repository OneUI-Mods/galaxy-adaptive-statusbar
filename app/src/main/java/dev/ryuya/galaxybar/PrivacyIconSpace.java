package dev.ryuya.galaxybar;

import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;

/** One owner for icon translation while the large chip hands over to the persistent dot. */
final class PrivacyIconSpace {
  final View group;
  private final float baseline;
  private final ArrayList<Clipping> clipping = new ArrayList<>();
  private float applied, chipShift, dotShift;

  PrivacyIconSpace(View group, View window) {
    this.group = group;
    baseline = applied = group.getTranslationX();
    for (var parent = group.getParent();
        parent instanceof ViewGroup container && container != window;
        parent = container.getParent()) {
      clipping.add(
          new Clipping(container, container.getClipChildren(), container.getClipToPadding()));
      container.setClipChildren(false);
      container.setClipToPadding(false);
    }
  }

  float baselineLeft() {
    int[] at = new int[2];
    group.getLocationOnScreen(at);
    return at[0] - group.getTranslationX() + baseline;
  }

  void chip(float shift) {
    chipShift = shift;
    apply();
  }

  void dot(float shift) {
    dotShift = shift;
    apply();
  }

  private void apply() {
    applied = baseline + PrivacySlideGeometry.combinedShift(chipShift, dotShift);
    group.setTranslationX(applied);
  }

  boolean idle() {
    return chipShift == 0 && dotShift == 0;
  }

  void close() {
    if (Math.abs(group.getTranslationX() - applied) < .01f) group.setTranslationX(baseline);
    for (Clipping item : clipping) {
      item.parent.setClipChildren(item.children);
      item.parent.setClipToPadding(item.padding);
    }
    clipping.clear();
  }

  private record Clipping(ViewGroup parent, boolean children, boolean padding) {}
}
