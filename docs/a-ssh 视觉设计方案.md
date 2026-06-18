# a-ssh 视觉设计方案

> 本文档定义 a-ssh 的整体视觉风格、核心页面设计与 Compose 层实现提示，供开发阶段对照实现。
> 配套阅读：`开发文档-v1.md`（功能与架构）、`开发文档-v2.md`、`开发文档-v3.md`。

---

## 1. 设计风格定义：Cyber-Minimalism（赛博极简）

**核心理念：内容即界面。** 减少不必要的装饰线条，利用深色沉浸式背景、柔和的弥散阴影和极简的排版来突出服务器和终端内容。

### 1.1 配色方案 (Theme)

| 角色 | 色值 | 说明 |
|---|---|---|
| Background（深暗底色） | `#0F172A` | 深蓝黑，非纯黑，更有空间感 |
| Surface（卡片色） | `#1E293B` | 提升层级感 |
| Primary（强调色） | `#3B82F6` | 电光蓝，用于连接状态、主按钮 |
| Success（成功色） | `#10B981` | 用于表示 SSH 已连接 |
| Warning（风险色） | `#F59E0B` | 用于 HostKey 变更警告 |

---

## 2. 核心页面设计方案

### 2.1 服务器列表屏 (Hosts List)

**设计目标：高效、直观、美观。**

- **布局**：采用 Staggered Card（交错卡片）或标准 Elevated Card。
- **视觉细节**：
  - 每个服务器卡片左侧有一个自动生成的 **彩色缩写头像**（如：主机名为 "Prod-DB"，头像显示 "PD"），背景使用低饱和度渐变。
  - **状态指示器**：在卡片右上角显示一个小呼吸灯。
    - 连接中 → 闪烁蓝
    - 已连接 → 常亮绿
    - 断开 → 灰色
- **快速操作**：长按卡片触发 Haptic Feedback（触感反馈），并弹出底部的 `ModalBottomSheet`，提供「编辑、克隆、删除」选项。
- **FAB（浮动按钮）**：一个简约的 `+` 按钮，背景采用 Primary 蓝，并带有 8dp 的模糊阴影。

### 2.2 终端工作区 (Terminal View)

**设计目标：沉浸式、极客感。**

- **沉浸化处理**：
  - 状态栏颜色与 `TerminalView` 背景完全对齐（`#0F172A`）。
  - 使用 **JetBrains Mono** 或 **Fira Code** 字体，字号建议 13sp，行高 1.4。
- **悬浮工具栏 (Accessory Bar)**：
  - 放置在键盘上方，背景采用 **毛玻璃效果 (Glassmorphism)**。
  - `Ctrl`、`Alt`、`Esc` 键点击后高亮（表示粘滞状态）。
  - **自定义命令 Chip**：横向滚动的 Chip 列表。长按可直接在当前界面快速新增命令。
- **交互动效**：点击「重新连接」时，界面中心出现一个极细的圆形进度条，成功后通过一个绿色的淡入动画恢复终端输入。

### 2.3 配置编辑页 (Edit/Create Host)

**设计目标：逻辑清晰，降低认知负担。**

- **分组式表单**：
  1. **基础信息**：标签、IP、端口、用户
  2. **凭据设置**：通过选卡切换（密码 / 私钥别名）
  3. **高级设置**：字符集、初始命令 —— 默认折叠，通过文本按钮展开
- **视觉引导**：当用户选择「私钥认证」时，点击「选择私钥」直接跳转到一个半透明的密钥选择弹窗，选定后显示密钥别名及其类型（如 ED25519）。

---

## 3. 技术实现提示 (Compose 层)

### 3.1 颜色定义 (`ui/theme/Color.kt`)

```kotlin
val Navy900 = Color(0xFF0F172A)
val Navy800 = Color(0xFF1E293B)
val BlueAccent = Color(0xFF3B82F6)
val GreenSuccess = Color(0xFF10B981)
```

### 3.2 沉浸式状态栏 (`MainActivity.kt`)

使用 `enableEdgeToEdge()` 让 `TerminalView` 填充整个屏幕：

```kotlin
WindowCompat.setDecorFitsSystemWindows(window, false)
// 在 TerminalScreen 中处理 WindowInsets 避免被导航栏遮挡
```

### 3.3 终端 AndroidView 封装

```kotlin
@Composable
fun TerminalComponent(session: SshTerminalSession) {
    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                setBackgroundColor(0xFF0F172A.toInt()) // 与 Compose 主题对齐
                attachSession(session)
                // 设置自定义字体等
            }
        },
        modifier = Modifier.fillMaxSize().imePadding() // 自动处理键盘高度
    )
}
```

---

## 4. 细节提升建议 (Modern Touch)

1. **骨架屏 (Skeleton Screen)**：在数据库读取服务器列表时，显示灰色的闪烁占位图，提升感官速度。
2. **Shared Element Transition**：从列表页点击进入终端页时，让服务器的「头像图标」平滑移动并转化为顶部的「连接状态点」。
3. **连接成功提示**：不要使用生硬的弹窗，在终端顶部下滑出一个 2 秒即逝的简约通知条（Toast/Snackbar 增强版）。
