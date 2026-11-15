# Galaxy Adaptive Bar

[日本語](README.md) · English

A Vector module that keeps the Galaxy status bar readable against its background. Choose per-character coloring, per-part coloring, or automatic selection, or return to the normal status-bar appearance.

## Features

- Choose **Per character**, **Per part**, or **Automatic** under coloring granularity. Per-part mode gives the date and time their own uniform colors, even when they share one display area. Automatic mode normally keeps each part uniform and switches only portions that become more readable with per-character coloring. It waits for the background to settle before returning to whole parts, reducing repeated changes while moving a map or photo. The default remains per-character coloring; setting changes apply immediately.
- Automatic black/white colors, adjustable fade duration, refresh rate, and background-analysis method.
- Follow One UI Home's app opening, closing, and recent-app transitions, including gesture reversals.
- Independently enable coloring for the clock, notifications, connectivity indicators, and battery.
- Color the notification-overflow dot using the notification-icon setting.
- Color Android 17's network-speed indicator and adjust the spacing between its number and unit.
- On Android 17, move the right-side icons alongside camera/microphone indicators. Preserve the original indicator transformation while making the circular green dot slightly larger, with an option to fall back to the stock layout when space is insufficient. Icons also move smoothly when the usage indicator becomes a green dot.
- Stop capture while the screen is off or locked; per-target crash protection and restoration after startup.
- Material 3 settings and a searchable license list.

## Requirements

Physical-device coverage is Galaxy S26 Ultra (SM-S948Q), Android 16 / One UI 8.5 (AZHL), and Android 17 / One UI 9 (BZI9). Separate compatibility paths are maintained for both versions. A supported root environment and Vector with libxposed API 102 are required. Unverified paths are not applied on other devices, firmware, or launcher versions.

## Installation and updates

1. Install the signed release APK from [Releases](https://github.com/OneUI-Mods/galaxy-adaptive-statusbar/releases). Updates to the same package install in place and preserve settings.
2. Enable the module in Vector and select SystemUI and One UI Home as targets.
3. Reload the target processes after initial installation or an APK update. [Galaxy Reload](https://github.com/OneUI-Mods/galaxy-reload) can reload SystemUI. Depending on the reload method, you may need to unlock the phone afterward.
4. Enable automatic coloring and the features you need in the app. Ordinary setting changes do not require process reloads.

## Limitations and disabling

When capture becomes expensive, the module automatically lengthens its update interval. The configured rate is an upper limit: under load, colors may update more slowly to reduce interference with screen rendering.

Validation focuses on portrait orientation and a single display. Landscape, split screen, DeX, and every Home Up effect are not guaranteed. A clock format whose date/time boundaries cannot be identified is treated as one part. Protected screens and backgrounds that cannot be analyzed use stock colors. Background images are saved only when diagnostic saving is explicitly enabled.

Turn off the app's switches to stop its features. To remove hooks completely, disable the module in Vector and reload the target processes. Repeated crashes activate protection for the affected target. Resolve the cause before resetting protection in the app.

## Development and licenses

Build instructions, architecture, and verification are in the [developer guide (Japanese)](docs/development.md). Report problems through [Issues](https://github.com/OneUI-Mods/galaxy-adaptive-statusbar/issues), including the device, OS and target-app versions, and reproduction steps.

See [LICENSE](LICENSE) for the app's license and the in-app or bundled third-party notices for dependencies. This is not an official Samsung app.

[OneUI Mods app catalog](https://github.com/OneUI-Mods)

### Charging animation (Android 17)

Enable **Keep the status bar visible during charging animation** under **Adjustments** to show the clock, notifications, and connectivity indicators while preserving the original charging animation. Neighboring icons move left with the enlarged battery indicator and return as it shrinks. The return to the normal battery matches position and size, with a smooth fade handing over the percentage display. This option defaults to ON; changes apply from the next charging animation.

## String compatibility

Handling of certain whitespace and empty strings in per-character color analysis and saved-data reading has been restored to the previous behavior.

## Compatibility after updates

After a target-app update, open this module once to finish preparation, then reopen the target app. Discovery results are stored in each module's own app data and are not rescanned when nothing changes. No other OneUI Mods app is required. Features stop applying when a unique compatible target cannot be confirmed. Compatibility with every future OS or app update is not guaranteed.

Version 0.7.7 removed an unnecessary owning reference from the battery drawing cache. See its [validation scope (Japanese)](docs/memory-0.7.7.md).
