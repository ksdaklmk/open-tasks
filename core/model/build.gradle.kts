plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.opentasks.core.model"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
