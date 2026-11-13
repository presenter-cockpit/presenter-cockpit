plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.android.gms.oss-licenses-plugin")
}

android {
    namespace = "it.presenter.app"
    compileSdk = 37
    // Pinned: with a read-only SDK installed by the system AGP would otherwise try to download another one.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "it.presenter.app"
        // API 29: minimum for the low-latency rendering (front buffer) of Jetpack Ink.
        minSdk = 29
        targetSdk = 36
        versionCode = 238
        versionName = "0.8.3"
    }

    // The release keys live outside the repository; tools/release.sh passes them in environment variables.
    // "release" signs full and fdroid (the APK published on GitHub). "upload" signs the store bundle sent to
    // Play, and Play signs it again with its own key. Without these variables, the full and store release
    // builds use the debug key, for tests on the tablet.
    val keys = listOf("release" to "PC_RELEASE", "upload" to "PC_UPLOAD").mapNotNull { (name, env) ->
        val store = System.getenv("${env}_KEYSTORE") ?: return@mapNotNull null
        signingConfigs.create(name) {
            storeFile = file(store)
            storePassword = System.getenv("${env}_PASSWORD")
            keyAlias = System.getenv("${env}_ALIAS")
            keyPassword = storePassword
        }
    }.associateBy { it.name }
    fun key(name: String) = keys[name] ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Only the architectures in use: the tablet (arm64) and the emulator (x86_64). Smaller APK,
            // faster build and install; the release keeps them all.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The flavor picks the key (see signingConfigs above).
        }
    }

    // Pseudo-terminal of the local terminal (src/main/jni, ndk-build: no CMake needed).
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Robolectric reads the merged resources and manifest of the app.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    // Three distributions. "full": everything, with all files access (MANAGE_EXTERNAL_STORAGE, only in
    // the manifests of src/full and src/fdroid). "store": for the Play Store, which grants that permission
    // only to some categories of apps; PDFs and code live in the folders the user grants (system picker), and
    // there is no local terminal. "fdroid": like "full" but with free software only, so without handwriting
    // recognition (ML Kit, see src/mlkit).
    // Each variant has its own id, so they can live on the same tablet.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            signingConfig = key("release")
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
            // Distribution shown in Settings → About.
            buildConfigField("String", "DISTRIBUTION", "\"full\"")
        }
        create("store") {
            dimension = "distribution"
            signingConfig = key("upload")
            applicationIdSuffix = ".store"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "false")
            buildConfigField("String", "DISTRIBUTION", "\"store\"")
        }
        create("fdroid") {
            dimension = "distribution"
            // F-Droid builds this flavor without our keys and signs it with its own, so it expects an
            // unsigned APK: here there is no fallback to the debug key.
            signingConfig = keys["release"]
            applicationIdSuffix = ".fdroid"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
            buildConfigField("String", "DISTRIBUTION", "\"fdroid\"")
        }
    }

    // Handwriting recognition with ML Kit (not free software): only in "full" and "store"; "fdroid" has
    // its own Handwriting without it (src/fdroid).
    sourceSets {
        for (flavor in listOf("full", "store")) getByName(flavor).kotlin.directories.add("src/mlkit/java")
    }

    // Only the app's languages: the libraries (Compose, Material) bring dozens, and in a language
    // without a translation the texts would be mixed (app in English, system components in that language).
    androidResources {
        localeFilters += listOf("en", "it")
    }
}

// Bytecode for Java 17 with whatever JDK runs Gradle (17 here, 21 on the F-Droid build server). A toolchain
// would require exactly JDK 17 to be installed, and the F-Droid server does not have it.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// No Compose mapping file in release builds: the task that writes it reads every class of the
// dependencies, and JSch ships classes for Java 24 (META-INF/versions/24) that it cannot parse.
// The file only maps obfuscated Compose names in crash stack traces.
composeCompiler {
    includeComposeMappingFile.set(false)
}

