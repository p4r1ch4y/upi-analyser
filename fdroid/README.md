# Publishing SpendLens on F-Droid

## Why two flavours matter here

Submit **`standard`** only, at least to begin with.

`full` requests `READ_SMS` and `RECEIVE_SMS`. F-Droid will accept it — the
permissions are declared, disclosed and genuinely used — but they are the most
invasive thing the app asks for, and the SMS rail is the one part of the app that
is optional. `standard` is the version most people should install.

If `full` is submitted later it belongs as a separate build entry, and it is
worth expecting reviewer questions about why the app reads SMS at all. The answer
is in `BUILD.md`: Android exposes no notification history to third-party apps, so
SMS is the only rail that can recover spending from before installation.

## Checklist against F-Droid's requirements

| Requirement | Status |
|---|---|
| Public repo with real source | [gitlab.com/p4r1ch4y-group/SpendLens](https://gitlab.com/p4r1ch4y-group/SpendLens) — verified: anonymous clone succeeds |
| FOSS licence file | `LICENSE` — GPL-3.0 |
| Version tag per release | `v0.1.5-alpha` (versionCode 6) |
| Builds from a clean checkout | **verified** — anonymous clone at the tag builds `app-standard-release-unsigned.apk`, which is exactly what F-Droid signs |
| Only FOSS dependencies | verified, see below |
| No Google Play Services / Firebase | none — the app has no `INTERNET` permission at all |
| No ads, tracking, or proprietary services | none |
| No binary blobs in the source tree | only `gradle/wrapper/gradle-wrapper.jar`, which F-Droid replaces |
| fastlane metadata | `fastlane/metadata/android/en-US/` |
| Screenshots | 8, in `fastlane/metadata/android/en-US/images/phoneScreenshots/` |

## Dependency licences

Everything is Apache-2.0 from Google Maven or Maven Central, except where noted.

| Dependency | Licence | Native code? |
|---|---|---|
| `androidx.*`, Jetpack Compose | Apache-2.0 | `libandroidx.graphics.path.so` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-*` | Apache-2.0 | no |
| `app.cash.sqldelight:*` | Apache-2.0 | no |
| `net.zetetic:sqlcipher-android` | BSD-style (SQLCipher Community Edition) | `libsqlcipher.so` |
| `junit:junit` (test only) | EPL-1.0 | no |

Two prebuilt native libraries end up in the APK, both pulled from trusted Maven
repositories and both free software. F-Droid's inclusion policy allows this
explicitly: *"Applications can download prebuilt FLOSS binaries with specific
conditions from trusted Maven repositories."* Note that one of the two comes from
AndroidX itself, so no Compose app can avoid the situation.

Unused dependencies were removed rather than left to be audited: `datastore`
(which also dragged in a third native library) and `navigation-compose` were
declared but never referenced.

## OCR, and why it is not here yet

Most UPI apps share a *screenshot* rather than text, so the share rail only
catches the minority that share a receipt as text. Reading the screenshot needs
OCR, and the choice is not free:

| | Licence | Size | F-Droid |
|---|---|---|---|
| ML Kit text recognition | proprietary model | ~4 MB | **rejected** |
| Tesseract (`tesseract4android`) | Apache-2.0 | ~20 MB with English data | acceptable |

So the FOSS-compatible path exists but costs roughly twice the current APK, for a
feature that is a convenience over typing four characters. The trade is still
open.

Until it is made, a shared image opens manual entry **with the receipt rendered
inside the form** and the date and time read from the screenshot's own timestamp.
The earlier wording here — that the receipt stayed "on screen behind" the form —
was not true of the running app: a bottom sheet covers it, and the user was left
retyping an amount from memory. Showing the picture in the form costs one decode
and removes the app-switching entirely, which is a large part of what OCR was
going to buy.

**Google Pay needs no OCR at all.** It names the file it hands to the share sheet
after the payment: `1738737495 - 165.00 To Krishnendu Diyan on Google Pay.png`.
That is a timestamp, an amount, a direction and a counterparty, written by the
payment app, and `ReceiptFileName` reads it. Those receipts file themselves.

So the remaining case for OCR is narrower than it looked: apps that name their
receipts after nothing, PhonePe (`TransactionReceipt4551195680020140631`) being
the obvious one. That is worth 20 MB to some users and not to others, which is
the actual shape of the decision — a second build flavour, not a default.

## Screenshots

Eight, all from a clean install with invented data, in
`fastlane/metadata/android/en-US/images/phoneScreenshots/`, ordered as a story:
the stream, a split payment's detail with the message it was read from, insights,
month by month with budgets and the breakdown, the budget sheet, manual entry,
import, and the permission panel.

Taken by clearing the app, importing an invented CSV statement, and driving the
UI over adb with the system UI in demo mode, so the status bar carries a fixed
clock and no personal notification icons. The ledger behind them is entirely
made up — Chai Point, Big Basket, Anita and Ravi are not anybody.

Screenshots of a real ledger must never be committed. One taken during this pass
showed a genuine counterparty's full name against a real transaction and was
discarded; the earlier set also carried an account number, a phone number and a
UPI address. Anything published here should come from a fresh install.

## Ready to submit

Everything on the checklist is done and checked against the live repository, not
just assumed:

```bash
# the exact operation F-Droid's build server performs
git clone --branch v0.1.5-alpha --depth 1 \
  https://gitlab.com/p4r1ch4y-group/SpendLens.git
cd SpendLens && ./gradlew assembleStandardRelease
# -> app-standard-release-unsigned.apk
```

Unsigned is correct: F-Droid signs with its own key, and the build deliberately
refuses to fall back to the Android debug certificate when no keystore is
present.

## Submitting

1. ~~Push the repository to GitLab~~ — done: https://gitlab.com/p4r1ch4y-group/SpendLens
2. ~~Tag the release commit~~ — done: `v0.1.0-alpha`
3. Fork <https://gitlab.com/fdroid/fdroiddata>, branch `com.spendlens`.
4. Copy `fdroid/com.spendlens.yml` to `metadata/com.spendlens.yml` in the fork.
5. `fdroid lint com.spendlens` and `fdroid rewritemeta com.spendlens`.
6. Commit as `New App: com.spendlens`, push, open the merge request.

Expect roughly 24–48 hours from approval to the app appearing, because signing
needs a human.
