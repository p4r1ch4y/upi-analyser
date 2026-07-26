# SpendLens ProGuard rules

# --- SQLCipher -------------------------------------------------------------
# net.zetetic:sqlcipher-android ships its classes under net.zetetic.database,
# not the net.sqlcipher package the pre-4.5 artifact used. Its JNI layer looks
# these up by name, so they cannot be renamed or stripped.
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }
-keepclasseswithmembernames class net.zetetic.database.** {
    native <methods>;
}

# --- SQLDelight ------------------------------------------------------------
-dontwarn app.cash.sqldelight.**
-keep class com.spendlens.core.database.** { *; }

# --- Domain models ---------------------------------------------------------
# Kept so crash reports and database rows stay legible while the app is alpha.
-keep class com.spendlens.core.model.** { *; }

# --- Compose ---------------------------------------------------------------
-dontwarn androidx.compose.**

# --- Logging ---------------------------------------------------------------
# Strip debug/verbose/info from release builds. Warnings and errors survive:
# they are the only signal left when a parser template stops matching.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
