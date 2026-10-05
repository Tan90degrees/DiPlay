# 本机 iPhone 配对验证（Windows）

本入口直接编译仓库的 `LockdownPairingClient`、`LockdownPairRecord`、
`LockdownPlistChannel` 和字节流接口，使用与 APK 相同的 Pair 请求和证书生成代码。
桌面适配器替换 Android bulk 主机边界，由官方 Apple Devices 的本机 usbmux 服务
连接真实 iPhone。Python 仅通过进程管道转发字节，避免本机 JVM Socket 兼容问题；
它不生成配对证书，不代替共享 Kotlin 的配对协议实现。

需要 Android Studio 自带 JDK/Kotlin 编译器、Python 3 和官方 Apple Devices。
首次运行会从 Maven Central 下载并校验 XML pull parser，缓存及产物保存在根目录
`.private/desktop-pairing`，不进入源码。手机保持解锁；首次安装官方驱动后可能需要
重新插线，在 Apple Devices 中确认能看到手机。

从仓库根目录运行：

```powershell
python legacy-wired/tools/desktop-pairing/run.py self-test
python legacy-wired/tools/desktop-pairing/run.py inspect
python legacy-wired/tools/desktop-pairing/run.py pair
```

`inspect` 只读取 QueryType、型号和系统版本。`pair` 使用新的独立 HostID/SystemBUID
发送真实配对请求，可能出现新的手机信任提示；成功后仅将配对材料写入私有目录，
再检查同一记录是否被 StartSession 接受并要求 TLS。不要分享私有目录中的记录。
它不读取电脑已有配对私钥，不执行同步、备份、恢复或 CarPlay USB 模式切换。

成功仅证明这台 iPhone 接受桌面上同一份共享配对代码生成的记录。它没有执行
TLS 握手、iAP2/MFi、USB NCM 或 CarPlay，不能替代 T3 内核/Dalvik/解码器的实机验证。
Android APK 不加载这里的桌面适配器；仅用于减少配对问题的上车排查次数。

本机通道格式参考 [pymobiledevice3 usbmux 实现](https://github.com/doronz88/pymobiledevice3/blob/master/pymobiledevice3/usbmux.py)。
