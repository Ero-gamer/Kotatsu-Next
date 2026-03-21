# Kotatsu-Next Custom Optimization Rules
-optimizationpasses 8
-dontobfuscate

# AVIF-CODER (dav1d) Native Support - Essential for Xiaomi Box
-keep class com.radzivon.vicvane.android.avif.** { *; }
-keepclassmembers class com.radzivon.vicvane.android.avif.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlin Intrinsics optimizations
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void checkExpressionValueIsNotNull(...);
	public static void checkNotNullExpressionValue(...);
	public static void checkReturnedValueIsNotNull(...);
	public static void checkFieldIsNotNull(...);
	public static void checkParameterIsNotNull(...);
	public static void checkNotNullParameter(...);
}

# Library Warnings
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn coil3.PlatformContext

# Kotatsu UI/Fragment Preservation
-keep class org.koitharu.kotatsu.settings.NotificationSettingsLegacyFragment
-keep class org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment
-keep class org.koitharu.kotatsu.core.exceptions.* { *; }
-keep class org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy { *; }
-keep class org.koitharu.kotatsu.backups.ui.periodical.PeriodicalBackupSettingsFragment { *; }

# Parser/Utility Preservation
-keep class org.jsoup.parser.Tag
-keep class org.jsoup.internal.StringUtil

# ACRA (Crash Reporting) Support
-keep class org.acra.security.NoKeyStoreFactory { *; }
-keep class org.acra.config.DefaultRetryPolicy { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class org.acra.sender.JobSenderService
