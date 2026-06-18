# a-ssh

Android SSH 客户端 — Jetpack Compose + sshj + Termux 终端组件 + AI Agent 自主运维。

## 核心功能

### SSH 连接与终端
1. 服务器连接配置管理（增删改查、克隆）
2. 密码 / 私钥两种认证方式
3. 私钥别名管理（导入、复制公钥、删除保护）
4. 完整交互式终端（xterm-256color、PTY、resize、ANSI 色彩）
5. 终端快捷工具条：特殊键、Ctrl/Alt 粘滞键、复制粘贴、自定义命令 Chip
6. 前台 Service 保活、断线检测、一键重连
7. Host Key TOFU 校验与变更警告（MITM 防护）
8. 凭据 Android Keystore 硬件级加密（StrongBox / TEE，AES-256-GCM）

### AI Agent 自主运维
9. 集成 Claude / OpenAI 等 LLM，Agent 自主执行 SSH 命令
10. Function Calling 工具链：exec_command、read_file、write_file、search_web、fetch_url
11. 危险命令检测（rm -rf、dd、mkfs 等）与用户确认
12. 会话历史记录与重放（JSON 格式存储）
13. 多 LLM 配置切换（API Key、模型、System Prompt）

### 数据同步
14. WebDAV 双向同步：主机配置、私钥、命令历史
15. 端到端加密（AES-256-GCM + Argon2id 密钥派生）
16. 冲突检测与合并策略（本地优先 / 远程优先 / 手动）

## 构建

用 Android Studio 打开工程（Gradle JDK 选内置 JBR 17+），同步后直接运行。

- compileSdk 35 / targetSdk 35 / minSdk 26
- AGP 8.7.x + Kotlin 2.0.x + KSP + Room + Compose BOM

命令行（需 JDK 17+）：

```bash
JAVA_HOME="<jbr-path>" ./gradlew :app:assembleDebug
```

## 许可证

本项目包含来自 [termux/termux-app](https://github.com/termux/termux-app) 的
`terminal-emulator` 与 `terminal-view` 模块（已修改：移除 JNI 本地 pty，
改为可插拔的 `SessionTransport` 以接入 SSH 流）。

因此整个项目以 **GPLv3** 发布，详见 [LICENSE](LICENSE)。
发布的 APK 对应版本的完整源码可从本仓库获取。

## 技术栈

- **UI**: Jetpack Compose + Material3 + Navigation
- **SSH**: sshj (会话管理 + 认证 + 端口转发)
- **终端**: Termux terminal-emulator/view (修改版，移除 JNI pty)
- **数据库**: Room + SQLite (主机、私钥、命令历史)
- **加密**: Android Keystore + AES-256-GCM
- **AI**: Anthropic Claude API / OpenAI API (Retrofit + SSE)
- **同步**: OkHttp + WebDAV + Argon2id
