plugins { id("com.android.application") }

android {
    namespace = "com.globaltv.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.globaltv.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 6
        versionName = "5.0.0"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
