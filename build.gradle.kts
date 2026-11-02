import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
}

// Findings already in a module's baseline pass; any new finding fails `detekt`.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt.yml"))
        baseline = file("detekt-baseline.xml")
    }
}

// Kover attaches its agent to every test task by default. Measure only with `-Pcoverage`, so
// scripts/check.sh and CI run the unit tests exactly as before.
subprojects {
    plugins.withId("org.jetbrains.kotlinx.kover") {
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            currentProject {
                instrumentation {
                    disabledForAll.set(!providers.gradleProperty("coverage").isPresent)
                }
            }
        }
    }
}
