plugins {
    id("com.android.application")
}

android {
    namespace = "it.bancofiches"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.bancofiches"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Chiave condivisa (non segreta) così l'APK compilato qui, quello in release/
    // e quelli futuri si aggiornano a vicenda senza disinstallare.
    // Per pubblicare sul Play Store crea una tua chiave privata.
    signingConfigs {
        create("shared") {
            storeFile = file("bancofiches.keystore")
            storePassword = "bancofiches"
            keyAlias = "bancofiches"
            keyPassword = "bancofiches"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation("junit:junit:4.13.2")
}
