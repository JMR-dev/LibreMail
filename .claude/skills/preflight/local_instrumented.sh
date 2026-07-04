#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# local_instrumented.sh — reliable LOCAL instrumented / E2E test runner for LibreMail.
#
#   Usage:  local_instrumented.sh <fully.qualified.TestClass>[,<Class2>,...]
#   Example:
#     .claude/skills/preflight/local_instrumented.sh \
#         org.libremail.ui.compose.ComposeScreenE2ETest
#     .claude/skills/preflight/local_instrumented.sh \
#         org.libremail.ui.compose.ComposeScreenE2ETest,org.libremail.ui.compose.RecipientChipTest
#
# =============================================================================
# WHY THIS SCRIPT EXISTS  (issue #269)
# -----------------------------------------------------------------------------
# On this machine (Windows + the AEHD 2.2 hypervisor) the Gradle Managed Device
# (GMD) instrumented tasks — `apiXXDebugAndroidTest` — FAIL during setup. GMD tries
# to save/load an AVD *snapshot* and AEHD 2.2 cannot complete it:
#
#     AvdSnapshotHandler$EmulatorSnapshotCannotCreatedException: Snapshot creation timed out
#
# GMD retries the snapshot ~5x, rebooting the AVD each time — that endless reboot is
# the "cycling" that eats hours. The emulator ITSELF is healthy (8 GB RAM,
# `sys.boot_completed=1`, shell-responsive); only GMD's snapshot step is broken. So
# every LOCAL GMD task is affected: coverage lanes 3/5 (#248/#250) and the /preflight
# api35/api36 steps (#266). CI is unaffected — it uses reactivecircus/android-emulator-runner
# + `connectedDebugAndroidTest`, never GMD.
#
# THE RELIABLE LOCAL PATH (this script):
#   Cold-boot ONE emulator by hand with `-no-snapshot` (no GMD, no snapshot machinery),
#   then run `:app:connectedDebugAndroidTest` — the exact technique CI and
#   `api37_e2e.py` already use. We reuse a GMD-provisioned AVD by name so we don't have
#   to re-download a system image; GMD re-provisions its own copy on its next run, so
#   the `-wipe-data` cold boot here does not disturb it.
#
# =============================================================================
# KEEP RUNS TARGETED — THE ~114-TEST MID-SUITE WEDGE
# -----------------------------------------------------------------------------
# Running the WHOLE instrumented suite (~114 tests) via `connectedDebugAndroidTest`
# on this box tends to wedge partway through — the emulator stops making progress
# mid-run. Small, targeted class sets do NOT hit that wedge. That is why this helper
# takes an explicit `<fully.qualified.TestClass>[,...]` argument and filters the run
# with `-Pandroid.testInstrumentationRunnerArguments.class=...` instead of running
# everything. Run the class(es) you actually changed; do not use this to run the full
# suite (that is CI's / preflight's job across the API matrix).
#
# =============================================================================
# FREEZE / HYGIENE RATIONALE — WHY THE ORPHAN-KILL + TEARDOWN VERIFY ARE MANDATORY
# -----------------------------------------------------------------------------
# A hung `adb emu kill` (or an interrupted run) leaves a detached
# `qemu-system-x86_64-headless.exe` behind. These orphans do not show up in
# `adb devices`, they keep holding the hypervisor + RAM, and accumulated orphans have
# FROZEN this machine outright. So this script:
#   * PREAMBLE  — force-kills any pre-existing qemu/emulator processes and resets the
#                 adb server BEFORE booting, so we always start from a clean slate.
#   * TEARDOWN  — `adb emu kill`, then re-checks `tasklist` for qemu and force-kills any
#                 survivor. The teardown runs even on Ctrl-C / error (EXIT/INT/TERM trap).
#   * VERIFY    — if a qemu process is STILL alive after the force-kill, the script exits
#                 non-zero (code 3) so the leak is never silently ignored.
# Never leave an emulator running after this script; if it exits 3, hunt the zombie
# down by hand (`tasklist | grep -i qemu`; `taskkill //F //IM qemu-system-x86_64-headless.exe`).
#
# =============================================================================
# REQUIREMENTS
#   * Git Bash (this is a bash script; it shells out to Windows `tasklist`/`taskkill`).
#   * Android SDK `emulator` + `adb` on PATH (SDK at C:\Android here).
#   * A JDK 17–21 for the Gradle daemon — AGP 9.2 fails on JDK 25+. This script pins
#     JAVA_HOME to a known JDK 21 (override with LOCAL_INSTRUMENTED_JDK) because the
#     ambient JAVA_HOME on this box points at JDK 25.
#   * A free hardware hypervisor (VT-x/WHPX/AEHD). Shut down VirtualBox / other VMs first
#     or the AVD hangs at 0% CPU and never reaches sys.boot_completed.
#
# Overridable via environment (defaults target THIS machine):
#   LOCAL_INSTRUMENTED_AVD           AVD name to boot   (dev36_google_apis_x86_64_Pixel_2)
#   ANDROID_AVD_HOME                 AVD home dir       (C:/Users/jasonross/.android/avd/gradle-managed)
#   LOCAL_INSTRUMENTED_JDK           JDK 17–21 home     (Eclipse Adoptium jdk-21.0.11.10-hotspot)
#   LOCAL_INSTRUMENTED_SERIAL        adb serial         (emulator-5554)
#   LOCAL_INSTRUMENTED_BOOT_TIMEOUT  boot wait seconds  (300)
# =============================================================================

