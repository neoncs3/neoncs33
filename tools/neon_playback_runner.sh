#!/usr/bin/env bash
set -euo pipefail

APP_ID="com.lagradost.cloudstream3.prerelease.debug"
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

# Bu tanılama adımları emulator/Android sürümüne göre 0 dışı dönebilir.
# Playback testinin sırf AppOps komutu yüzünden başlamasını engelleme.
echo ">>> Kurulu APK:"
if ! adb shell pm path "${APP_ID}"; then
  echo "UYARI: pm path başarısız oldu; uygulamanın gerçekten kurulduğunu ayrıca kontrol edeceğiz."
fi

echo ">>> MANAGE_EXTERNAL_STORAGE AppOp:"
if adb shell appops set "${APP_ID}" MANAGE_EXTERNAL_STORAGE allow; then
  echo "MANAGE_EXTERNAL_STORAGE=allow ayarlandı."
else
  echo "UYARI: MANAGE_EXTERNAL_STORAGE AppOp ayarlanamadı; teste devam ediliyor."
fi

adb shell appops get "${APP_ID}" MANAGE_EXTERNAL_STORAGE || true

echo ">>> External storage kontrolü:"
adb shell mkdir -p "${PLUGIN_DIR}" || true
adb shell ls -ld "/sdcard" "/sdcard/Cloudstream3" "${PLUGIN_DIR}" || true

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
rm -f "${REPORT}" "${GITHUB_WORKSPACE}/neon-playback-report.json"

# Prefer the app-private report when run-as is available. GitHub emulator images
# can reject run-as; in that case format_playback_report.py will reconstruct the
# report directly from playback-status/logcat.txt.
if adb exec-out run-as "${APP_ID}" cat files/neon-playback-report.json > "${REPORT}" 2>/dev/null; then
  if [ -s "${REPORT}" ]; then
    if python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); raise SystemExit(0 if isinstance(d,dict) and isinstance(d.get("results"),list) else 1)' "${REPORT}"; then
      cp "${REPORT}" "${GITHUB_WORKSPACE}/neon-playback-report.json"
      cat "${REPORT}"
    else
      echo "UYARI: run-as raporu geçerli JSON değil; logcat raporu kullanılacak."
      rm -f "${REPORT}"
    fi
  fi
else
  echo "UYARI: run-as rapor dosyasına erişilemedi; logcat raporu kullanılacak."
fi

echo "=== TEST_EXIT=${TEST_EXIT} ==="
exit "${TEST_EXIT}"
