#!/usr/bin/env bash
#
# Creates the release signing key and wires it up locally and in CI.
#
#   ./tools/make-release-key.sh
#
# Run this once, ever. The key you create here becomes the app's permanent
# identity: Android will only accept an update if it is signed with the same key.
# Lose it and you cannot ship an update to anyone — they would have to uninstall
# first, which erases their ledger.
#
# Nothing this script creates is committed. `spendlens-release.jks` and
# `keystore.properties` are both git-ignored.

set -euo pipefail

cd "$(dirname "$0")/.."

KEYSTORE="spendlens-release.jks"
ALIAS="spendlens"

if [ -f "$KEYSTORE" ]; then
    echo "error: $KEYSTORE already exists."
    echo
    echo "Refusing to overwrite it. If this really is a fresh start and no build"
    echo "signed with it has ever been published, move it aside first:"
    echo "    mv $KEYSTORE $KEYSTORE.old"
    exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
    JBR="${JAVA_HOME:-}/bin/keytool"
    if [ -x "$JBR" ]; then
        keytool() { "$JBR" "$@"; }
    else
        echo "error: keytool not found. Set JAVA_HOME to a JDK, e.g."
        echo "    JAVA_HOME=~/android-studio/jbr ./tools/make-release-key.sh"
        exit 1
    fi
fi

echo "Creating $KEYSTORE."
echo
echo "You will be asked for a password, then a few identity questions. The"
echo "identity fields are cosmetic — they show up in the certificate and nothing"
echo "checks them. The password is what matters: put it in a password manager"
echo "before you close this terminal."
echo
read -rsp "Choose a keystore password (min 6 chars): " PASS; echo
read -rsp "Type it again: " PASS2; echo

if [ "$PASS" != "$PASS2" ]; then
    echo "error: passwords do not match."
    exit 1
fi
if [ "${#PASS}" -lt 6 ]; then
    echo "error: keytool requires at least 6 characters."
    exit 1
fi

# 10000 days ~= 27 years. Play requires a key valid past 2033; F-Droid does not
# care, but a key that expires is a key you have to migrate away from, and
# migrating a signing key means every user reinstalling.
keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=SpendLens, OU=SpendLens, O=SpendLens, L=, ST=, C=IN"

umask 077
cat > keystore.properties <<EOF
storeFile=$KEYSTORE
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF

echo
echo "Done. Local release builds are now signed:"
echo "    ./gradlew assembleStandardRelease"
echo
echo "Fingerprint (this is what identifies your app to Android forever):"
keytool -list -v -keystore "$KEYSTORE" -storepass "$PASS" -alias "$ALIAS" \
    | grep -E "SHA256:" | head -1
echo
echo "Next, push the key to CI so releases build themselves. From this directory:"
echo
echo "    gh secret set ANDROID_KEYSTORE_BASE64   < <(base64 -w0 $KEYSTORE)"
echo "    gh secret set ANDROID_KEYSTORE_PASSWORD"
echo "    gh secret set ANDROID_KEY_ALIAS"
echo "    gh secret set ANDROID_KEY_PASSWORD"
echo
echo "or run ./tools/push-ci-secrets.sh to do all of that from keystore.properties."
echo
echo "BACK UP $KEYSTORE AND ITS PASSWORD NOW."
echo "There is no recovery. Losing it ends your ability to update the app."
