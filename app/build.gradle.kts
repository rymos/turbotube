import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing secrets come from local.properties or environment variables, never from the repository.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
// Trimmed, because values pasted into GitHub secrets often carry a trailing line break
fun secret(name: String): String =
    (localProperties.getProperty(name) ?: System.getenv(name) ?: "").trim()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // FilterChip and a few other Compose for TV parts are still marked experimental
        optIn.add("androidx.tv.material3.ExperimentalTvMaterial3Api")
    }
}

android {
    namespace = "io.github.rymos.turbotube"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.rymos.turbotube"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions passes its run number, which is also the number of the release
        // (build-<n>). The update button compares against it.
        val build = secret("TURBOTUBE_BUILD_NUMBER").toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.3.$build"
        buildConfigField("int", "BUILD_NUMBER", build.toString())
    }

    signingConfigs {
        // Release key from environment (GitHub Actions secrets). Without it the release build
        // is signed with the debug key, which is fine for sideloading on your own TV.
        val keystore = secret("TURBOTUBE_KEYSTORE_FILE")
        if (keystore.isNotEmpty() && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = secret("TURBOTUBE_KEYSTORE_PASSWORD")
                keyAlias = secret("TURBOTUBE_KEY_ALIAS").ifEmpty { "turbotube" }
                keyPassword = secret("TURBOTUBE_KEY_PASSWORD")
                    .ifEmpty { secret("TURBOTUBE_KEYSTORE_PASSWORD") }
            }
        }
    }

    // A published build must never fall back to the debug key: that APK can never be updated and
    // the debug key is public. The workflow sets this for builds that go out as a release.
    if (secret("TURBOTUBE_REQUIRE_SIGNING") == "1" && signingConfigs.findByName("release") == null) {
        throw GradleException(
            "TURBOTUBE_REQUIRE_SIGNING=1, aber kein Signaturschlüssel gefunden " +
                "(TURBOTUBE_KEYSTORE_BASE64/TURBOTUBE_KEYSTORE_FILE fehlt). Ohne eigenen " +
                "Schlüssel lässt sich die APK später nicht mehr updaten.",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.compose.material.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
