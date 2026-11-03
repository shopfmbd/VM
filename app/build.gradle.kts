plugins { id("com.android.application") }
android {
    namespace = "dev.openvm"
    compileSdk = 34
    defaultConfig { applicationId = "dev.openvm"; minSdk = 24; targetSdk = 28; versionCode = 12; versionName = "0.5.5" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs { useLegacyPackaging = true } }
}
// sun.misc.Unsafe is not in android.jar; the vendored HiddenApiBypass needs it at compile time only (the device has the real class).
dependencies { compileOnly(files("libs/compile-stubs.jar")) }
