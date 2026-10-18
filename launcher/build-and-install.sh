#!/usr/bin/env bash
# Build a debug APK, verify it opens, and select its explicit Home component.
# Inspired by upstream PR #2; native adb supports paired wireless debugging.
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "$*" >&2; exit 1; }
TV_SERIAL="${TV_SERIAL:-${TV_HOST:+${TV_HOST}:${TV_PORT:-5555}}}"
[[ -n "$TV_SERIAL" ]] || fail "Set TV_SERIAL=IP:port, or TV_HOST=IP for legacy port 5555."
TV_USER="${TV_USER:-0}"
[[ "$TV_USER" =~ ^[0-9]+$ ]] || fail "TV_USER must be a numeric Android user ID."
DISABLE_STOCK="${DISABLE_STOCK:-0}"
[[ "$DISABLE_STOCK" == 0 || "$DISABLE_STOCK" == 1 ]] || fail "Use DISABLE_STOCK=0 or DISABLE_STOCK=1."
APP_PACKAGE=com.gathin.tvhome
APP_COMPONENT="$APP_PACKAGE/.MainActivity"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-}"
if [[ "$DISABLE_STOCK" == 1 ]]; then
  [[ "$STOCK_LAUNCHER" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || fail "Set STOCK_LAUNCHER to the exact stock Home package before disabling it."
  case "$STOCK_LAUNCHER" in
    "$APP_PACKAGE"|*setupwraith*|*setupwizard*|com.android.tv.settings|com.android.systemui)
      fail "This script cannot disable $STOCK_LAUNCHER. Review setup and system services separately." ;;
  esac
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /usr/libexec/java_home ]]; then JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; fi
  if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
      /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/java-17-openjdk-*; do
      if [[ -x "$candidate/bin/java" ]]; then JAVA_HOME="$candidate"; break; fi
    done
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  [[ -x "$JAVA_HOME/bin/java" ]] || fail "JAVA_HOME has no Java executable. Set it to a JDK 17 installation."
  export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
fi
command -v java >/dev/null || fail "Install JDK 17 and set JAVA_HOME before building."
java_major="$(java -version 2>&1 | awk -F '"' '/version/ { split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit }')"
[[ "$java_major" =~ ^[0-9]+$ && "$java_major" -ge 17 ]] || fail "This build needs JDK 17 or later. Set JAVA_HOME to a compatible JDK."

if [[ -z "${ANDROID_HOME:-}" ]]; then
  ANDROID_HOME="${ANDROID_SDK_ROOT:-}"
  if [[ -z "$ANDROID_HOME" && -f local.properties ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n 1)"
  fi
  if [[ -z "$ANDROID_HOME" ]]; then
    for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
      /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
      if [[ -d "$candidate/platforms/android-34" ]]; then ANDROID_HOME="$candidate"; break; fi
    done
  fi
fi
[[ -d "${ANDROID_HOME:-}/platforms/android-34" ]] || fail "Set ANDROID_HOME to an Android SDK containing platforms;android-34."
export ANDROID_HOME PATH="$ANDROID_HOME/platform-tools:$PATH"
ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null || fail "Install Android SDK Platform-Tools, or set ADB to the adb executable."
adb_tv() { "$ADB" -s "$TV_SERIAL" "$@"; }
if ! adb_tv get-state >/dev/null 2>&1; then
  [[ "$TV_SERIAL" == *:* ]] || fail "ADB cannot find $TV_SERIAL. Check adb devices."
  "$ADB" connect "$TV_SERIAL"
  adb_tv get-state >/dev/null || fail "ADB cannot connect. Pair the TV first, then use its current connection port."
fi
active_user="$(adb_tv shell am get-current-user | tr -d '\r')"
[[ "$active_user" == "$TV_USER" ]] || fail "The active Android user is $active_user. Set TV_USER to that user before installing."

component_from_output() {
  tr -d '\r' | sed -nE '/^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/p' | tail -n 1
}
resolve_home() {
  adb_tv shell cmd package resolve-activity --brief --user "$TV_USER" \
    -a android.intent.action.MAIN -c android.intent.category.HOME | component_from_output
}
normal_component() {
  local package="${1%%/*}" activity="${1#*/}"
  [[ "$activity" == .* ]] && activity="$package$activity"
  printf '%s/%s\n' "$package" "$activity"
}
is_custom_home() { [[ -n "$1" && "$(normal_component "$1")" == "$(normal_component "$APP_COMPONENT")" ]]; }
package_state() {
  adb_tv shell dumpsys package "$1" | tr -d '\r' \
    | sed -nE "/User $TV_USER:/s/.* enabled=([0-4]).*/\1/p" | head -n 1
}
set_package_state() {
  local command
  case "$2" in
    0) command=default-state ;; 1) command=enable ;; 2) command=disable ;;
    3) command=disable-user ;; 4) command=disable-until-used ;;
    *) return 1 ;;
  esac
  adb_tv shell pm "$command" --user "$TV_USER" "$1"
  [[ "$(package_state "$1")" == "$2" ]]
}
foreground_is_custom() {
  adb_tv shell dumpsys activity activities \
    | awk '/mResumedActivity:|topResumedActivity=/ { print }' \
    | grep -F "$APP_PACKAGE/" | grep -q 'MainActivity'
}
wait_for_custom() {
  local attempt
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if foreground_is_custom; then return 0; fi
    sleep 0.3
  done
  return 1
}

