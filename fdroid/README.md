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
| Public repo with real source | GitHub today; **GitLab mirror still to create** |
| FOSS licence file | `LICENSE` — GPL-3.0 |
| Version tag per release | `v0.1.0-alpha` |
| Builds from a clean checkout | verified — fonts are committed, no generated files needed |
| Only FOSS dependencies | verified, see below |
| No Google Play Services / Firebase | none — the app has no `INTERNET` permission at all |
| No ads, tracking, or proprietary services | none |
| No binary blobs in the source tree | only `gradle/wrapper/gradle-wrapper.jar`, which F-Droid replaces |
| fastlane metadata | `fastlane/metadata/android/en-US/` |
| Screenshots | **not yet** — see below |

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

## Screenshots

Not committed, deliberately. The only screenshots taken so far are of the
author's real ledger and contain a third party's name, phone number and UPI
address. Store screenshots must be taken from a fresh install with invented data,
and go in
`fastlane/metadata/android/en-US/images/phoneScreenshots/` as `1.png`, `2.png`, …

F-Droid publishes without them; they only affect how the listing looks.

## Submitting

1. Push the repository to GitLab and update the three `REPLACE_ME` URLs in
   `fdroid/com.spendlens.yml`.
2. Tag the release commit: `git tag v0.1.0-alpha && git push --tags`.
3. Fork <https://gitlab.com/fdroid/fdroiddata>, branch `com.spendlens`.
4. Copy `fdroid/com.spendlens.yml` to `metadata/com.spendlens.yml` in the fork.
5. `fdroid lint com.spendlens` and `fdroid rewritemeta com.spendlens`.
6. Commit as `New App: com.spendlens`, push, open the merge request.

Expect roughly 24–48 hours from approval to the app appearing, because signing
needs a human.
