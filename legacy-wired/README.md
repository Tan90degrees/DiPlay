# Android 4.4 / 5.x 有线实验版

这个独立 Gradle 工程面向 Android **4.4.2 / API 19、ARMv7** 老车机，首个目标设备为
Allwinner T3、四核 Cortex-A7、1 GB RAM、1024×600 屏幕。最低版本为 API 19，
也在 API 21 的模拟框架中检查启动。**尚未在真实 T3 车机和 iPhone 上验证连接**。
构建、API lint 或启动测试通过，不等于硬件 USB、认证或解码已成功。

应用标识为 `com.shihab.diplay.legacy`，可以与现有 DiPlay 并存。此工程单独使用
JDK 17、Gradle 8.7、AGP 8.5.2、SDK 34、NDK 25.2.9519653；原工程仍使用自己的工具链。
它在构建时选取 `shared` 中的 iAP2 / USBMUX / Lockdown / AirPlay 协议源码，
替换 Android USB、VPN、界面和媒体后端。没有直接降低原应用的 minSdk。

## 首版范围

- 直接 USB Host → iPhone，有线模式切换、系统 USB 授权、信任配对、iAP2 和 AirPlay。
- H.264，向手机声明 **800×480 / 30 fps**，在屏幕中等比显示，双指触摸回传。
- PCM 和 AAC 音频输出。协商时不声明 Opus、HEVC、麦克风或第二屏。
- 断线、USB 模式切换和后台退出的资源清理；USB 配置及 H.264 解码器诊断。
- 不包含无线连接、Siri 麦克风、车机系统音频路由整合、HUD、视频播放扩展。

## 构建

安装 JDK 17 和 Android SDK，然后在 `legacy-wired` 目录运行：

```sh
sdkmanager 'platforms;android-34' 'build-tools;34.0.0' 'ndk;25.2.9519653'
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

可用 `ANDROID_HOME` 指定 SDK，或在此目录创建不提交的 `local.properties`。
APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`，只含 `armeabi-v7a` 原生库，
保留 KitKat 所需的 v1 APK 签名和 MultiDex 引导。

GitHub Actions 的 **Legacy wired Android 4.4** 工作流也执行上述检查，
并提供 `diplay-legacy-wired-source-only` APK 下载。

### 认证输入

**源码测试 APK 不含认证身份，可以检查 USB 和解码器，不能独立完成 CarPlay 认证。**
连接需要自行配置有效、匹配的认证证书和私钥。文件匹配不代表 iPhone 信任该证书。
本工程不创建或获取认证身份，也不使用缺省／伪造身份。

需要带入认证时，在仓库之外准备仅含下列两个文件的目录：

```text
offline-mfi/identity.pk8
offline-mfi/certificate.p7b
```

然后运行：

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/private/assets ./gradlew :app:assembleStandaloneDebug
```

文件必须各自为 1–16384 字节。运行时校验证书、密钥和签名匹配。
带认证的 APK 包含私钥，须自行保管；不要上传到公开仓库、PR、日志或公开构建产物。
私有配对记录保存在应用 `filesDir` / 私有 SharedPreferences 中，禁用系统备份。

## T3 车机第一次测试

1. 安装源码测试 APK，先确认能打开界面。
2. 将一台解锁的 iPhone 用数据线接到车机 **USB Host 数据口**，点击「USB 诊断」。
   电源口或 USB Device 口无法替代 Host。不要同时连接多个 Apple 设备。
3. 记录 Android/API、USB 配置及接口、是否发现 NCM，以及 H.264 解码器名称。
   「日志」可以查看最近 100 条；截图前检查是否包含个人信息。
4. 配置认证的版本中点击「连接」，同意系统 VPN 和 USB 授权。
   USB 模式切换后，系统可能再次请求授权；在 iPhone 上允许信任／CarPlay。
5. 有画面后用「菜单」收起控制栏，检查颜色、比例、单击、拖动、双指与音乐输出。
6. 拔线、重插、重复连接至少 5 次。退出应用后重新进入；检查旧会话是否清理。
   更换手机或 iPhone 撤销信任后，先断开再点「重置配对」。

应用仅在前台使用；切到后台会断开连接。第一轮不自动执行 root 命令或修改车机系统。
已 root 不意味着 USB Host、VPN/TUN、IPv6 和硬件解码都可用。

## 尚需设备验证的项目

| 环节 | 实现与验证边界 |
| --- | --- |
| USB 配置/alternate | 从原始描述符读取；通过授权 USB fd 的 usbfs ioctl 切换，不调用 API 21 的 UsbConfiguration/setInterface |
| 旧内核传输 | 每次最多 16 KiB，IO 使用原生缓冲区，避免在阻塞期间固定 Java 数组；需要检查 T3 内核/SELinux 是否允许相关 ioctl |
| NCM 网络 | NTB16 有界分片重组；非阻塞 TUN 用 poll；IPv6 链路本地服务使用接口作用域；需检查厂商 ROM 的 VPN/IPv6 支持 |
| 超时 | 启动前的 NCM NAK 超时丢弃该数据报；CarPlay 启动后超时终止连接，不重发可能部分发送的块 |
| 解码 | API 16 的 MediaCodec 缓冲区数组；Surface 变化重建解码器；队列溢出等待关键帧；需确认厂商解码器实际输出 |
| 音频 | API 19 AudioTrack 构造器和缓冲区写入；PCM 字节序转换、AAC ADTS；车机 DSP/通话/媒体路由未验证 |
| Android 5 | 同一 APK 的目标；API 21 启动检查通过，但连接和影音仍需实机验证 |

如果 ROM 的 VPN/TUN 或 IPv6 被裁掉，当前网络方案不能连接。设备已经 root，
后续可根据诊断结果单独评估原生 TUN/路由配置；首版不会在未知 ROM 上自动修改系统。

## 自动验证

测试覆盖 USB 描述符截断与畸形输入、alternate 选择、NCM 分片/合并及短包填充、
队列内存上限与关键帧恢复、PCM/AAC 的能力声明、原有 `/info` 回归、
API 19 / 21 启动销毁、认证缺失时拒绝连接，以及私有身份持久化。
Robolectric 使用 JVM 和 Android 模拟框架，不测试 native usbfs、真实 Dalvik、GPU 或 iPhone。
最终装机记录才用于判断这台 T3 是否已经实现可用的有线 CarPlay。
