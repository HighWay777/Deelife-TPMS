#!/usr/bin/env bash
# Build, verify and publish a Deelife TPMS release to Gitea.
#
# Usage:  scripts/publish-release.sh <release-notes.md> [--draft]
#
# Creates tag v<versionName> (from app/build.gradle.kts), a Gitea release with that
# body, and uploads two assets that the in-app updater relies on:
#   Deelife-TPMS-v<versionName>.apk   signed with the release key
#   update.json                       {versionCode, versionName, apk, sha256, minSdk}
#
# Auth: $GITEA_TOKEN if set, otherwise the git credential stored for the server.
# Requires: JDK 17 (JAVA_HOME), Android SDK (tpms-app/local.properties), curl, python.
set -euo pipefail

SERVER="https://git.vhelectronics.com"
REPO="vhadmin/Deelife-TPMS"
# SHA-256 of the release signing certificate. Every update MUST be signed with this key,
# otherwise Android refuses to install it over the existing app.
EXPECTED_CERT_SHA256="F5:75:78:43:EB:43:98:BB:F0:91:F1:BE:73:F2:28:CB:19:75:CB:FB:AC:45:20:1A:53:B4:C9:D3:59:12:47:FF"

NOTES_FILE="${1:?usage: $0 <release-notes.md> [--draft]}"
DRAFT=false
[[ "${2:-}" == "--draft" ]] && DRAFT=true
[[ -f "$NOTES_FILE" ]] || { echo "Notes file not found: $NOTES_FILE" >&2; exit 1; }
NOTES_FILE="$(cd "$(dirname "$NOTES_FILE")" && pwd)/$(basename "$NOTES_FILE")"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/tpms-app"
GRADLE_FILE="$APP/app/build.gradle.kts"

VERSION_NAME=$(sed -n 's/^ *versionName *= *"\(.*\)".*/\1/p' "$GRADLE_FILE" | head -1)
VERSION_CODE=$(sed -n 's/^ *versionCode *= *\([0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)
MIN_SDK=$(sed -n 's/^ *minSdk *= *\([0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)
TAG="v$VERSION_NAME"
APK_NAME="Deelife-TPMS-$TAG.apk"
echo "==> Releasing $TAG (versionCode $VERSION_CODE, minSdk $MIN_SDK)"

# --- sanity checks -----------------------------------------------------------
if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then
    echo "Working tree is not clean; commit first." >&2; exit 1
fi
if git -C "$ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "Tag $TAG already exists; bump versionCode/versionName first." >&2; exit 1
fi
[[ -f "$APP/keystore.properties" ]] || { echo "tpms-app/keystore.properties missing (release key)." >&2; exit 1; }

# --- auth --------------------------------------------------------------------
if [[ -n "${GITEA_TOKEN:-}" ]]; then
    AUTH=(-H "Authorization: token $GITEA_TOKEN")
else
    CRED=$(printf 'url=%s\n\n' "$SERVER" | git credential fill)
    GUSER=$(sed -n 's/^username=//p' <<<"$CRED")
    GPASS=$(sed -n 's/^password=//p' <<<"$CRED")
    AUTH=(-u "$GUSER:$GPASS")
fi
API="$SERVER/api/v1/repos/$REPO"

# --- build -------------------------------------------------------------------
echo "==> Building"
(
    cd "$APP"
    if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
        cmd //c "gradlew.bat --no-daemon -q testDebugUnitTest assembleRelease"
    else
        ./gradlew --no-daemon -q testDebugUnitTest assembleRelease
    fi
)
APK="$APP/app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || { echo "Signed release APK not found (is keystore.properties valid?)" >&2; exit 1; }

# --- verify signing key -------------------------------------------------------
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p' | tr -d '\r')
fi
KEYTOOL="$JAVA_HOME/bin/keytool"
CERT=$("$KEYTOOL" -printcert -jarfile "$APK" | sed -n 's/^[[:space:]]*SHA256: *//p' | head -1 | tr -d '\r')
if [[ "$CERT" != "$EXPECTED_CERT_SHA256" ]]; then
    echo "APK signed with unexpected certificate: $CERT" >&2; exit 1
fi
echo "==> Signature OK"

# --- assets --------------------------------------------------------------------
DIST="$APP/build/dist"
rm -rf "$DIST"; mkdir -p "$DIST"
cp "$APK" "$DIST/$APK_NAME"
SHA=$(sha256sum "$DIST/$APK_NAME" | cut -d' ' -f1)
cat > "$DIST/update.json" <<EOF
{"versionCode":$VERSION_CODE,"versionName":"$VERSION_NAME","apk":"$APK_NAME","sha256":"$SHA","minSdk":$MIN_SDK}
EOF
echo "==> $APK_NAME sha256=$SHA"

# --- tag + release -----------------------------------------------------------------
git -C "$ROOT" tag -a "$TAG" -m "Release $TAG"
git -C "$ROOT" push origin "$TAG"

BODY_JSON=$(python -c 'import json,sys; print(json.dumps(open(sys.argv[1],encoding="utf-8").read()))' "$NOTES_FILE")
RELEASE=$(curl -sf "${AUTH[@]}" -H "Content-Type: application/json" -X POST "$API/releases" \
    -d "{\"tag_name\":\"$TAG\",\"name\":\"Deelife TPMS $TAG\",\"body\":$BODY_JSON,\"draft\":$DRAFT,\"prerelease\":false}")
RELEASE_ID=$(python -c 'import json,sys; print(json.loads(sys.stdin.read())["id"])' <<<"$RELEASE")
echo "==> Created release id $RELEASE_ID"

for f in "$DIST/$APK_NAME" "$DIST/update.json"; do
    name=$(basename "$f")
    curl -sf "${AUTH[@]}" -X POST "$API/releases/$RELEASE_ID/assets?name=$name" \
        -F "attachment=@$f" >/dev/null
    echo "==> Uploaded $name"
done

echo "==> Done: $SERVER/$REPO/releases/tag/$TAG"
