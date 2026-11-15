import java.security.MessageDigest

plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}
android {
    namespace = "dev.blx.btprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.blx.btprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.13-media-alerts"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src", "kotlin"))
            res.setSrcDirs(listOf("res"))
        }
        getByName("androidTest").java.srcDir("compose-tests")
    }
    signingConfigs {
        create("existing") {
            storeFile = file(".keys/test.jks")
            storePassword = "android"; keyAlias = "btprobe"; keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("existing") }
        getByName("release") { signingConfig = signingConfigs.getByName("existing"); isMinifyEnabled = false }
    }
    buildFeatures { compose = true; buildConfig = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // Only the codec is needed; its optional AppCompat/Material dependencies are unused.
    implementation("io.github.awxkee:jxl-coder:2.6.1") { isTransitive = false }
    implementation("dev.chrisbanes.haze:haze:1.5.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Pin the publisher's Maven Central AAR digests, including the bundled native codecs.
val verifyImageCodec by tasks.registering {
    doLast {
        val expected = mapOf(
            "jxl-coder-2.6.1-debug.aar" to "2060666603434cf9e9f7208afc0b33aac3e4628b3b84f588becf07de08276bcc",
            "jxl-coder-2.6.1-release.aar" to "5cf6ce13eccb227ce3d4f3a142cdc0e02023803d592caeade532c3450e59e7fa"
        )
        for (variant in listOf("debug", "release")) {
            val artifact = configurations.getByName("${variant}RuntimeClasspath").resolvedConfiguration.resolvedArtifacts.single {
                it.moduleVersion.id.group == "io.github.awxkee" && it.name == "jxl-coder"
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == expected[artifact.file.name]) { "Image codec checksum mismatch: ${artifact.file.name}" }
            logger.lifecycle("Verified image codec: ${artifact.file.name} SHA-256 $digest")
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyImageCodec) }
