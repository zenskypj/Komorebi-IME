import com.google.protobuf.gradle.id
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "me.zssu.ime.mozc"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
    sourceSets["main"].assets.srcDirs("src/main/assets")
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/runtimeAssets"))

    testOptions.unitTests.isReturnDefaultValues = true

    // mozc.data stays deflated in the APK — it compresses to roughly half its 18 MB, and the file
    // has to be extracted to a real path for DataManager either way, so leaving it uncompressed
    // would only inflate the download. See MozcEngine.extractDataFile.
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                // javalite keeps the generated code small; mozc protos are large.
                id("java") { option("lite") }
            }
        }
    }
}

dependencies {
    // `api`, not `implementation`: the generated mozc protos are this module's public vocabulary,
    // so consumers need GeneratedMessageLite and friends on their compile classpath too.
    api(libs.protobuf.javalite)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}

val verifyMozcRuntime by tasks.registering {
    group = "verification"
    description = "Fails before packaging when generated Mozc runtime files are absent."

    val runtimeFiles = listOf(
        "src/main/assets/mozc.data",
        "src/main/jniLibs/arm64-v8a/libmozc.so",
        "src/main/jniLibs/armeabi-v7a/libmozc.so",
        "src/main/jniLibs/x86/libmozc.so",
        "src/main/jniLibs/x86_64/libmozc.so",
    ).map(::file)
    inputs.files(runtimeFiles)
    val runtimeRoot = projectDir
    val fingerprint = layout.buildDirectory.file("generated/runtimeAssets/mozc-runtime.sha256")
    outputs.file(fingerprint)

    doLast {
        val missing = inputs.files.files.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Mozc runtime files are missing:")
                    missing.forEach { appendLine("  - $it") }
                    append("Run scripts/build_mozc.sh before building the APK.")
                }
            )
        }
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val identity = runtimeFiles.joinToString("\n") {
            it.relativeTo(runtimeRoot).invariantSeparatorsPath + ":" + sha256(it.readBytes())
        }
        fingerprint.get().asFile.apply { parentFile.mkdirs(); writeText(sha256(identity.toByteArray()) + "\n") }
    }
}

// A source-only checkout is useful for editing, but must never silently produce an installable APK
// whose keyboard cannot convert anything. Keep unit-test compilation available while making every
// Android package merge — and every lint task, which reads the generated runtimeAssets directory as
// a declared input — depend on the generated native engine and dictionary.
tasks.configureEach {
    if (name.startsWith("merge") && (name.endsWith("NativeLibs") || name.endsWith("Assets"))) {
        dependsOn(verifyMozcRuntime)
    }
    if (name.contains("lint", ignoreCase = true)) {
        dependsOn(verifyMozcRuntime)
    }
}
