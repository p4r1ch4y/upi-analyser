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

# GitLab will only mask a value that is a single line, at least 8 characters,
# and drawn from the Base64 alphabet plus @ : . ~. A password with a hyphen,
# underscore, or punctuation outside that set is rejected outright — with a bare
# `400 {message: {value: [is invalid]}}` that says nothing about why.
maskable() {
    local v="$1"
    [ "${#v}" -ge 8 ] || return 1
    [[ "$v" =~ ^[A-Za-z0-9+/=@:.~]+$ ]] || return 1
    return 0
}

push_gitlab() {
    command -v glab >/dev/null || { echo "skip gitlab: glab not installed"; return; }
    echo "→ GitLab CI/CD variables"

    set_var() {
        local key="$1" value="$2" want_mask="$3"
        local flags=(--protected)

        # Note the value arrives on stdin with no positional argument: glab only
        # reads stdin when the value is omitted entirely. Passing `-` sets the
        # variable to the literal string "-", silently and successfully.
        if [ "$want_mask" = "yes" ] && maskable "$value"; then
            flags+=(--masked)
        elif [ "$want_mask" = "yes" ]; then
            echo "   note: $key cannot be masked by GitLab (needs 8+ chars from"
            echo "         A-Z a-z 0-9 + / = @ : . ~). Stored protected but unmasked."
        fi

        glab variable delete "$key" >/dev/null 2>&1 || true
        printf '%s' "$value" | glab variable set "$key" "${flags[@]}" >/dev/null
        echo "   set $key"
    }

    # The base64 keystore is thousands of characters, far past what GitLab will
    # mask, so it is stored protected only. That is acceptable: it is a
    # password-protected container, and the password beside it is what matters.
    set_var ANDROID_KEYSTORE_BASE64   "$KEYSTORE_B64" no
    set_var ANDROID_KEYSTORE_PASSWORD "$STORE_PASS"   yes
    set_var ANDROID_KEY_ALIAS         "$KEY_ALIAS"    yes
    set_var ANDROID_KEY_PASSWORD      "$KEY_PASS"     yes

    echo
    echo "   verifying what GitLab actually stored:"
    glab api "projects/${CI_PROJECT_PATH:-$(git remote get-url gitlab 2>/dev/null | sed -E 's#.*[:/]([^/]+/[^/]+)\.git#\1#' | sed 's#/#%2F#')}/variables" 2>/dev/null \
        | python3 -c "
import sys, json
try:
    for v in json.load(sys.stdin):
        print(f\"     {v['key']:28} masked={v['masked']} protected={v['protected']} length={len(v['value'])}\")
except Exception:
    print('     (could not read them back — check the project settings)')"
}

case "$TARGET" in
    github) push_github ;;
    gitlab) push_gitlab ;;
    both)   push_github; echo; push_gitlab ;;
    *) echo "usage: $0 [github|gitlab|both]"; exit 1 ;;
esac

echo
echo "Done. Cut a release with:"
echo "    git tag -a v0.1.1 -m 'SpendLens 0.1.1' && git push gitlab v0.1.1 && git push origin v0.1.1"
