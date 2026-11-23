package com.android_tools.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import com.android_tools.app.sync.SyncEngine

/**
 * 用系统文件管理器打开同步目录。
 *
 * 依次尝试：
 * 1. `ACTION_VIEW` + `vnd.android.document/directory`（DocumentsUI / 三星「我的文件」等常见实现）；
 * 2. `ACTION_VIEW` + `resource/folder`（部分老文件管理器只认这个 MIME）；
 * 3. 都不行就提示一句，并让用户用系统的目录选择器重新定位。
 *
 * 注意：应用私有目录（`Android/data/<包名>/files/sync`）在 Android 11+ 上
 * 系统文件管理器根本进不去，这种情况下直接提示用户换一个公共目录（例如「下载」）。
 */
internal fun openSyncFolder(context: Context, folderUri: String, engine: SyncEngine) {
    val dirMime = DocumentsContract.Document.MIME_TYPE_DIR
    val candidates = ArrayList<Uri>()

    if (folderUri.isNotEmpty()) {
        // tree uri 交给文件管理器只会停在上级目录；转成 document uri 才能直接进这个文件夹
        val treeUri = runCatching { Uri.parse(folderUri) }.getOrNull()
        if (treeUri != null) {
            runCatching {
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            }.getOrNull()?.let { candidates += it }
            candidates += treeUri
        }
    } else {
        val dir = engine.defaultFolder().absolutePath.replace('\\', '/')
        val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath.replace('\\', '/')
        val docId = when {
            dir.startsWith("$storageRoot/") -> "primary:" + dir.removePrefix("$storageRoot/")
            dir.startsWith("/storage/") -> {
                val rest = dir.removePrefix("/storage/")
                val volume = rest.substringBefore('/')
                val tail = rest.substringAfter('/', "")
                "$volume:$tail"
            }
            else -> "primary:Android/data/${context.packageName}/files/sync"
        }
        candidates += Uri.parse("content://com.android.externalstorage.documents/document/${Uri.encode(docId)}")
    }

    for (uri in candidates) {
        for (mime in listOf(dirMime, "resource/folder")) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // 换下一个 MIME
            } catch (_: Exception) {
                // 某些机型抛 SecurityException，继续尝试
            }
        }
    }

    // 都打不开：应用私有目录本来就不允许浏览，提示用户换个公共目录
    val isPrivate = folderUri.isEmpty() || folderUri.contains("Android%2Fdata") || folderUri.contains("Android/data")
    Toast.makeText(
        context,
        if (isPrivate) "系统文件管理器无法浏览应用私有目录，请在「文件同步设置」里选一个公共目录（如「下载」）"
        else "没有可用的文件管理器，请先在系统里安装「文件」应用",
        Toast.LENGTH_LONG,
    ).show()
}
