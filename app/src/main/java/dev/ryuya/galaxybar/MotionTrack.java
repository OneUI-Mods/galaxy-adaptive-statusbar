package dev.ryuya.galaxybar;

/** Keeps one visible handoff across native animator replacements and rejects delayed callbacks. */
final class MotionTrack {
  static final int END_GRACE_MS = 48;

  enum Change {
    IGNORE,
    START,
    CONTINUE,
    END
  }

  private MotionFrame current;
  private long sealedSession;
  private long endAt = -1;

  Change accept(MotionFrame frame, int width, int height, long now) {
    if (!frame.valid(width, height, now) || frame.session() <= sealedSession) return Change.IGNORE;
    if (current != null
        && (frame.session() < current.session()
            || (frame.session() == current.session() && frame.time() < current.time()))) {
      return Change.IGNORE;
    }
    if (frame.end()) {
      sealedSession = frame.session();
      // Cancel/end is commonly followed by a replacement animator on the next frame.
      // Keep the current drawing briefly so that the real bar never flashes in between.
      if (current != null) endAt = now + END_GRACE_MS;
      return Change.END;
    }
    boolean continuous = current != null && frame.session() == current.session();
    if (!continuous && current != null) {
      continuous =
          frame.taskId() == current.taskId()
              && frame.time() >= current.time()
              && frame.time() - current.time() <= 180
              && Math.abs(frame.x() - current.x()) < width * .12f
              && Math.abs(frame.y() - current.y()) < height * .12f
              && Math.abs(frame.width() - current.width()) < width * .12f
              && Math.abs(frame.height() - current.height()) < height * .12f;
    }
    current = frame;
    endAt = -1;
    return continuous ? Change.CONTINUE : Change.START;
  }

  MotionFrame current() {
    return current;
  }

  boolean endDue(long now) {
    return endAt >= 0 && now >= endAt;
  }

  void clear() {
    if (current != null) sealedSession = Math.max(sealedSession, current.session());
    current = null;
    endAt = -1;
  }

  static MotionFrame newer(MotionFrame a, MotionFrame b) {
    if (a == null || b.session() > a.session()) return b;
    if (b.session() < a.session() || b.time() < a.time()) return a;
    if (a.end() && b.session() == a.session()) return a;
    return b;
  }
}
