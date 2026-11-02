#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077
# No defaults: a missing alias/password must fail before decoding anything.
for name in FIELD_RELEASE_KEYSTORE_BASE64 FIELD_STORE_PASSWORD FIELD_KEY_PASSWORD FIELD_KEY_ALIAS; do
  if [[ -z "${!name:-}" ]]; then echo "::error::Required signing configuration missing: $name"; exit 1; fi
done
keydir=$(mktemp -d "$RUNNER_TEMP/field-signing.XXXXXXXX")
trap 'rm -rf -- "$keydir"' EXIT HUP INT TERM
# Prevent tool diagnostics from exposing private input; report only a generic failure.
if ! printf '%s' "$FIELD_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$keydir/release.p12" 2> "$keydir/error"; then
  echo '::error::Invalid encoded signing material'; exit 1
fi
unset FIELD_RELEASE_KEYSTORE_BASE64
export FIELD_KEYSTORE="$keydir/release.p12"
# Prove the private key entry and passwords work before signing.
if ! keytool -certreq -keystore "$FIELD_KEYSTORE" -alias "$FIELD_KEY_ALIAS" -storepass:env FIELD_STORE_PASSWORD -keypass:env FIELD_KEY_PASSWORD > "$keydir/request" 2> "$keydir/error"; then
  echo '::error::Signing entry or password invalid'; exit 1
fi
keytool -exportcert -keystore "$FIELD_KEYSTORE" -alias "$FIELD_KEY_ALIAS" -storepass:env FIELD_STORE_PASSWORD > "$keydir/cert.der" 2> "$keydir/error" || { echo '::error::Cannot export public certificate'; exit 1; }
actual=$(sha256sum "$keydir/cert.der" | cut -d ' ' -f 1)
[[ "$actual" == "$FIELD_RELEASE_CERT_SHA256" ]] || { echo '::error::Keystore certificate differs from approved identity'; exit 1; }
mkdir delivery
apk_name=$(python3 -c 'import json; print(json.load(open("scripts/release/plan.json"))["apk_name"])')
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -P 16 -f 4 unsigned/app-release-unsigned.apk "$keydir/aligned.apk"
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 "$keydir/aligned.apk"
if ! "$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$FIELD_KEYSTORE" --ks-key-alias "$FIELD_KEY_ALIAS" --ks-pass env:FIELD_STORE_PASSWORD --key-pass env:FIELD_KEY_PASSWORD --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out "delivery/$apk_name" "$keydir/aligned.apk" > "$keydir/output" 2> "$keydir/error"; then
  echo '::error::APK signing failed'; exit 1
fi
