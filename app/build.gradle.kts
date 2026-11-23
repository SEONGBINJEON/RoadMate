import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// AI 대화(Gemini) 키는 저장소에 올리지 않는 local.properties에서 읽는다. 없으면 기본 대본으로 동작.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.roadmate.app"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.roadmate.app"
        minSdk = 28          // Pleos 요구사항: API 28 이상
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"

        buildConfigField("String", "GEMINI_API_KEY", "\"${localProps.getProperty("GEMINI_API_KEY", "")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${localProps.getProperty("GEMINI_MODEL", "gemini-flash-lite-latest")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 제출용 release APK를 바로 실행할 수 있도록 debug 키로 서명
            signingConfig = signingConfigs.getByName("debug")
            // 제출용 APK에는 개발용과 분리한 제출 전용 키만 넣는다(심사 후 폐기). 없으면 대본으로 동작.
            buildConfigField("String", "GEMINI_API_KEY", "\"${localProps.getProperty("GEMINI_API_KEY_RELEASE", "")}\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Pleos Connect SDK (버전은 공식 문서 기준)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    testImplementation("junit:junit:4.13.2")
}
