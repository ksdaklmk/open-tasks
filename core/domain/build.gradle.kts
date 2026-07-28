plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.opentasks.core.domain"
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
    api(project(":core:model"))
    implementation(project(":core:sync"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
