plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.github.lsposed.magicwindow"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.github.lsposed.magicwindow"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 20405
        versionName = "2.4.5"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../magicwindow.keystore")
            storePassword = "magicwindow123"
            keyAlias = "magicwindow"
            keyPassword = "magicwindow123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources {
            // libxposed 入口清单必须原样打包进 APK 根 META-INF/xposed/
            merges.add("META-INF/xposed/*")
        }
    }
}

dependencies {
    implementation(project(":library:common"))
    implementation(project(":library:libhook"))

    // libxposed 服务端 SDK：供 App 侧与 LSPosed 框架通信（远程配置 + 激活状态）。
    // 通过 XposedProvider 跨进程调用框架，类须随 APK 一起打包
    implementation(libs.libxposed.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
}
