#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${APK_PATH:-app/build/outputs/apk/release/app-release.apk}"
OUT_PATH="${OUT_PATH:-dist/update.json}"
VERSION_CODE="${VERSION_CODE:?VERSION_CODE is required}"
VERSION_NAME="${VERSION_NAME:?VERSION_NAME is required}"
COMMIT_SHA="${COMMIT_SHA:?COMMIT_SHA is required}"
EXPECTED_CERT="${EXPECTED_CERT:?EXPECTED_CERT is required}"
PUBLISHED_AT="${PUBLISHED_AT:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
RELEASE_NOTES="${RELEASE_NOTES:-版本 ${VERSION_NAME}（build ${VERSION_CODE}）}"

test -f "$APK_PATH" || { echo "APK not found: $APK_PATH"; exit 1; }
mkdir -p "$(dirname "$OUT_PATH")"

APK_SIZE=$(wc -c < "$APK_PATH" | tr -d ' ')
APK_SHA=$(sha256sum "$APK_PATH" | awk '{print tolower($1)}')
CERT_SHA=$(echo "$EXPECTED_CERT" | tr 'A-F' 'a-f')

python3 - "$OUT_PATH" "$VERSION_CODE" "$VERSION_NAME" "$APK_SIZE" "$APK_SHA" "$CERT_SHA" "$COMMIT_SHA" "$PUBLISHED_AT" "$RELEASE_NOTES" <<'PY'
import json
import sys

path, version_code, version_name, apk_size, apk_sha, cert_sha, commit_sha, published_at, notes = sys.argv[1:]
data = {
    "schemaVersion": 1,
    "applicationId": "com.xjb.medicalquestionai",
    "versionCode": int(version_code),
    "versionName": version_name,
    "minSdk": 26,
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
