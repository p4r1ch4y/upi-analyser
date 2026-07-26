#!/usr/bin/env bash
#
# Pushes the signing key from keystore.properties into GitHub Actions secrets
# and GitLab CI variables, so tagged builds sign themselves.
#
#   ./tools/push-ci-secrets.sh            both
#   ./tools/push-ci-secrets.sh github     one
#   ./tools/push-ci-secrets.sh gitlab
#
# Reads the values from keystore.properties rather than asking, so the password
# is never typed into a shell history or a terminal that might be shared.

set -euo pipefail
cd "$(dirname "$0")/.."

TARGET="${1:-both}"

if [ ! -f keystore.properties ]; then
    echo "error: keystore.properties not found. Run ./tools/make-release-key.sh first."
    exit 1
fi

# shellcheck disable=SC1091
STORE_FILE=$(grep -E '^storeFile=' keystore.properties | cut -d= -f2-)
STORE_PASS=$(grep -E '^storePassword=' keystore.properties | cut -d= -f2-)
KEY_ALIAS=$(grep -E '^keyAlias=' keystore.properties | cut -d= -f2-)
KEY_PASS=$(grep -E '^keyPassword=' keystore.properties | cut -d= -f2-)

if [ ! -f "$STORE_FILE" ]; then
    echo "error: $STORE_FILE not found."
    exit 1
fi

KEYSTORE_B64=$(base64 -w0 "$STORE_FILE")

push_github() {
    command -v gh >/dev/null || { echo "skip github: gh not installed"; return; }
    echo "→ GitHub Actions secrets"
    printf '%s' "$KEYSTORE_B64" | gh secret set ANDROID_KEYSTORE_BASE64
    printf '%s' "$STORE_PASS"   | gh secret set ANDROID_KEYSTORE_PASSWORD
    printf '%s' "$KEY_ALIAS"    | gh secret set ANDROID_KEY_ALIAS
    printf '%s' "$KEY_PASS"     | gh secret set ANDROID_KEY_PASSWORD
    gh secret list
}

push_gitlab() {
    command -v glab >/dev/null || { echo "skip gitlab: glab not installed"; return; }
    echo "→ GitLab CI/CD variables"
    # Masked so they never appear in job output; protected so they are only
    # exposed to pipelines on protected refs — which is why the v* tag pattern
    # needs protecting too (Settings -> Repository -> Protected tags).
    set_var() {
        local key="$1" value="$2" masked="$3"
        glab variable delete "$key" >/dev/null 2>&1 || true
        printf '%s' "$value" | glab variable set "$key" --masked="$masked" --protected -
    }
    # A base64 keystore is far too long for GitLab's masking rules, so it goes
    # in unmasked but still protected. It is a public certificate wrapper around
    # a password-protected key; the password is what stays masked.
    set_var ANDROID_KEYSTORE_BASE64   "$KEYSTORE_B64" false
    set_var ANDROID_KEYSTORE_PASSWORD "$STORE_PASS"   true
    set_var ANDROID_KEY_ALIAS         "$KEY_ALIAS"    true
    set_var ANDROID_KEY_PASSWORD      "$KEY_PASS"     true
    glab variable list
}

case "$TARGET" in
    github) push_github ;;
    gitlab) push_gitlab ;;
    both)   push_github; echo; push_gitlab ;;
    *) echo "usage: $0 [github|gitlab|both]"; exit 1 ;;
esac

echo
echo "Done. Cut a release with:"
echo "    git tag -a v0.1.1 -m 'SpendLens 0.1.1' && git push --tags"
