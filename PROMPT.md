# The prompt

Paste this into Claude Code (or another coding assistant with shell access) on a computer that is
on the same Wi-Fi as the TV. Works on Android TV / Google TV sets (Xiaomi, TCL, Hisense, Sony,
OnePlus, Realme, Nvidia Shield and most others). It will not work on Samsung Tizen, LG webOS or
Fire TV. If you cloned this repo, mention that: the assistant can reuse `tools/` and `launcher/`
instead of writing them from scratch. Review the fork's current features in `README.md` first.

Before you start, on the TV: Settings → Device Preferences → About → press OK on "Build" 7 times,
then go back → Developer options → turn on USB debugging (and Network/ADB debugging if your TV
shows it). Keep the TV on.

---

I want to debloat my Android TV and replace its launcher with a clean Apple TV style home screen.
My TV model is: <MODEL NAME HERE>. Work on the real device, step by step, and verify everything you
do with screenshots pulled from the TV. Ask me before anything that depends on my preferences.

Connect
- Use native Android SDK Platform-Tools. For Wireless debugging, pair with the pairing code and
  pairing port, then connect to the separate connection port shown on the TV. Use `TV_SERIAL=IP:port`;
  neither wireless port should be assumed to be 5555. For legacy unencrypted network ADB, use
  `TV_HOST=IP` and `TV_PORT=5555` unless the device reports another port.
- If native adb reports "No route to host" on macOS, check Local Network permission and connectivity.
  `tools/tv.py` is a fallback only for legacy unencrypted ADB; it does not support paired TLS or report
  remote command exit codes. Use native adb for installing and switching Home.
- Tell me when the "Allow USB debugging?" prompt will appear on the TV so I can accept it.

Back up first
- Save the full package list, disabled-package list, getprop output and all settings to a local backup
  before changing anything. Record the active Android user, original Home component, each affected
  package's exact enabled state and any affected component overrides. Prepare a restore command that
  returns those original states rather than enabling packages that were already disabled.

Debloat
- List installed packages and sort them into: manufacturer telemetry/analytics, manufacturer content
  and ad services (PatchWall, recommendation rows, content providers), background services I probably
  don't need (share/cast duplicates, cleaners), preloaded apps, and streaming apps.
- Disable only identified and approved nonessential packages with `pm disable-user --user <active-user>`;
  never uninstall system packages or clear their data.
- Preserve the TV app, HDMI/tuner input services, source menu, remote and Bluetooth pairing services,
  on-screen keyboard, accessibility services, Google Play Store and Play Services. Manufacturer package
  names can hide several functions: inspect activities, services and providers with `dumpsys package`
  before making a change, and ask me when a package's role is unclear.
- Before touching streaming apps, voice assistant, Chromecast, Miracast or the update service, ask me
  which ones I use with a checklist question and disable only what I don't tick.
- Run `pm` commands one per shell call, chained commands may silently stop after the first.

Launcher
- Don't install a third-party launcher that charges for basics like wallpapers. Build a small native
  Android TV launcher instead (Java, AndroidX RecyclerView, minSdk matching the TV, debug build is fine),
  install a debug build over native ADB, open its explicit component and verify navigation before
  selecting it as Home with `cmd package set-home-activity --user <active-user> package/.Activity`.
- Keep the stock launcher enabled by default. Disable it only after the new launcher opens successfully
  and I explicitly choose that step. Verify the resolved Home component and the actual Home button;
  "Success" from set-home-activity alone does not prove the firmware uses it.
- Never automatically disable a setup/recovery helper to bypass Home priority. Such helpers can also
  own account setup, remote pairing, cast provisioning or update-status integrations. If one blocks
  Home selection, explain the device evidence and obtain separate approval for any proposed change.
- Design, Apple TV 4K style: pitch black background; a dock row at the bottom of the screen inside a
  slightly lighter rounded band holding the four apps I pick (ask me which, and the order); the rest of
  the apps in a 4-column grid below that scrolls up when I press Down; one translucent "glass" highlight
  that springs from tile to tile instead of per-tile borders; the focused tile scales up slightly and
  shows its name underneath; above the dock, the focused dock app's name in large light type with an
  ambient glow in a colour sampled from its banner; frosted pill buttons top right for Inputs, Network
  and Settings, with accurate network status; a greeting, a large thin clock and the date top left in a
  downloaded open-licence typeface such as Outfit. Inputs opens a compact glass dropdown under the pill
  listing the HDMI ports, AV and Live TV, switching via the TV input framework.
- Make D-pad navigation deterministic: Up/Down move exactly one row, Up from the dock goes to the top
  bar, the ends of the top bar don't fall through to tiles. Remember the last focused tile.
- Keep it light: the TV has about 1 GB of RAM. No blur, no video, no network polling.

Verify
- After each change, inject remote key presses over ADB, take a screenshot from the TV and look at it.
  Fix what you see before moving on. Report observed results separately from author claims or untested
  behavior, especially casting, voice search, account setup and firmware updates.
- When done, give me a restore script, a build-and-install script, and a README that explains how to
  re-enable any app and how to change the dock order or the wallpaper.

---

Expect to answer three or four checklist questions (which apps you use, which four go in the dock,
whether to keep the voice assistant, Chromecast and firmware updates). Everything is reversible.
