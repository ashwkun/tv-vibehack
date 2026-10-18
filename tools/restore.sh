#!/usr/bin/env bash
# Restore recorded package states and a Home component through native adb.
# STATE_DIR reads snapshots from launcher/build-and-install.sh.
# Optional PACKAGE_STATES accepts a JSON object of package: original-enabled-state.
set -euo pipefail
fail() { echo "$*" >&2; exit 1; }
LIST="${1:-}"
STOCK="${2:-${STOCK_LAUNCHER:-}}"
STATE_DIR="${STATE_DIR:-}"
PACKAGE_STATES="${PACKAGE_STATES:-}"
[[ -n "$LIST" || -n "$STATE_DIR" || -n "$PACKAGE_STATES" ]] \
  || fail "Use STATE_DIR=backup/install-... tools/restore.sh, or pass a debloat list and stock Home package."
[[ -z "$LIST" || -r "$LIST" ]] || fail "Cannot read debloat list: $LIST"
if [[ -n "$STATE_DIR" ]]; then
  [[ -d "$STATE_DIR" ]] || fail "Cannot read restore snapshot: $STATE_DIR"
  if [[ -z "${TV_SERIAL:-}" && -z "${TV_HOST:-}" && -r "$STATE_DIR/device-serial.txt" ]]; then
    TV_SERIAL="$(cat "$STATE_DIR/device-serial.txt")"
  fi
  [[ -n "${TV_USER:-}" || ! -r "$STATE_DIR/user.txt" ]] || TV_USER="$(cat "$STATE_DIR/user.txt")"
  [[ -n "$STOCK" || ! -r "$STATE_DIR/stock-launcher.txt" ]] || STOCK="$(cat "$STATE_DIR/stock-launcher.txt")"
fi
TV_SERIAL="${TV_SERIAL:-${TV_HOST:+${TV_HOST}:${TV_PORT:-5555}}}"
[[ -n "$TV_SERIAL" ]] || fail "Set TV_SERIAL=IP:port, or TV_HOST=IP for legacy port 5555."
TV_USER="${TV_USER:-0}"
[[ "$TV_USER" =~ ^[0-9]+$ ]] || fail "TV_USER must be a numeric Android user ID."
ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null || fail "Install Android SDK Platform-Tools, or set ADB to the adb executable."
adb_tv() { "$ADB" -s "$TV_SERIAL" "$@"; }
adb_tv get-state >/dev/null || fail "Connect the TV with adb before restoring. Its wireless connection port may have changed."
active_user="$(adb_tv shell am get-current-user | tr -d '\r')"
[[ "$active_user" == "$TV_USER" ]] || fail "This snapshot targets Android user $TV_USER; the active user is $active_user. Switch users before restoring."

HOME_COMPONENT="${STOCK_COMPONENT:-}"
if [[ -z "$HOME_COMPONENT" && -n "$STATE_DIR" && -r "$STATE_DIR/original-home.txt" ]]; then
  HOME_COMPONENT="$(cat "$STATE_DIR/original-home.txt")"
fi
if [[ -n "$HOME_COMPONENT" ]]; then
  [[ "$HOME_COMPONENT" =~ ^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$ ]] || fail "The saved Home component is invalid. Set STOCK_COMPONENT=package/.Activity."
elif [[ -z "$STOCK" ]]; then
  fail "Set STOCK_LAUNCHER to the stock Home package, or supply a snapshot with original-home.txt."
