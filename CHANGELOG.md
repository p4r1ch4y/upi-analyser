# Changelog

All notable changes to SpendLens are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Each released version has a matching git tag (`v0.1.0-alpha`), which is what
F-Droid builds from. The user-facing summary for each release also lives in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, capped at 500
characters — this file is the long form.

## [Unreleased]

Nothing yet.

## [0.1.2-alpha] — 2026-07-27

### Added

- **Source messages.** Every payment keeps the notification or SMS it was read
  out of, shown under *Source* when the row is opened. A payment caught on two
  rails shows both.
- **Export to CSV** through the file picker, with original messages opt-in.
- **Contact the developer**, which opens the mail client with a build report.
- **Configurable currency** — a display setting; stored payments keep the
  currency they were captured in.
- Report filters: expense or income, four ranges, grouping by merchant, tag or
  payment type, sorting by amount, count or name, share-of-total on every bar,
  and a balance card.

### Fixed

- Standing-instruction debits (`… debited from your account towards Google Play`)
  now name the payee instead of falling to the catch-all and reading "Payment".
- Changing currency did not redraw anything: the formatter was a global, which
  Compose cannot observe. Amounts now read it through a CompositionLocal.
- "No email app is set up on this phone" on phones that plainly had one. The
  check used `resolveActivity`, which returns null under package visibility
  filtering unless the app declares a matching `<queries>` entry. The address and
  website are now also shown as text, so they are usable whatever the intent does.

## [0.1.1-alpha] — 2026-07-26

No functional change. `0.1.0-alpha` was tagged before the release pipeline
existed, so no APK was ever built for it; this is the first version with
downloadable, signed builds and published checksums.

### Added

- Signed release APKs attached automatically to GitHub and GitLab releases, with
  `SHA256SUMS.txt`.
- `CONTRIBUTING.md`, this changelog, and store screenshots.

## [0.1.0-alpha] — 2026-07-26

First tagged release. Alpha: the capture pipeline and the ledger are real and
tested, but there is no backup format yet, so treat the data as disposable.

### Added

- **Capture from UPI notifications** across 19 payment and bank apps. Templates
  are keyed on the *shape* of the message rather than on the app, because Indian
  UPI apps all paraphrase the same NPCI sentences.
- **Notification-tray backfill** on listener connect and on demand, so payments
  made shortly before install are not lost.
- **Bank SMS capture** (`full` flavour only), live and from the existing inbox.
  This is the only rail that reaches backwards: Android exposes no notification
  history to third-party apps.
- **CSV statement import** through the Storage Access Framework, locating columns
  by header synonym and accepting both the withdrawal/deposit and signed-amount
  conventions.
- **Manual entry** with amount, counterparty, direction, payment type and an
  editable date and time.
- **Splits.** Split a payment, a whole day, or any selection of payments between
  people; track who has settled. Shares always sum to exactly the total.
- **Tags and trips.** A trip is a tag that owns a date range, so the home screen
  can show how far through it you are without anyone entering dates.
- **Insights**: spend by day, merchant, tag and rail, over 7, 30 or 90 days.
- **Encrypted storage.** SQLDelight over SQLCipher, with the passphrase sealed by
  an AES-256-GCM key in the Android Keystore.
- **No `INTERNET` permission** in either flavour, which is checkable on the APK.

### Notes

- Split payments count only *your share* everywhere in the app. Reporting the
  gross would tell someone who fronted a group dinner that they spent ₹8,000 on
  food.
- Two flavours: `standard` (notifications only, no dangerous permissions) and
  `full` (adds SMS). Most people want `standard`.

[Unreleased]: https://gitlab.com/p4r1ch4y-group/SpendLens/-/compare/v0.1.2-alpha...main
[0.1.2-alpha]: https://gitlab.com/p4r1ch4y-group/SpendLens/-/tags/v0.1.2-alpha
[0.1.1-alpha]: https://gitlab.com/p4r1ch4y-group/SpendLens/-/tags/v0.1.1-alpha
[0.1.0-alpha]: https://gitlab.com/p4r1ch4y-group/SpendLens/-/tags/v0.1.0-alpha