set -uo pipefail

# ---- configuration (env-overridable; defaults are correct for this machine) -----------
AVD_NAME="${LOCAL_INSTRUMENTED_AVD:-dev36_google_apis_x86_64_Pixel_2}"
AVD_HOME="${ANDROID_AVD_HOME:-C:/Users/jasonross/.android/avd/gradle-managed}"
JDK_HOME="${LOCAL_INSTRUMENTED_JDK:-C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot}"
SERIAL="${LOCAL_INSTRUMENTED_SERIAL:-emulator-5554}"
BOOT_TIMEOUT="${LOCAL_INSTRUMENTED_BOOT_TIMEOUT:-300}"
QEMU_IMAGE="qemu-system-x86_64-headless.exe"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"     # .claude/skills/preflight -> repo root
GRADLEW="${REPO_ROOT}/gradlew"
EMU_LOG="${TMPDIR:-/tmp}/libremail-local-instrumented-emulator.log"

EMU_PID=""
TEST_EXIT=1
ZOMBIE=0
TEARDOWN_DONE=0

log()  { printf '\n=== %s ===\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 2; }

# All emulator/qemu processes Windows currently sees (empty string if none).
list_emu_procs() { tasklist 2>/dev/null | grep -iE 'qemu|emulator' || true; }
list_qemu()      { tasklist 2>/dev/null | grep -i 'qemu' || true; }

# ---- argument parsing -----------------------------------------------------------------
TEST_CLASSES="${1:-}"
if [[ -z "${TEST_CLASSES}" ]]; then
  cat >&2 <<'USAGE'
usage: local_instrumented.sh <fully.qualified.TestClass>[,<Class2>,...]

Cold-boots ONE emulator (no GMD, no snapshot) and runs :app:connectedDebugAndroidTest
filtered to the given instrumented test class(es). Keep the set small and targeted —
see the header for the ~114-test mid-suite wedge.
USAGE
  exit 2
fi

# ---- preconditions --------------------------------------------------------------------
command -v emulator >/dev/null 2>&1 || die "emulator not on PATH (install Android SDK emulator)."
command -v adb      >/dev/null 2>&1 || die "adb not on PATH (install Android SDK platform-tools)."
command -v tasklist >/dev/null 2>&1 || die "tasklist not found — this helper targets Windows/Git Bash."
[[ -f "${GRADLEW}" ]] || die "gradlew not found at ${GRADLEW}."
[[ -d "${JDK_HOME}" ]] || die "JDK 17-21 not found at '${JDK_HOME}'. Set LOCAL_INSTRUMENTED_JDK."
[[ -f "${AVD_HOME}/${AVD_NAME}.ini" ]] || \
  die "AVD '${AVD_NAME}' not found under '${AVD_HOME}'. Set LOCAL_INSTRUMENTED_AVD / ANDROID_AVD_HOME.
       (GMD AVDs are created by any local apiXXDebugAndroidTest run.)"

export JAVA_HOME="${JDK_HOME}"
export ANDROID_AVD_HOME="${AVD_HOME}"

# ---- teardown: always runs (normal exit, error, or Ctrl-C) ----------------------------
teardown() {
  [[ "${TEARDOWN_DONE}" == "1" ]] && return 0
  TEARDOWN_DONE=1

  log "Teardown: killing emulator and verifying no orphaned qemu remains"
  adb -s "${SERIAL}" emu kill >/dev/null 2>&1 || true
  sleep 2

  # Belt-and-suspenders: kill the emulator launcher process we started, if still alive.
  if [[ -n "${EMU_PID}" ]] && kill -0 "${EMU_PID}" 2>/dev/null; then
    kill "${EMU_PID}" 2>/dev/null || true
    sleep 1
    kill -9 "${EMU_PID}" 2>/dev/null || true
  fi

  # Verify: any surviving qemu is a machine-freezing zombie — force-kill and re-check.
  local remaining
  remaining="$(list_qemu)"
  if [[ -n "${remaining}" ]]; then
    warn "qemu still present after 'adb emu kill'; force-killing:"
    printf '%s\n' "${remaining}" >&2
    taskkill //F //IM "${QEMU_IMAGE}" >/dev/null 2>&1 || true
    # Sweep any other stray qemu-system image name, too.
    taskkill //F //IM "qemu-system-x86_64.exe" >/dev/null 2>&1 || true
    sleep 2
    remaining="$(list_qemu)"
    if [[ -n "${remaining}" ]]; then
      warn "qemu ZOMBIE survived teardown — kill it by hand or the machine may freeze:"
      printf '%s\n' "${remaining}" >&2
      ZOMBIE=1
    fi
  fi

  adb kill-server >/dev/null 2>&1 || true
}
trap teardown EXIT INT TERM

# ---- 1. orphan-kill preamble ----------------------------------------------------------
log "Orphan-kill preamble: ensuring a clean slate before boot"
existing="$(list_emu_procs)"
if [[ -n "${existing}" ]]; then
  warn "Pre-existing emulator/qemu processes found — force-killing them first:"
  printf '%s\n' "${existing}" >&2
  taskkill //F //IM "${QEMU_IMAGE}" //IM "emulator.exe" >/dev/null 2>&1 || true
  sleep 2
else
  echo "No pre-existing qemu/emulator processes."
fi
adb kill-server  >/dev/null 2>&1 || true
adb start-server >/dev/null 2>&1 || true

# ---- 2. cold-boot ONE emulator (no snapshot) ------------------------------------------
log "Cold-booting @${AVD_NAME} (no GMD, no snapshot); log -> ${EMU_LOG}"
emulator "@${AVD_NAME}" \
  -no-window -no-snapshot -no-boot-anim -no-audio \
  -gpu auto-no-window -cores 8 -wipe-data \
  >"${EMU_LOG}" 2>&1 &
EMU_PID=$!
echo "emulator launcher pid=${EMU_PID}"

echo "Waiting up to ${BOOT_TIMEOUT}s for sys.boot_completed on ${SERIAL}..."
deadline=$(( $(date +%s) + BOOT_TIMEOUT ))
booted=0
while (( $(date +%s) < deadline )); do
  if ! kill -0 "${EMU_PID}" 2>/dev/null; then
    warn "emulator process exited during boot; last log lines:"
    tail -n 40 "${EMU_LOG}" >&2 || true
    break
  fi
  state="$(adb -s "${SERIAL}" get-state 2>/dev/null | tr -d '\r')"
  if [[ "${state}" == "device" ]]; then
    bc="$(adb -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n ')"
    if [[ "${bc}" == "1" ]]; then booted=1; break; fi
  fi
  sleep 3
done

if [[ "${booted}" != "1" ]]; then
  warn "Emulator did not reach sys.boot_completed within ${BOOT_TIMEOUT}s."
  tail -n 40 "${EMU_LOG}" >&2 || true
  # teardown runs via the EXIT trap; surface a boot failure distinctly.
  exit 4
fi
echo "Emulator booted."

# Dismiss the keyguard (mirrors CI + api37_e2e.py). Best-effort: a cold -wipe-data boot
# rarely needs it, and the input service can lose a race right after boot.
adb -s "${SERIAL}" shell input keyevent 82 >/dev/null 2>&1 || true

# ---- 3. run the targeted instrumented tests -------------------------------------------
log "Running :app:connectedDebugAndroidTest for: ${TEST_CLASSES}"
echo "JAVA_HOME=${JAVA_HOME}"
"${GRADLEW}" :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=${TEST_CLASSES}" \
  --stacktrace
TEST_EXIT=$?

# ---- 4. teardown + verify, then exit --------------------------------------------------
teardown

if (( ZOMBIE != 0 )); then
  warn "Exiting 3: a qemu zombie was left behind (see above) — clean it up before the next run."
  exit 3
fi
if (( TEST_EXIT != 0 )); then
  warn "connectedDebugAndroidTest failed (exit ${TEST_EXIT}). Report: app/build/reports/androidTests/connected/"
  exit "${TEST_EXIT}"
fi
log "PASS — instrumented tests green for: ${TEST_CLASSES}"
exit 0
