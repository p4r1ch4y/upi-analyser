# Building and Testing SpendLens on Your Android Phone

## Summary

**What was pushed to GitHub:**
- 43 source files (1,989 lines of Kotlin code)
- Complete Android app foundation
- Core business logic modules
- Build system configuration
- Documentation (excluding design/ and docs/ folders)

**Repository:** https://github.com/p4r1ch4y/upi-analyser

---

## Option 1: Build on Your Computer (Recommended)

### Prerequisites

1. **Install Android Studio**
   - Download: https://developer.android.com/studio
   - Choose "Android Studio Hedgehog" or newer
   - Install with default settings

2. **Enable Developer Options on Phone**
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   - Go back to Settings → Developer Options
   - Enable "USB Debugging"

### Building Steps

1. **Clone the repository**
   ```bash
   git clone https://github.com/p4r1ch4y/upi-analyser.git
   cd upi-analyser
   ```

2. **Download Required Fonts**
   
   Create directory: `app/src/main/res/font/`
   
   Download these fonts and place them in that folder:
   
   **Bricolage Grotesque:**
   - Visit: https://fonts.google.com/specimen/Bricolage+Grotesque
   - Download → Select "SemiBold 600"
   - Rename to: `bricolage_grotesque_semibold.ttf`
   
   **IBM Plex Sans:**
   - Visit: https://fonts.google.com/specimen/IBM+Plex+Sans
   - Download → Select "Regular 400", "Medium 500", "SemiBold 600"
   - Rename to:
     - `ibm_plex_sans_regular.ttf`
     - `ibm_plex_sans_medium.ttf`
     - `ibm_plex_sans_semibold.ttf`

3. **Create Launcher Icons**
   
   In Android Studio:
   - Right-click `app/res` → New → Image Asset
   - Icon Type: Launcher Icons
   - Name: `ic_launcher`
   - Choose/create an icon
   - Click Next → Finish

4. **Build the APK**
   
   **Using Android Studio:**
   - Open the project in Android Studio
   - Wait for Gradle sync to complete
   - Build → Build Bundle(s) / APK(s) → Build APK(s)
   - APK will be at: `app/build/outputs/apk/standard/debug/app-standard-debug.apk`
   
   **Using Terminal:**
   ```bash
   ./gradlew assembleStandardDebug
   ```

5. **Install on Phone**
   
   **Via USB:**
   - Connect phone via USB
   - Click "Run" in Android Studio (or use `./gradlew installStandardDebug`)
   
   **Via APK Transfer:**
   - Copy `app-standard-debug.apk` to phone
   - Install from file manager

---

## Option 2: Build Directly on Your Android Phone

Yes, you can build Android apps on your phone! Here are two methods:

### Method A: Using Termux (Terminal-based)

1. **Install Termux**
   - Download from F-Droid: https://f-droid.org/packages/com.termux/
   - (Don't use Play Store version - it's outdated)

2. **Setup Build Environment**
   ```bash
   # Update packages
   pkg update && pkg upgrade
   
   # Install required tools
   pkg install git openjdk-17 wget
   
   # Clone repository
   git clone https://github.com/p4r1ch4y/upi-analyser.git
   cd upi-analyser
   ```

3. **Download Fonts**
   ```bash
   # Create font directory
   mkdir -p app/src/main/res/font
   
   # You'll need to manually download fonts from Google Fonts
   # and transfer them to this directory using a file manager
   ```

4. **Build APK**
   ```bash
   # Make gradlew executable
   chmod +x gradlew
   
   # Build (this will take a while on phone)
   ./gradlew assembleStandardDebug
   ```
   
   **Note:** Building on phone is SLOW (30-60 minutes) and drains battery. Not recommended for regular development.

### Method B: Using AIDE or Android Studio for Android

**AIDE (Android IDE)**
- Install from Play Store: https://play.google.com/store/apps/details?id=com.aide.ui
- Limitations:
  - Doesn't support Gradle Kotlin DSL well
  - May struggle with our project structure
  - Not recommended for this project

