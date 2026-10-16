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
  DiziDad
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

# Include every generator-created provider, not just the old hard-coded controls.
# Without this, a newly created Auto*.cs3 would never be loaded by the emulator
# and the smoke test could falsely appear to validate the automatic generator.
echo "=== Otomatik oluşturulan eklentiler indiriliyor ==="
MANIFEST="${RUNNER_TEMP}/neoncs-plugins.json"
curl -fsSL "https://raw.githubusercontent.com/neoncs3/neoncs33/builds/plugins.json" -o "${MANIFEST}"
python3 - "${MANIFEST}" > "${RUNNER_TEMP}/neon-auto-plugins.tsv" <<'PY'
import json
import re
import sys

manifest = json.load(open(sys.argv[1], encoding="utf-8"))
expected_prefix = "https://raw.githubusercontent.com/neoncs3/neoncs33/builds/"
for item in manifest:
    name = str(item.get("internalName") or "")
    url = str(item.get("url") or "")
    file_hash = str(item.get("fileHash") or "")
    file_size = item.get("fileSize")
    if not re.fullmatch(r"Auto[A-Za-z0-9]+", name):
        continue
    if url != expected_prefix + name + ".cs3":
        continue
    if not re.fullmatch(r"sha256-[a-fA-F0-9]{64}", file_hash):
        continue
    if not isinstance(file_size, int) or file_size < 1:
        continue
    print(f"{name}\t{url}\t{file_hash}\t{file_size}")
PY

AUTO_PLUGIN_COUNT=0
while read -r plugin url expected_hash expected_size; do
  [[ -n "${plugin}" ]] || continue
  [[ "${plugin}" =~ ^Auto[A-Za-z0-9]+$ ]] || {
    echo "::error::Güvenli olmayan otomatik eklenti adı: ${plugin}"
    exit 1
  }
  file="${RUNNER_TEMP}/${plugin}.cs3"
  echo ">>> Otomatik ${plugin}.cs3 indiriliyor"
  curl -fsSL "${url}" -o "${file}"
  test -s "${file}"

  actual_size="$(stat -c '%s' "${file}")"
  actual_hash="sha256-$(sha256sum "${file}" | awk '{print $1}')"
  if [[ "${actual_size}" != "${expected_size}" || "${actual_hash}" != "${expected_hash}" ]]; then
    echo "::error::${plugin}.cs3 manifest hash/boyut kontrolünden geçemedi."
    exit 1
  fi

  adb push "${file}" "${PLUGIN_DIR}/${plugin}.cs3"
  AUTO_PLUGIN_COUNT=$((AUTO_PLUGIN_COUNT + 1))
done < "${RUNNER_TEMP}/neon-auto-plugins.tsv"

if [[ "${AUTO_PLUGIN_COUNT}" -eq 0 ]]; then
  echo "::error::plugins.json içinde doğrulanmış Auto*.cs3 eklentisi bulunamadı; otomatik oluşturucu playback testi yapılmayacak."
  exit 1
fi
echo "Doğrulanmış otomatik eklenti sayısı: ${AUTO_PLUGIN_COUNT}"

echo "=== Emulator plugin dosyaları ==="
adb shell ls -lah "${PLUGIN_DIR}"
adb shell "run-as ${APP_ID} ls -lah ${PLUGIN_DIR}" || true

echo "=== Gerçek CloudStream / ExoPlayer testi ==="
set +e
./gradlew :app:connectedPrereleaseDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.cloudstream3.NeonPlaybackSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.providers=DiziDAD,Dizimag,TvDiziler,HdFilmDiziizle \
  --no-daemon --stacktrace 2>&1 | tee "${RUNNER_TEMP}/instrumentation.log"
TEST_EXIT="${PIPESTATUS[0]}"
set -e

cp "${RUNNER_TEMP}/instrumentation.log" "${INSTRUMENTATION_LOG}" || true

echo "=== Playback raporu alınıyor ==="
rm -f "${REPORT}" "${GITHUB_WORKSPACE}/neon-playback-report.json"

# Prefer the app-private report when run-as is available. GitHub emulator images
# can reject run-as; in that case format_playback_report.py will reconstruct the
# report directly from playback-status/logcat.txt.
adb exec-out run-as "${APP_ID}" cat files/neon-playback-report.json > "${REPORT}" 2>/dev/null || true
if [ -s "${REPORT}" ]; then
  if python3 -c 'import json,sys; from pathlib import Path; p=Path(sys.argv[1]); d=json.loads(p.read_text(encoding="utf-8")); sys.exit(0 if isinstance(d,dict) and isinstance(d.get("results"),list) else 1)' "${REPORT}" 2>/dev/null; then
    cp "${REPORT}" "${GITHUB_WORKSPACE}/neon-playback-report.json"
    cat "${REPORT}"
  else
    echo "UYARI: run-as raporu geçerli JSON değil; logcat raporu kullanılacak."
    rm -f "${REPORT}"
  fi
else
  echo "UYARI: run-as rapor dosyasına erişilemedi; logcat raporu kullanılacak."
fi

echo "=== TEST_EXIT=${TEST_EXIT} ==="
exit "${TEST_EXIT}"
