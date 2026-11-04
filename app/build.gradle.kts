plugins {
    id("com.android.application")
}

android {
    namespace = "com.atvremote.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.atvremote.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }

    buildTypes {
        release {
            // Not minified: the TV runs the Injector class straight out of this APK.
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly on a phone.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
