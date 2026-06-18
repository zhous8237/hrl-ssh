# a-ssh

Android SSH 客户端 — Jetpack Compose + sshj + Termux 终端组件。

## 功能（v1）

1. 服务器连接配置管理（增删改查、克隆）
2. 密码 / 私钥两种认证方式
3. 私钥别名管理（导入、复制公钥、删除保护）
4. 完整交互式终端（xterm-256color、PTY、resize、ANSI 色彩）
5. 终端快捷工具条：特殊键、Ctrl/Alt 粘滞键、复制粘贴、自定义命令 Chip
6. 前台 Service 保活、断线检测、一键重连
7. Host Key TOFU 校验与变更警告（MITM 防护）
8. 凭据 Android Keystore 硬件级加密（StrongBox / TEE，AES-256-GCM）

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

## 文档

- [开发文档 v1](docs/开发文档-v1.md) — 架构与功能 1–6
- [视觉设计方案](docs/a-ssh%20视觉设计方案.md) — Cyber-Minimalism 设计语言
