#!/usr/bin/env bash
set -euo pipefail
# Run from any directory; secrets are provided via environment, never printed.
cd "$(dirname "$0")/../android"
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
apk=app/build/outputs/apk/release/app-release.apk
if [[ -z "${FIELD_KEYSTORE:-}" ]]; then
  echo 'Release is unsigned: not an installable delivery. Configure the original private signing identity.' >&2
  exit 2
fi
"${ANDROID_HOME:?}/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$apk"
sha256sum "$apk"
