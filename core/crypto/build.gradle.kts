plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.opentasks.core.crypto"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.tink)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}
