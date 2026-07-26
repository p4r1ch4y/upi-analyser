# SpendLens - Build Instructions

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | **21** | Only needed to *run* Gradle. Android Studio's bundled JBR works. |
| Gradle | 9.5.0 | Provided by the wrapper — do not install it separately. |
| Android Gradle Plugin | 9.3.1 | Requires Gradle ≥ 9.5.0. |
| Kotlin | 2.3.21 | Applied by AGP's built-in Kotlin support. |
| Android SDK Platform | 36 | `compileSdk` / `targetSdk`. |
| Build Tools | 36.0.0 | |
| Min SDK | 26 | Android 8.0 — SQLCipher's floor. |

Launcher icons are vector drawables in the repository — nothing to generate.

**Fonts are not committed** (`.gitignore` excludes `*.ttf`). A fresh clone needs
four files in `app/src/main/res/font/`, both families SIL OFL 1.1:

| File | Source |
|---|---|
| `bricolage_grotesque_semibold.ttf` | [Bricolage Grotesque](https://fonts.google.com/specimen/Bricolage+Grotesque) |
| `ibm_plex_sans_regular.ttf` | [IBM Plex Sans](https://fonts.google.com/specimen/IBM+Plex+Sans) |
| `ibm_plex_sans_medium.ttf` | IBM Plex Sans |
| `ibm_plex_sans_semibold.ttf` | IBM Plex Sans |

Without them the resource build fails on `Theme.kt`. Both licences permit
bundling, so if you would rather the repo build straight from a clone, drop the
`*.ttf` lines from `.gitignore` and commit the files together with their OFL
licence text.

### Picking the JDK

Gradle 9.5 refuses to run on anything below Java 17, and the modules request a
Java **21** toolchain. If `java -version` reports 8 or 11, point Gradle at a
newer JDK rather than changing your system default:

```bash
JAVA_HOME=/path/to/android-studio/jbr ./gradlew assembleStandardDebug
```

The build declares `jvmToolchain(21)` and applies the
`foojay-resolver-convention` plugin, so on a machine with no JDK 21 Gradle
downloads one automatically. To use a JDK that is installed somewhere Gradle
does not look by default, add it to `~/.gradle/gradle.properties` — machine
specific, so it does not belong in the repository:

```properties
org.gradle.java.installations.paths=/path/to/android-studio/jbr
```

In Android Studio this is **Settings → Build → Build Tools → Gradle → Gradle
JDK**.

## Build

```bash
# Debug APK, notification rail only
./gradlew :app:assembleStandardDebug

# Debug APK, notification + SMS rails
./gradlew :app:assembleFullDebug

# Minified release APKs (currently signed with the debug key)
./gradlew assembleStandardRelease assembleFullRelease

# Everything
./gradlew test lint assemble
```

Outputs land in `app/build/outputs/apk/<flavor>/<buildType>/`.

## Flavours

The two flavours differ only in whether the SMS rail is compiled in.

| | `standard` | `full` |
|---|---|---|
| UPI notification capture | yes | yes |
| Notification-tray backfill | yes | yes |
| Manual entry, CSV import | yes | yes |
| Live bank SMS capture | no | yes |
| SMS inbox history import | no | yes |
| `READ_SMS` / `RECEIVE_SMS` | **not declared** | declared |
| Distributable on Google Play | yes | no (SMS/Call Log policy) |

`standard` is the default. Choose `full` only if you want transaction history
from before you installed the app — see [the notes on history](#a-note-on-history).

Neither flavour declares `INTERNET`. That is verifiable on the built artifact:

```bash
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging \
  app/build/outputs/apk/standard/release/app-standard-release.apk | grep uses-permission
```

## Tests

```bash
./gradlew test
```

79 unit tests across `core:model`, `core:parser`, `core:resolution` and
`core:fusion`. They are plain JVM tests — no emulator, no Robolectric — because
every parsing and resolution decision lives in Android-free modules.

`TemplateParserTest` and `BankSmsTest` carry notification and SMS text captured
from real devices (anonymised in the SMS case). Those are regression tests in the
strict sense: when one fails, real payments have stopped being recorded. Roughly
half of `BankSmsTest` asserts the *opposite* — that failed payments, collect
requests, mandate approvals, bill reminders and OTPs produce nothing — because a
phantom transaction is worse than a missing one.

### Measuring against your own SMS

`CorpusHarness` runs the parser over a real SMS backup (the XML that apps like
SMS Backup & Restore produce) and reports the match rate, a per-template
breakdown, and every message it could not read, grouped by shape:

```bash
./gradlew :core:parser:test --tests '*CorpusHarness*' \
  -Dspendlens.corpus=/path/to/sms-backup.xml --rerun-tasks
```

It skips silently without that property, so it never runs in CI and no personal
data is ever needed — or committed — to build and test the project. The current
reference corpus is 4,103 messages: 603 of 652 candidates captured, no closing
balance misread as a payment amount.

## A note on history

Android exposes **no notification history to third-party apps**. The system
Notification History log is behind `ACCESS_NOTIFICATION_HISTORY`, which is
signature-level, so anything already swiped away is unreachable no matter what
this app does. Three things follow:

1. **Tray backfill** (both flavours) reads notifications still *in* the tray, so
   payments made shortly before install, or while the listener was unbound, are
   picked up. Import → *Scan notifications now*.
2. **SMS inbox import** (`full` only) is the only rail that reaches genuinely
   backwards. Bank transaction messages already on the phone typically go back
   months or years, and cover cards, NEFT and ATM withdrawals that never produce
   a UPI notification.
3. **CSV import** (both flavours) covers everything else, via a statement
   exported from your bank.

## Signing a release

`app/build.gradle.kts` currently signs release builds with the debug key, which
is marked `FIXME`. Before distributing anything, create a keystore and replace
that `signingConfig`. Keep the keystore and its passwords out of the repository.

## Troubleshooting

**`Cannot find a Java installation ... matching languageVersion=21`**
Gradle cannot see a JDK 21 and could not download one. Set
`org.gradle.java.installations.paths` as above, or check network access.

**`Minimum supported Gradle version is 9.5.0`**
`gradle/wrapper/gradle-wrapper.properties` was changed, or the wrapper was
bypassed. Always build through `./gradlew`.

**`Unresolved reference` in `com.spendlens.core.database`**
The SQLDelight sources are generated. Run
`./gradlew :core:database:generateDebugSpendLensDatabaseInterface`, or just
build normally.

**Play Protect blocks the install**
Expected for a sideloaded, debug-signed app that asks for notification access.
Choose *Install anyway*. A properly signed release build attracts far less of
this.

**Nothing is captured after granting notification access**
Open Import → *Scan notifications now*. If it reports that notification access
is not connected, revoke and re-grant it in
Settings → Notifications → Device & app notifications; Android sometimes leaves
a listener unbound after an app update.
