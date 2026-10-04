plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "io.github.libxposed.service"

    sourceSets {
        val main by getting
        main.apply {
            setRoot("service/service/src/main")
            aidl.directories += "service/interface/src/main/aidl"
        }
    }

    buildFeatures {
        buildConfig = false
        resValues = false
        aidl = true
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
    // The submodule sources carry the upstream API 102 RFC, annotated with
    // @SinceApi from io.github.libxposed:annotation - the same compileOnly
    // dependency the upstream service project itself declares.
    compileOnly("io.github.libxposed:annotation:1.0.0")
}
