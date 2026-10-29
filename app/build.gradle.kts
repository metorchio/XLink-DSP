plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Versión que se ve en el panel lateral: short SHA del commit (+ "-dirty" con cambios sin commitear).
fun git(vararg args: String): String = try {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    ""
}

val gitSha = git("rev-parse", "--short", "HEAD").ifEmpty { "dev" } +
        if (git("status", "--porcelain").isNotEmpty()) "-dirty" else ""

// En el workflow de Release (tag vX.Y.Z) la versión sale del tag y el código del número de corrida.
val releaseTag = System.getenv("GITHUB_REF_NAME")?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }
// Firma del release: la keystore la arma el workflow desde los secrets del repo.
val releaseKeystore = System.getenv("XLINK_KEYSTORE")

android {
    namespace = "ar.mtorchio.xlinkdsp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "ar.mtorchio.xlinkdsp"
        minSdk = 35
        targetSdk = 36
        versionCode = if (releaseTag != null) System.getenv("GITHUB_RUN_NUMBER").toInt() else 1
        versionName = releaseTag?.removePrefix("v") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("XLINK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("XLINK_KEY_ALIAS")
                keyPassword = System.getenv("XLINK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}