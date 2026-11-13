plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布签名：通过环境变量提供固定密钥（CI 里用 Secrets）。未提供时退回 debug 签名，
// 这样任何机器都能直接构建出可安装的 APK。
val ksFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.pocketshell.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketshell.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (ksFile != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // 终端库自带的本地 pty 库用不到（我们只连远程 SSH），不打进包里
        jniLibs.excludes += "**/libtermux.so"
        resources.excludes += setOf(
            "META-INF/versions/**", "META-INF/*.kotlin_module",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES"
        )
    }
    lint { abortOnError = false; checkReleaseBuilds = false }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // 让 JSch 在测试里和在 Android 上一样走基础类（BouncyCastle 实现），而不是 JDK 多版本类
                it.jvmArgs("-Djdk.util.jar.enableMultiRelease=false")
                it.testLogging { showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
        }
    }
}

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
}
