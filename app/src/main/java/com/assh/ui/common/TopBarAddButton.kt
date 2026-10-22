package com.assh.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.assh.ui.theme.Navy700
import com.assh.ui.theme.Slate400
import com.assh.ui.theme.BlueAccent

/**
 * 各 Tab 统一的标题栏右侧操作按钮：带阴影的圆形小号 FAB。
 * 取代原先各 Tab 形状/位置不一的 FloatingActionButton（问题 3）。
 */
@Composable
fun TopBarAddButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector = Icons.Default.Add
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = BlueAccent,
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
        modifier = Modifier
            .padding(end = 12.dp)
            .size(40.dp)
            .shadow(8.dp, CircleShape, spotColor = BlueAccent)
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}

/**
 * 标题栏「次要操作」圆按钮：与 [TopBarAddButton] 同尺寸（40dp / 圆形 / 图标 20dp），
 * 但用低强调配色（Navy700 底 + 次要色图标），让主操作（蓝色 + 号）保持唯一视觉焦点。
 *
 * 间距由调用方用 `Row(horizontalArrangement = Arrangement.spacedBy(...))` 控制，
 * 这里不再自带 margin，避免与主按钮的 padding 叠加导致不等距。
 */
@Composable
fun TopBarIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = Navy700,
        contentColor = Slate400,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
        modifier = Modifier.size(40.dp)
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}
