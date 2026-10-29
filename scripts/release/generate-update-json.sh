#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${APK_PATH:-app/build/outputs/apk/release/app-release.apk}"
OUT_PATH="${OUT_PATH:-dist/update.json}"
EXPECTED_VERSION_CODE="${EXPECTED_VERSION_CODE:?EXPECTED_VERSION_CODE is required}"
EXPECTED_VERSION_NAME="${EXPECTED_VERSION_NAME:?EXPECTED_VERSION_NAME is required}"
COMMIT_SHA="${COMMIT_SHA:?COMMIT_SHA is required}"
EXPECTED_CERT="${EXPECTED_CERT:?EXPECTED_CERT is required}"
EXPECTED_APPLICATION_ID="${EXPECTED_APPLICATION_ID:-com.xjb.medicalquestionai}"
EXPECTED_MIN_SDK="${EXPECTED_MIN_SDK:-26}"
PUBLISHED_AT="${PUBLISHED_AT:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
RELEASE_NOTES="${RELEASE_NOTES:-版本 ${EXPECTED_VERSION_NAME}（build ${EXPECTED_VERSION_CODE}）}"

test -f "$APK_PATH" || { echo "APK not found: $APK_PATH"; exit 1; }
mkdir -p "$(dirname "$OUT_PATH")"

find_tool() {
    local name="$1"
    if command -v "$name" >/dev/null 2>&1; then
        command -v "$name"
        return 0
    fi
    local local_sdk=""
    if [ -f local.properties ]; then
        local_sdk="$(sed -n 's/^sdk.dir=//p' local.properties | sed 's#\\\\#/#g')"
    fi
    local root candidate
    for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$local_sdk"; do
        [ -n "$root" ] || continue
        [ -d "$root/build-tools" ] || continue
        candidate="$(find "$root/build-tools" -type f \( -name "$name" -o -name "$name.exe" -o -name "$name.bat" \) 2>/dev/null | sort -V | tail -1)"
        if [ -n "$candidate" ] && [ -f "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

AAPT="$(find_tool aapt)"
APKSIGNER="$(find_tool apksigner)"
test -n "$AAPT" || { echo "aapt 未找到"; exit 1; }
test -n "$APKSIGNER" || { echo "apksigner 未找到"; exit 1; }

BADGING="$("$AAPT" dump badging "$APK_PATH")"
PACKAGE_LINE="$(printf '%s\n' "$BADGING" | head -1)"
APK_PACKAGE="$(printf '%s\n' "$PACKAGE_LINE" | sed -n "s/.*package: name='\([^']*\)'.*/\1/p")"
APK_VERSION_CODE="$(printf '%s\n' "$PACKAGE_LINE" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
APK_VERSION_NAME="$(printf '%s\n' "$PACKAGE_LINE" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
APK_MIN_SDK="$(printf '%s\n' "$BADGING" | sed -n "s/.*sdkVersion:'\([0-9]*\)'.*/\1/p" | head -1)"
APK_CERT="$("$APKSIGNER" verify --print-certs "$APK_PATH" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | tr 'A-F' 'a-f')"

test -n "$APK_PACKAGE" || { echo "无法读取 APK 包名"; exit 1; }
test -n "$APK_VERSION_CODE" || { echo "无法读取 APK versionCode"; exit 1; }
test -n "$APK_VERSION_NAME" || { echo "无法读取 APK versionName"; exit 1; }
test -n "$APK_MIN_SDK" || { echo "无法读取 APK minSdk"; exit 1; }
test -n "$APK_CERT" || { echo "无法读取 APK 签名证书"; exit 1; }

[ "$APK_PACKAGE" = "$EXPECTED_APPLICATION_ID" ] || { echo "APK 包名不匹配: $APK_PACKAGE"; exit 1; }
[ "$APK_VERSION_CODE" = "$EXPECTED_VERSION_CODE" ] || { echo "APK versionCode 不匹配: $APK_VERSION_CODE"; exit 1; }
[ "$APK_VERSION_NAME" = "$EXPECTED_VERSION_NAME" ] || { echo "APK versionName 不匹配: $APK_VERSION_NAME"; exit 1; }
[ "$APK_MIN_SDK" = "$EXPECTED_MIN_SDK" ] || { echo "APK minSdk 不匹配: $APK_MIN_SDK"; exit 1; }
[ "$APK_CERT" = "$(printf '%s' "$EXPECTED_CERT" | tr 'A-F' 'a-f')" ] || { echo "APK 签名证书不匹配"; exit 1; }

APK_SIZE=$(wc -c < "$APK_PATH" | tr -d ' ')
APK_SHA=$(sha256sum "$APK_PATH" | awk '{print tolower($1)}')
CERT_SHA="$APK_CERT"
PYTHON=python
command -v "$PYTHON" >/dev/null 2>&1 || PYTHON=python3

"$PYTHON" - "$OUT_PATH" "$APK_PACKAGE" "$APK_VERSION_CODE" "$APK_VERSION_NAME" "$APK_MIN_SDK" "$APK_SIZE" "$APK_SHA" "$CERT_SHA" "$COMMIT_SHA" "$PUBLISHED_AT" "$RELEASE_NOTES" <<'PY'
import json
import sys

path, application_id, version_code, version_name, min_sdk, apk_size, apk_sha, cert_sha, commit_sha, published_at, notes = sys.argv[1:]
data = {
    "schemaVersion": 1,
    "applicationId": application_id,
    "versionCode": int(version_code),
    "versionName": version_name,
    "minSdk": int(min_sdk),
    "apkAssetName": "medical-question-ai.apk",
    "apkSizeBytes": int(apk_size),
    "apkSha256": apk_sha,
    "signingCertSha256": cert_sha,
    "commitSha": commit_sha,
    "publishedAt": published_at,
    "releaseNotes": notes,
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(data, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
PY

echo "Wrote $OUT_PATH"
