# 本机 iPhone 配对验证（Windows）

本入口直接编译仓库的 `LockdownPairingClient`、`LockdownPairRecord`、
`LockdownPlistChannel`、TLS、CarKit、iAP2 和本地认证实现，使用与 APK 相同的协议源码。
桌面适配器替换 Android bulk 主机边界，由官方 Apple Devices 的本机 usbmux 服务
连接真实 iPhone。Python 仅通过进程管道转发字节，避免本机 JVM Socket 兼容问题；
它不生成配对证书，不代替共享 Kotlin 的配对协议实现。

需要 Android Studio 自带 JDK/Kotlin 编译器、Python 3 和官方 Apple Devices。
首次运行会从 Maven Central 下载并校验 XML pull parser 和 BC/BCJSSE 1.79，缓存及产物保存在根目录
`.private/desktop-pairing`，不进入源码。手机保持解锁；首次安装官方驱动后可能需要
重新插线，在 Apple Devices 中确认能看到手机。

从仓库根目录运行：

```powershell
python legacy-wired/tools/desktop-pairing/run.py self-test
python legacy-wired/tools/desktop-pairing/run.py inspect
python legacy-wired/tools/desktop-pairing/run.py pair
python legacy-wired/tools/desktop-pairing/run.py session
python legacy-wired/tools/desktop-pairing/run.py iap2 --auth-assets .private/verification/standalone-assets
```

`inspect` 只读取 QueryType、型号和系统版本。`pair` 使用新的独立 HostID/SystemBUID
发送真实配对请求，可能出现新的手机信任提示；成功后仅将配对材料写入私有目录，
再检查同一记录是否被 StartSession 接受并要求 TLS。不要分享私有目录中的记录。
`session` 重用已保存的记录，验证共享 TLS 握手、加密 QueryType、StopSession，以及
共享 CarKit 客户端的服务启动和端口连接；不会生成新身份。
`iap2` 继续执行真实链路协商、身份识别和 MFi 认证；必须主动指定自己的私有认证资产目录，
其中包含 `offline-mfi/identity.pk8` 和 `offline-mfi/certificate.p7b`。资产不会复制到公开源码。
身份识别声明与车机相同的 NCM 接口 3；桌面不会打开 NCM 或发送 CarPlayStartSession。
它不读取电脑已有配对私钥，不执行同步、备份、恢复或 CarPlay USB 模式切换。

桌面默认 JDK 的 X.509/JSSE 会拒绝 Lockdown 使用的空 issuer 配置。仅在桌面
`session`/`iap2` 模式注册 BC/BCJSSE 1.79，使共享 TLS 工厂和字节流实现保持原样。
没有关闭 JDK 算法约束或修改 APK 的 TLS provider；桌面 TLS 成功不能证明 Android provider 成功。
`Build.VERSION.SDK_INT` 桩选择 API 19 分支，运行时仍是桌面 JVM。

成功证明这台 iPhone 接受这些桌面协议交换。它没有执行 USB NCM 或完整 CarPlay，
不能替代 T3 内核/Dalvik/解码器的实机验证，也不代表 Apple 官方认证。
Android APK 不加载这里的桌面适配器；仅用于减少配对问题的上车排查次数。

2026-10-05 的真实 iPhone8,1 / iOS 15.8.8 已通过 Pair、记录复用、TLS、CarKit、
iAP2 身份识别，以及实验身份的 `0xaa05 AuthenticationSucceeded`。
同日换成实际用车的 iPhone 13（手机报告 iPhone14,5 / iOS 26.6.2），
使用独立的新配对记录后，同样通过上述流程。
脱敏记录见 [VERIFICATION.md](VERIFICATION.md)。

本机通道格式参考 [pymobiledevice3 usbmux 实现](https://github.com/doronz88/pymobiledevice3/blob/master/pymobiledevice3/usbmux.py)。

0.1.13 的旧版 APK 为 USB Lockdown 自带独立 BC/BCJSSE 1.79，不依赖 T3 系统
SSLEngine 的 TLS 1.2 支持。用 `--legacy-tls` 编译 APK 实际使用的 TLS 工厂，
此模式不会注册桌面全局 provider。其他共享协议源码仍保持一致。
多台手机可用 `--record-dir` 隔离记录，例如：

```powershell
python legacy-wired/tools/desktop-pairing/run.py iap2 --legacy-tls --record-dir .private/desktop-pairing/iphone13 --auth-assets .private/verification/standalone-assets
```

旧版 SSLEngine provider 与 Android 原生执行仍需实机验证；此入口运行在桌面 JVM。
