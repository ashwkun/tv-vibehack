# Development scope and credits

This fork starts from [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). It keeps the
original package ID and MIT license. Contributions are selected by behavior and reviewed together;
upstream pull-request descriptions and screenshots are author evidence, not validation of this fork.

## Phase 1

- App/component identity and reliable focus restoration, drawing on
  [PR #16](https://github.com/Gathin23/tv-vibehack/pull/16), while preserving focused tile visuals.
- Accurate network state using Android network capabilities, informed by
  [PR #9](https://github.com/Gathin23/tv-vibehack/pull/9). The Network label stays fixed; SSID lookup and
  its location permission are unnecessary.
- Cancel stale hero-title/glow animation callbacks during quick focus changes.
- Improve app-tile accessibility and restore visible focus after returning from another app.
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

## Planned separately

[PR #19](https://github.com/Gathin23/tv-vibehack/pull/19) proposes an installed-app library, remote
reordering, hidden shortcuts and persistent dock editing. Integration must give app tiles, empty
dock slots and the library footer distinct identities, preserve focused labels and make move state
accessible. This feature is not part of the phase-1 launcher.

Screensavers, Fire TV support and tuner-specific input changes remain separate work. Phase 1 targets
Android API 34 and includes the associated compatibility fixes. No new Internet access, artwork
downloading, blur or background service is planned in this phase.

## Validation

Build and lint results are separate from hardware behavior. Record the tested revision and device,
including focus after app-list changes/recreation, rapid dock changes, app launch/return, network
changes, Home selection and inputs. Tooling checks should cover installation/launch failure and
firmware Home-priority failures with mock adb before any live run. Casting, voice, account setup and
OTA installation must be marked untested unless exercised end to end.

Run the identity and saved-layout model tests without an Android device:

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
