# Development scope and credits

This fork starts from [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). It keeps the
original package ID and MIT license. Contributions are selected by behavior and reviewed together;
upstream pull-request descriptions and screenshots are author evidence, not validation of this fork.

## Phase 1

- App/component identity and reliable focus restoration, drawing on
  [PR #16](https://github.com/Gathin23/tv-vibehack/pull/16), while preserving focused tile visuals.
- Accurate network state using Android network capabilities, informed by
  [PR #9](https://github.com/Gathin23/tv-vibehack/pull/9). Current status appears inside one compact pill
  with the available Wi-Fi name, connection icon and a subtle neutral connection mark; the separate status
  line is removed. Full content/state descriptions remain available to accessibility services.
  Captive portal, missing validation, Ethernet, Wi-Fi, offline and checking branches are source-reviewed;
  this does not imply every network state was replayed on hardware.
- Cancel stale hero-title/glow animation callbacks during quick focus changes.
- Set the greeting, clock and full date in an open, left-aligned header: a small sentence-case greeting,
  a large light clock and one date line below. Native `TextClock` views add no blur, capture, frame loop
  or service.
- Improve app-tile accessibility and restore visible focus after returning from another app.
  During an offscreen focus request, rapid D-pad navigation advances from the latest requested app
  key. Package refresh preserves that logical target; completed focus, cancellation or leaving the
  launcher clears it.
- Measure the Inputs popup to fit its contents, drawing on
  [PR #5](https://github.com/Gathin23/tv-vibehack/pull/5). Input connection states remain the states
  reported by the device; an HDMI input is not assumed to be connected on every firmware.
- Extract user-facing strings and add German resources, drawing on
  [PR #12](https://github.com/Gathin23/tv-vibehack/pull/12).
- Correct JPEG asset extensions and API/lint compatibility issues, drawing on
  [PR #7](https://github.com/Gathin23/tv-vibehack/pull/7).
- Explicit Home components and JDK/SDK discovery, drawing on
  [PR #2](https://github.com/Gathin23/tv-vibehack/pull/2), with native-adb install/launch/Home checks,
  opt-in stock disabling and saved-state rollback.
- Essential-service protection in the setup prompt, adapted from
  [PR #8](https://github.com/Gathin23/tv-vibehack/pull/8).
- Longer legacy Python pull timeouts from
  [PR #3](https://github.com/Gathin23/tv-vibehack/pull/3) and BOM-compatible batch input from
  [PR #15](https://github.com/Gathin23/tv-vibehack/pull/15). These do not add TLS support or remote
  exit-code propagation to the Python client.

## Phase 2: app library and saved layout

[PR #19](https://github.com/Gathin23/tv-vibehack/pull/19) informs the installed-app library, remote
reordering, hidden shortcuts and persistent dock editing now implemented in this fork. The integration
retains phase-1 focus and network behavior while using a separate saved-layout model.

- Apps, recent representations, empty slots, unavailable pins, section headings and the full-span
  library entry have distinct stable identities. A recent representation has a prefixed identity and
  resolves to its canonical Home shortcut for editing.
- Dock slots, grid package order and hidden shortcuts persist independently of installed-app discovery.
  An unavailable app keeps its intent and friendly label rather than being silently removed.
- The library discovers enabled TV and ordinary launcher entries, deduplicates packages and prefers TV
  activities. Users can open an app, edit its Home shortcut, choose a dock slot, open App info or request
  uninstall through Android's confirmation.
- Reorder stages a copy of the saved layout, supports dock/grid moves, and exposes persistent instructions
  and an accessibility description. OK saves; Back cancels. Leaving the activity or an actual catalog
  change cancels an unfinished move. Begin/end rebinding also clears stale moving descriptions.
- A changed package catalog dismisses stale menus. Reorder resolves the selected app's current key instead of a
  captured position. A separate visual-row navigation model skips section headings,
  while reorder operations map to canonical dock/grid indices. Down visits a partial final row before
  the library; footer navigation cannot target decoration rows.
- XML dock/hidden arrays are initial defaults; saved user choices take precedence on later launches.
- Uninstall uses `ACTION_UNINSTALL_PACKAGE` with a package URI and the normal
  [`REQUEST_DELETE_PACKAGES` permission](https://developer.android.com/reference/android/Manifest.permission#REQUEST_DELETE_PACKAGES).
  Android owns confirmation and protected/system-app rules. The launcher leaves its model unchanged
  while confirmation is pending, so cancelling does not remove a shortcut or app. This is distinct from
  Remove from Home. The intent remains supported but is deprecated from API 29; future API migration
  can adopt PackageInstaller without changing the requirement for confirmation.
- App actions, library choices and dock pickers use native list views with a shared glass-panel drawable
  and focus states. The styling adds no blur, snapshot capture, frame callback, dependency or service.
  System uninstall/Settings UI remains controlled by Android.

## App catalog and Home returns

`AppCatalog` owns an in-memory snapshot and one serial background executor for the activity's lifetime.
It retains only the application context. PackageManager discovery, version checks and artwork loading
run off the main thread. Returning Home applies an available snapshot immediately and refreshes the
catalog in the background. A fresh activity/process still needs an initial background load.

`AppCatalogCache` keys loaded records by package, selected component, long version code and last-update
time, within the current resource configuration. Locale, density, UI mode and other resource qualifiers
invalidate the configuration; a configuration sequence number alone does not. Broadcasts invalidate
the affected package before a debounced refresh. A resume scan detects installs, updates, removals and
component availability changes missed while paused. A package disappearing during discovery gets one
bounded retry; failed scans retain the previous complete snapshot.

Loaded records contain cloneable Drawable state. The adapter creates its own Drawable instances only
for changed records and retains unchanged entries, including their sampled ambient colors. Removed
components are pruned after a successful scan. There is no disk artwork cache, dependency, service or
periodic poller. Executor shutdown, request generations and configuration checks reject stale work and
callbacks after activity destruction or superseding requests.

Unchanged catalog revisions do not rebind rows, dismiss menus, cancel Reorder or restore stale focus.
Launcher-local recent opens can change rows independently. An actual catalog change cancels staged
edits, dismisses captured app actions and restores the latest stable focus key. On the first load,
automatic header focus does not override the saved tile; deliberate header navigation takes precedence
over a pending restore. Existing entrance effects run only when rows actually change.

## Recent apps and section navigation

The dock keeps its original bottom placement; content below it is reached with Down. A Recent apps
heading and row appear only when eligible entries fill the configured column count (four by default).
With fewer entries, the All apps heading/grid follows the dock directly. App library follows the
final app row. The floating All apps button and empty Recent apps message are removed.

Recent apps is launcher-local: `startActivity` must succeed from an app tile or the library before the
package is recorded. It stores at most 20 distinct packages in most-recently-opened order, without a
date or time cutoff, and filters dock packages, hidden apps and unavailable packages before applying
the full-row threshold. No usage-access permission, system-wide task observation, poller or service is
added. Apps opened through other launchers or external shortcuts are not observed.

Recent-only ordinary apps can be opened or added to Home/the dock through their menu. Reorder is
offered once a canonical Home shortcut exists. Adding a recent app to the dock restores focus to its
canonical dock tile; its recent representation is then filtered out. Saved Home intent remains
separate from recent launch history.

## Wi-Fi name and permissions

The network pill reads only the current connection's SSID from `WifiInfo`, with a guarded
`WifiManager` compatibility fallback when network capabilities omit or redact the name.
Android can redact the name when precise location access or the system Location setting is unavailable;
the pill then retains its Wi-Fi/status fallback. Android documents this name redaction in
[`WifiInfo.getSSID()`](https://developer.android.com/reference/android/net/wifi/WifiInfo#getSSID()).
The lookup checks the granted fine-location and Wi-Fi permissions, then relies on the public API's
redaction and exception handling rather than rejecting the read solely because Location reports off.
Show Wi-Fi name offers Location settings guidance only if the API cannot supply a name and Location
is off. This accommodates firmware differences without changing system settings or using a privileged
identity.
The lookup does not scan networks, read coordinates, poll or add a background service. Connection
validation and transport status continue to come from network capabilities.

Holding OK on the network pill exposes Show Wi-Fi name. Its explanation precedes an optional runtime
request for `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` together, as required for the precise
permission flow on [Android 12 and later](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime).
The launcher does not request permission at startup or change the TV's global Location setting. A
denied or redacted name does not prevent opening Network settings. `ACCESS_WIFI_STATE` is a normal
permission; the location permissions are runtime permissions, separate from the normal uninstall
permission. The app still declares no Internet permission, and build/install tooling grants no runtime
permissions automatically.

Master Location and the launcher's runtime permission control access to the protected Wi-Fi name.
Google Location Accuracy is a separate system-service choice. When GMS supplies network location,
this setting is also required for that provider, and can process wireless-signal and sensor data to
improve location accuracy. See
[Google's description](https://support.google.com/android/answer/15157297) and the
[`SettingsClient` reference](https://developers.google.com/android/reference/com/google/android/gms/location/SettingsClient).
On the tested network-only TV, enabling Location opens GMS's
`com.google.android.location.network.NetworkConsentActivity`. Resolving that native consent through
explicit acceptance kept master Location enabled. The user chooses on the native Google screen;
the launcher and build/install scripts do not accept accuracy consent or enable scanning automatically.

## Deferred work

Screensavers, Fire TV support and tuner-specific input changes remain separate work. Phase 1 targets
Android API 34 and includes the associated compatibility fixes. No new Internet access, artwork
downloading, blur or background service is planned in this phase.

## Validation

Debug build 13 (`2.4.0`) passes `assembleDebug`, `lintDebug` (zero errors, 21 warnings) and all five
pure Java suites. The added cache suite covers identity/version/update-time changes, package-wide
invalidation, configuration round trips, superseded scans and removal pruning. Independent source
review covered callback/lifecycle guards, Drawable ownership, recent-row updates and unchanged-row
suppression.

Cache checks on the same Android 14 TV established:

- Repeated warm Home returns kept one catalog revision and reused all 23 records, with zero artwork
  loads. Discovery/version checks ran on the catalog worker.
- Installing a private fixture loaded one new record and reused 23. Updating it while Home was paused
  changed its label, banner and exported launch component on return, again loading one record.
- Disabling/re-enabling only that fixture removed/restored its tile; uninstalling it removed the tile
  without reloading the other 23 apps. The fixture and its temporary saved-layout entries were removed.
- Before the fixture checks, the exact saved dock, grid and hidden-app arrays matched the pre-update
  snapshot. Cleanup retained those arrays. No system package or Location setting was changed.

An atrace comparison used 18 native Network settings-to-Home returns per build, discarding the first
three as warmups. Across the remaining 15 samples, main-thread framework `activityResume` elapsed
time fell from a median 124.5 ms to 43.8 ms; P90 fell from 416.1 ms to 114.7 ms. These are resume-path
wall times, not remote-input-to-visible-frame measurements. The cache build was measured before the
final cold-start focus guard; that guard does not alter the warm catalog path. The final APK was
installed, but its cold-start focus recheck ended when the TV returned to active use. Screenshots,
traces, fixture sources/APKs and the parser remain private verification artifacts.

On 18 October 2026, debug build 12 (`2.3.6`) passed `assembleDebug` and `lintDebug` with zero errors
and 21 warnings. Hardware checks used a MiTV-MZTU0 running Android 14. Build and lint results remain
separate from the device observations below.

All four pure Java suites pass: stable identities, saved layout, recent opens and section navigation.
Nine isolated installer/restore mock cases also pass, covering install/launch failures, ignored Home
selection, setup-helper priority, opt-in stock disabling and exact prior-state restoration. These mocks
use a fake adb executable and do not contact a TV.

An independent read-only model review also exercised 100,000 deterministic operations covering
dock/grid moves, replacements, hiding/restoring apps, availability changes and persistence reconstruction;
available nonhidden shortcuts remained unique
and preserved. This model result does not establish remote focus, dialog layout or device behavior.

Hardware observations on build 12:

- Six rapid Down presses reached App library. Seven rapid Up presses reached the top controls;
  Down from there restored the remembered YouTube dock tile.
- Immediate Down, Right, Right, OK opened the intended VLC app. Home restored its Recent apps tile.
- Immediate Down, Down, Hold OK opened Apple TV's actions. Back dismissed the menu without launching
  an app or changing launch history or the saved layout.
- Reorder moved Apple TV from the grid into the dock. Back first left the exact saved layout unchanged;
  saving persisted the change, and force-stop followed by Home retained it.
- The new dock app disappeared from Recent apps. The displaced YouTube app was already in launch
  history and became eligible, so the Recent apps row remained full and visible.

After testing, the dock was restored to the user's saved order: YouTube, Netflix, Hotstar, Prime.
Hidden shortcuts were unchanged and the visible grid order matched the baseline. Down from the dock
reached the Recent apps VLC tile; Back returned to the YouTube dock tile. Final package inspection
confirmed version code 12/name `2.3.6`, Home resolved to the custom launcher, and the disabled-package
set matched the state from before the app update. Remembered grid positions for dock-pinned packages
can change during swaps.

Build 10 (`2.3.4`) also established the following device behavior before the dispatcher fixes:

- The open header and neutral silver connection mark were visually checked. The dock retained its
  earlier vertical anchor; its saved left-to-right order was preserved.
- Three eligible non-dock recent apps hid the Recent apps section. Opening a fourth showed one MRU
  row, and Down from the dock reached it.
- Hold OK on a Recent apps tile opened its five-action glass menu. App info opened native TV Settings
  for the selected app, and Home restored that Recent apps tile.
- Uninstall opened Android's confirmation for VLC. Back cancelled it; VLC remained installed and the
  exact dock, grid and hidden-shortcut snapshot remained unchanged. No uninstall was confirmed.
- Inputs fitted all HDMI, AV and Live TV rows in a measured 220dp-wide popup and dismissed with Back.
  The network shortcut opened native Network & Internet settings and returned through Home.

Follow-up diagnosis observed an unresolved GMS `NetworkConsentActivity` during Location enablement.
After the user explicitly approved and accepted Google Location Accuracy, the same installed build
12 (`2.3.6`) displayed the actual Wi-Fi name with an accessible connected status. Location stayed on
through Home and a 30-second wait, a force-stop/Home cold launcher restart, and a verified native
Network settings roundtrip. Explicit native acceptance resolved the observed return to Location off;
no launcher source change or rebuild was needed. A full TV reboot has not been tested.

Other network states have source review, rather than end-to-end hardware replay. Casting,
voice, account setup, pairing a replacement remote, factory reset and OTA installation remain untested
end to end.

An exhaustive independent navigation check passed 21,788 invariants across 130 layouts with zero to
four recent apps and zero to 25 grid apps, including the absent-Recent section mapping. Targets and
fallback focus remained on dock/recent/grid/library items, canonical index round trips held, footer
Down stopped and dock Up allowed the native header controls. This is a model check, separate from
device rendering and D-pad validation.

Run the pure Java model tests without an Android device:

```bash
JAVA_HOME=/path/to/jdk-17 bash launcher/tests/run.sh
# With the JDK's javac and java already on PATH:
bash launcher/tests/run.sh
```

The phase-1 hardware smoke script opens Home and YouTube without editing the saved layout. It assumes
the original dock order (Netflix, Prime Video, YouTube, JioHotstar), so retain that order for this check:

```bash
TV_SERIAL=192.168.1.50:CURRENT_PORT \
  python3 tools/check-launcher.py --output backup/smoke-YYYYMMDD-HHMMSS
```

Screenshots, UI dumps and results include device information. Keep their output in the ignored
`backup/` directory and inspect the captures along with the reported checks.
