# Optimization settings
-optimizationpasses 8
-dontobfuscate
-allowaccessmodification

# Kotlin Intrinsics - Keep check-ins for stability but ignore side effects for speed
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
}

# --- Library Specific Dontwarns ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn coil3.PlatformContext
# KSP / Room 3.0 related warnings to ignore
-dontwarn androidx.room.paging.**
-dontwarn androidx.room.MultiInstanceInvalidationService

# --- Core App Fragment & UI Keeps ---
-keep class org.koitharu.kotatsu.settings.NotificationSettingsLegacyFragment
-keep class org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment
-keep class org.koitharu.kotatsu.backups.ui.periodical.PeriodicalBackupSettingsFragment { *; }

# --- Exception & Policy Models ---
-keep class org.koitharu.kotatsu.core.exceptions.* { *; }
-keep class org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy { *; }

# --- Jsoup (Required for Parsers) ---
-keep class org.jsoup.parser.Tag
-keep class org.jsoup.internal.StringUtil

# --- ACRA (Crash Reporting) ---
-keep class org.acra.security.NoKeyStoreFactory { *; }
-keep class org.acra.config.DefaultRetryPolicy { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class org.acra.sender.JobSenderService

# --- Hilt (Dependency Injection) ---
# Prevent R8 from removing Hilt's generated entry points
-keep class *_HiltModules* { *; }
-keep class *__HiltAndroidApp* { *; }

# --- Room 3.0 / KSP Specific ---
# Keep the generated Room database and DAO implementations
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.Entity
-keep @androidx.room.Entity class * { *; }

# --- Media: AVIF & SSIV (Critical for Xiaomi Box stability) ---
# Keep native JNI methods for the AVIF decoder
-keep class io.github.awxkee.avif.coder.** { *; }
-keepclassmembers class io.github.awxkee.avif.coder.** {
    native <methods>;
}

# Subsampling Scale Image View (SSIV)
-keep class com.davemorrissey.labs.subscaleview.** { *; }

# --- Coil 3 ---
# Keep Coil's internal image decoders
-keep class coil3.** { *; }
