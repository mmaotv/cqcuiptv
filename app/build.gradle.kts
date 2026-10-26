plugins {
    id("com.android.application")
}

val appVersionName = "3.0.6"
val appVersionCode = 10

// 默认播放列表地址，可用 -PdefaultPlaylist=... 在构建时覆盖（方便局域网调试）
val defaultPlaylist = (findProperty("defaultPlaylist") as String?)
    ?: "https://raw.githubusercontent.com/mmaotv/cqcuiptv/main/cqcu-tv.m3u"

android {
    // 包名刻意避开 tv / iptv / launcher 等词：部分电视厂商的安装管控会按这些关键词判为
    // 「电视应用」或「运营商业务」直接拦截，与功能无关。
    namespace = "com.miaomiao.qinglive"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miaomiao.qinglive"
        minSdk = 19
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "default_playlist", defaultPlaylist)
    }

    signingConfigs {
        create("release") {
            // 公开项目的固定自签名，仅用于让不同版本的 APK 能相互覆盖安装
            storeFile = file("../keystore/qinglive.jks")
            storePassword = "qinglive"
            keyAlias = "qinglive"
            keyPassword = "qinglive"
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
        // targetSdk 故意停在 28（Android 4.4 走老路径 + 少权限弹窗），
        // lintVitalRelease 会因为「targetSdk 过旧」直接判构建失败，跳过它
        checkReleaseBuilds = false
    }
}

// 产物统一改名，方便下载
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val impl = output as com.android.build.api.variant.impl.VariantOutputImpl
            impl.outputFileName.set("LightLive-${appVersionName}.apk")
        }
    }
}
