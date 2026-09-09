plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.kotlinx.serizliation) apply false
    alias(libs.plugins.compose.compiler) apply false
    // alias(libs.plugins.decoroutinator) apply false

    // Code Formatting & Static Analysis
    id("com.diffplug.spotless") version "8.10.2" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

subprojects {
    // ---- Spotless Formatting Configuration ----
    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("**/*.kt")
            targetExclude("**/build/**/*.kt", "**/bin/**/*.kt")

            // Auto-fixes formatting, spacing, & removes unused imports
            ktlint("1.8.0").editorConfigOverride(
                mapOf(
                    "indent_size" to "4",
                    "continuation_indent_size" to "4",
                    "ij_kotlin_allow_trailing_comma" to "true",
                    "ktlint_standard_no-unused-imports" to "enabled"
                )
            )
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("**/*.gradle.kts")
            ktlint("1.8.0")
        }
    }

    // ---- Detekt Static Analysis Configuration ----
    apply(plugin = "io.gitlab.arturbosch.detekt")
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        autoCorrect = true
    }
}
