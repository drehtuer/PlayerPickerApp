import com.android.build.api.variant.impl.VariantOutputImpl
import org.gradle.api.tasks.PathSensitivity
import java.util.Properties

/**
 * Signing material never lives in this repository - it is public. Values come
 * from keystore/keystore.properties (local, gitignored) or, in CI, from
 * environment variables fed by GitHub Actions secrets. When neither is present
 * the build still works: debug falls back to the SDK's own debug key, and
 * release is produced unsigned.
 *
 * The file sits with the keystores it points at, under the one directory that
 * is gitignored whole - so everything secret is in a single place, and there is
 * one rule to get wrong rather than two. The `storeFile` values in it stay
 * relative to the repository root, which is what `signingValue` resolves them
 * against and what the CI environment variables use.
 */
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(env)

/**
 * The marketing version, in one place. The debug build appends `-debug` to
 * `versionName`, so the APK naming below uses this rather than the variant's
 * own version - `Shotgun-debug-0.1.1.apk` reads better than
 * `Shotgun-0.1.1-debug.apk`, and matches how the file is asked for.
 *
 * The release workflow refuses to run if the `v*` tag does not match this.
 */
val appVersion = "0.1.4"

/**
 * Derived, never written by hand. `versionCode` is what Android compares to
 * decide whether an APK is an update, and a release that repeats or lowers it
 * cannot be installed over the one before - which, with immutable releases, is
 * not a thing that can be corrected afterwards.
 *
 * `major * 10000 + minor * 100 + patch`, so 0.1.1 is 101. It steps up from the
 * 1 that `v0.1.0` shipped by hand, which is all that is required of it.
 */
val appVersionCode = appVersion.split(".").let { (major, minor, patch) ->
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ksp)
    // AGP's enableUnitTestCoverage runs JaCoCo but does not apply the plugin,
    // so JacocoTaskExtension does not exist until this line does. Without it
    // the Robolectric fix below cannot be configured at all.
    jacoco
}

android {
    namespace = "de.drehtuer.shotgun"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.drehtuer.shotgun"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            val store = signingValue("debug.storeFile", "DEBUG_KEYSTORE_FILE")
                ?.let { rootProject.file(it) }
            if (store?.exists() == true) {
                storeFile = store
                storePassword = signingValue("debug.storePassword", "DEBUG_KEYSTORE_PASSWORD")
                keyAlias = signingValue("debug.keyAlias", "DEBUG_KEY_ALIAS")
                keyPassword = signingValue("debug.keyPassword", "DEBUG_KEY_PASSWORD")
            }
        }
        create("release") {
            val store = signingValue("release.storeFile", "RELEASE_KEYSTORE_FILE")
                ?.let { rootProject.file(it) }
            if (store?.exists() == true) {
                storeFile = store
                storePassword = signingValue("release.storePassword", "RELEASE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("release.keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("release.keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Carries full debug data: debuggable, unminified, symbols intact.
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")

            // Unit-test coverage only. enableAndroidTestCoverage would
            // instrument the APK itself, and this app is judged on touch and
            // countdown timing - a slowed build would misreport how it feels.
            // Android-framework code is covered on the JVM by Robolectric
            // instead, which needs no instrumentation and no device.
            enableUnitTestCoverage = true

            // No applicationIdSuffix on purpose: it would rename the package
            // for debug builds and break every documented adb command.
        }
        release {
            // Optimised and stripped: no debug symbols, no debuggable flag.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            isJniDebuggable = false
            ndk { debugSymbolLevel = "NONE" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Left unsigned when the release key is absent, so a checkout
            // without the keystore still builds. CI does have the key, as a
            // repository secret, and the release workflow refuses to publish
            // if it is missing rather than shipping this fallback.
            signingConfig = signingConfigs.getByName("release")
                .takeIf { it.storeFile?.exists() == true }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // For VERSION_NAME, shown on the settings screen.
        buildConfig = true
    }
    // Exported schemas are committed, so a migration can be written against a
    // real before-and-after rather than from memory.
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    testOptions {
        // Robolectric renders real composables, so it needs the merged
        // resources - the theme, the bundled Archivo fonts, the strings.
        // Without this every screen test fails on resource lookup.
        unitTests { isIncludeAndroidResources = true }
    }
}

/**
 * Makes JaCoCo see what Robolectric ran.
 *
 * Robolectric loads application classes through its own sandbox classloader,
 * and those classes arrive without a source location. JaCoCo skips such classes
 * by default, so the screen tests passed while every screen still reported zero
 * coverage - the tests ran, the report just could not see them.
 */
tasks.withType<Test>().configureEach {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        // Instrumenting the JDK's own internals breaks the agent.
        excludes = listOf("jdk.internal.*")
    }
}

/**
 * What SonarQube measures coverage over.
 *
 * The variant is pinned to `debug` because that is the only one JaCoCo
 * instruments (`enableUnitTestCoverage` above), and the report path is given
 * explicitly: AGP's `createDebugUnitTestCoverageReport` is not a `JacocoReport`
 * task, so the scanner's own auto-detection does not find it and coverage
 * arrives as zero without a word of complaint.
 *
 * Nothing is excluded, and nothing needs to be. Room's generated sources live
 * under `build/generated`, and the scanner sets `sonar.sources` to
 * `src/main/{java,res,AndroidManifest.xml}` - generated code is outside the
 * analysis to begin with, so it cannot be measured or counted against us.
 */
sonar {
    properties {
        property("sonar.androidVariant", "debug")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/coverage/test/debug/report.xml")
                .get().asFile.path,
        )
    }
}

/**
 * Name the APKs for people rather than for Gradle: `Shotgun-0.1.0.apk` and
 * `Shotgun-debug-0.1.0.apk`, instead of `app-release.apk` and `app-debug.apk`.
 * A release asset has to say what it is and which version it is without being
 * opened, and the default name says neither.
 *
 * `outputFileName` is not on the public `VariantOutput` interface, so this has
 * to go through `VariantOutputImpl`. The cast is checked rather than forced -
 * an output that is not one leaves the default name instead of failing the
 * build.
 */
/**
 * The design export is an input to the unit tests, and Gradle has to be told
 * so. `DesignTokenTest` reads `design/Shotgun.dc.html` at runtime, which is
 * invisible to task fingerprinting: without this, changing *only* the export -
 * which is exactly what a re-export does - leaves `testDebugUnitTest`
 * UP-TO-DATE, and the one test written to catch that change never runs.
 *
 * Found by mutating the export and watching all three checks pass in a second.
 */
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("design/Shotgun.dc.html"))
        .withPropertyName("designExport")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

androidComponents {
    onVariants { variant ->
        val suffix = if (variant.buildType == "debug") "-debug" else ""
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set(
                "Shotgun$suffix-$appVersion.apk"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Robolectric runs the Android framework on the JVM, so the screens are
    // covered by the same `./gradlew testDebugUnitTest` that covers the rules -
    // in CI, with no emulator. It does not replace `app/src/androidTest/`:
    // multi-touch, real haptics and how the countdown feels still need the
    // phone, and an emulator agreeing with Robolectric would only ever confirm
    // both simulations at once.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
