package ru.svechtov.ai

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

data class MergeReport(val database:JSONObject,val sources:Int,val conflicts:List<String>,val warnings:List<String>)

object SnapshotMerger {
 private data class Version(val entity:JSONObject,val source:String) {
  val revision:JSONObject get()=entity.optJSONObject("revision")?:JSONObject()
  val op:String get()=revision.optString("opId")
  val ancestors:Set<String> get() {
   val arr=revision.optJSONArray("ancestorOpIds")?:return emptySet()
   return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
  }
 }
 fun merge(files:List<Pair<String,JSONObject>>):MergeReport {
  require(files.isNotEmpty()) { "Нет снимков для объединения" }
  val versions=linkedMapOf<String,MutableList<Version>>()
  val warnings=mutableListOf<String>()
  files.forEach { (source,root)->
   val data=root.optJSONObject("data")?:root
   val entities=data.optJSONArray("entities")
   if(entities==null) {
    warnings.add("$source: отсутствует data.entities")
    return@forEach
   }
   for(i in 0 until entities.length()) {
    val item=entities.optJSONObject(i)?:continue
    val key=item.optString("key")
    if(key.isNotBlank()) versions.getOrPut(key){mutableListOf()}.add(Version(item,source))
   }
  }
  require(versions.isNotEmpty()) { "В файлах нет записей Order Tracker" }
  val merged=JSONArray()
  val conflicts=mutableListOf<String>()
  versions.forEach { (key,candidates)->
   val distinct=candidates.distinctBy { it.op.ifBlank { it.entity.toString() } }
   val winner=if(distinct.size==1) distinct.first() else {
    val descendants=distinct.filter { candidate->
     candidate.revision.optBoolean("ancestryComplete",false) &&
      distinct.all { other->candidate===other || (other.op.isNotBlank() && other.op in candidate.ancestors) }
    }
    when {
     descendants.size==1 -> descendants.first()
     distinct.map { it.entity.optJSONObject("value")?.toString()?:"" }.distinct().size==1 -> distinct.maxByOrNull { it.revision.optLong("changedAtMs",0) }!!
     else -> {
      conflicts.add(key)
      // Keep a deterministic candidate for display, but do not expose disputed data in analytics.
      null
     }
    }
   }
   if(winner!=null) merged.put(winner.entity)
  }
  val data=JSONObject().put("entities",merged)
  return MergeReport(JSONObject().put("data",data),files.size,conflicts,warnings)
 }
 fun readDocuments(resolver:ContentResolver,uris:List<Uri>):List<Pair<String,JSONObject>> =
  uris.map { uri->
   val json=resolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()) }
    ?:error("Не удалось открыть $uri")
   uri.toString() to json
  }
 fun folderDocuments(resolver:ContentResolver,tree:Uri):List<Uri> {
  val parent=DocumentsContract.getTreeDocumentId(tree)
  val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
  val result=mutableListOf<Uri>()
  resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use { cursor->
   while(cursor.moveToNext()) {
    val name=cursor.getString(1)?:continue
    val mime=cursor.getString(2)
    if(name.startsWith("orders-",true)&&name.endsWith(".json",true)&&mime!=DocumentsContract.Document.MIME_TYPE_DIR)
     result.add(DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0)))
   }
  }?:error("Поставщик документов не разрешает чтение папки")
  return result
 }
 fun active(root:JSONObject,kind:String):List<JSONObject> {
  val entities=root.optJSONObject("data")?.optJSONArray("entities")?:return emptyList()
  return (0 until entities.length()).mapNotNull { i->
   entities.optJSONObject(i)?.takeIf { it.optString("key").startsWith("$kind:") }?.optJSONObject("value")
  }.filterNot { it.optBoolean("deleted",false) }
 }
 fun summary(root:JSONObject):String {
  val orders=active(root,"order")
  val clients=active(root,"client")
  val byStatus=orders.groupingBy { it.optString("status","unknown") }.eachCount()
  val total=orders.sumOf { it.optDouble("total",0.0) }
  return "Заказов: ${orders.size}; клиентов: ${clients.size}; икон в заказах: ${total.toLong()}. Статусы: ${byStatus.entries.joinToString { "${it.key} — ${it.value}" }}. Это количество в заказах, не производство за месяц."
 }
}
