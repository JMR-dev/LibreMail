# SPDX-License-Identifier: GPL-3.0-or-later
# R8/ProGuard keep rules for LibreMail's release build.

# --- Jakarta / Angus Mail (IMAP/SMTP) ---
# Protocol providers (IMAP/SMTP stores and transports) are discovered via reflection and
# META-INF service files, so keep the mail and activation classes and their members intact.
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-keep class org.eclipse.angus.mail.** { *; }
-keep class org.eclipse.angus.activation.** { *; }
-dontwarn jakarta.mail.**
-dontwarn jakarta.activation.**
-dontwarn org.eclipse.angus.**
-dontwarn com.sun.activation.**

# --- AppAuth ---
# AppAuth (de)serializes its models (AuthState, token responses) reflectively.
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# --- Strip debug/verbose logging from release builds ---
# Drops Log.d/Log.v calls (and the evaluation of their arguments) so nothing like an
# account address is ever written to logcat in a release build.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# --- SQLCipher (opt-in encrypted cache) ---
# JNI-bound classes referenced by the native library; keep them intact.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**
