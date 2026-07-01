// SPDX-License-Identifier: GPL-3.0-or-later
//
// AGP 9.x uses "built-in Kotlin"; the legacy org.jetbrains.kotlin.android plugin is
// incompatible with its new DSL (applying it throws a ClassCastException). Built-in
// Kotlin compiles with AGP's bundled KGP (2.2.10) unless a newer Kotlin Gradle plugin
// is placed on the buildscript classpath. To build with Kotlin 2.4.0 we put KGP 2.4.0
// — plus the matching Compose compiler, KSP and Hilt plugins — on the single buildscript
// classpath here, and apply them by id in the module build files. Versions mirror
// gradle/libs.versions.toml (which still supplies all library versions).
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.2.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.0")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.9")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.60")
    }
}