ORIGINAL_HOME="$(resolve_home)"
[[ -n "$ORIGINAL_HOME" ]] || fail "ADB could not resolve the current Home activity. Check the TV before continuing."
STATE_DIR="${STATE_DIR:-../backup/install-$(date +%Y%m%d-%H%M%S)-$$}"
umask 077
mkdir -p "$(dirname "$STATE_DIR")"
mkdir "$STATE_DIR" || fail "Choose a new STATE_DIR so an earlier backup is preserved."
STATE_DIR="$(cd "$STATE_DIR" && pwd)"
printf '%s\n' "$TV_SERIAL" > "$STATE_DIR/device-serial.txt"
printf '%s\n' "$TV_USER" > "$STATE_DIR/user.txt"
printf '%s\n' "$ORIGINAL_HOME" > "$STATE_DIR/original-home.txt"
STOCK_STATE=
if [[ "$DISABLE_STOCK" == 1 ]]; then
  stock_home="$(adb_tv shell cmd package resolve-activity --brief --user "$TV_USER" \
    -a android.intent.action.MAIN -c android.intent.category.HOME "$STOCK_LAUNCHER" | component_from_output)"
  [[ "$stock_home" == "$STOCK_LAUNCHER/"* ]] || fail "$STOCK_LAUNCHER has no enabled Home activity. Leave DISABLE_STOCK=0 if it is already disabled."
  STOCK_STATE="$(package_state "$STOCK_LAUNCHER")"
  [[ "$STOCK_STATE" == 0 || "$STOCK_STATE" == 1 ]] || fail "The stock package is not enabled. Leave DISABLE_STOCK=0."
  printf '%s\n' "$STOCK_LAUNCHER" > "$STATE_DIR/stock-launcher.txt"
  printf '%s\t%s\n' "$STOCK_LAUNCHER" "$STOCK_STATE" > "$STATE_DIR/package-states.tsv"
fi

HOME_CHANGED=0
STOCK_CHANGED=0
rollback_on_failure() {
  local result=$?
  trap - EXIT
  if [[ "$result" != 0 ]]; then
    if [[ "$STOCK_CHANGED" == 1 ]]; then
      if set_package_state "$STOCK_LAUNCHER" "$STOCK_STATE"; then
        echo "The stock launcher's previous package state was restored." >&2
      else
        echo "Could not restore the stock launcher. Use tools/restore.sh with STATE_DIR=$STATE_DIR." >&2
      fi
    fi
    if [[ "$HOME_CHANGED" == 1 ]]; then
      if ! adb_tv shell cmd package set-home-activity --user "$TV_USER" "$ORIGINAL_HOME"; then
        echo "Could not reselect the previous Home. Restore from $STATE_DIR." >&2
      fi
    fi
  fi
  exit "$result"
}
trap rollback_on_failure EXIT

echo "Building the debug launcher."
./gradlew assembleDebug --console=plain --no-daemon
APK=app/build/outputs/apk/debug/app-debug.apk
[[ -f "$APK" ]] || fail "The build did not produce $APK. Check the Gradle output."
install_output="$(adb_tv install -r "$APK")"
printf '%s\n' "$install_output"
[[ "$install_output" =~ (^|$'\n')Success($|$'\r'|$'\n') ]] || fail "ADB did not confirm installation. The stock launcher was left enabled."
launch_output="$(adb_tv shell am start -W --user "$TV_USER" -n "$APP_COMPONENT")"
printf '%s\n' "$launch_output"
[[ "$launch_output" == *"Status: ok"* ]] && wait_for_custom \
  || fail "The new launcher did not reach the foreground. Home and the stock launcher were left unchanged."

HOME_CHANGED=1
home_output="$(adb_tv shell cmd package set-home-activity --user "$TV_USER" "$APP_COMPONENT")"
printf '%s\n' "$home_output"
[[ "$home_output" == *Success* ]] || fail "Android did not confirm the Home selection. Restoring the previous Home."
if [[ "$DISABLE_STOCK" == 1 ]]; then
  STOCK_CHANGED=1
  set_package_state "$STOCK_LAUNCHER" 3 || fail "Could not verify the stock launcher was disabled. Restoring its previous state."
  adb_tv shell cmd package set-home-activity --user "$TV_USER" "$APP_COMPONENT"
fi
selected_home="$(resolve_home)"
if ! is_custom_home "$selected_home"; then
  fail "This firmware still selects ${selected_home:-another activity} for Home. Restoring the previous Home and any stock package change. Review competing Home apps separately; setup helpers are never disabled by this script."
fi
adb_tv shell input keyevent KEYCODE_HOME
wait_for_custom || fail "The Home button did not open the new launcher. Restoring the previous Home and any stock package change."
echo "Debug launcher installed. The Home button opens $APP_COMPONENT."
echo "Restore snapshot: $STATE_DIR"
