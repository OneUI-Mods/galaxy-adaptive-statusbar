package dev.ryuya.galaxybar.compat

internal object DexKitConfig {
    const val SCHEMA = 1
    const val MODULE_ID = "dev.ryuya.galaxybar"
    val MODULE_VERSION
        get() = dev.ryuya.galaxybar.BuildConfig.VERSION_CODE

    const val PROFILE_HASH = "872560b2008b7cd63520f6b95bb5ab84caba07b3d3a7ade22b20f2e0efd25c8a"
    val TARGETS = setOf("com.android.systemui", "com.sec.android.app.launcher")
    val HOSTS = setOf("com.sec.android.app.launcher", "com.android.systemui")
    val CLASSES_BY_TARGET =
        mapOf(
            "com.sec.android.app.launcher" to
                listOf(
                    "androidx.compose.runtime.State",
                    "androidx.compose.ui.graphics.drawscope.DrawScope",
                    "androidx.compose.ui.platform.AndroidComposeView",
                    "androidx.compose.ui.platform.ComposeView",
                    "com.android.systemui.plugins.DarkIconDispatcher",
                    "com.android.systemui.plugins.DarkIconDispatcher\$DarkReceiver",
                    "com.honeyspace.common.taskscene.SceneStateInfo",
                    "com.honeyspace.common.taskscene.TaskSceneData",
                    "com.honeyspace.common.taskscene.TaskSceneView",
                    "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager",
                    "com.honeyspace.sdk.AppScreen\$Normal",
                    "com.honeyspace.sdk.HomeScreen\$Normal",
                    "com.honeyspace.sdk.transition.TransitionTargets",
                    "com.honeyspace.transition.anim.floating.PlayerImpl",
                    "com.honeyspace.transition.anim.floating.animator.ValueAnimPlayer",
                    "com.honeyspace.transition.anim.floating.calculator.ValueBaseCalculator",
                    "com.honeyspace.transition.anim.floating.entity.DesktopInputData",
                    "com.honeyspace.transition.anim.floating.entity.FloatingInputData",
                    "com.honeyspace.transition.anim.floating.utils.RectFSpringRelayer",
                    "com.honeyspace.transition.anim.floating.utils.UpdateInfo",
                    "com.honeyspace.transition.data.AppTransitionParams",
                    "com.honeyspace.transition.data.open.OpenTransitionParams",
                    "com.honeyspace.transition.utils.SurfaceTransaction",
                    "com.honeyspace.transition.utils.SurfaceTransactionApplier",
                    "com.honeyspace.transition.utils.TaskLaunchTransition",
                    "com.honeyspace.ui.common.taskScene.SceneStateInfo",
                    "com.honeyspace.ui.common.taskScene.TaskSceneData",
                    "com.honeyspace.ui.common.taskScene.TaskSceneView",
                    "kotlinx.coroutines.DelayKt",
                ),
            "com.android.systemui" to
                listOf(
                    "androidx.compose.runtime.State",
                    "androidx.compose.ui.graphics.drawscope.DrawScope",
                    "androidx.compose.ui.platform.AndroidComposeView",
                    "androidx.compose.ui.platform.ComposeView",
                    "androidx.core.animation.Animator",
                    "com.android.keyguard.KeyguardUpdateMonitor",
                    "com.android.keyguard.KeyguardViewController",
                    "com.android.systemui.Dependency",
                    "com.android.systemui.animation.view.LaunchableFrameLayout",
                    "com.android.systemui.battery.BatteryLayoutState",
                    "com.android.systemui.battery.BatteryMeterView",
                    "com.android.systemui.battery.SamsungBatteryMeterDrawable",
                    "com.android.systemui.battery.SamsungBatteryState",
                    "com.android.systemui.battery.view.SamsungBatteryComposeViewKt\$\$ExternalSyntheticLambda0",
                    "com.android.systemui.keyguard.KeyguardViewMediator",
                    "com.android.systemui.keyguard.domain.interactor.KeyguardInteractor",
                    "com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor",
                    "com.android.systemui.plugins.DarkIconDispatcher",
                    "com.android.systemui.plugins.DarkIconDispatcher\$DarkReceiver",
                    "com.android.systemui.privacy.ui.view.ComposeOngoingPrivacyChip",
                    "com.android.systemui.statusbar.data.repository.StatusBarModePerDisplayRepositoryImpl",
                    "com.android.systemui.statusbar.data.repository.StatusBarModePerDisplayRepositoryImpl\$commandQueueCallback\$1",
                    "com.android.systemui.statusbar.events.PrivacyDotViewControllerImpl",
                    "com.android.systemui.statusbar.events.ViewState",
                    "com.android.systemui.statusbar.phone.SwitchableDoubleShadowTextView",
                    "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView",
                    "com.android.systemui.statusbar.policy.NetspeedView",
                    "com.android.systemui.statusbar.policy.QSClockIndicatorView",
                    "com.android.systemui.user.domain.interactor.SelectedUserInteractor",
                    "dagger.Lazy",
                    "kotlinx.coroutines.DelayKt",
                ),
        )
    val KNOWN_VERSIONS =
        mapOf(
            "com.sec.android.app.launcher" to setOf(1750305027L, 1800705053L),
            "com.android.systemui" to setOf(36L, 37L),
        )
    val PROFILE_PACKAGES =
        mapOf(
            36 to setOf("com.android.systemui", "com.sec.android.app.launcher"),
            37 to setOf("com.android.systemui", "com.sec.android.app.launcher"),
        )
}
