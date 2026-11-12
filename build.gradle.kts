plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    // Collects the licenses of the dependencies in res/raw (shown in Settings → About).
    id("com.google.android.gms.oss-licenses-plugin") version "0.13.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
