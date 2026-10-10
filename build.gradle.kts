// Build output is redirected outside the source tree; see PROJECT.md for the build layout.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

allprojects {
    layout.buildDirectory.set(file("C:/Users/xuyuntao/AndroidBuild/HighSpeedIEM/${project.name}"))
}
