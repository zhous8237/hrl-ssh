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
