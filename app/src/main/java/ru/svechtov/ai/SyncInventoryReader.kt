package ru.svechtov.ai

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject

data class SyncInventory(val folders:Map<String,Int>,val examples:Map<String,List<String>>,val warnings:List<String>)

/** Read-only inspection of the entire OrderTrackerSync tree; never applies unknown operations. */
object SyncInventoryReader {
 fun scan(resolver:ContentResolver,tree:Uri,maxDepth:Int=4,maxItems:Int=3000):SyncInventory {
  val counts=linkedMapOf<String,Int>()
  val examples=linkedMapOf<String,MutableList<String>>()
  val warnings=mutableListOf<String>()
  var visited=0
  fun visit(id:String,path:String,depth:Int) {
   if(depth>maxDepth || visited>=maxItems) { warnings.add("Достигнут предел обхода: $path");return }
   val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id)
   try {
    resolver.query(children,arrayOf(
     DocumentsContract.Document.COLUMN_DOCUMENT_ID,
     DocumentsContract.Document.COLUMN_DISPLAY_NAME,
     DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use { cursor->
     while(cursor.moveToNext() && visited<maxItems) {
      visited++
      val childId=cursor.getString(0)?:continue
      val name=cursor.getString(1)?:"?"
      val mime=cursor.getString(2)
      if(mime==DocumentsContract.Document.MIME_TYPE_DIR) visit(childId,"$path/$name",depth+1)
      else {
       counts[path]=(counts[path]?:0)+1
       val list=examples.getOrPut(path){mutableListOf()}
       if(list.size<5) list.add(name)
      }
     }
    }?:warnings.add("Папка недоступна: $path")
   } catch(e:Exception) { warnings.add("Ошибка чтения $path: ${e.message}") }
  }
  visit(DocumentsContract.getTreeDocumentId(tree),"OrderTrackerSync",0)
  return SyncInventory(counts,examples,warnings)
 }
}
