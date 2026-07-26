# SpendLens — Status

Alpha. Both flavours build, all 56 unit tests pass, lint is clean, and the app
captures, stores and displays real payments end to end.

## Working

### Capture

| Rail | Flavour | Reaches back? |
|---|---|---|
| UPI app notifications (live) | both | no — from install onward |
| Notification tray sweep | both | only what is still in the tray |
| Bank SMS (live) | `full` | no — from install onward |
| SMS inbox import | `full` | **yes** — months to years |
| CSV statement import | both | **yes** — whatever the file holds |
| Manual entry | both | user's choice of date |

Android exposes no notification history to third-party apps
(`ACCESS_NOTIFICATION_HISTORY` is signature-level), so SMS and CSV import are the
only routes to history that predates installation. See `BUILD.md`.

### Pipeline

Every rail converges on one path: **parse → dedupe → fuse → resolve → persist →
nudge**.

- **Parser** (`core/parser`) — templates keyed on *message shape* rather than on
  app, so a wording the author never saw is still read. Currency is always parsed
  and never assumed. Amounts go through `BigDecimal`, never `Double`. A loose
  catch-all runs last, so an unrecognised phrasing lands as a reviewable row
  instead of vanishing.
- **Dedupe** — SHA-256 over message body *and* post time. A listener rebind
  replays identical hashes and collapses; two separate ₹20 chai payments do not.
- **Fusion** (`core/fusion`) — exact RRN → confidence 1.0; matching amount,
  currency and direction within ±90 s → 0.8. Sources are merged into one row,
  field by field, by trust; the source mask records who contributed.
- **Resolution** (`core/resolution`) — the 6-rung ladder. Never returns
  "Unknown". Naming a merchant writes a rule and replays it over every past
  payment to the same VPA.
- **Persistence** (`core/database`) — SQLDelight over SQLCipher. The passphrase
  is 32 random bytes sealed with an AES-256-GCM key in the Android Keystore; only
  the wrapped blob reaches SharedPreferences.

### UI

Day stream (time-ordered, not a dashboard), tap bar with square-root scaling,
receipt-grammar rows with dotted leaders, review chips on low-confidence rows,
manual-entry sheet, import sheet, live day total in the foreground notification,
real-time nudge.

### Privacy posture

Verifiable on the artifact, not just claimed:

- No `INTERNET` permission in either flavour.
- `standard` ships 6 permissions and no SMS access at all.
- No third-party SDKs, no analytics. WorkManager was removed because it merged
  `WAKE_LOCK` and `ACCESS_NETWORK_STATE` into the manifest for no benefit.
- Notification bodies are never written to logcat.

## Fixed since the first build

- **Nothing was ever captured.** Templates were keyed per app and only covered
  `₹<amount> paid to <name>`. Real BHIM notifications say
  `Received INR 1.00 in your … account(XX0563) from NAME (vpa@psp)` and matched
  nothing. Rebuilt around message shapes; the exact field-captured text is now a
  regression test.
- Notifications older than 5 minutes were discarded, which made a tray sweep
  pointless. Removed; the timestamped dedupe hash handles replays instead.
- SMS was documented but never implemented — no permission, no receiver, no
  reader.
- `strings.xml` closed with `</string>`; `ic_notification.xml` had a `<resources>`
  root and no closing tag; launcher icons did not exist; the theme inherited from
  `Theme.Material3.*`, which Compose Material3 does not ship. All four broke the
  resource build.
- `val notification Intent = …` — a syntax error in the foreground service.
- `MerchantResolver` fell over a cross-module smart cast; `TemplateParser` threw
  on any template that did not declare every named group.
- `Money.formatIndian()` silently dropped digits (₹18,40,000 rendered as
  ₹1,40,000) and wrote 5 paise as `.5`.
- `FusedTxn.displayName()` returned the *merchant ID*, and fell back to the
  literal string "Unknown transaction" — the one thing the product promises never
  to show.
- Build stack was unrunnable: AGP 8.5.1 on Gradle 9.3.0 with a JDK-17 toolchain
  on a machine with no JDK 17. Now Gradle 9.5.0 / AGP 9.3.1 / Kotlin 2.3.21 with
  a JDK-21 toolchain and the foojay resolver.

## Not built yet

- Encrypted backup and restore (Argon2id → XChaCha20-Poly1305 over SAF). The
  format is specified in `ARCHITECTURE_FLOW.md`; none of it is implemented.
- Splits, budgets, categories. Tables exist; no UI reaches them.
- Merchant naming sheet. The review chip is rendered and
  `TransactionRepository.nameMerchant` works and is tested by construction, but
  the chip is not yet wired to a sheet.
- Editing or deleting a transaction from the UI (`softDelete` exists underneath).
- PDF and XLS statement import. CSV only.
- Release signing — release builds still use the debug key.
- Instrumented tests. All 56 tests are JVM-only.
