plugins {
    `kotlin-dsl`
    `kotlin-dsl-precompiled-script-plugins`
}

repositories {
    gradlePluginPortal() // so that external plugins can be resolved in dependencies section
    mavenCentral()
}
dependencies {
    // hotfix to make kotlin scratch files work in idea
    implementation(kotlin("script-runtime"))
    implementation(kotlin("gradle-plugin", "1.6.21"))
    implementation("org.jlleitschuh.gradle:ktlint-gradle:10.2.0")
    implementation("com.adarshr:gradle-test-logger-plugin:3.2.0")
}

