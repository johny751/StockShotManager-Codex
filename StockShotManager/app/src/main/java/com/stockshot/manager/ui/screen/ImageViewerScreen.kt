package com.stockshot.manager.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.stockshot.manager.StockShotApp
import com.stockshot.manager.data.SettingsStore
import com.stockshot.manager.data.entity.StockRecord
import com.stockshot.manager.ui.nav.ViewerHolder
import kotlinx.coroutines.launch
import java.io.File

/**
 * 全屏图片查看器：横滑切换（首尾循环）+ 双指缩放。顶部显示代码/名称（搜索来源额外显示日期与条数），
 * 底部显示进度与标记星标（点击切换）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(nav: NavHostController) {
    val session = remember { ViewerHolder.session }
    val records = session.records
    val context = LocalContext.current
    val app = context.applicationContext as StockShotApp
    val scope = rememberCoroutineScope()
    val pageDirection = remember {
        app.settingsStore.getSync(
            SettingsStore.KEY_VIEWER_PAGE_DIRECTION,
            SettingsStore.DEFAULT_VIEWER_PAGE_DIRECTION,
        )
    }

    if (records.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("无图片", color = Color.White)
        }
        return
    }

    val count = records.size
    // 首尾循环：使用超大页数并取模映射到真实索引
    val startPage = remember { session.startIndex.coerceIn(0, count - 1) }
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { count })

    val currentReal by remember { derivedStateOf { pagerState.currentPage.coerceIn(0, count - 1) } }
    val currentRecord = records[currentReal]

    // 标记状态缓存：key = code|date
    val markedMap = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(currentReal) {
        val key = markKey(currentRecord)
        if (!markedMap.containsKey(key)) {
            markedMap[key] = app.repository.isMarked(currentRecord.stockCode, currentRecord.date)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (pageDirection == SettingsStore.VIEWER_PAGE_VERTICAL) {
            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(records[page])
            }
        } else {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(records[page])
            }
        }

        // 顶部信息栏
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = "${currentRecord.stockCode}  ${currentRecord.stockName ?: ""}".trim(),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (session.fromSearch) {
                        Text(
                            text = "${currentRecord.date.replace('-', '/')}  第${currentReal + 1}条/共${count}条",
                            color = Color(0xCCFFFFFF),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        // 底部进度与标记
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0x99000000))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val marked = markedMap[markKey(currentRecord)] ?: false
            IconButton(onClick = {
                val key = markKey(currentRecord)
                val next = !(markedMap[key] ?: false)
                markedMap[key] = next
                scope.launch {
                    app.repository.setMarked(currentRecord.stockCode, currentRecord.date, next)
                }
            }) {
                if (marked) {
                    Icon(Icons.Filled.Star, contentDescription = "已标记", tint = Color(0xFFFFC107))
                } else {
                    Icon(Icons.Outlined.StarBorder, contentDescription = "标记", tint = Color.White)
                }
            }
            Text(
                text = if (marked) "已标记" else "未标记",
                color = Color.White,
                fontSize = 13.sp,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "${currentReal + 1} / $count",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun markKey(r: StockRecord): String = "${r.stockCode}|${r.date}"

@Composable
private fun ZoomableImage(record: StockRecord) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        if (scale > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }
    val context = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val zoomed = scale > 1f
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(record.imagePath))
                .crossfade(true)
                .build(),
            contentDescription = record.stockCode,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .then(
                    if (zoomed) Modifier.transformable(state = transformState)
                    else Modifier
                ),
        )
    }
}
