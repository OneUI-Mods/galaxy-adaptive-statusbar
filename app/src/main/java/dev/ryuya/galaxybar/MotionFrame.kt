package dev.ryuya.galaxybar

import android.os.Parcel
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.abs

/* Only geometry crosses the process boundary; no application pixels or package names. */
@JvmRecord
data class MotionFrame(
    val session: Long,
    val time: Long,
    val opening: Boolean,
    val taskId: Int,
    val interactive: Boolean,
    val overview: Boolean,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val remainingMs: Float,
    val radius: Float,
    val end: Boolean,
) {
    fun valid(screenWidth: Int, screenHeight: Int, now: Long): Boolean {
        if (session <= 0 || time > now + 50 || now - time > 180) return false
        if (end) return true
        return taskId >= 0 &&
            java.lang.Float.isFinite(x) &&
            java.lang.Float.isFinite(y) &&
            java.lang.Float.isFinite(width) &&
            java.lang.Float.isFinite(height) &&
            java.lang.Float.isFinite(alpha) &&
            java.lang.Float.isFinite(remainingMs) &&
            java.lang.Float.isFinite(radius) &&
            width > 0 &&
            height > 0 &&
            width < screenWidth * 1.5f &&
            height < screenHeight * 1.5f &&
            Math.abs(x) < screenWidth * 2 &&
            Math.abs(y) < screenHeight * 2 &&
            alpha >= 0 &&
            alpha <= 1 &&
            radius >= 0 &&
            radius < screenWidth
    }

    fun write(p: Parcel) {
        p.writeInterfaceToken(MotionFrame.DESCRIPTOR)
        p.writeLong(session)
        p.writeLong(time)
        p.writeBoolean(opening)
        p.writeInt(taskId)
        p.writeBoolean(interactive)
        p.writeBoolean(overview)
        p.writeFloat(x)
        p.writeFloat(y)
        p.writeFloat(width)
        p.writeFloat(height)
        p.writeFloat(alpha)
        p.writeFloat(remainingMs)
        p.writeFloat(radius)
        p.writeBoolean(end)
    }

    companion object {
        const val DESCRIPTOR: String = "dev.ryuya.galaxybar.Motion.v3"

        @JvmStatic
        fun read(p: Parcel): MotionFrame {
            p.enforceInterface(MotionFrame.DESCRIPTOR)
            val frame =
                MotionFrame(
                    p.readLong(),
                    p.readLong(),
                    p.readBoolean(),
                    p.readInt(),
                    p.readBoolean(),
                    p.readBoolean(),
                    p.readFloat(),
                    p.readFloat(),
                    p.readFloat(),
                    p.readFloat(),
                    p.readFloat(),
                    p.readFloat(),
                    p.readFloat(),
                    p.readBoolean(),
                )
            p.enforceNoDataAvail()
            return frame
        }
    }

    override fun toString(): String =
        "MotionFrame[session=$session, time=$time, opening=$opening, taskId=$taskId, interactive=$interactive, overview=$overview, x=$x, y=$y, width=$width, height=$height, alpha=$alpha, remainingMs=$remainingMs, radius=$radius, end=$end]"
}