// App name (app_name) and short name under the icon (app_launcher_name), per variant: debug builds
// end with "*", the debug store and F-Droid ones (only for tests on the tablet, NEVER published) also have
// a superscript "s" or "f". This way the variants can be told apart on the tablet.
androidComponents {
    onVariants { variant ->
        val debug = variant.buildType == "debug"
        val flavorMark = mapOf("store" to "ˢ", "fdroid" to "ᶠ")[variant.flavorName].orEmpty()
        val mark = if (debug) "$flavorMark*" else ""
        for ((key, name) in listOf("app_name" to "Presenter Cockpit", "app_launcher_name" to "Cockpit")) {
            variant.resValues.put(
                variant.makeResValueKey("string", key),
                com.android.build.api.variant.ResValue(name + mark, "App name (see app/build.gradle.kts)"),
            )
        }
    }
}

// oss-licenses puts only a placeholder ("Debug License Info") in debug builds: the tablet gets the
// debug build, so the real list computed for the release (same dependencies) is copied into it.
// The release list is an input and the result is not taken from the build cache: the placeholder is the
// same for every distribution, and the cache would give one distribution the list of another.
for (flavor in listOf("full", "store", "fdroid")) {
    val raw = "generated/res/%sOssLicensesTask/raw"
    val release = layout.buildDirectory.dir(raw.format("${flavor}Release"))
    tasks.matching { it.name == "${flavor}DebugOssLicensesTask" }.configureEach {
        dependsOn("${flavor}ReleaseOssLicensesTask")
        inputs.dir(release)
        outputs.cacheIf { false }
        doLast {
            copy {
                from(release)
                into(layout.buildDirectory.dir(raw.format("${flavor}Debug")))
            }
        }
    }
}

dependencies {
    // --- Ink: Jetpack Ink (low-latency rendering, pressure-sensitive brushes) ---
    val ink = "1.0.0"
    implementation("androidx.ink:ink-authoring:$ink")
    implementation("androidx.ink:ink-brush:$ink")
    implementation("androidx.ink:ink-geometry:$ink")
    implementation("androidx.ink:ink-rendering:$ink")
    implementation("androidx.ink:ink-strokes:$ink")
    // Stroke serialization: saved in the PDF, they stay editable when it is reopened.
    implementation("androidx.ink:ink-storage:$ink")
    // Motion prediction: reduces the perceived latency of laser and stroke.
    implementation("androidx.input:input-motionprediction:1.0.0")

    // --- PDF: writing the annotations (display stays with the system PdfRenderer) ---
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // --- Handwriting turned into text (models downloaded once, then offline). Not free software: not in
    // the "fdroid" distribution. ---
    for (flavor in listOf("full", "store")) "${flavor}Implementation"("com.google.mlkit:digital-ink-recognition:19.0.0")

    // --- Code and terminal tabs: SSH/SFTP (JSch, maintained fork) and terminal (libvterm, Compose) ---
    implementation("com.github.mwiede:jsch:2.28.7")
    implementation("org.connectbot:termlib:0.3.10")
    // SSH key in the Keystore unlocked with the fingerprint.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    // --- UI ---
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")

    // --- Tests (JVM): the real org.json, because the one in android.jar is only a stub in tests ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Android classes on the JVM (session, files, tabs), without the emulator.
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
}

// Property and fuzz tests (src/test/…/testing/Property.kt): seed to replay and number of cases, from the
// command line (-Pproperty.seed=N, -Pproperty.scale=K).
tasks.withType<Test>().configureEach {
    for (key in listOf("property.seed", "property.scale")) {
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    // Deep tests (src/test/…/deep: whole flows and random sequences of user actions): slow, only on request
    // with -Pdeep (see tools/deep-tests.sh).
    if (!providers.gradleProperty("deep").isPresent) exclude("it/presenter/app/deep/**")
}
