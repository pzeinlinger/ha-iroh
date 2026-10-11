#!/bin/bash
# One-time: create the APK signing key and upload it as GitHub secrets (needs keytool, gh).
# KEEP ha-iroh.jks IN A PASSWORD MANAGER. Losing it = no updates for existing installs.
set -euo pipefail
cd "$(dirname "$0")/.."
key_alias=ha-iroh
read -rsp "Keystore/key password: " pass; echo
if [ -f ha-iroh.jks ]; then keytool -list -keystore ha-iroh.jks -storepass "$pass" >/dev/null
else keytool -genkeypair -v -keystore ha-iroh.jks -alias "$key_alias" -keyalg RSA -keysize 4096 \
  -validity 36500 -storepass "$pass" -keypass "$pass"
fi
base64 < ha-iroh.jks | gh secret set KEYSTORE_B64
printf %s "$pass" | gh secret set KEYSTORE_PASS
printf %s "$pass" | gh secret set KEY_PASS
printf %s "$key_alias" | gh secret set KEY_ALIAS
echo "Done. Back up ha-iroh.jks now (it is gitignored)."
