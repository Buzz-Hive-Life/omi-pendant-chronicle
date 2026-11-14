import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    signingConfigs {
        create("hive") {
            // ⚠️ CI generates this keystore per-run and NEVER stores it. A throwaway key is the right shape
            // for a personal build: nothing to leak, nothing to rotate.
            val ks = System.getenv("HIVE_KEYSTORE") ?: "${rootDir}/hive-release.jks"
            storeFile = file(ks)
            storePassword = System.getenv("HIVE_KS_PASS") ?: "hivepass"
            keyAlias = System.getenv("HIVE_KEY_ALIAS") ?: "hive"
            keyPassword = System.getenv("HIVE_KEY_PASS") ?: "hivepass"
        }
    }


    namespace = "com.connor.pendant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.connor.pendant"
        minSdk = 31  // Pixel 8 Pro is Android 14+; we don't care about older
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-hive3-rawroute"

        // ⚠️ SHOWN IN THE APP so a screenshot identifies the build. Three APKs went out tonight
        // carrying ONE version string, and neither side could tell which was installed.
        buildConfigField("String", "HIVE_VERSION", "\"0.1.0-hive3-rawroute\"")
        buildConfigField(
            "String",
            "AGENT_URL",
            "\"${localProps.getProperty("agent.url", "https://omi-ingest-production.up.railway.app/raw")}\""
        )
        buildConfigField(
            "String",
            "PENDANT_SECRET",
            "\"${localProps.getProperty("pendant.secret", "changeme")}\""
        )
        // Phase 1 audio-out: defaults to agent:8774/ws but local.properties wins.
        buildConfigField(
            "String",
            "AUDIO_OUT_WS_URL",
            "\"${localProps.getProperty("audio.out.url", "ws://agent:8774/ws")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        getByName("release") {
            // the whole point: R8 strips what is unused, which is most of the 27 MB
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("hive")
        }
    }

}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location)
    implementation(libs.androidx.health.connect.client)
}
