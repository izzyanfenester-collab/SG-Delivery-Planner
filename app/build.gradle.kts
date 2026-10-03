plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }
android {
 namespace = "com.izzyan.sgdeliveryplanner"
 compileSdk = 36
 defaultConfig { applicationId = "com.izzyan.sgdeliveryplanner"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1.0" }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.12.00"))
 implementation("androidx.activity:activity-compose:1.12.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
 implementation("androidx.room:room-runtime:2.8.4")
 implementation("androidx.room:room-ktx:2.8.4")
 ksp("androidx.room:room-compiler:2.8.4")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 implementation("com.squareup.retrofit2:retrofit:2.11.0")
 implementation("com.squareup.retrofit2:converter-gson:2.11.0")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.osmdroid:osmdroid-android:6.1.20")
 testImplementation("junit:junit:4.13.2")
 testImplementation("androidx.test:core:1.7.0")
 testImplementation("org.robolectric:robolectric:4.16.1")
}
