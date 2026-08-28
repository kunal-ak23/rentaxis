import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
val mapsProperties = Properties()
val mapsPropertiesFile = rootProject.file("maps.properties")
val releaseBuildRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}

if (mapsPropertiesFile.exists()) {
    mapsPropertiesFile.inputStream().use(mapsProperties::load)
} else if (releaseBuildRequested) {
    throw GradleException(
        "Google Maps is not configured. Add android/maps.properties with GOOGLE_MAPS_API_KEY.",
    )
}

val googleMapsApiKey = mapsProperties.getProperty("GOOGLE_MAPS_API_KEY", "")
if (releaseBuildRequested && googleMapsApiKey.isBlank()) {
    throw GradleException("GOOGLE_MAPS_API_KEY must not be blank for a release build.")
}

if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
} else if (releaseBuildRequested) {
    throw GradleException(
        "Release signing is not configured. Add android/key.properties and an upload keystore.",
    )
}

android {
    namespace = "com.rentaxis.renter"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.rentaxis.renter"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsApiKey
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

flutter {
    source = "../.."
}
