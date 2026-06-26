# SPDX-License-Identifier: GPL-3.0-or-later
# LibreMail ProGuard/R8 rules.
#
# Release builds are currently not minified (see app/build.gradle.kts). When
# minification is enabled for release, add keep rules for the email/auth stack:
#
# Jakarta / Angus Mail (reflection-based provider registration):
#   -keep class jakarta.mail.** { *; }
#   -keep class org.eclipse.angus.mail.** { *; }
#   -keep class com.sun.mail.** { *; }
#   -dontwarn jakarta.activation.**
#
# AppAuth:
#   -keep class net.openid.appauth.** { *; }