**Android Studio for Android (Beta)**
- Not yet available for regular users
- Expected late 2026

---

## Option 3: Use GitHub Codespaces (Cloud Build)

1. Go to: https://github.com/p4r1ch4y/upi-analyser
2. Click "Code" → "Codespaces" → "Create codespace"
3. Wait for environment to load
4. Run:
   ```bash
   ./gradlew assembleStandardDebug
   ```
5. Download APK from: `app/build/outputs/apk/standard/debug/`

---

## Testing on Your Phone

### Step 1: Grant Notification Access

1. Open **Settings**
2. Go to **Apps** → **Special app access**
3. Select **Notification access**
4. Enable **SpendLens**

### Step 2: Make a Test Payment

1. Open Google Pay / PhonePe / Paytm
2. Send ₹1 to yourself or a friend
3. SpendLens should capture the notification within seconds

### Step 3: Verify

1. Open SpendLens app
2. You should see the transaction in the day stream
3. Check the tap bar visualization
4. Verify dotted leader lines appear

### Troubleshooting

**Notification not captured:**
```bash
# Check logs via ADB
adb logcat -s UpiNotificationListener

# Or install "Logcat Reader" from Play Store
```

**App crashes:**
- Check that fonts are properly installed
- Verify launcher icons were created
- Check Android version (requires Android 8.0+)

**Fonts not loading:**
- Filenames must be lowercase with underscores
- Must be TTF format
- Must be in exact location: `app/src/main/res/font/`

---

## Recommended Workflow

**For Development:**
1. Build on computer (faster, better tools)
2. Install on phone via USB
3. Test real UPI transactions
4. Iterate

**For Quick Testing:**
1. Build on computer
2. Transfer APK to phone
3. Install and test

**For On-Device Building (Not Recommended):**
- Only if you don't have access to a computer
- Expect very slow builds
- High battery drain
- Limited debugging capabilities

---

## Build Variants

### Standard (Recommended)
```bash
./gradlew assembleStandardDebug
```
- No SMS permission
- Notification-only capture
- For Google Play Store

### Full (F-Droid)
```bash
./gradlew assembleFullDebug
```
- Includes SMS parsing
- For F-Droid or sideloading
- Requires SMS permission

---

## What to Test

1. **Notification Capture**
   - Make UPI payments via GPay, PhonePe, Paytm
   - Verify transactions appear within seconds
   - Check merchant names are resolved

2. **UI**
   - Tap bar visualization
   - Dotted leader lines
   - Transaction rows
   - Dark mode

3. **Background Service**
   - Lock phone, make payment
   - Verify capture still works
   - Check notification updates

4. **OEM-Specific**
   - Battery optimization settings
   - Autostart permissions
   - Background restrictions

---

## Next Steps After Testing

1. **Build Golden Test Corpus**
   - Capture notification payloads
   - Share anonymized samples for parser improvements

2. **Report Issues**
   - Open issues on GitHub
   - Include device model, Android version
   - Share anonymized logs

3. **Contribute**
   - Bank SMS templates
   - OEM-specific fixes
   - Parser improvements

---

## FAQ

**Q: Do I need to download fonts every time?**
A: No, once fonts are in place, they're committed to your local repo.

**Q: Can I use the app without building?**
A: Not yet. Pre-built APKs will be available once MVP is complete.

**Q: Why is building on phone so slow?**
A: Android phones have limited RAM and CPU compared to computers. Gradle builds are resource-intensive.

**Q: Can I use Android emulator?**
A: Yes, but notification testing won't work properly. Use a real device for UPI testing.

**Q: What Android version do I need?**
A: Minimum Android 8.0 (API 26), recommended Android 10+

**Q: Will this work on iPhone?**
A: No, this is Android-only. iOS version is planned for v3.0.

---

## Support

- GitHub Issues: https://github.com/p4r1ch4y/upi-analyser/issues
- Documentation: See README.md, BUILD.md
- Architecture: See ARCHITECTURE_FLOW.md

---

**Recommendation:** Build on computer for best experience. On-device building is technically possible but impractical for regular development.
