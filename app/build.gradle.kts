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
        versionCode = 28
        versionName = "1.28"
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

dependencies {
    // 仅用于「一键授权」：通过 Shizuku 以 shell 身份执行 pm grant
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
