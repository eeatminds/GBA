// Packs the extra cores (not part of LemuroidCores) into "bundle" builds.
plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    kotlinOptions {
        jvmTarget = "17"
    }
    namespace = "com.swordfish.lemuroid.extracores"
    sourceSets["main"].jniLibs.srcDir("../lemuroid_core_gpsp/src/main/jniLibs")
}

dependencies {
    implementation(kotlin(deps.libs.kotlin.stdlib))
}
