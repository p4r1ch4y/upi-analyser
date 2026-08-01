# Changelog

All notable changes to SpendLens are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Each released version has a matching git tag (`v0.1.0-alpha`), which is what
F-Droid builds from. The user-facing summary for each release also lives in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, capped at 500
characters — this file is the long form.

## [Unreleased]

## [0.1.3-alpha] — 2026-08-01

Measured against six months of one person's real ledger, and against a
4,103-message SMS backup from the same handset: 603 of 652 candidate messages
captured (92%), and of the 597 whose own wording is unambiguous, **597 are filed
on the correct side of the ledger** — zero credits read as debits. Every fix
below was found by running the build on a physical device against that data.

### Added

- **Budgets.** The `budgets` table has existed since the first schema and nothing
  ever wrote to it. It does now — but scoped to what this app can actually stand
  behind: everything, one tag, or one payee. There is no category model here on
  purpose, so a budget keyed on a category id was never going to work.

  Each budget carries a **pace marker**: where an even spread would have you by
  today. "₹4,120 of ₹8,000" looks comfortable and is not, if it is the 6th of the
  month, and no amount of restyling the bar says so. The line underneath reads
  "on course for ₹18,600 · ₹190 a day from here" rather than a percentage.

  Monthly budgets reset on the day they were created, not on the 1st. Salaries
  here land on the 1st, the 7th and the 25th depending on the employer, and a
  month that resets on a day the money does not arrive is wrong for its own first
  week. The first budget is the hard one to set, so the sheet offers what that
  scope actually cost last period as the starting figure.
- **Month by month.** Twelve months of in and out, one scale across all of them,
  each row opening the stream on that month. The headline could already say "12%
  more than the period before", but that is a single neighbour — whether a month
  is genuinely high, or just high for this person, needs the other eleven on
  screen.

  Money out is the thick mark and money in the thin one underneath: out is the
  subject of the screen, in is context, and giving them equal weight would imply
  a comparison nobody asked for. The section keeps its own twelve-month window
  rather than following the range chips, because narrowing the range to look at
  something closely should not delete the comparison that gives it meaning.
- **The spend-by-day columns open too.** Tapping one shows that day's payments.
  Hit-tested by slot rather than by drawn bar, so a day with nothing spent is
  still openable — "why was this day empty" is a real question.
- **Tap a bar to see the payments behind it.** The breakdown named a payee, gave
  a total, and then dead-ended. Tapping any row now opens the stream on exactly
  those payments — carrying the window as well as the group, so a bar reading
  ₹4,320 over 30 days does not land on a year's ₹51,000 for the same payee.
  Tapping a budget does the same over the budget's own period.
- **A privacy panel that proves rather than claims.** It reads the app's own
  permission list back out of the package manager at runtime and shows what is
  actually there, including that `INTERNET` is not — using the same API any
  auditor would. It also explains how to check the APK from outside, because a
  user who does not trust the app should not have to trust that screen either.
- **Rename a payment.** With a VPA this writes a rule replayed over every payment
  to the same address; without one, only that row changes.
- **Retroactive re-parse.** Every row keeps the message it was read from, so
  improvements to the parser now repair payments already in the ledger instead of
  helping only future ones. Verified on device: a row reading "Payment" became
  "Google Play". Labels only — never an amount, direction or date.

### Fixed

- **Back left the app from anywhere**, which made it a trap while reading: one
  stray press in the middle of checking a month and the whole screen was gone.
  Back now unwinds what is on screen, innermost first — a selection, then a
  breakdown filter, then a search, then Insights back to the stream — and only
  once there is nothing left to undo does it ask, with "press back again to
  leave". The prompt disarms itself after a couple of seconds so a back tapped a
  minute later is never read as confirming something forgotten.
- **Eleven of the twelve month bars were invisible.** Money out was drawn in
  `leader` on a `ruleSoft` track — two greys four steps apart — so the income
  marks read fine and the subject of the chart did not appear at all. Now ink for
  the field and the accent for the peak, the pairing the ranked bars already use.
- **One unusual month flattened the other eleven.** A ₹1,50,380 December against
  ordinary ₹7,000 months left every ordinary month an identical 5% stub on a
  linear scale. Square-root scaling now, matching the tap bar and the daily
  columns, which is the same argument those two already make.
- **The share label was unreadable on a full bar.** It sits at the end of the
  track, so on the longest bar it lands *on* the fill rather than beside it, and
  graphite on ink cannot be read. It flips to paper once the fill reaches it.
- **"75% of what?"** The breakdown showed shares of a total that appeared nowhere
  on screen. The denominator is now printed above the bars — and grouping by tag
  makes the point, because there it is deliberately not the headline figure but
  the total of *tagged* payments.
- **The breakdown could truncate in silence.** A list that stops at twenty and
  says nothing reads as "these are all of them". It now says how many it dropped.
- **Months before the ledger began were listed as months you spent nothing.** They
  are months the app was not watching, and a run of them pushed the real data off
  the screen. Empty months *inside* the span still show, because there they are a
  fact about the person.
- **"1 tags · total tagged."** The sort chips were also `mist` on paper with no
  bounds, which read as disabled text rather than three things you can tap.
