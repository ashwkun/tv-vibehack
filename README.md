# tv-vibehack — development fork

A small native Android TV home screen with a black background, pinned dock, app grid and a floating
glass focus highlight. This fork develops selected improvements from
[Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack) while retaining its MIT license.

| Original home screen | Upstream launcher |
|---|---|
| ![Stock Google TV home with a sponsored ad](docs/stock-home.jpg) | ![tvOS-style launcher](docs/new-home.jpg) |

The launcher has no Internet permission, downloads or background services. Its Uninstall action opens
Android's confirmation screen. System debloating over ADB remains a separate task requiring a backup
and a reviewed device-specific list.

## Development scope

Phase 1 focuses on app identity and focus restoration, accurate Network status, cancelling stale
hero-title animations, accessibility, and safer build/install tooling. A compact network pill shows
the current Wi-Fi name when Android permits it, with a connection icon and a subtle status mark.
Ethernet, Offline, No Internet, Sign-in needed and Checking states have short labels;
full status remains available to accessibility services. If Android hides the Wi-Fi name, the pill
falls back to Wi-Fi and still reports connection status. The connected mark is neutral silver;
checking or attention uses a muted warm tone, and offline uses gray.
See [development notes and upstream credits](docs/development.md) for scope and validation status.

The header uses open, left-aligned typography: a small greeting, a large light clock and one full-date
line underneath. Native text and clock views keep the layout lightweight, without blur or a new
animation loop.

Hold **OK** on the network pill and choose **Show Wi-Fi name** to enable name access. Home explains
Android's location permission before requesting it, then lets Android decide whether to reveal the
name. Some TVs also require Location turned on; Home offers settings guidance when the name remains
hidden and Location is off. It does not prompt at startup or turn on Location itself. The lookup reads
the current Wi-Fi connection, without Wi-Fi scans, coordinate lookup or polling. Denying permission
leaves the network shortcut usable. The installer grants no runtime permissions.

Google TV may also show a native **Google Location Accuracy** consent screen when enabling Location.
That separate system service can process wireless-signal and sensor data to improve location accuracy.
Review and choose on Google's screen; the launcher and installer do not accept it automatically.
On the tested network-only TV, explicit native acceptance kept Location enabled and allowed the
actual Wi-Fi name to appear.

Phase 2 adds remote dock editing and an installed-app library, adapted from upstream
[PR #19](https://github.com/Gathin23/tv-vibehack/pull/19), with separate identities for apps, empty slots,
unavailable pins and the library entry. Build/model checks and hardware validation are recorded
separately in the development notes.

The dock keeps its original position near the bottom of Home. Use **Down** to reach **Recent apps**,
then **All apps**. Recent apps appears only when there are enough eligible apps to fill one row
(four with the default column count); otherwise All apps follows the dock directly. It records
successful opens from this launcher's app tiles or library, excluding dock apps, hidden shortcuts and
unavailable apps. It keeps up to 20 distinct entries privately in most-recently-opened order, with no
date or time cutoff. It does not observe apps opened elsewhere or request usage access. The floating
All apps button is removed.

## Organize apps with the remote

Hold **OK** on an app to open its actions. Choose **Reorder**, use the D-pad to move it within or between
the dock and grid, then press **OK** to save or **Back** to cancel. Leaving the launcher or refreshing
the installed-app list cancels an unfinished move. The on-screen hint and accessibility description
identify move mode.

App actions also offer dock replacement, removal from Home, App info and **Uninstall**. Removing a dock shortcut
leaves an **Add app** slot; it does not uninstall the app or clear its data. Replacing a dock app keeps
the displaced app available in the library. An unavailable pin keeps its saved slot and package
identity, so editing another shortcut does not erase it; it can be replaced or removed explicitly.
Recent tiles edit the same app's saved Home shortcut. A recent-only app opened from the library can
be added to Home or the dock before reordering its permanent shortcut.

**App library**, after the final app row, lists enabled apps with TV or ordinary Android launcher
entries, once per package. A TV entry is preferred when both exist. Choose an app to open it, add or
remove its Home shortcut, select a dock slot, open App info, or request uninstall. Default-hidden shortcuts can be added
back from this list; background-only and disabled packages are not listed.

Dock slots, grid order and hidden shortcuts are saved privately and survive launcher restarts.
[`config.xml`](launcher/app/src/main/res/values/config.xml) supplies the initial dock and hidden-app
defaults before a saved layout exists. On an existing installation, use the remote to change saved
choices; rebuilding with different defaults does not overwrite those choices.

**Uninstall** asks Android to handle removal and show its confirmation; cancelling leaves the app and
saved layout in place. Confirmed removal can delete the app's data. Android decides whether a system
or protected app can be removed, whether only its updates can be removed, or whether to refuse the
request. The normal `REQUEST_DELETE_PACKAGES` permission enables this confirmation flow; it does not
grant silent removal. No Internet permission is added.

Launcher-owned menus and explanations use the same rounded glass-panel background as Inputs.
There is no blur, screenshot processing, new animation loop or background worker for the glass
appearance. Android-owned uninstall and Settings screens keep their system appearance.

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
