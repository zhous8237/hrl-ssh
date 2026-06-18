# sshj / BouncyCastle 通过反射加载算法实现，必须 keep
-keep class com.hierynomus.** { *; }
-keep class net.schmizz.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
# Android 上不存在的 JDK 内部类，sshj/eddsa 仅在反射路径引用，忽略即可
-dontwarn javax.security.auth.login.**
-dontwarn sun.security.x509.**
-dontwarn javax.security.auth.**
-dontwarn sun.security.**

# Termux 终端模块
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
