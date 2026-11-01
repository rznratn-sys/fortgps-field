#!/usr/bin/env bash
set -euo pipefail
: "${FIELD_KEYSTORE:?Set private release keystore path}"
: "${FIELD_STORE_PASSWORD:?Load password from private signing configuration}"
cd "$(dirname "$0")/../android"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintRelease
