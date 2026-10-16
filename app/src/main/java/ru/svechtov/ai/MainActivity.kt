package ru.svechtov.ai

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { App() } }
 private fun key(): java.security.Key {
  val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  store.getKey("svechtov_yandex",null)?.let { return it }
  val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
  gen.init(KeyGenParameterSpec.Builder("svechtov_yandex",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  return gen.generateKey()
 }
 private fun saveSecret(value:String) {
  val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key())
  getSharedPreferences("ai",0).edit().putString("secret",Base64.encodeToString(c.iv+c.doFinal(value.toByteArray()),Base64.NO_WRAP)).apply()
 }
 private fun loadSecret():String=runCatching {
  val raw=Base64.decode(getSharedPreferences("ai",0).getString("secret",""),Base64.DEFAULT)
  val c=Cipher.getInstance("AES/GCM/NoPadding")
  c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(0,12)))
  String(c.doFinal(raw.copyOfRange(12,raw.size)))
 }.getOrDefault("")
 private suspend fun ask(key:String,model:String,prompt:String):String=withContext(Dispatchers.IO) {
  val conn=(URL("https://llm.api.cloud.yandex.net/foundationModels/v1/completion").openConnection() as HttpURLConnection)
  try {
   conn.requestMethod="POST";conn.connectTimeout=15000;conn.readTimeout=45000;conn.doOutput=true
   conn.setRequestProperty("Content-Type","application/json")
   conn.setRequestProperty("Authorization","Api-Key $key")
   val payload=JSONObject().put("modelUri",model).put("completionOptions",JSONObject().put("stream",false).put("temperature",0.2).put("maxTokens","1000")).put("messages",org.json.JSONArray().put(JSONObject().put("role","system").put("text","Ты полезный русскоязычный помощник Артель Свечтовъ. Отвечай на общие вопросы без базы. Если вопрос требует конкретных данных заказов, а данные не предоставлены, честно сообщи об этом. Никогда не выдумывай цифры.")).put(JSONObject().put("role","user").put("text",prompt)))
   conn.outputStream.use { it.write(payload.toString().toByteArray()) }
   val status=conn.responseCode
   val response=(if(status in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
   if(status !in 200..299) return@withContext "Ошибка API ($status): "+response.take(350)
   JSONObject(response).getJSONObject("result").getJSONArray("alternatives").getJSONObject(0).getJSONObject("message").getString("text")
  } finally { conn.disconnect() }
 }
 @Composable private fun App() {
  var tab by remember { mutableIntStateOf(0) }
  var secret by remember { mutableStateOf("") }
  var model by remember { mutableStateOf(getSharedPreferences("ai",0).getString("model","") ?: "") }
  var question by remember { mutableStateOf("") }
  var response by remember { mutableStateOf("Загрузите снимок базы JSON или задайте вопрос.") }
  var snapshot by remember { mutableStateOf<JSONObject?>(null) }
  var fileName by remember { mutableStateOf("База не загружена") }
  var savedUri by remember { mutableStateOf(getSharedPreferences("ai",0).getString("snapshot_uri","") ?: "") }
  fun reloadSnapshot(uri:Uri) {
   runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { r->JSONObject(r.readText()) } ?: error("Пустой файл") }
    .onSuccess { snapshot=it;fileName="База обновлена: "+java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",java.util.Locale.getDefault()).format(java.util.Date()) }
    .onFailure { fileName="Не удалось прочитать базу: "+it.message }
  }
  LaunchedEffect(savedUri) { if(savedUri.isNotBlank()) reloadSnapshot(Uri.parse(savedUri)) }
  var busy by remember { mutableStateOf(false) }
  val scope=rememberCoroutineScope()
  val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri? ->
   uri?.let { selected ->
    runCatching { contentResolver.takePersistableUriPermission(selected,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    savedUri=selected.toString()
    getSharedPreferences("ai",0).edit().putString("snapshot_uri",savedUri).apply()
    reloadSnapshot(selected)
   }
  }
  MaterialTheme { Scaffold(topBar={ Text("Артель Свечтовъ AI",modifier=Modifier.padding(18.dp),style=MaterialTheme.typography.titleLarge) },bottomBar={ NavigationBar {
   listOf("Чат","Аналитика","Настройки").forEachIndexed { i,t->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(if(i==0)"●" else if(i==1)"▤" else "⚙")},label={Text(t)}) }
  } }) { padding->
   Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    when(tab) {
     0 -> {
      Text("ИИ-помощник",style=MaterialTheme.typography.titleMedium)
      Text(response)
      OutlinedTextField(value=question,onValueChange={question=it},label={Text("Вопрос")},modifier=Modifier.fillMaxWidth())
      Button(enabled=!busy&&question.isNotBlank(),onClick={
       busy=true;scope.launch {
        val q=question;val s=snapshot
        val local=if(s!=null) "\nЛокальный снимок (ограниченная выборка): "+s.toString().take(8000) else "\nБаза не загружена."
        response=if(model.isBlank()) "Укажи modelUri в настройках." else {
         val k=secret.ifBlank { loadSecret() }
         if(k.isBlank()) "Сохрани API-ключ в настройках." else runCatching { ask(k,model,q+local) }.getOrElse { "Ошибка сети: "+it.message }
        };busy=false
       }
      }) { Text(if(busy)"Ожидание..." else "Спросить Яндекс") }
     }
     1 -> {
      Text("Локальная база",style=MaterialTheme.typography.titleMedium)
      Text(fileName)
      Button(onClick={picker.launch(arrayOf("application/json","text/plain"))}) { Text("Выбрать файл на Google Drive") }
      Button(enabled=savedUri.isNotBlank(),onClick={reloadSnapshot(Uri.parse(savedUri))}) { Text("Обновить данные") }
      Text("Выбранный документ запоминается. Обновление при открытии приложения и по кнопке; изменения исходного файла не выполняются.")
      val s=snapshot
      if(s!=null) {
       Text("Заказов: "+(s.optJSONArray("orders")?.length() ?: 0))
       Text("Клиентов: "+(s.optJSONArray("clients")?.length() ?: 0))
       Text("Позиций склада: "+(s.optJSONArray("stock")?.length() ?: 0))
       Text("Синхронизация: ручная. Источник данных не изменяется.")
      }
     }
     else -> {
      Text("Yandex AI Studio",style=MaterialTheme.typography.titleMedium)
      OutlinedTextField(value=secret,onValueChange={secret=it},label={Text("API-ключ")},modifier=Modifier.fillMaxWidth(),visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
      OutlinedTextField(value=model,onValueChange={model=it},label={Text("modelUri (например gpt://<folder-id>/yandexgpt-lite/latest)")},modifier=Modifier.fillMaxWidth())
      Button(onClick={saveSecret(secret);getSharedPreferences("ai",0).edit().putString("model",model).apply();secret="";response="Настройки сохранены"}) { Text("Сохранить настройки") }
      Text("Ключ хранится зашифрованным на устройстве. Не добавляйте ключ в GitHub.")
     }
    }
   }
  } }
 }
}
