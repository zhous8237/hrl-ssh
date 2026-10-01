package com.assh.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.assh.ui.theme.BlueAccent
import com.assh.ui.theme.Navy800
import kotlinx.coroutines.launch

/** 列表需保留短尾项作为底部锚点，才能越过超长末条内容；不满一屏时不显示按钮。 */
@Composable
fun BoxScope.ScrollToEdgeButtons(listState: LazyListState) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (listState.canScrollBackward) {
            ScrollFab(Icons.Default.KeyboardArrowUp, "回到顶部") {
                scope.launch { listState.animateScrollToItem(0) }
            }
        }
        if (listState.canScrollForward) {
            ScrollFab(Icons.Default.KeyboardArrowDown, "到最底部") {
                scope.launch {
                    listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                }
            }
        }
    }
}

@Composable
private fun ScrollFab(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Navy800.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, desc, tint = BlueAccent, modifier = Modifier.size(24.dp))
        }
    }
}
