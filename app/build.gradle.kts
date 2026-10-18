plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }
android {
 namespace = "com.izzyan.sgdeliveryplanner"
 compileSdk = 36
 defaultConfig { applicationId = "com.izzyan.sgdeliveryplanner"; minSdk = 26; targetSdk = 36; versionCode = 8; versionName = "1.4.3" }
 // Release signing is intentionally supplied only by environment variables.
 // Never commit the keystore or passwords to the repository.
 val releaseKeystore = System.getenv("SG_DELIVERY_KEYSTORE_PATH")
 val releaseStorePassword = System.getenv("SG_DELIVERY_KEYSTORE_PASSWORD")
 val releaseKeyAlias = System.getenv("SG_DELIVERY_KEY_ALIAS")
 val releaseKeyPassword = System.getenv("SG_DELIVERY_KEY_PASSWORD")
 val releaseSigningReady = listOf(releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
   .all { !it.isNullOrBlank() }

 if (releaseSigningReady) {
   signingConfigs {
     create("sgDeliveryRelease") {
       storeFile = file(requireNotNull(releaseKeystore))
       storePassword = requireNotNull(releaseStorePassword)
       keyAlias = requireNotNull(releaseKeyAlias)
       keyPassword = requireNotNull(releaseKeyPassword)
     }
   }
 }
 buildTypes {
   getByName("release") {
     isMinifyEnabled = false
     if (releaseSigningReady) signingConfig = signingConfigs.getByName("sgDeliveryRelease")
   }
 }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

 testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.12.00"))
 implementation("androidx.activity:activity-compose:1.12.1")
 implementation("androidx.core:core-ktx:1.17.0")
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
 implementation("org.maplibre.navigation:navigation-core-android:5.0.0")
 implementation("org.maplibre.gl:android-sdk:13.5.0")
 implementation("com.google.android.gms:play-services-location:21.4.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("androidx.test:core:1.7.0")
 testImplementation("org.robolectric:robolectric:4.16.1")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 testImplementation("org.apache.poi:poi-ooxml:5.4.1")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
