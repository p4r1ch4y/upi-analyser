# Contributing to SpendLens

Thanks for looking. This is a small, opinionated app, so this document is mostly
about the opinions — knowing them up front saves you writing something that gets
turned down for reasons that were never written anywhere.

Canonical repository: <https://gitlab.com/p4r1ch4y-group/SpendLens>
(a mirror exists on GitHub; merge requests go to GitLab).

## The three rules that will get a patch rejected

**1. The app must never gain the ability to phone home.**

There is no `INTERNET` permission, in either flavour, and that is the product.
Anything that would add it — an analytics SDK, crash reporting, a remote
merchant directory, an update check, a font fetched at runtime — is out of
scope regardless of how useful it is. The privacy claim is checkable on the
built artifact rather than promised in a policy, and it stays that way:

```bash
aapt2 dump badging app-standard-release.apk | grep uses-permission
```

**2. Money is integer minor units, and never a floating-point number.**

`2999.95 * 100` is `299994.999…` in binary floating point, which truncates to
₹2999.94. Every amount in this codebase is a `Long` of paise, parsed through
`BigDecimal`. If you find a `Double` anywhere near a currency value, that is a
bug worth reporting on its own.

**3. The app never displays "Unknown".**

The resolution ladder always produces something a person will recognise — a
merchant name, a VPA, an account tail, or an honest description of where the
record came from. If your change can make a row render as "Unknown", or as a
label that is untrue (an imported bank message must not say "Manual entry"),
it needs rethinking. See `MerchantResolver`.

## What is most useful

**Parser templates.** By far the highest-value contribution. The parser is
measured against real messages, and every bank words things differently. If
SpendLens misses your bank:

1. Add the shape to `BuiltInTemplates` in `core/parser`.
2. Add a test to `BankSmsTest` or `TemplateParserTest` using the real wording,
   **anonymised** — change the amounts, account numbers, names and references.
   Never commit a real message.
3. If you can, measure the before and after against your own SMS backup:

   ```bash
   ./gradlew :core:parser:test --tests '*CorpusHarness*' \
     -Dspendlens.corpus=/path/to/sms-backup.xml --rerun-tasks
   ```

   It reports the match rate, a per-template breakdown, and every message it
   could not read, grouped by shape. It skips unless that property is set, so
   nobody else ever needs your data.

Note that roughly half of `BankSmsTest` asserts the *opposite* of a match — that
failed payments, collect requests, mandate approvals, bill reminders and OTPs
produce nothing. A phantom transaction is worse than a missing one, because the
user cannot tell it is wrong without opening their bank app. New templates are
expected to come with the negative cases too.

## Working on it

```bash
./gradlew :app:assembleStandardDebug   # notifications only
./gradlew :app:assembleFullDebug       # adds the SMS rail
./gradlew test lint                    # must both pass
```

`BUILD.md` covers the toolchain, the JDK, and why Play Protect warns on install.

- **Tests must pass and lint must be clean** on both flavours before a merge
  request is looked at.
- **Parsing, resolution, fusion and money live in `core/`**, which has no Android
  dependencies and is tested with plain JUnit — no emulator, no Robolectric. Keep
  new logic there where you can; it is the difference between a test suite that
  runs in two seconds and one nobody runs.
- **Comments explain why, not what.** The codebase is written so that the next
  person understands the reasoning behind a decision that looks odd. If you fix a
  subtle bug, leave a sentence saying what went wrong — several comments in here
  exist because the obvious implementation was wrong in a way that only showed up
  on a real device.

## Commit messages and merge requests

Write the subject as what the change does, and use the body to explain why it was
needed. Enough detail that someone reading `git log` in a year understands the
decision without opening the diff.

One logical change per merge request. If you have a refactor and a fix, that is
two.

## Reporting a bug in the parser

Please **do not paste a real bank message** into an issue — it identifies your
bank, your account and often the person you paid. Replace the amounts, account
digits, names and reference numbers with invented ones, keeping the wording and
punctuation exactly as they were. The wording is the only part that matters.

## Licence

SpendLens is GPL-3.0. Contributions are accepted under the same licence. The
bundled fonts are SIL OFL 1.1 — see `LICENSES/FONTS.txt`.

## Contact

Subrata — <iamcsubrata@gmail.com> · <https://p4r1ch4y.github.io/>
