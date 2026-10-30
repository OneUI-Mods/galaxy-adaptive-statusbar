package dev.ryuya.galaxybar

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.LocalSocketAddress.Namespace
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings.Global
import android.system.Os
import android.system.OsConstants
import dev.ryuya.galaxybar.hook.HookCallback
import dev.ryuya.galaxybar.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxybar.hook.HookCallback.Unhook
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.io.IOException
import java.lang.Boolean as JavaBoolean
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.Properties
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Resumes only a root-authorized, already-unlocked session during a single SystemUI restart. */
object ReloadSession {
    private val hooks: ArrayList<Unhook?> = ArrayList<Unhook?>()
    private val main: Handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var files: Path? = null
    private var mediator: Any? = null
    private var ticket: ReloadTicket? = null
    @Volatile private var active: Boolean = false
    private var deadline: Long = 0
    private var boot: Int = 0

    @JvmStatic
    fun install(host: Context, loader: ClassLoader?) {
        if (!ReloadSession.hooks.isEmpty()) return
        try {
            // Independently checked on AZHL (16) and BZI9 (17); never borrow another SDK's profile.
            val ownerName: String? =
                when (Build.VERSION.SDK_INT) {
                    36 -> "com.android.systemui.keyguard.KeyguardViewMediator"
                    37 -> "com.android.systemui.keyguard.KeyguardViewMediator"
                    else -> throw UnsupportedOperationException("Unreviewed keyguard version")
                }
            val owner = Reflect.findClass(ownerName!!, loader)
            if (
                owner!!
                    .getDeclaredMethod(
                        "doKeyguardLocked",
                        Bundle::class.java,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    )
                    .getReturnType() != Boolean::class.javaPrimitiveType
            )
                throw NoSuchMethodException("Keyguard return type")
            owner!!.getDeclaredMethod("onSystemReady")
            owner!!.getDeclaredMethod("onStartedGoingToSleep", Int::class.javaPrimitiveType)
            owner!!.getDeclaredMethod("onKeyguardExitFinished", String::class.java)
            owner!!.getDeclaredMethod("showKeyguard$1", Bundle::class.java)
            owner!!.getDeclaredField("mKeyguardViewControllerLazy")
            owner!!.getDeclaredField("mSystemReady")
            owner!!.getDeclaredField("mKeyguardInteractor")
            val interactor =
                Reflect.findClass(
                    "com.android.systemui.keyguard.domain.interactor.KeyguardInteractor",
                    loader,
                )
            interactor!!.getDeclaredMethod("dismissKeyguard")
            interactor!!.getDeclaredField("keyguardTransitionInteractor")
            Reflect.findClass(
                    "com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor",
                    loader,
                )!!
                .getDeclaredField("currentKeyguardState")
            Reflect.findClass("com.android.keyguard.KeyguardViewController", loader)!!
                .getDeclaredMethod(
                    "hide",
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                )
            for (field in
                arrayOf<String>(
                    "mShowing",
                    "mPM",
                    "mSelectedUserInteractor",
                    "mUpdateMonitor",
                )) owner!!.getDeclaredField(field)
            val monitor = Reflect.findClass("com.android.keyguard.KeyguardUpdateMonitor", loader)
            monitor!!.getDeclaredMethod("isForcedLock")
            monitor!!.getDeclaredMethod("isSimPinSecure")
            monitor!!.getDeclaredMethod("isUserInLockdown", Int::class.javaPrimitiveType)
            Reflect.findClass(
                    "com.android.systemui.user.domain.interactor.SelectedUserInteractor",
                    loader,
                )!!
                .getDeclaredMethod("getSelectedUserId")
            ReloadSession.context = host
            ReloadSession.files = host.createDeviceProtectedStorageContext().getFilesDir().toPath()
            ReloadSession.boot = Global.getInt(host.getContentResolver(), Global.BOOT_COUNT, -1)
            ReloadSession.hooks.add(
                Reflect.findAndHookMethod(
                    owner,
                    "onSystemReady",
                    object : HookCallback() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            try {
                                ReloadSession.consume(p.thisObject)
                            } catch (error: Throwable) {
                                ReloadSession.stop(error)
                            }
                        }

                        override fun afterHookedMethod(p: MethodHookParam) {
                            if (p.hasThrowable()) {
                                ReloadSession.finish(true)
                                return
                            }
                            if (!ReloadSession.active) return
                            ReloadSession.main.post(Runnable { ReloadSession.resumeWhenReady() })
                        }
                    },
                )
            )
            ReloadSession.hooks.add(
                Reflect.findAndHookMethod(
                    owner,
                    "doKeyguardLocked",
                    Bundle::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    object : HookCallback() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            if (!ReloadSession.active || p.thisObject !== ReloadSession.mediator)
                                return
                            try {
                                // Explicit lock requests and forced authentication always keep the
                                // native path.
                                if (p!!.args!![0] != null || !ReloadSession.eligible()) {
                                    ReloadSession.finish(true)
                                    return
                                }
                                p.setResult(false)
                            } catch (error: Throwable) {
                                ReloadSession.stop(error)
                            }
                        }
                    },
                )
            )
            ReloadSession.hooks.add(
                Reflect.findAndHookMethod(
                    owner,
                    "onStartedGoingToSleep",
                    Int::class.javaPrimitiveType,
                    object : HookCallback() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            if (ReloadSession.active) ReloadSession.finish(true)
                        }
                    },
                )
            )
            val support = Properties()
            support.setProperty("protocol", ReloadTicket.PROTOCOL.toString())
            support.setProperty("pid", Process.myPid().toString())
            support.setProperty("boot", ReloadSession.boot.toString())
            support.setProperty("sdk", Build.VERSION.SDK_INT.toString())
            ReloadSession.write(ReloadTicket.SUPPORT, support)
        } catch (error: Throwable) {
            ReloadSession.stop(error)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun consume(owner: Any?) {
        val request = ReloadSession.files!!.resolve(ReloadTicket.REQUEST)
        if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)) return
        var properties: Properties?
        try {
            val stat = Os.lstat(request.toString())
            if (
                !OsConstants.S_ISREG(stat.st_mode) ||
                    stat.st_uid != Process.myUid() ||
                    (stat.st_mode and 63) != 0 ||
                    stat.st_size > 2048
            )
                return
            properties = ReloadSession.read(request)
        } finally {
            // Consume before using it, including rejected, expired, and interrupted requests.
            Files.deleteIfExists(request)
        }
        val candidate = ReloadTicket.parse(properties)
        val user =
            (Reflect.callMethod(
                    Reflect.getObjectField(owner!!, "mSelectedUserInteractor")!!,
                    "getSelectedUserId",
                ) as Number)
                .toInt()
        val power: PowerManager? = Reflect.getObjectField(owner!!, "mPM") as PowerManager?
        if (
            candidate == null ||
                !candidate.accepts(
                    ReloadSession.boot,
                    user,
                    SystemClock.uptimeMillis(),
                    power!!.isInteractive(),
                    ReloadSession.context!!.getSystemService<UserManager?>(UserManager::class.java)
                        .isUserUnlocked(),
                )
        )
            return
        // The helper must still be alive and watching the screen through the restart gap.
        // A stale file alone can never authorize a later, unrelated SystemUI start.
        try {
            LocalSocket().use { socket ->
                socket.connect(
                    LocalSocketAddress(
                        "galaxy-reload-" + candidate.nonce,
                        Namespace.ABSTRACT,
                    )
                )
                socket.setSoTimeout(1000)
                if (socket.getPeerCredentials().getUid() != 0) return
                socket
                    .getOutputStream()
                    .write((candidate.nonce + "\n").toByteArray(StandardCharsets.US_ASCII))
                if (
                    "ALLOW\n" !=
                        String(socket.getInputStream().readNBytes(6), StandardCharsets.US_ASCII)
                )
                    return
            }
        } catch (noLiveHelper: IOException) {
            return
        }
        ReloadSession.mediator = owner
        ReloadSession.ticket = candidate
        ReloadSession.deadline = SystemClock.uptimeMillis() + 5000
        ReloadSession.active = true
        if (!ReloadSession.eligible()) {
            ReloadSession.finish(true)
            return
        }
        val result = candidate.properties()
        result.setProperty("phase", "accepted")
        result.setProperty("pid", Process.myPid().toString())
        ReloadSession.write(ReloadTicket.RESULT, result)
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun eligible(): Boolean {
        if (
            !ReloadSession.active ||
                ReloadSession.mediator == null ||
                SystemClock.uptimeMillis() > ReloadSession.deadline
        )
            return false
        if (
            !(Reflect.getObjectField(ReloadSession.mediator!!, "mPM") as PowerManager)
                .isInteractive()
        )
            return false
        val user =
            (Reflect.callMethod(
                    Reflect.getObjectField(ReloadSession.mediator!!, "mSelectedUserInteractor")!!,
                    "getSelectedUserId",
                ) as Number)
                .toInt()
        if (user != ReloadSession.ticket!!.user) return false
        val monitor = Reflect.getObjectField(ReloadSession.mediator!!, "mUpdateMonitor")
        if (
            JavaBoolean.TRUE == Reflect.callMethod(monitor!!, "isUserInLockdown", user) ||
                JavaBoolean.TRUE == Reflect.callMethod(monitor!!, "isForcedLock") ||
                JavaBoolean.TRUE == Reflect.callMethod(monitor!!, "isSimPinSecure")
        )
            return false
        val cancel = ReloadSession.files!!.resolve(ReloadTicket.CANCEL)
        return !Files.exists(cancel) ||
            !ReloadSession.ticket!!.nonce.equals(ReloadSession.read(cancel).getProperty("nonce"))
    }

    @JvmStatic
    private fun observeCompletion() {
        if (!ReloadSession.active) return
        try {
            if (!ReloadSession.eligible()) {
                ReloadSession.finish(true)
                return
            }
            if (
                !Reflect.getBooleanField(ReloadSession.mediator!!, "mShowing") &&
                    "GONE" == ReloadSession.keyguardState()
            ) {
                val result = ReloadSession.ticket!!.properties()
                result.setProperty("phase", "resumed")
                result.setProperty("pid", Process.myPid().toString())
                ReloadSession.write(ReloadTicket.RESULT, result)
                // No persistent exemption: subsequent lock requests use the unmodified native
                // logic.
                ReloadSession.finish(false)
            } else
                ReloadSession.main.postDelayed(
                    Runnable { ReloadSession.observeCompletion() },
                    100,
                )
        } catch (error: Throwable) {
            ReloadSession.stop(error)
        }
    }

    @JvmStatic
    private fun resumeWhenReady() {
        if (!ReloadSession.active) return
        try {
            if (!ReloadSession.eligible()) {
                ReloadSession.finish(true)
                return
            }
            // onSystemReady only queues work. Wait for the native startup transition before
            // dismissing;
            // an earlier exit can be overwritten by LOCKSCREEN and leave the status bar hidden.
            if (
                !Reflect.getBooleanField(ReloadSession.mediator!!, "mSystemReady") ||
                    "LOCKSCREEN" != ReloadSession.keyguardState()
            ) {
                ReloadSession.main.postDelayed(
                    Runnable { ReloadSession.resumeWhenReady() },
                    50,
                )
                return
            }
            val controller =
                Reflect.callMethod(
                    Reflect.getObjectField(
                        ReloadSession.mediator!!,
                        "mKeyguardViewControllerLazy",
                    )!!,
                    "get",
                )
            Reflect.callMethod(controller!!, "hide", SystemClock.uptimeMillis(), 0L)
            Reflect.callMethod(
                ReloadSession.mediator!!,
                "onKeyguardExitFinished",
                "Galaxy Reload resume",
            )
            ReloadSession.main.postDelayed(
                Runnable { ReloadSession.observeCompletion() },
                100,
            )
        } catch (error: Throwable) {
            ReloadSession.stop(error)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun keyguardState(): String? {
        val interactor = Reflect.getObjectField(ReloadSession.mediator!!, "mKeyguardInteractor")
        val transition = Reflect.getObjectField(interactor!!, "keyguardTransitionInteractor")
        val flow = Reflect.getObjectField(transition!!, "currentKeyguardState")
        return (Reflect.callMethod(flow!!, "getValue") as Enum<*>).name
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun read(path: Path?): Properties {
        require(Files.size(path) <= 2048) { "Oversized reload record" }
        val result = Properties()
        Files.newInputStream(path).use { input ->
            result.load(input)
        }
        return result
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun write(name: String?, properties: Properties) {
        val temporary = ReloadSession.files!!.resolve(name + ".tmp")
        Files.newOutputStream(temporary).use { output ->
            properties.store(output, "Galaxy Reload")
        }
        Os.chmod(temporary.toString(), 384)
        Files.move(
            temporary,
            ReloadSession.files!!.resolve(name),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    @JvmStatic
    private fun finish(showNativeLock: Boolean) {
        if (!ReloadSession.active) return
        ReloadSession.active = false
        ReloadSession.main.removeCallbacksAndMessages(null)
        val owner = ReloadSession.mediator
        if (showNativeLock && owner != null)
            ReloadSession.main.post(
                Runnable {
                    try {
                        Reflect.callMethod(owner, "showKeyguard$1", null as Any?)
                    } catch (error: Throwable) {
                        Hooks.log(error)
                    }
                }
            )
    }

    @JvmStatic
    private fun stop(error: Throwable?) {
        ReloadSession.finish(true)
        for (hook in ReloadSession.hooks) try {
            hook!!.unhook()
        } catch (ignored: Throwable) {}
        ReloadSession.hooks.clear()
        if (ReloadSession.files != null)
            try {
                Files.deleteIfExists(ReloadSession.files!!.resolve(ReloadTicket.SUPPORT))
            } catch (ignored: Exception) {}
        Hooks.log("GalaxyBar: optional reload session unavailable: " + error)
    }

    @JvmStatic
    fun reset() {
        ReloadSession.stop(IllegalStateException("Module hooks stopped"))
    }
}
