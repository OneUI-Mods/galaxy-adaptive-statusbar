package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

open class HookCompatibilityTest {
    @Test
    open fun knownAndroid16StillUsesItsOriginalProfile() {
        assertEquals(HookCompatibility.Platform.ANDROID_16, HookCompatibility.platform(36))
        assertTrue(
            HookCompatibility.supports(36, "samsung", "SM-S948Q", "m3q", Android16Hooks.BUILD)
        )
    }

    @Test
    open fun android17DoesNotReuseTheAndroid16Allowlist() {
        assertEquals(HookCompatibility.Platform.ANDROID_17, HookCompatibility.platform(37))
        assertFalse(
            HookCompatibility.supports(37, "samsung", "SM-S948Q", "m3q", Android16Hooks.BUILD)
        )
        assertFalse(
            HookCompatibility.supports(
                37,
                "samsung",
                "SM-S948Q",
                "m3q",
                Android17Hooks.CANDIDATE_FIRMWARE,
            )
        )
        assertTrue(
            HookCompatibility.supports(37, "samsung", "SM-S948Q", "m3q", Android17Hooks.BUILD)
        )
    }

    @Test
    open fun android17AdmissionRejectsOtherHardwareAndBuilds() {
        assertFalse(
            HookCompatibility.supports(37, "other", "SM-S948Q", "m3q", Android17Hooks.BUILD)
        )
        assertFalse(HookCompatibility.supports(37, "samsung", "other", "m3q", Android17Hooks.BUILD))
        assertFalse(
            HookCompatibility.supports(37, "samsung", "SM-S948Q", "other", Android17Hooks.BUILD)
        )
        assertFalse(HookCompatibility.supports(37, "samsung", "SM-S948Q", "m3q", "other-firmware"))
        assertFalse(
            HookCompatibility.supports(36, "samsung", "SM-S948Q", "m3q", Android17Hooks.BUILD)
        )
    }

    @Test
    open fun futureAndOlderVersionsAreNotImplicitlyEnabled() {
        for (sdk in intArrayOf(35, 38, 100)) {
            assertEquals(HookCompatibility.Platform.UNSUPPORTED, HookCompatibility.platform(sdk))
            assertFalse(
                HookCompatibility.supports(sdk, "samsung", "SM-S948Q", "m3q", Android16Hooks.BUILD)
            )
        }
    }

    @Test
    open fun launcherArtifactsNeverCrossMatchVersions() {
        assertTrue(HookCompatibility.matchesLauncherVersion(36, 1750305027L))
        assertTrue(HookCompatibility.matchesLauncherVersion(37, 1800705053L))
        assertFalse(HookCompatibility.matchesLauncherVersion(36, 1800705053L))
        assertFalse(HookCompatibility.matchesLauncherVersion(37, 1750305027L))
        assertFalse(HookCompatibility.matchesLauncherVersion(38, 1800705053L))
    }

    @Test
    open fun taskSceneRelocationIsVersionSpecific() {
        assertEquals(
            "com.honeyspace.ui.common.taskScene.TaskSceneView",
            HookCompatibility.taskSceneView(36),
        )
        assertEquals(
            "com.honeyspace.common.taskscene.TaskSceneView",
            HookCompatibility.taskSceneView(37),
        )
        assertEquals(
            "com.honeyspace.common.taskscene.TaskSceneData",
            HookCompatibility.taskSceneData(37),
        )
    }

    @Test
    open fun removedAndroid17EndMethodIsNotRegistered() {
        assertArrayEquals(
            arrayOf<String>("end", "finish"),
            HookCompatibility.playerFinishMethods(36),
        )
        assertArrayEquals(arrayOf<String>("finish"), HookCompatibility.playerFinishMethods(37))
    }
}
