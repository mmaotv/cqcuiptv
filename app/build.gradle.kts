plugins {
    id("com.android.application")
}

val appVersionName = "2.0.0"
val appVersionCode = 2

// 默认播放列表地址，可用 -PdefaultPlaylist=... 在构建时覆盖（方便局域网调试）
val defaultPlaylist = (findProperty("defaultPlaylist") as String?)
    ?: "https://raw.githubusercontent.com/mmaotv/cqcuiptv/main/cqcu-tv.m3u"

android {
    namespace = "com.miaomiaotv.cqcuiptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miaomiaotv.cqcuiptv"
        minSdk = 19
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "default_playlist", defaultPlaylist)
    }

    signingConfigs {
        create("release") {
            // 公开项目的固定自签名，仅用于让不同版本的 APK 能相互覆盖安装
            storeFile = file("../keystore/cqcuiptv.jks")
            storePassword = "cqcuiptv"
            keyAlias = "cqcuiptv"
            keyPassword = "cqcuiptv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
    }
}

// 产物统一改名，方便下载
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val impl = output as com.android.build.api.variant.impl.VariantOutputImpl
            impl.outputFileName.set("LightTV-${appVersionName}.apk")
        }
    }
}
