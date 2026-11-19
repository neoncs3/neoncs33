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

echo "=== NeonHub, AsyaWatch ve DiziCaN eklentileri indiriliyor ve doğrulanıyor ==="
MANIFEST="${RUNNER_TEMP}/neoncs-plugins.json"
curl -fsSL "https://raw.githubusercontent.com/neoncs3/neoncs33/builds/plugins.json" -o "${MANIFEST}"

python3 - "${MANIFEST}" > "${RUNNER_TEMP}/neon-plugins-manifest.txt" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as f:
    manifest = json.load(f)

expected = {
    "NeonHub": "https://raw.githubusercontent.com/neoncs3/neoncs33/builds/NeonHub.cs3",
    "AsyaWatch": "https://raw.githubusercontent.com/neoncs3/neoncs33/builds/AsyaWatch.cs3",
    "DiziCaN": "https://raw.githubusercontent.com/neoncs3/neoncs33/builds/DiziCaN.cs3",
}
for name, expected_url in expected.items():
    matches = [item for item in manifest if item.get("internalName") == name]
    if len(matches) != 1:
        raise SystemExit(f"Beklenen tek {name} kaydı bulunamadı: {len(matches)}")
    item = matches[0]
    file_hash = str(item.get("fileHash") or "")
    file_size = item.get("fileSize")
    if item.get("url") != expected_url:
        raise SystemExit(f"{name} URL adresi beklenen yayın adresiyle eşleşmiyor")
    if not re.fullmatch(r"sha256-[a-fA-F0-9]{64}", file_hash):
        raise SystemExit(f"{name} SHA-256 alanı geçersiz")
    if not isinstance(file_size, int) or file_size < 1:
        raise SystemExit(f"{name} dosya boyutu geçersiz")
    if name == "AsyaWatch" and int(item.get("version", 0)) < 4:
        raise SystemExit("AsyaWatch sürüm 4 ve üzeri bekleniyor")
    if name == "DiziCaN" and int(item.get("version", 0)) < 12:
        raise SystemExit("DiziCaN sürüm 12 ve üzeri bekleniyor")
    print(f"{name}|{expected_url}|{file_hash}|{file_size}")
PY

while IFS='|' read -r plugin url expected_hash expected_size; do
  [[ -n "${plugin}" ]] || continue
  file="${RUNNER_TEMP}/${plugin}.cs3"
  echo ">>> ${plugin}.cs3 indiriliyor"
  curl -fsSL "${url}" -o "${file}"
  test -s "${file}"

  actual_size="$(stat -c '%s' "${file}")"
  actual_hash="sha256-$(sha256sum "${file}" | awk '{print $1}')"
  if [[ "${actual_size}" != "${expected_size}" || "${actual_hash}" != "${expected_hash}" ]]; then
    echo "::error::${plugin}.cs3 manifest hash/boyut kontrolünden geçemedi."
    exit 1
  fi

  adb push "${file}" "${PLUGIN_DIR}/${plugin}.cs3"
  echo "${plugin}.cs3 manifest hash ve boyut kontrolünden geçti."
done < "${RUNNER_TEMP}/neon-plugins-manifest.txt"

echo "=== Emulator plugin dosyaları ==="
adb shell ls -lah "${PLUGIN_DIR}"
adb shell "run-as ${APP_ID} ls -lah ${PLUGIN_DIR}" || true

echo "=== Gerçek CloudStream / ExoPlayer testi ==="
set +e
./gradlew :app:connectedPrereleaseDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.cloudstream3.NeonPlaybackSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.providers=DiziCaN \
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
