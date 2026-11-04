// The Game Boy core for Play Store builds, where each core is its own download: our Gambatte
// with the network friendly link cable (see ../gambatte-link), in place of LemuroidCores' stock one.
plugins {
    id("com.android.dynamic-feature")
    id("kotlin-android")
    id("kotlin-kapt")
}

android {
    namespace = "com.swordfish.lemuroid.core.gambatte"
    defaultConfig {
        missingDimensionStrategy("opensource", "play")
        missingDimensionStrategy("cores", "dynamic")
    }
    sourceSets["main"].jniLibs.setSrcDirs(listOf("../gambatte-link/jniLibs"))
    packagingOptions {
        doNotStrip("*/*/*_libretro_android.so")
    }
}

dependencies {
    implementation(project(":lemuroid-app"))
    implementation(kotlin(deps.libs.kotlin.stdlib))
}
