package com.android_tools.app.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 录制文件的首帧缩略图。读取失败（例如文件已被删除、格式不受支持）时退化为占位图标，
 * 不会让列表崩溃。
 */
@Composable
fun VideoThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    val context = LocalContext.current
    val frame by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { loadVideoFrame(context, uri) }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = frame
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun loadVideoFrame(context: Context, uri: Uri): ImageBitmap? {
    // MediaStore / SAF 走系统缩略图，最快也最省内存。
    runCatching {
        context.contentResolver.loadThumbnail(uri, Size(640, 360), null)
    }.getOrNull()?.let { return it.asImageBitmap() }

    // 应用专属目录的 file:// 路径（以及个别不提供缩略图的 Provider）解码首帧。
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") {
                uri.path?.let(retriever::setDataSource)
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.asImageBitmap()
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()
}
