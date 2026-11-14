// LibretroDroid 0.14.0 (GPL-3.0, https://github.com/Swordfish90/LibretroDroid),
// vendored so we can add the libretro netpacket interface for online link play.
plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.swordfish.libretrodroid"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(kotlin(deps.libs.kotlin.stdlib))
    implementation(deps.libs.androidx.lifecycle.runtime)
}
