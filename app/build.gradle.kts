plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val bundleZenzai = providers.gradleProperty("zenzai").orNull == "true"
if (bundleZenzai) {
    val validateZenzai by tasks.registering {
        val required = listOf("src/zenzai/assets/zenzai/model.json", "src/zenzai/assets/zenzai/zenzai-plus-ft-f16.gguf", "src/zenzai/jniLibs/arm64-v8a/libzinna_zenzai.so").map(::file)
        inputs.files(required)
        doLast { check(required.all { it.isFile }) { "Run scripts/prepare_zenzai.py and scripts/build_zenzai.sh before -Pzenzai=true" } }
    }
    tasks.named("preBuild").configure { dependsOn(validateZenzai) }
}
android {
    namespace = "me.zssu.ime"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "me.zssu.ime"
        minSdk = 24
        targetSdk = 36
        versionCode = 29
        versionName = "0.3.0-rc.5-dev"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    if (bundleZenzai) {
        sourceSets["main"].assets.srcDir("src/zenzai/assets")
        sourceSets["main"].jniLibs.srcDir("src/zenzai/jniLibs")
        defaultConfig.ndk.abiFilters.add("arm64-v8a")
    }
    androidResources.noCompress += "gguf"
    packaging {
        // libmozc.so must stay page-aligned for the 16 KB page-size requirement on Android 15+.
        jniLibs.useLegacyPackaging = false
    }

    // App Bundles already split by ABI at install time and AGP cannot combine an AAB with multiple
    // shrunk APK outputs in one invocation. Keep normal/Play builds single-output; GitHub release
    // jobs opt in with -PenableAbiSplits=true and run assemble in a separate invocation.
    splits {
        abi {
            isEnable = providers.gradleProperty("enableAbiSplits")
                .orNull
                ?.toBooleanStrictOrNull() == true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(project(":mozc"))
    implementation(project(":karukan"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
