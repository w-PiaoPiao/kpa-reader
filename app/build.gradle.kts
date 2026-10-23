import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名信息放在 local.properties（已在 .gitignore 里），不随仓库分发。
// 没 keystore 或没配密码时，release 包自动退回 debug 签名 —— 保证别人克隆下来也能构建。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystoreFile = rootProject.file("release.keystore")
val canSignRelease = keystoreFile.exists() &&
    !localProps.getProperty("KEYSTORE_PASSWORD").isNullOrBlank()

android {
    namespace = "com.kpa.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kpa.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"
    }

    signingConfigs {
        // 只在本地备好 keystore + 密码时才注册这个 config
        if (canSignRelease) {
            create("release") {
                storeFile = keystoreFile
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias = localProps.getProperty("KEY_ALIAS") ?: "release"
                keyPassword = localProps.getProperty("KEY_PASSWORD")
                    ?: localProps.getProperty("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (canSignRelease) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
