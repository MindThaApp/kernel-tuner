# Kernel Tuner

A small, hardware-agnostic kernel tuning app for rooted Android devices. One APK adapts at runtime to the
phone it runs on (tested layouts: Snapdragon 625 / kernel 4.9 with `policyN` cpufreq and Adreno `kgsl`, and
Exynos 7420 / kernel 3.10 with the legacy per-CPU cpufreq layout and Mali).

## Credits and licence

Kernel Tuner is a fork of [RvKernel Manager](https://github.com/Rve27/RvKernel-Manager) by Rve and
contributors. Their history is preserved in this repository (branch `main` is the untouched upstream base).
The project is licensed under the **GNU General Public License v3.0 or later**; see [LICENSE](LICENSE).
Root access uses [libsu](https://github.com/topjohnwu/libsu) (Apache-2.0).

Most of the UI and all of the sysfs layer were rewritten for this fork; only the build setup and licence are
carried over.

## Features

- **Runtime hardware detection** (no device-name tables): CPU clusters from `cpufreq/policyN` or the old
  `cpuN/cpufreq` layout (frequency table from `scaling_available_frequencies`, else `stats/time_in_state`,
  else `cpuinfo_min/max`); GPU backends Qualcomm `kgsl` and Samsung Mali (`*.mali`: `clock`, `utilization`,
  `dvfs_governor`, `dvfs_table`, `dvfs_min_lock`/`dvfs_max_lock`); thermal zones filtered to real sensors;
  I/O schedulers per block device.
- **Dashboard**: per-core frequency, governor, GPU clock and load, temperatures, RAM/swap/zram. Polls only
  while the app is on screen.
- **Controls**: CPU governor and min/max per cluster, GPU governor and min/max, I/O scheduler.
- **Overclock steps** (0.3.0): steps above the stock maximum are marked "Overclock", the ones expected to be
  unstable are flagged, and they always run in test mode. GPU ceilings: Samsung `gpu_max_clock_limit`
  (OC kernels only), Adreno `max_pwrlevel`. Cores that are hotplugged off are skipped and re-applied when they return.
- **Temperature limit** (0.3.0): Samsung IPA `control_temp`; step_wise trip points (CPU/GPU zones) that can only be
  lowered below the stock trip. Stock shown, reset to stock, never applied on boot unless saved in a profile.
- **Quick presets**: Battery saver / Balanced (stock) / Performance, stock-range values only.
- Every write goes through a fixed whitelist of files and values.
- **Memory values** (swappiness, dirty ratios, vfs_cache_pressure, zram, lmk props) are read-only by default;
  overriding is an explicit per-item opt-in with a warning. zram and lmk values are never writable.
- **Profiles** with optional "apply on boot" (off by default, one boot profile at most). Everything reverts
  to stock on reboot unless a profile is applied on boot.
- **Stability safety**
  - Test mode: changes above the stock maximum are applied for 3 minutes (configurable), then reverted via
    `AlarmManager` unless you tap Keep.
  - Clean-reboot detection: a shutdown/reboot receiver writes a marker; at boot the marker, boot-reason
    properties and (via root) pstore / `last_kmsg` are checked. After an unclean reboot with non-stock
    settings active, nothing is applied, the profile is marked unstable, a notification names the settings
    and offers a one-notch step-down, and the last ~50 lines of the crash log are saved for viewing.
  - Values are clamped to the detected hardware tables; going above stock requires confirmation.
- **Lightweight**: no background service, no internet permission, receivers only for boot, shutdown and two
  one-shot alarms.

## Building

Requires JDK 21 and the Android SDK (platform 36). Keep signing material outside the repository:

```
./gradlew :app:testDebugUnitTest
./gradlew -PktSigning=/path/to/signing.properties :app:assembleRelease
```

`signing.properties` holds `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Never commit it.
Unit tests use fixture trees captured read-only from real devices under `app/src/test/resources/fixtures`.

## Limitations

Writes need root and may be overridden by ROM services (thermal engine, PowerHAL). A cluster whose CPUs are
all offline at detection time is not listed until re-detected. GPU governor writes on Mali use the governor
index and are verified by read-back.
