#!/usr/bin/env bash
set -euo pipefail

APP_ID="com.lagradost.cloudstream3.prerelease"
REPORT="${RUNNER_TEMP}/neon-playback-report.json"
LOG="${GITHUB_WORKSPACE}/playback-status/logcat.txt"
INSTRUMENTATION_LOG="${GITHUB_WORKSPACE}/playback-status/instrumentation.log"
PLUGIN_DIR="/sdcard/Cloudstream3/plugins"

mkdir -p "${GITHUB_WORKSPACE}/playback-status"
rm -f "${GITHUB_WORKSPACE}/neon-playback-report.json" "${INSTRUMENTATION_LOG}" "${LOG}"

adb logcat -c || true
trap 'adb logcat -d -v time > "${LOG}" || true' EXIT

echo "=== CloudStream temiz kurulum ==="
adb uninstall "${APP_ID}" || true
./gradlew :app:installPrereleaseDebug --no-daemon --stacktrace

echo "=== CloudStream depolama yetkisi ==="
adb shell pm path "${APP_ID}"
adb shell appops set "${APP_ID}" MANAGE_EXTERNAL_STORAGE allow
adb shell appops get "${APP_ID}" MANAGE_EXTERNAL_STORAGE || true

echo "=== Plugin klasörü hazırlanıyor ==="
adb shell mkdir -p "${PLUGIN_DIR}"
adb shell rm -f "${PLUGIN_DIR}"/*.cs3 || true

plugins=(
  AsyaFilmIzle
  DiziBoxizle
  DiziPal
  Dizigecesi
  Dramadizilerim
  FilmModu
  JetFilmizle
  SetFilmIzle
  SinemaCX
  WebDramaTurkey
)

for plugin in "${plugins[@]}"; do
  file="${RUNNER_TEMP}/${plugin}.cs3"
  echo ">>> ${plugin}.cs3 indiriliyor"
  curl -fsSL "https://raw.githubusercontent.com/neoncs3/neoncs33/builds/${plugin}.cs3" -o "${file}"
  test -s "${file}"
  adb push "${file}" "${PLUGIN_DIR}/${plugin}.cs3"
done

echo "=== Emulator plugin dosyaları ==="
adb shell ls -lah "${PLUGIN_DIR}"
adb shell "run-as ${APP_ID} ls -lah ${PLUGIN_DIR}" || true

echo "=== Gerçek CloudStream / ExoPlayer testi ==="
set +e
./gradlew :app:connectedPrereleaseDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.cloudstream3.NeonPlaybackSmokeTest \
  --no-daemon --stacktrace 2>&1 | tee "${RUNNER_TEMP}/instrumentation.log"
TEST_EXIT="${PIPESTATUS[0]}"
set -e

cp "${RUNNER_TEMP}/instrumentation.log" "${INSTRUMENTATION_LOG}" || true

echo "=== Playback raporu alınıyor ==="
rm -f "${REPORT}"
adb exec-out run-as "${APP_ID}" cat files/neon-playback-report.json > "${REPORT}" || true

if [ -s "${REPORT}" ]; then
  cp "${REPORT}" "${GITHUB_WORKSPACE}/neon-playback-report.json"
  cat "${REPORT}"
else
  echo '{"results":[],"pass":0,"blocked":0,"fail":1,"error":"Instrumentation raporu alınamadı"}' > "${GITHUB_WORKSPACE}/neon-playback-report.json"
  cat "${GITHUB_WORKSPACE}/neon-playback-report.json"
fi

echo "=== TEST_EXIT=${TEST_EXIT} ==="
exit "${TEST_EXIT}"
