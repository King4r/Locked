import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val local = Properties().apply { val f=rootProject.file("local.properties"); if(f.exists()) f.inputStream().use { load(it) } }
android { namespace="com.locked.app"; compileSdk=35
 defaultConfig { applicationId="com.locked.app"; minSdk=26; targetSdk=35; versionCode=1; versionName="1.0"; buildConfigField("String","SUPABASE_URL","\"${local.getProperty("SUPABASE_URL","")}\""); buildConfigField("String","SUPABASE_ANON_KEY","\"${local.getProperty("SUPABASE_ANON_KEY","")}\"") }
 buildFeatures { compose=true; buildConfig=true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