- **Live SMS capture never worked in the `full` flavour.** The manifest declares
  `RECEIVE_SMS` and registers an `SMS_RECEIVED` receiver, but the app only ever
  requested `READ_SMS` at runtime — and a receiver whose permission was never
  granted simply never fires. The inbox import worked, so the rail looked healthy
  while every bank SMS arriving after install was silently missed. Caught on a
  handset: `READ_SMS granted=true, RECEIVE_SMS granted=false`.

  Both are now asked for together, and the import still runs on `READ_SMS` alone
  so someone who grants one and refuses the other gets the history they agreed
  to. The `standard` flavour declares neither and is untouched — verified against
  the built APK.
- **Switching Insights to Income left every statistic reporting spending.** Found
  straight after an SMS import: the headline read "₹71,104 income" and directly
  under it sat "biggest day ₹1,50,000", "days you spent nothing: 225", and a
  comparison line quoting the change in *expenses*. The direction chips flipped
  the headline and the breakdown and nothing else.

  Numbers that look like answers to the question just asked, and are about
  something else. Every summary now follows the chips, captions included — "on
  days you received", "biggest day in", "days nothing came in", "money in by day".
- **"9127% more than the period before."** Arithmetically right against a
  near-empty previous period, and useless: nobody holds a ninety-one-fold rise in
  their head as a percentage. Past ten times over it now reads "×92".
- **Repeat payments to the same shop were silently merged into one.** Found on a
  real ledger: two ₹45 payments to a chai shop a minute apart were stored as a
  single ₹45 row, with both notifications sitting under *Source* as the evidence.
  ₹45 had simply gone missing.

  Fusion matches on amount, currency, direction and a ninety-second window,
  because that is how a bank's SMS is recognised as describing the payment the UPI
  app already announced. But it is also exactly what two genuine back-to-back
  payments to the same shop look like — two chais, two auto fares, a bill settled
  by sending ₹200 twice — and nothing in that comparison can tell them apart.

  What can is *who is talking*. A UPI app posts one notification per payment; it
  never announces the same payment twice. So a heuristic match is now only allowed
  against a payment that has **not already been seen on that same rail and sender**
  (`canFuseAcrossSources`). Notification + bank SMS still fuses. Notification +
  notification from the same package never does. An exact RRN match is exempt and
  still fuses unconditionally, because an RRN identifies the payment itself.

  This fixes new captures. Rows already merged stay merged — the second message is
  still stored beside them, so a repair is possible, but it would change totals
  the user has already seen and is not done silently.
- **A day that only received money showed a bare ₹0.** The day total is a
  *spending* figure and stays that way — netting a salary against a week of chai
  would make it meaningless — but rendering "₹0" above a row plainly reading
  "+₹150" looks like the app losing track. Money in now sits beside the tap count:
  "1 tap · 1 merchant · +₹150 in".
- **Sharing a receipt image opened an empty form.** Three things were wrong.

  The share was read from `EXTRA_TEXT` alone, so apps that put the caption in the
  clip data, the subject or a `SEND_MULTIPLE` payload looked to SpendLens like
  they had shared nothing at all — and the app was missing from some share sheets
  entirely for want of a `SEND_MULTIPLE` filter. Every text the intent carries is
  now tried against the parser, individually and joined.

  The comment claimed the form opened "with the receipt still on screen behind
  it". It does not: the form covers it, leaving the user retyping an amount from
  memory or bouncing between two apps. **The receipt is now shown inside the
  form**, cropped to the part that carries the amount, and one tap from full
  size.

  The date defaulted to now. A receipt screenshot is taken seconds after the
  payment; the share may be the following evening. **The screenshot's own
  timestamp is now the default**, which stops those payments being filed on the
  wrong day and quietly corrupting that day's total.

  SpendLens still does not read images — see `fdroid/README.md`, where that
  trade is still open. This makes the honest path a good one instead of a
  dead-ended one.
- **A Google Pay receipt needs no OCR at all.** Its shared file is *named* after
  the payment it depicts — `1738737495 - 165.00 To Krishnendu Diyan on Google
  Pay.png` — which is a timestamp, an amount, a direction and a counterparty,
  written by the payment app itself. Those shares now file themselves, invisibly,
  the way a parsed notification does. Verified end to end on a handset against two
  real receipts.

  A file name is user-editable and therefore not evidence, so the pattern is drawn
  tight: the leading epoch, a two-decimal amount, an exact `To`/`From`, a trailing
  `on <app>`, and a timestamp that has to land in a plausible window. Anything
  that misses falls through to the entry form exactly as before. PhonePe names its
  receipts `TransactionReceipt4551195680020140631` and says nothing, so those
  still need the picture read.
- **A shared receipt is usually not from today**, so the confirmation now names
  the date it was filed under — "Added · 5 Feb 2025". A bare "Added" sent the user
  to today's stream to look for a payment eighteen months back, and the reasonable
  conclusion was that it had been dropped.
- **82% of rows read "Bank message".** Not a parser bug: most bank SMS never
  names the payee — "Rs. 10.00 debited from Airtel Payments Bank a/c Txn ID
  8159…" contains no merchant anywhere. What those messages do carry, 97% of the
  time, is the institution. Rows with a real label went from 22 to 584 of 596.
- Payments made just before midnight were filed on the following day, because the
  SMS arrived after it. The message's own stated time is now used when it is
  within three days of arrival — a misparsed date is far worse than a late one.

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
