plugins {
    id("com.android.application")
}

android {
    namespace = "com.rltranslator.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rltranslator.overlay"
        minSdk = 30
        targetSdk = 35
        versionCode = 44
        versionName = "1.44"
    }

    // 签名走环境变量，避免把 keystore 提交进仓库：
    //   JIMI_KEYSTORE / JIMI_STOREPASS / JIMI_KEYPASS
    signingConfigs {
        create("release") {
            val store = System.getenv("JIMI_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("JIMI_STOREPASS")
                keyPassword = System.getenv("JIMI_KEYPASS")
                keyAlias = "jimi"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// 没有任何第三方依赖：整个 APK 约 66 KB。
dependencies {}
