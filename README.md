# SpendLens

**The UPI tracker that can't leak your data — because it can't reach the internet.**

A privacy-first UPI expense tracker for Android that captures transactions in real-time from notifications, keeps all data encrypted on-device, and never connects to the internet.

## Key Features

###  **True Privacy**
- **No INTERNET permission** - literally can't send data anywhere
- All data encrypted with SQLCipher
- No analytics, no crash reporting, no third-party SDKs
- Open source (GPLv3)

###  **Real-Time Capture**
- Captures UPI transactions from notifications (GPay, PhonePe, Paytm, etc.)
- Shows "nudge" notification: *"₹250 → Swiggy · ₹1,840 today"*
- Works entirely in background

###  **Smart Resolution**
- **Never shows "Unknown"** - always displays merchant name or VPA
- 6-rung resolution ladder (user rules → notification name → VPA structure → directory → fuzzy → raw VPA)
- User corrections become permanent rules and apply retroactively

###  **Multi-Source Fusion**
- Merges data from notifications + SMS + statements
- Keeps best fields from each source (notification name + SMS RRN + statement accuracy)
- Handles refunds, reversals, self-transfers intelligently

###  **Distinctive Design**
- Day stream (not dashboard) - organized by time
- **Tap bar** - signature visualization showing transaction frequency
- Dotted leader lines (receipt grammar)
- Violet for splits, amber for review, monochrome everywhere else
- Tabular figures for perfect column alignment

## Architecture

### Module Structure

```
SpendLens/
├── app/                      # Android UI (Jetpack Compose)
├── core/
│   ├── model/               # Domain types (Money, Vpa, Transaction)
│   ├── parser/              # Template-based notification/SMS parser
│   ├── resolution/          # 6-rung merchant resolution ladder
│   ├── fusion/              # Cross-source transaction merger
│   └── database/            # SQLDelight schema + SQLCipher
```

### Key Principles

1. **Money is an integer** - Always stored in minor units (paise), never floats
2. **Never "Unknown"** - Use raw VPA or meaningful fallback
3. **No assumptions** - Currency must be explicitly parsed, never defaulted to INR
4. **Merge, don't dedupe** - Combine fields from multiple sources
5. **Local only** - No server, no account, no login required

## Building

### Prerequisites

- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 35
- Gradle 8.5+

### Steps

1. Clone the repository
```bash
cd upi_analyser
```

2. Open in Android Studio

3. **Important**: Add font files to `app/src/main/res/font/`:
   - `bricolage_grotesque_semibold.ttf`
   - `ibm_plex_sans_regular.ttf`
   - `ibm_plex_sans_medium.ttf`
   - `ibm_plex_sans_semibold.ttf`

   Download from:
   - [Bricolage Grotesque](https://fonts.google.com/specimen/Bricolage+Grotesque)
   - [IBM Plex Sans](https://fonts.google.com/specimen/IBM+Plex+Sans)

4. Build variants:
   - **standard** - No SMS, notification-only (for Play Store)
   - **full** - Includes SMS parsing (for F-Droid)

```bash
./gradlew assembleStandardDebug    # Play Store variant
./gradlew assembleFullDebug        # F-Droid variant
```

## Testing

### Unit Tests

```bash
./gradlew test
```

### Running on Device

1. Enable notification access: **Settings → Apps → Special access → Notification access → SpendLens**
2. Make a test UPI payment
3. Transaction should appear within seconds

## Distribution Strategy

### Phase 1: Notification-only
- Launch **standard** flavor to Play Store (no SMS permission)
- Also publish same build to F-Droid
- Build user base and template corpus

### Phase 2: SMS Declaration
- Submit SMS Permissions Declaration to Google Play
- Key argument: app has no INTERNET permission, cannot exfiltrate SMS
- If approved: add SMS as optional toggle
- If denied: **full** flavor ships to F-Droid only

## Current Status

 **Implemented (MVP Foundation)**
- Core domain models (Money, Vpa, Transaction)
- Template-based parser framework
- 6-rung resolution ladder
- Multi-source fusion engine
- SQLCipher database schema
- Notification listener service
- Foreground service for reliability
- Complete design system in Compose
- Signature tap bar visualization
- Day stream UI

 **Next Steps**
1. Add actual transaction persistence (database integration)
2. Implement nudge notifications
3. Build golden test corpus from real notifications
4. Add statement import (CSV/PDF)
5. Implement E2EE backup via SAF
6. OEM-specific reliability improvements
7. Manual entry + quick-add tile

## Technical Highlights

### 1. No INTERNET Permission

The app ships **without** `android.permission.INTERNET`. Backups are written through the Storage Access Framework, so the user's Drive/OneDrive app performs network I/O while SpendLens never gets a socket.

### 2. SQLCipher Encryption

Database is encrypted with AES-256 at page level. DEK stored wrapped by Android Keystore KEK.

**Critical**: `setUserAuthenticationRequired(false)` on Keystore key so notification listener can decrypt while device is locked.

### 3. Notification Listener Reliability

- Foreground service (type: dataSync)
- Boot receiver to re-arm
- `onListenerDisconnected()` → `requestRebind()`
- Health monitor via WorkManager

### 4. The Tap Bar

Square-root height scaling so ₹20 chai stays visible next to ₹2,400 grocery run:
```kotlin
height = min + (max - min) × sqrt(amount / dayMax)
```

### 5. Fusion Algorithm

Match by:
1. Exact RRN (confidence 1.0)
2. Amount + currency + direction within ±90s (confidence 0.8)
3. Also matching account tail → confidence 0.9

Then merge fields with trust hierarchy per field type.

## Design Philosophy

> Make the invisible flow visible, in the moment it happens, and keep every byte of it on the user's device.

**Three consequences:**
1. Latency budget is seconds - nudge is the product, ledger is byproduct
2. Trust must be verifiable - proven in manifest, not claimed in policy
3. Restraint is the aesthetic - color only where it carries meaning

## License

GNU General Public License v3.0

This ensures:
- Anyone can verify the privacy claims
- Reproducible builds possible
- F-Droid compatible
- Copyleft protects against proprietary forks

## Acknowledgments

- Architecture inspired by the problem: UPI made spending invisible
- Design vocabulary from receipt grammar
- Privacy model: "can't leak what you can't reach"

---

**Note**: This is alpha software. The notification parser templates are minimal. Real-world usage will require building a comprehensive golden corpus from actual bank notifications and UPI apps.

## Contributing

Contributions welcome, especially:
- Bank SMS templates (anonymized)
- Notification payload samples (anonymized)
- OEM-specific reliability fixes
- Parser edge cases

See `docs/` for detailed architecture and design specifications.
