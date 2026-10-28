plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Repo GitHub otomatis diisi saat build di GitHub Actions (untuk fitur update dalam aplikasi).
val githubRepo: String = System.getenv("GITHUB_REPOSITORY") ?: "USERNAME/ZixTheServer"
val appVersionName: String = System.getenv("APP_VERSION_NAME") ?: "2.0.0-dev"
val appVersionCode: Int = 200 + (System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: 0)

android {
    namespace = "com.roziq.zixtheserver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.roziq.zixtheserver"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    // Tanda tangan HARUS tetap sama agar APK baru bisa menimpa (update) yang lama.
    // Bawaan: keystore di repo. Lebih aman: isi GitHub Secrets (lihat README).
    signingConfigs {
        create("zix") {
            storeFile = file(System.getenv("ZIX_KEYSTORE_FILE") ?: "zix-release.jks")
            storePassword = System.getenv("ZIX_KEYSTORE_PASSWORD") ?: "zixtheserver"
            keyAlias = System.getenv("ZIX_KEY_ALIAS") ?: "zix"
            keyPassword = System.getenv("ZIX_KEY_PASSWORD") ?: "zixtheserver"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("zix")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zix")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/**"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SSH client (fork JSch yang aktif dirawat, mendukung algoritma modern)
    implementation("com.github.mwiede:jsch:0.2.18")
}
