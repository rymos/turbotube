import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// OAuth client for "Login with Google". Comes from local.properties or environment variables,
// never from the repository. See README.md for how to create one.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
fun secret(name: String): String =
    localProperties.getProperty(name) ?: System.getenv(name) ?: ""

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "io.github.rymos.turbotube"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.rymos.turbotube"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "OAUTH_CLIENT_ID", "\"${secret("TURBOTUBE_OAUTH_CLIENT_ID")}\"")
        buildConfigField(
            "String", "OAUTH_CLIENT_SECRET", "\"${secret("TURBOTUBE_OAUTH_CLIENT_SECRET")}\"",
        )
    }

    signingConfigs {
        // Release key from environment (GitHub Actions secrets). Without it the release build
        // is signed with the debug key, which is fine for sideloading on your own TV.
        val keystore = secret("TURBOTUBE_KEYSTORE_FILE")
        if (keystore.isNotEmpty() && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = secret("TURBOTUBE_KEYSTORE_PASSWORD")
                keyAlias = secret("TURBOTUBE_KEY_ALIAS")
                keyPassword = secret("TURBOTUBE_KEY_PASSWORD")
            }
        }
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons)
    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
