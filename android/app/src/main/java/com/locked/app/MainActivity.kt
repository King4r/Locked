package com.locked.app

import android.os.Bundle
import android.os.BatteryManager
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

private val Gold = Color(0xFFD4B56F)
private val Ink = Color(0xFF0A0B0E)
private val Card = Color(0xFF17181D)
private val client = OkHttpClient()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("locked", Context.MODE_PRIVATE)
        val installationId = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installation_id", it).apply()
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Ink, surface = Card)) {
                LockedApp(
                    installationId = installationId,
                    enrolled = prefs.getBoolean("enrolled", false),
                    onEnrolled = { prefs.edit().putBoolean("enrolled", true).apply() },
                    deviceInfo = { collectDeviceInfo(this@MainActivity, installationId) }
                )
            }
        }
    }
}

@Composable
private fun LockedApp(installationId: String, enrolled: Boolean, onEnrolled: () -> Unit, deviceInfo: () -> JSONObject) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(enrolled) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(76.dp).background(Color(0xFF211C11), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shield, null, tint = Gold, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("LOCKED", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
            Text("ANDROID DEVICE MANAGEMENT", color = Gold, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(42.dp))
            if (done) {
                Icon(Icons.Default.Lock, null, tint = Gold, modifier = Modifier.size(28.dp))
                Text("This device is registered", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Device ID: ${installationId.take(18)}…", color = Color.LightGray, fontSize = 12.sp)
                Text("Your administrator manages enrollment and device settings.", color = Color.Gray, fontSize = 13.sp)
            } else {
                Text("Register this tablet", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text("Enter the one-time code supplied by your administrator.", color = Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(value = code, onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(5) }, label = { Text("Enrollment code") }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, focusedLabelColor = Gold, cursorColor = Gold))
                Spacer(Modifier.height(14.dp))
                Button(onClick = {
                    scope.launch {
                        busy = true; message = ""
                        try {
                            val result = withContext(Dispatchers.IO) { enroll(code, deviceInfo()) }
                            if (result) { done = true; onEnrolled(); message = "Registration successful." }
                            else message = "Registration failed. Check the code and backend configuration."
                        } catch (e: Exception) { message = e.message ?: "Could not connect." }
                        busy = false
                    }
                }, enabled = code.length == 5 && !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp) else Text("REGISTER DEVICE", fontWeight = FontWeight.Bold)
                }
            }
            if (message.isNotBlank()) Text(message, color = if (message.contains("successful")) Gold else Color(0xFFFF9999), fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(36.dp))
            Text("SECURE ENROLLMENT • ADMIN AUTHORIZED", color = Color.Gray, fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}

private fun collectDeviceInfo(context: Context, installationId: String): JSONObject {
    val battery = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    return JSONObject().apply {
        put("p_installation_id", installationId)
        put("p_name", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".take(80))
        put("p_manufacturer", android.os.Build.MANUFACTURER)
        put("p_model", android.os.Build.MODEL)
        put("p_android_version", android.os.Build.VERSION.RELEASE)
        put("p_sdk_int", android.os.Build.VERSION.SDK_INT)
        put("p_battery_percent", battery.coerceIn(0, 100))
    }
}
private fun enroll(code: String, info: JSONObject): Boolean {
    if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) throw IllegalStateException("Add Supabase settings in android/local.properties.")
    val hash = MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString("") { "%02x".format(it) }
    info.put("p_code_hash", hash)
    val body = info.toString().toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/claim_enrollment_code")
        .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
        .post(body).build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Server rejected enrollment (HTTP ${response.code}).")
        return true
    }
}
