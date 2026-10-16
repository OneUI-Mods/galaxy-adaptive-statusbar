package dev.ryuya.galaxybar;

import android.os.Parcel;

/** Only geometry crosses the process boundary; no application pixels or package names. */
record MotionFrame(
    long session,
    long time,
    boolean opening,
    int taskId,
    boolean interactive,
    float x,
    float y,
    float width,
    float height,
    float alpha,
    float remainingMs,
    float radius,
    boolean end) {
  static final String DESCRIPTOR = "dev.ryuya.galaxybar.Motion.v2";

  boolean valid(int screenWidth, int screenHeight, long now) {
    if (session <= 0 || time > now + 50 || now - time > 180) return false;
    if (end) return true;
    return taskId >= 0
        && Float.isFinite(x)
        && Float.isFinite(y)
        && Float.isFinite(width)
        && Float.isFinite(height)
        && Float.isFinite(alpha)
        && Float.isFinite(remainingMs)
        && Float.isFinite(radius)
        && width > 0
        && height > 0
        && width < screenWidth * 1.5f
        && height < screenHeight * 1.5f
        && Math.abs(x) < screenWidth * 2
        && Math.abs(y) < screenHeight * 2
        && alpha >= 0
        && alpha <= 1
        && radius >= 0
        && radius < screenWidth;
  }

  void write(Parcel p) {
    p.writeInterfaceToken(DESCRIPTOR);
    p.writeLong(session);
    p.writeLong(time);
    p.writeBoolean(opening);
    p.writeInt(taskId);
    p.writeBoolean(interactive);
    p.writeFloat(x);
    p.writeFloat(y);
    p.writeFloat(width);
    p.writeFloat(height);
    p.writeFloat(alpha);
    p.writeFloat(remainingMs);
    p.writeFloat(radius);
    p.writeBoolean(end);
  }

  static MotionFrame read(Parcel p) {
    p.enforceInterface(DESCRIPTOR);
    MotionFrame frame =
        new MotionFrame(
            p.readLong(),
            p.readLong(),
            p.readBoolean(),
            p.readInt(),
            p.readBoolean(),
            p.readFloat(),
            p.readFloat(),
            p.readFloat(),
            p.readFloat(),
            p.readFloat(),
            p.readFloat(),
            p.readFloat(),
            p.readBoolean());
    p.enforceNoDataAvail();
    return frame;
  }
}
