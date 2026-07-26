# SpendLens — Status

Alpha. Both flavours build, all 110 unit tests pass, lint is clean, and the app
captures, stores and displays real payments end to end — verified on a physical
device, not just in the test suite.

The SMS parser is measured against a 4,103-message backup from a real handset:
**603 of 652 candidate messages captured (92%)**, with zero closing balances
misread as payment amounts. The 49 misses are almost all correct refusals -
failed payments, collect requests, mandate approvals, bill reminders, OTPs and
outright scam SMS.

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

### Manual entry

Amount, counterparty, spent/received, **payment type** (UPI, cash, card, ATM,
bank transfer) and an editable **date and time**. The date matters: the common
reason to type an entry is remembering a payment later, and defaulting to now
without letting it be changed would file this morning's chai on the wrong day and
quietly corrupt every daily total. Payment type is stored as `Channel`, and the
schema already carries `category_id` for categories to hang off later.

### Pipeline

Every rail converges on one path: **parse → dedupe → fuse → resolve → persist →
nudge**.

- **Parser** (`core/parser`) — templates keyed on *message shape* rather than on
  app, so a wording the author never saw is still read. Currency is always parsed
  and never assumed. Amounts go through `BigDecimal`, never `Double`. Account
  numbers and reference numbers are scanned for across the whole message, because
  they move around far more than the sentence stating the payment does.

  Notifications get a loose catch-all, so an unrecognised phrasing lands as a
  reviewable row instead of vanishing. SMS deliberately does not: an inbox is
  thousands of messages of marketing, OTPs and bill reminders written in the same
  grammar as payments. Instead every SMS shape anchors its amount hard against
  the verb — which is also what stops a closing balance being banked as a
  payment — and a veto rejects money that has not actually moved ("will be
  debited", "has failed", "amount payable").
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

**Stream** — day-ordered, not a dashboard. Tap bar with square-root scaling,
receipt-grammar rows with dotted leaders, review chips on low-confidence rows.
Past days collapse to one line and expand in place. Long-pressing a payment
starts a selection; long-pressing a day header takes the whole day.

**Splits** — split any selection of payments N ways, name the people or don't.
Each payment is split on its own total, so the arithmetic stays true per row and
one can be settled without touching the rest. The stream shows *your share*, and
so does every total in the app; the detail sheet is where "you paid ₹2,400 /
your share ₹600" lives, along with who has settled and what is still owed.

**Tags and trips** — a trip is a tag that knows its own date range, so the banner
can say "day 3 of 5" without anyone entering dates. Trips take the accent colour,
plain tags stay neutral; identity always comes from the label, never the colour.

**Source messages** — every row keeps the notification or SMS it was read out
of, verbatim, shown under *Source* when the row is opened. A payment caught on
two rails shows both messages, which is also the clearest explanation of why it
appears once rather than twice. Everything else on that sheet is the parser's
conclusion; this is the evidence, so a wrong amount can be seen for what it is
rather than taken on faith.

**Insights** — filter by expense or income over 7/30/90/365 days; group by
merchant, tag or payment type; sort by amount, count or name. A balance card
(in, out, net), a headline figure, stat tiles, spend-by-day columns, and ranked
bars carrying each row's payment count and share of the total. One mark language
throughout: magnitude is length, colour is emphasis only. No categorical
palette, no legends, no pie charts.

**Manual entry** — amount, counterparty, direction, payment type, editable date
and time.

**More** — currency, export, and contacting the developer. Currency is a display
setting only: every payment keeps the currency it was captured in, so switching
never reinterprets an old amount. Export writes the whole ledger to a CSV file
the user picks, with the original messages left out unless they opt in — an
export gets mailed to accountants and dropped in cloud folders, and those lines
carry account numbers and payee names. Feedback opens the mail client with a
build report and nothing about the ledger; in an app with no network permission,
other apps are the only way to reach the outside world.

### Privacy posture

Verifiable on the artifact, not just claimed:

- No `INTERNET` permission in either flavour.
- Every dependency is FOSS; the two prebuilt native libraries that reach the APK
  (`libsqlcipher.so`, and AndroidX's own `libandroidx.graphics.path.so`) come
  from trusted Maven repositories, which F-Droid's inclusion policy permits.
  `datastore` and `navigation-compose` were declared but never used, and were
  removed rather than left in the audit surface — datastore was dragging in a
  third native library for nothing.
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
  reader. Once written, the first template set matched **1 of 652** real messages,
  because every rule demanded an account number immediately after the verb and
  almost no bank writes them that way. Rebuilt against the corpus: 92%.
- `SmsInboxImporter` appended `LIMIT` to the query's sort order. That is a SQLite
  implementation detail the SMS provider is free to reject, and some OEM builds
  throw on it — failing the whole import. The cap is applied while walking the
  cursor now.
- A bulk import re-read the user-rule table once per message. Hoisted out of the
  loop; on an encrypted database that was most of the wall-clock cost of importing
  years of history.
- The resolution ladder only trusted counterparty names captured from
  *notifications*, so every name bank SMS does give up was thrown away and the
  VPA used instead — a Google Play mandate rendered as its 32-character hash. It
  also fell back to the literal string "Manual entry", which labelled several
  hundred imported bank messages as something the user had typed by hand. Both
  found by installing the build and reading the dashboard, neither caught by any
  test. Display names are resolved once and stored, so a one-off idempotent
  repair fixes rows already in the ledger.
- Most bank-SMS shapes hard-coded `Channel.UNKNOWN`, which made "how you paid" a
  single meaningless bar. The rail is now read off the message when the template
  cannot name it.
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
- Budgets. The table exists; no UI reaches it.
- Accounts as first-class objects, transfers between them, and per-category
  spending limits — the things a conventional tracker's nav drawer offers. Each
  needs its own model work rather than another screen.
- Editing an amount or merchant after capture. The source message makes a
  misparse visible; it cannot yet be corrected in place.
- Merchant naming sheet. The review chip is rendered and
  `TransactionRepository.nameMerchant` works and is tested by construction, but
  the chip is not yet wired to a sheet.
- Editing or deleting a transaction from the UI (`softDelete` exists underneath).
- PDF and XLS statement import. CSV only.
- Release signing key. The build is wired for one (`keystore.properties`) and
  no longer falls back to the debug key, but no key has been created yet, so
  release builds come out unsigned.
- GitLab mirror, and screenshots for the F-Droid listing. See `fdroid/README.md`.
- Instrumented tests. All 110 tests are JVM-only.
