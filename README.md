# Moonlight DS

A fork of [Moonlight V+](https://github.com/qiin2333/moonlight-vplus) for streaming PC games to Android handhelds, especially dual-screen ones. It adds:

- **Native DualShock 4 gyro** that keeps working while the handheld's sticks and buttons are in use. No mouse emulation, and it works with stock Sunshine.
- **Bottom-screen DS4 touchpad**: on dual-screen handhelds, the second screen becomes the DS4 touchpad, with a mouse trackpad and keyboard page.

**Devices:** built and tested on the **AYN Thor**. Other Android handhelds, such as Retroid Pocket devices, should work but are untested.

**Install:** download `moonlight-ds-<version>.apk` from the [latest release](https://github.com/Zach200910/moonlight-vplus-aynthor-gyro/releases/latest). It installs as `com.limelight.ds`, next to Moonlight or Moonlight V+ if you have them. After that, the app offers new versions itself (or use Settings → Check for updates).

For the original app's features and documentation, see the [upstream README](https://github.com/qiin2333/moonlight-vplus#readme).

## Timeline

| Date | Change |
| --- | --- |
| 2026-11-19 | **Gyro ratchet button.** A small button in the top-right of the stream (on devices with a gyroscope): hold it to stop gyro input, re-centre the handheld, then release. |
| 2026-11-18 | **Renamed to Moonlight DS and opened to everyone.** Public builds are signed release builds with their own app ID (`com.limelight.ds`), published as `ds-<version>` releases. |
| 2026-11-18 | **Patch cleanup.** Touchpad moved from a patch into source. The ENet fix is applied by one script in every CI job, so lint and unit tests now run on the fork's code, and CI fails if a fork fix goes missing. Unused patches moved to `patches/archive/`. |
| 2026-11-18 | Disabled a flaky upstream haptics unit test that was failing CI on unrelated changes. |
| 2026-11-18 | **In-app updates.** Every master build is published as a `thor-<version>` release (e.g. `12.15.17.28`), and the app checks this fork's releases instead of upstream's. ([#2](https://github.com/Zach200910/moonlight-vplus-aynthor-gyro/pull/2)) |
| 2026-11-18 | **Fixed debug signing.** CI signs every build with one key, so new builds install as updates and keep pairing and settings. ([#1](https://github.com/Zach200910/moonlight-vplus-aynthor-gyro/pull/1)) |
| 2026-11-18 | **Touchpad v8:** gyro calibration now runs on the Thor's raw (uncalibrated) gyroscope, so Android's own bias correction isn't applied on top. Adds GYRO CALIBRATE and GYRO TEST. |
| 2026-11-18 | **Touchpad v7:** hub wheel (MENU, KEYS, COLOR, OPTIONS, ESC), an options wheel (pixel shift, mouse speed, gyro calibrate/reset), and every colour, size and timing in `bottom_screen_style.properties`. |
| 2026-11-17 – 18 | **Bottom-screen DS4 touchpad** (v1–v6), ported from DroidDeck: two pads for the DS4's two touch halves, CLICK buttons, a keyboard page with a mouse trackpad, and OLED care (dimming, pixel shift). |
| 2026-11-17 | Fixed the build so the ENet fix is actually compiled into the APK (it had only been applied in the test job). |
| 2026-11-16 – 17 | Tried and parked four gyro experiments: 200 Hz sampling on a dedicated thread, raw gyro sensor, and higher input-thread priority (app and native). Not in the build. The raw-sensor change was later folded into touchpad v8. |
| 2026-11-16 | **Gyro freeze fix.** While a stick moved, ENet's congestion throttle dropped most gyro packets (up to ~149 ms freezes). The fix stops the throttle from discarding them (`patches/common-c/0001-enet-gyro-throttle.patch`). Confirmed on the Thor. |
| 2026-11-16 | Forked from Moonlight V+ 12.15.17. |
