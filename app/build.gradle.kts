import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

// Signing-Konfiguration wird aus keystore.properties gelesen (nicht versioniert,
// siehe .gitignore). Datei existiert nur lokal bzw. wird in CI aus Secrets erzeugt.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasSigningConfig = keystorePropertiesFile.exists()
if (hasSigningConfig) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "io.github.tux4us.miriquidihub"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.tux4us.miriquidihub"
        minSdk = 24
        targetSdk = 36
        versionCode = 11
        versionName = "0.3.0"
    }

    signingConfigs {
        create("release") {
            if (hasSigningConfig) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ohne keystore.properties (z.B. frischer Klon eines Mitwirkenden)
            // bleibt der Release-Build unsigned buildbar, statt den Build hart
            // abzubrechen. Ein unsignierter Release kann nicht installiert werden,
            // aber Kompilierung/Tests funktionieren weiterhin.
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            // smbj bringt BouncyCastle (Multi-Release-JAR) mit; doppelte Metadaten sind für die App unnötig.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/{AL2.0,LGPL2.1}"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.okhttp)
    implementation(libs.smbj)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.osmdroid.android)
    implementation(libs.gson)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.rules)
}
