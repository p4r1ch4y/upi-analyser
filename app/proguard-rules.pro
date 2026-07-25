# SpendLens ProGuard rules

# Keep SQLCipher
-keep,includedescriptorclasses class net.sqlcipher.** { *; }
-keep,includedescriptorclasses interface net.sqlcipher.** { *; }

# Keep domain models (for debugging)
-keep class com.spendlens.core.model.** { *; }

# Keep Compose runtime
-dontwarn androidx.compose.**

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
