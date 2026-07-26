SpendLens tracks UPI spending entirely on your phone. It holds no `INTERNET`
permission, so nothing it reads can leave the device.

See [CHANGELOG.md](CHANGELOG.md) for what changed in this version.

## Which APK do I want?

| | |
|---|---|
| **`standard`** | Reads payment notifications. No dangerous permissions. **Start here.** |
| **`full`** | Also reads bank SMS — the only way to recover spending from *before* you installed the app, since Android exposes no notification history to third-party apps. |

Most people want `standard`.

## Verifying the download

`SHA256SUMS.txt` is attached. Check it before installing:

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing
```

You can also confirm the privacy claim on the file you downloaded, rather than
taking it on trust — this should print no `INTERNET` line:

```bash
aapt2 dump badging SpendLens-*-standard-release.apk | grep uses-permission
```

## Google Play Protect will warn you

Expected, and not something the app can avoid.

Play Protect blocks installs that come from a browser, messaging app or file
manager when the app declares `NOTIFICATION_LISTENER` — which is the entire
mechanism SpendLens uses to see your payments. The warning reads *"This app can
request access to sensitive data."*

Installing over `adb install` does not trigger it. Otherwise you will need to
allow the install explicitly.

## A warning about switching to the F-Droid build later

These APKs are signed with the project's own key. The F-Droid build is signed
with F-Droid's key. Android will not let one replace the other as an update, so
moving between them means uninstalling first — **which erases your ledger, and
there is no backup format yet.**

Pick one source and stay on it for now.
