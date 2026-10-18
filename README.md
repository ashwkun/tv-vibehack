# tv-vibehack — development fork

A small native Android TV home screen with a black background, pinned dock, app grid and a floating
glass focus highlight. This fork develops selected improvements from
[Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack) while retaining its MIT license.

| Original home screen | Upstream launcher |
|---|---|
| ![Stock Google TV home with a sponsored ad](docs/stock-home.jpg) | ![tvOS-style launcher](docs/new-home.jpg) |

The launcher has no Internet permission, downloads or background services. Device package changes
are separate from the app and require a backup and a reviewed device-specific list.

## Development scope

Phase 1 focuses on app identity and focus restoration, accurate Network status, cancelling stale
hero-title animations, accessibility, and safer build/install tooling. The Network button keeps a
fixed label and reports connection status without reading the Wi-Fi name or requesting location.
See [development notes and upstream credits](docs/development.md) for scope and validation status.

Remote dock editing and an installed-app library from upstream
[PR #19](https://github.com/Gathin23/tv-vibehack/pull/19) are planned separately. The current dock and
hidden apps remain configurable in
[`config.xml`](launcher/app/src/main/res/values/config.xml); changing these defaults requires a rebuild.

## Connect the TV

Install Android SDK Platform-Tools. Keep the computer and TV on the same network and enable the TV's
developer options and debugging. Android TV and Google TV menus vary by firmware.

For **Wireless debugging**, open **Pair device with pairing code** on the TV:

```bash
adb pair 192.168.1.50:PAIRING_PORT
# Enter the code shown on the TV when adb asks for it.
adb connect 192.168.1.50:CONNECTION_PORT
adb devices
```

The pairing port and connection port are different, and can change. Use the connection endpoint as
`TV_SERIAL`. The native `adb` client supports this paired, encrypted connection.

Older TVs may expose **legacy network ADB** through USB/network debugging on port 5555:

```bash
adb connect 192.168.1.50:5555
```

Accept any authorization prompt on the TV. If macOS reports “No route to host,” check network access
and the computer's Local Network privacy settings. The Python fallback below supports only legacy
unencrypted ADB; it cannot replace native adb for a paired wireless-debugging connection.

## Build and install

Install JDK 17, Android SDK platform 34, build-tools 34.0.0 and Platform-Tools. The build script prefers
a registered JDK 17 on macOS, then common Homebrew/Linux JDK paths; an explicit `JAVA_HOME` takes
precedence. SDK lookup uses `ANDROID_HOME`, `ANDROID_SDK_ROOT`, existing `local.properties`, then
Android Studio's macOS/Linux defaults and common Homebrew paths. Existing SDK configuration is not
overwritten.

From the repository root:

```bash
TV_SERIAL=192.168.1.50:35709 bash launcher/build-and-install.sh
```

For legacy ADB, the compatible host form defaults to port 5555:

```bash
TV_HOST=192.168.1.50 bash launcher/build-and-install.sh
# TV_PORT overrides 5555 for a legacy endpoint.
```

The script builds a **debug APK**, installs it with native adb, verifies that the launcher reaches the
foreground, and selects `com.gathin.tvhome/.MainActivity` as Home. It checks both Android's resolved
Home and the actual Home button. `TV_USER` defaults to `0` and must match the active Android user.
No runtime permissions are granted by the script.

The stock launcher stays enabled by default. If a tested new launcher needs the stock Home package
disabled, name that package explicitly and opt in:

```bash
TV_SERIAL=192.168.1.50:35709 \
  STOCK_LAUNCHER=com.google.android.apps.tv.launcherx DISABLE_STOCK=1 \
  bash launcher/build-and-install.sh
```

This disables only the named stock launcher, after installation and launch succeed. It records the
previous Home and stock package state under `backup/install-.../`. If Home still resolves elsewhere
or the Home button fails, it attempts to restore those states and exits with an error. A failed
installation never advances to stock disabling. The script **never disables setup/recovery helpers**;
some firmware gives those helpers priority over third-party launchers, requiring a separate review.

Set `STATE_DIR` to choose a new snapshot directory, or `ADB` to choose an adb executable. Snapshot
directories contain device information and are ignored by Git. Keep them local.

## Restore

Restore the state from a particular build/install run:

```bash
TV_SERIAL=192.168.1.50:CURRENT_PORT \
  STATE_DIR=backup/install-YYYYMMDD-HHMMSS-PID bash tools/restore.sh
```

The saved Home is the Home from **before that run**; it may already be the custom launcher. Stock
package defaults and explicit enabled states are restored exactly when recorded. The script leaves
app data, component overrides, animation settings and the installed custom launcher in place.

To use a separate package-state backup, supply a JSON object such as
`{"com.example.package": 0}` with the original Android enabled-state values:

```bash
TV_SERIAL=192.168.1.50:CURRENT_PORT \
  PACKAGE_STATES=backup/package-states.json \
  STOCK_LAUNCHER=com.google.android.apps.tv.launcherx bash tools/restore.sh
```

The older list interface is also available:

```bash
TV_SERIAL=192.168.1.50:CURRENT_PORT \
  bash tools/restore.sh my-reviewed-debloat.txt com.google.android.apps.tv.launcherx
```

Without a recorded state, list entries are explicitly enabled and the script says so; this cannot
reconstruct which apps were already disabled. Always capture original states before debloating.
The script resolves the supplied stock package to a full Home component, verifies the selection and
stops if firmware selects another app. It does not silently claim the original screen is restored.

## Device changes and legacy tools

[`PROMPT.md`](PROMPT.md) guides a device-specific backup, package review and launcher setup. Preserve
TV/input playback, source menus, remote pairing, keyboards, accessibility, Play Store and Play
Services. Review streaming, casting, voice and firmware-update services with the owner. Example
debloat lists describe other models and are starting points, not universal recommendations.

`tools/disable.sh` and the pure-Python `tools/tv.py` remain legacy utilities. `tv.py` prints remote
shell output without propagating remote exit codes; `--batch` is **not a fail-fast installer**.

```bash
python3 -m venv tools/venv
tools/venv/bin/pip install -r tools/requirements.txt
TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/tv.py --probe
```

The Python client's push/pull timeouts allow slow Wi-Fi transfers, and batch input accepts a UTF-8
byte-order mark. `b64push.py` remains available for legacy uploads with an MD5 transfer check.
Use native adb for paired TLS, installations and verified Home changes.

For a screenshot, return Home before capturing. Some TV/HDMI or protected video surfaces cannot be
captured. A screenshot alone does not prove voice search, casting or firmware updates work.

## Contributing and license

See [`CONTRIBUTING.md`](CONTRIBUTING.md). The original project and this fork use MIT; retain
[`LICENSE`](LICENSE). Outfit is licensed under SIL Open Font License in
[`launcher/FONT-LICENSE-OFL.txt`](launcher/FONT-LICENSE-OFL.txt).
