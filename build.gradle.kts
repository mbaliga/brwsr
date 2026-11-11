// Top-level build file. Plugins are declared with `apply false` and applied per-module, so
// versions live in one place (gradle/libs.versions.toml).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
