# SpendLens — Status

Alpha. Both flavours build, all 195 unit tests pass, lint is clean, and the app
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
| Shared receipt | both | whatever the user shares |
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

  The heuristic match only applies against a payment **not already seen on the
  same rail and sender**. Amount-plus-window cannot distinguish "the bank has now
  texted about the payment the app announced" from "the same shop was paid the
  same amount twice in three minutes", and the second is ordinary. A UPI app posts
  one notification per payment, so a second one from that package is a second
  payment. Found on a real ledger, where two ₹45 chai payments a minute apart had
  become one row and ₹45 had vanished. RRN matches are exempt: an RRN identifies
  the payment itself.
- **Resolution** (`core/resolution`) — the 6-rung ladder. Never returns
  "Unknown". Naming a merchant writes a rule and replays it over every past
  payment to the same VPA.
- **Nudge** — fires within a second of the payment, and *asks*. Most Indian bank
  SMS names no payee, so what makes a row readable later is a tag or a note, and
  the only person who can supply one is standing next to the shop right now. The
  notification carries a direct-reply note and one-tap buttons for the tags used
  most. Measured before building it: of 161 payments in one real month, 149 were
  labelled only "Airtel Payments Bank" and exactly one carried a note.
- **Persistence** (`core/database`) — SQLDelight over SQLCipher. The passphrase
  is 32 random bytes sealed with an AES-256-GCM key in the Android Keystore; only
  the wrapped blob reaches SharedPreferences.

### UI

**Back** unwinds rather than exits: a selection, then a breakdown filter, then a
search, then Insights back to the stream, and only then "press back again to
leave". Reading a report is exactly when a stray back is most expensive.

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

**Insights** — filter by expense or income over 7/30/90/365 days or a range you
pick; group by merchant, tag, payment type or **amount**; sort by amount, count or name. A balance card
(in, out, net), a headline figure, stat tiles, spend-by-day columns, and ranked
bars carrying each row's payment count and share of the total. One mark language
throughout: magnitude is length, colour is emphasis only. No categorical
palette, no legends, no pie charts.

Every mark opens. A breakdown bar, a day column, a month row — each shows the
stream narrowed to exactly the payments behind it, over the same window it was
measured across. A chart that names a payee, gives a total and then refuses to
say which payments those were is a dead end.

**Month by month** — twelve months of in and out on one scale, so a high month
can be recognised as high *for this person* rather than only against its
neighbour. Out is the thick mark, in the thin one beneath it. The section keeps
its own window rather than following the range chips: narrowing the range to look
at something closely should not delete the comparison that gives it meaning.

**Budgets** — scoped to everything, one tag, or one payee. There is no category
model here on purpose, so those are the two groupings a limit can honestly hang
off. Each budget draws a **pace marker**: where an even spread would have you by
today. That mark is the whole point — "₹4,120 of ₹8,000" looks comfortable and is
not, if it is the 6th of the month, and restyling the fill will never say so. The
line underneath reads "on course for ₹18,600 · ₹190 a day from here".

Monthly budgets reset on the day they were set rather than on the 1st, because
salaries land on the 1st, the 7th and the 25th depending on the employer. The
sheet offers what that scope actually cost last period as the opening figure: a
blank amount box is the real reason budget features go unused.

**Shared receipts** — most UPI apps share a screenshot rather than text. Three
things are tried, in order of how much they give up.

Every text the intent carries — extra, subject, clip data, `SEND_MULTIPLE`
payload — goes to the parser, individually and joined. Then the *file name*:
Google Pay names a shared receipt `1738737495 - 165.00 To Krishnendu Diyan on
Google Pay.png`, which is a whole transaction written by the payment app, so
those file themselves with no OCR. If neither reads, the entry form opens **with
the receipt rendered inside it** and the date taken from the screenshot's own
timestamp. The image is copied into private cache for exactly as long as the form
needs it, then deleted.

Verified on a handset against real receipts from both apps: the Google Pay pair
filed themselves and collapsed correctly when shared twice; the PhonePe pair,
whose names say nothing, fall through to the form.

**Manual entry** — amount, counterparty, direction, payment type, editable date
and time.

**More** — currency, **display size**, export, updates, and contacting the
developer. Display size is a multiple of the phone's own setting rather than an
override of it: the system dial moves every app at once, and someone who runs
their whole phone small to fit more on screen still wants a ledger of amounts
they can read. There is no in-app update check and there cannot be one — the app
holds no INTERNET permission — so "check for a newer version" hands the question
to a browser, the one component allowed to reach the network.

**Restricted settings** — installed from an APK rather than a store, Android 13+
greys out notification access and says only that "this setting is currently
unavailable". SpendLens captures nothing until it is granted, so that install
silently does nothing and looks broken. The app detects the condition (SDK 33+,
no store installer) and offers the four taps that fix it, with a button straight
to App info. Shown only where Android would actually be blocking it.

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
- OCR on a shared receipt. Google Pay needs none — its file name carries the whole
  payment — so what is left is apps that name their receipts after nothing, like
  PhonePe's `TransactionReceipt4551195680020140631`. For those the receipt is shown
  inside the entry form and the date read from the screenshot, but the amount and
  payee are still typed. The FOSS option (`tesseract4android`) costs roughly twice
  the current APK, which makes it a second build flavour rather than a default —
  the trade is written up in `fdroid/README.md` and has not been made.
- **The stream and Insights only reach back one year** (`HISTORY_MILLIS`). Sharing
  an older receipt files it correctly and then shows it nowhere, which is how the
  Feb 2025 receipt above behaved. The confirmation names the date so the user is
  not left thinking it vanished, but the window itself is still arbitrary.
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
