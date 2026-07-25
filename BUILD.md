# SpendLens - Build Instructions

## Quick Start

### 1. Prerequisites

Install:
- **Android Studio Hedgehog (2023.1.1)** or newer
- **JDK 17** (comes with Android Studio)
- **Android SDK 35** (install via SDK Manager)

### 2. Download Fonts

The app uses two custom font families. Download and place in `app/src/main/res/font/`:

**Bricolage Grotesque** (for large numbers):
- Visit: https://fonts.google.com/specimen/Bricolage+Grotesque
- Download: `bricolage_grotesque_semibold.ttf`

**IBM Plex Sans** (for body text):
- Visit: https://fonts.google.com/specimen/IBM+Plex+Sans
- Download these weights:
  - `ibm_plex_sans_regular.ttf`
  - `ibm_plex_sans_medium.ttf`
  - `ibm_plex_sans_semibold.ttf`

Place in: `/app/src/main/res/font/`

### 3. Create Font Directory

```bash
mkdir -p app/src/main/res/font
# Then copy the 4 TTF files into this directory
```

### 4. Generate App Icons

The app needs launcher icons. Use Android Studio's Image Asset tool:

1. Right-click `app/res` → New → Image Asset
2. Icon Type: Launcher Icons
3. Name: `ic_launcher`
4. Choose a simple icon (temporary - design your own later)
5. Generate

Or create placeholder icons:

```bash
mkdir -p app/src/main/res/mipmap-mdpi
mkdir -p app/src/main/res/mipmap-hdpi
mkdir -p app/src/main/res/mipmap-xhdpi
mkdir -p app/src/main/res/mipmap-xxhdpi
mkdir -p app/src/main/res/mipmap-xxxhdpi
# Android Studio can auto-generate these
```

### 5. Sync Gradle

```bash
cd /path/to/upi_analyser
./gradlew build
```

Or in Android Studio: **File → Sync Project with Gradle Files**

### 6. Build

#### Debug Build (Standard flavor - no SMS)
```bash
./gradlew assembleStandardDebug
```

#### Debug Build (Full flavor - with SMS)
```bash
./gradlew assembleFullDebug
```

Output: `app/build/outputs/apk/standard/debug/app-standard-debug.apk`

### 7. Install on Device

```bash
adb install app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

## Testing the App

### Step 1: Grant Notification Access

1. Open **Settings** → **Apps** → **Special app access**
2. Select **Notification access**
3. Enable **SpendLens**

### Step 2: Make a Test Payment

1. Open Google Pay / PhonePe / Paytm
2. Send ₹1 to yourself or a friend
3. SpendLens should capture the notification within seconds

### Step 3: Check the UI

- Open SpendLens
- You should see the transaction in the day stream
- Verify the tap bar appears
- Check the dotted leader lines

## Development Workflow

### Running in Android Studio

1. Select run configuration: **app**
2. Select device (physical device recommended for notification testing)
3. Click **Run** (Shift+F10)

### Debugging Notification Capture

Enable verbose logging:

```bash
adb shell setprop log.tag.UpiNotificationListener VERBOSE
adb logcat -s UpiNotificationListener
```

### Common Issues

**Fonts not loading?**
- Verify TTF files are in `app/src/main/res/font/`
- Check filenames are lowercase with underscores (not hyphens)
- Clean and rebuild: `./gradlew clean build`

**Notification listener not working?**
- Check permission is granted
- Restart the app
- Check `adb logcat` for "Notification listener connected"

**Build errors about missing resources?**
- Run `./gradlew clean`
- File → Invalidate Caches / Restart in Android Studio

## Building for Release

### 1. Create Signing Key

```bash
keytool -genkey -v -keystore spendlens-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias spendlens
```

### 2. Configure Signing in `app/build.gradle.kts`

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("path/to/spendlens-release.jks")
            storePassword = "your-store-password"
            keyAlias = "spendlens"
            keyPassword = "your-key-password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ...
        }
    }
}
```

### 3. Build Release APK

```bash
./gradlew assembleStandardRelease
```

Output: `app/build/outputs/apk/standard/release/app-standard-release.apk`

## Next Development Tasks

### Immediate (MVP Completion)

- [ ] Wire up database persistence
- [ ] Implement nudge notification on transaction capture
- [ ] Add transaction detail sheet
- [ ] Build notification test corpus from real devices

### Near-term (v1.0)

- [ ] Manual transaction entry
- [ ] Quick-add tile
- [ ] Cash transaction support
- [ ] User rule creation UI
- [ ] Category management
- [ ] Basic week/month views

### Later (v2.0)

- [ ] Statement import (CSV/PDF)
- [ ] Gap detection
- [ ] E2EE backup via SAF
- [ ] SMS parsing (pending Play Store approval)
- [ ] Split tracking
- [ ] Budget alerts

## Testing Checklist

Before each release:

- [ ] Test on Pixel (stock Android)
- [ ] Test on Xiaomi/Redmi (MIUI)
- [ ] Test on Samsung (One UI)
- [ ] Test notification capture from GPay, PhonePe, Paytm
- [ ] Verify no INTERNET permission in manifest
- [ ] Test database encryption
- [ ] Verify tabular figures render correctly
- [ ] Test dark mode
- [ ] Check accessibility (TalkBack)
- [ ] Verify app works fully offline

## Architecture Notes

See `docs/` for detailed documentation:
- `UPI_SpendLens_Architecture.md` - Technical architecture
- `SpendLens_Design_System.md` - UI/UX specifications
- `design_context.md` - Design rationale

## Questions?

- Check the main README.md
- Review architecture docs
- Look at inline code comments
- File an issue on GitHub (when public repo is set up)
