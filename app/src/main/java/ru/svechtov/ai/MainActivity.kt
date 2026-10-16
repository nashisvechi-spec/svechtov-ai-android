package ru.svechtov.ai

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import org.json.JSONArray
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

 private fun databaseContext(data:JSONObject,question:String):String {
  val orders=SnapshotMerger.active(data,"order")
  val clients=SnapshotMerger.active(data,"client")
  val clientsById=clients.associateBy { it.optString("id") }
  val matchedClients=clients.filter { c->c.optString("name").length>=3 && question.contains(c.optString("name"),true) }
  val filtered=if(matchedClients.isEmpty()) orders else orders.filter { o->matchedClients.any { it.optString("id")==o.optString("clientId") } }
  val out=StringBuilder(SnapshotMerger.summary(data))
  out.append("\\nЗаказов в выборке: ").append(filtered.size).append("; икон в выборке: ").append(filtered.sumOf { it.optDouble("total",0.0) }.toLong())
  filtered.take(30).forEach { o->
   out.append("\\nЗаказ ").append(o.optString("orderCode")).append(", клиент ").append(clientsById[o.optString("clientId")]?.optString("name")?:"неизвестен")
    .append(", статус ").append(o.optString("status")).append(", дата ").append(o.optString("date"))
    .append(", количество ").append(o.optDouble("total",0.0).toLong())
  }
  if(filtered.size>30) out.append("\\nПоказаны только 30 записей. Для подсчётов используй полную сводку выше.")
  return out.toString()
 }
 @Composable private fun App() {
  var tab by remember { mutableIntStateOf(0) }
  var secret by remember { mutableStateOf("") }
  var model by remember { mutableStateOf(getSharedPreferences("ai",0).getString("model","") ?: "") }
  var question by remember { mutableStateOf("") }
  var response by remember { mutableStateOf("Загрузите снимок базы JSON или задайте вопрос.") }
  var snapshot by remember { mutableStateOf<JSONObject?>(null) }
  var fileName by remember { mutableStateOf("База не загружена") }
  val prefs=remember { getSharedPreferences("ai",0) }
  var folderUri by remember { mutableStateOf(prefs.getString("folder_uri","")?:"") }
  var savedFiles by remember { mutableStateOf(prefs.getString("file_uris","")?:"") }
  var report by remember { mutableStateOf<MergeReport?>(null) }
  var busy by remember { mutableStateOf(false) }
  val scope=rememberCoroutineScope()
  fun reload() {
   runCatching {
    val uris=if(folderUri.isNotBlank()) SnapshotMerger.folderDocuments(contentResolver,Uri.parse(folderUri))
     else (0 until JSONArray(savedFiles).length()).map { Uri.parse(JSONArray(savedFiles).getString(it)) }
    require(uris.isNotEmpty()) { "В источнике не найдено файлов orders-*.json" }
    SnapshotMerger.merge(SnapshotMerger.readDocuments(contentResolver,uris))
   }.onSuccess {
    report=it
    snapshot=it.database
    fileName="Прочитано снимков: ${it.sources}; спорных записей: ${it.conflicts.size}; предупреждений: ${it.warnings.size}"
   }.onFailure { fileName="Ошибка обновления: "+it.message }
  }
  LaunchedEffect(folderUri,savedFiles) { if(folderUri.isNotBlank() || savedFiles.isNotBlank()) reload() }
  val folderPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri:Uri?->
   uri?.let {
    runCatching { contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    folderUri=it.toString();savedFiles=""
    prefs.edit().putString("folder_uri",folderUri).remove("file_uris").apply()
    reload()
   }
  }
  val filesPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris:List<Uri>->
   if(uris.isNotEmpty()) {
    uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    savedFiles=JSONArray(uris.map { it.toString() }).toString();folderUri=""
    prefs.edit().putString("file_uris",savedFiles).remove("folder_uri").apply()
    reload()
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
        val local=if(s!=null && Regex("заказ|склад|икон|клиент|производ|остат|выруч|статист|долг|верба",RegexOption.IGNORE_CASE).containsMatchIn(q)) "\\nЛокальные данные: "+databaseContext(s,q) else ""
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
      Button(onClick={folderPicker.launch(null)}) { Text("Подключить папку read-model") }
      Button(onClick={filesPicker.launch(arrayOf("application/json","text/plain","application/octet-stream"))}) { Text("Выбрать несколько JSON (если папка недоступна)") }
      Button(enabled=folderUri.isNotBlank()||savedFiles.isNotBlank(),onClick={reload()}) { Text("Обновить все снимки") }
      Text("Данные читаются только для анализа; файлы на Google Диске не изменяются.")
      snapshot?.let { Text(SnapshotMerger.summary(it)) }
      report?.let { r->
       if(r.conflicts.isNotEmpty()) Text("Спорные записи исключены из аналитики: "+r.conflicts.take(8).joinToString()+if(r.conflicts.size>8)"…" else "")
       if(r.warnings.isNotEmpty()) Text(r.warnings.take(4).joinToString("\\n"))
      }
     }
     else -> {
      Text("Yandex AI Studio",style=MaterialTheme.typography.titleMedium)
      OutlinedTextField(value=secret,onValueChange={secret=it},label={Text("API-ключ")},modifier=Modifier.fillMaxWidth(),visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
      OutlinedTextField(value=model,onValueChange={model=it},label={Text("modelUri")},modifier=Modifier.fillMaxWidth())
      Button(onClick={saveSecret(secret);getSharedPreferences("ai",0).edit().putString("model",model).apply();secret="";response="Настройки сохранены"}) { Text("Сохранить настройки") }
      Text("Ключ хранится зашифрованным на устройстве.")
     }
    }
   }
  } }
 }
}
