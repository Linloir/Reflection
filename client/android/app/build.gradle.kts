plugins { id("com.android.application") }

android {
    namespace = "io.github.linloir.reflection"
    compileSdk = 35
    defaultConfig {
        applicationId = providers.gradleProperty("reflectionApplicationId")
            .orElse("io.github.linloir.reflection").get()
        manifestPlaceholders["reflectionAppLabel"] = if (applicationId == "io.github.linloir.reflection")
            "Reflection" else "Reflection Preview"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}