fi
[[ -z "$STOCK" || "$STOCK" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || fail "STOCK_LAUNCHER must be a package name."

states=
if [[ -n "$PACKAGE_STATES" ]]; then
  [[ -r "$PACKAGE_STATES" ]] || fail "Cannot read package-state backup: $PACKAGE_STATES"
  command -v python3 >/dev/null || fail "Reading a JSON package-state backup needs Python 3."
  states="$(python3 - "$PACKAGE_STATES" <<'PY'
import json, re, sys
states = json.load(open(sys.argv[1], encoding="utf-8-sig"))
if not isinstance(states, dict):
    sys.exit("Package-state backup must be a JSON object.")
for package, state in states.items():
    if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", package) or type(state) is not int or state not in range(5):
        sys.exit("Package-state backup contains an invalid package or enabled state.")
    print(f"{package}\t{state}")
PY
)"
elif [[ -n "$STATE_DIR" && -r "$STATE_DIR/package-states.tsv" ]]; then
  states="$(cat "$STATE_DIR/package-states.tsv")"
fi
packages="$states"
if [[ -n "$LIST" ]]; then
  # Keep the old list interface; only unknown entries fall back to explicit enable.
  while IFS= read -r line || [[ -n "$line" ]]; do
    package="${line%%#*}"
    package="$(printf '%s' "$package" | awk '{$1=$1; print}')"
    [[ -n "$package" ]] || continue
    [[ "$package" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || fail "Invalid package in debloat list: $package"
    if ! printf '%s\n' "$packages" | awk -F '\t' -v p="$package" '$1 == p { found=1 } END { exit !found }'; then
      echo "No saved state for $package; restoring it as enabled." >&2
      packages="${packages}${packages:+$'\n'}${package}"$'\t'1
    fi
  done < "$LIST"
fi
if [[ -n "$STOCK" ]] && ! printf '%s\n' "$packages" | awk -F '\t' -v p="$STOCK" '$1 == p { found=1 } END { exit !found }'; then
  packages="${packages}${packages:+$'\n'}${STOCK}"$'\t'1
fi
while IFS=$'\t' read -r package state; do
  [[ -n "$package" ]] || continue
  [[ "$package" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || fail "Invalid package in restore snapshot."
  case "$state" in
    0) command=default-state ;; 1) command=enable ;; 2) command=disable ;;
    3) command=disable-user ;; 4) command=disable-until-used ;;
    *) fail "Invalid saved enabled state for $package." ;;
  esac
  adb_tv shell pm "$command" --user "$TV_USER" "$package"
  actual="$(adb_tv shell dumpsys package "$package" | tr -d '\r' \
    | sed -nE "/User $TV_USER:/s/.* enabled=([0-4]).*/\1/p" | head -n 1)"
  [[ "$actual" == "$state" ]] || fail "Could not verify the restored state of $package. Check the ADB output."
done <<< "$packages"

component_from_output() { tr -d '\r' | sed -nE '/^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/p' | tail -n 1; }
normal_component() {
  local package="${1%%/*}" activity="${1#*/}"
  [[ "$activity" == .* ]] && activity="$package$activity"
  printf '%s/%s\n' "$package" "$activity"
}
if [[ -z "$HOME_COMPONENT" ]]; then
  HOME_COMPONENT="$(adb_tv shell cmd package resolve-activity --brief --user "$TV_USER" \
    -a android.intent.action.MAIN -c android.intent.category.HOME "$STOCK" | component_from_output)"
  [[ "$HOME_COMPONENT" == "$STOCK/"* ]] || fail "The stock package has no enabled Home activity. Set STOCK_COMPONENT to its exact component."
fi
home_output="$(adb_tv shell cmd package set-home-activity --user "$TV_USER" "$HOME_COMPONENT")"
printf '%s\n' "$home_output"
selected="$(adb_tv shell cmd package resolve-activity --brief --user "$TV_USER" \
  -a android.intent.action.MAIN -c android.intent.category.HOME | component_from_output)"
[[ -n "$selected" && "$(normal_component "$selected")" == "$(normal_component "$HOME_COMPONENT")" ]] \
  || fail "The firmware selected another Home activity. Package states were restored; review Home selection on the TV."
adb_tv shell input keyevent KEYCODE_HOME
echo "Package states restored. Home resolves to $HOME_COMPONENT. The custom launcher remains installed."
