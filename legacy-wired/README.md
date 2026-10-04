# Android 4.4 / 5.x 有线实验版

这个独立 Gradle 工程面向 Android **4.4.2 / API 19、ARMv7** 老车机，首个目标设备为
Allwinner T3、四核 Cortex-A7、1 GB RAM、1024×600 屏幕。最低版本为 API 19，
也在 API 21 的模拟框架中检查启动。**T3 真机已验证短片解码和 iPhone USB 枚举，完整连接尚未验证**。
构建、API lint 或启动测试通过，不等于硬件 USB、认证或解码已成功。

应用标识为 `com.shihab.diplay.legacy`，可以与现有 DiPlay 并存。此工程单独使用
JDK 17、Gradle 8.7、AGP 8.5.2、SDK 34、NDK 25.2.9519653；原工程仍使用自己的工具链。
它在构建时选取 `shared` 中的 iAP2 / USBMUX / Lockdown / AirPlay 协议源码，
替换 Android USB、VPN、界面和媒体后端。没有直接降低原应用的 minSdk。

## 首版范围

- 直接 USB Host → iPhone，有线模式切换、系统 USB 授权、信任配对、iAP2 和 AirPlay。
- H.264，向手机声明 **800×480 / 30 fps**，在屏幕中等比显示，双指触摸回传。
- PCM 和 AAC 音频输出。协商时不声明 Opus、HEVC、麦克风或第二屏。
- 断线、USB 模式切换和后台退出的资源清理；独立设备诊断、H.264 实际解码测试和日志导出。
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
2. **不插手机**也可先点「设备诊断」，查看 Android/API、CPU ABI、USB Host 和
   H.264 解码器及 profile/level。再点「H.264 测试」，确认两段移动图案均正常显示。
   测试分别使用 H.264 Baseline 和 High、Level 3.1、800×480、30 fps，每段 60 帧，
   无音频，使用与有线后端相同的默认解码器选择和旧缓冲区数组接口。
   日志记录实际解码器名称、输入/输出帧数和耗时；名称或成功输出帧数不能代替目视检查，
   两秒片段也不能证明长时间播放、延迟或 iPhone 实际 profile/level 已满足要求。
3. 将一台解锁的 iPhone 用数据线接到车机 **USB Host 数据口**，点击「设备诊断」。
   电源口或 USB Device 口无法替代 Host。不要同时连接多个 Apple 设备。
   授予 USB 权限后自动继续诊断，记录当前配置、MUX/NCM、接口、端点及 packet size。
   USB 失败也会继续枚举解码器，诊断不切换配置或占用接口。
4. 「日志」查看最近 100 条，可复制文本或分享 `wired-diagnostics.txt`。
   每轮诊断后自动保存报告到应用私有目录，无需存储权限或文件选择器。
   分享前检查日志是否包含个人信息；诊断不会读取认证文件或配对记录。
5. 配置认证的版本中点击「连接」，同意系统 VPN 和 USB 授权。
   USB 模式切换后，系统可能再次请求授权；在 iPhone 上允许信任／CarPlay。
6. 有画面后用「菜单」收起控制栏，检查颜色、比例、单击、拖动、双指与音乐输出。
7. 拔线、重插、重复连接至少 5 次。退出应用后重新进入；检查旧会话是否清理。
   更换手机或 iPhone 撤销信任后，先断开再点「重置配对」。

应用仅在前台使用；切到后台会断开连接。第一轮不自动执行 root 命令或修改车机系统。
已 root 不意味着 USB Host、VPN/TUN、IPv6 和硬件解码都可用。

没有可接收分享的应用时，使用复制按钮，或在电脑上运行下列命令导出调试 APK 的报告：

```sh
adb shell run-as com.shihab.diplay.legacy cat files/wired-diagnostics.txt
```

报告只保留最近一次保存的内容，卸载会清除；连接失败后先打开「日志」刷新报告再导出。
CI 使用临时调试签名，不同构建的签名可能不同。更新安装若提示
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先导出报告，再卸载旧测试包后安装。
卸载会清除本地配对和诊断记录；带认证的安装请使用自己保管的固定签名配置。
如需重新生成原始测试图案，用支持 libx264 的 FFmpeg 执行
`python tools/generate_decoder_samples.py /path/to/ffmpeg`。片段为自行生成的测试图案，
没有外部视频、个人信息或认证材料。

## 无需 iPhone 的离线自测（0.1.2）

控制栏新增「离线自测」，与连接、USB 诊断和短视频测试互斥。可随时点
「停止/断开」取消，离开前台也会停止；等待清理完成后再启动另一项或连接手机。

| 自测 | 操作与报告 | 验证范围 |
| --- | --- | --- |
| 视频持续 60 秒 | Baseline、High 各 30 秒，复用同一解码器连续送入循环片段；每种预期输入/输出 900 帧，记录 native 内存前后值 | 解码与显示的短时持续运行；内存值不包含全部 GPU/解码器占用，不证明长期稳定或真实 CarPlay 延迟 |
| 音频 PCM / AAC | 44100 Hz 立体声；先左声道 440 Hz、右声道 660 Hz，再双声道 AAC 550 Hz；使用较低测试电平，记录 AudioTrack 写入字节 | 复用有线版的 PCM 字节序转换、AAC/ADTS 解码与旧 AudioTrack 后端；写入成功还需听音确认 |
| 触控单击 / 拖动 / 双指 | 网格、触点 ID、归一化坐标和 DOWN/UP 状态；结束时记录事件、最大触点与抬起数 | 与连接共用坐标转换；不验证手机端实际接收或响应 |
| 网络 TUN / IPv6 | 同意系统 VPN 授权，临时建立与有线路径相同的 fe80::2/64 TUN；读取 5 个本机 UDP 测试包并注入带校验和的 IPv6 回包，结束后关闭 TUN | 授权、TUN fd 的 native poll/read/write、接口作用域、IPv6 UDP 内核回包；不经过 USB/NCM 或 iPhone |

音频测试前把车机音量调到适中。网络自测可能替换正在使用的其他 VPN，测试前结束其他 VPN；
只添加链路本地 IPv6 路由，不配置默认路由或 DNS。停止后日志应出现「网络自测 TUN 已关闭」。
所有自测均不需要认证资产，也不执行 root 命令。

## T3 真机记录（2026-10-04，0.1.1）

用户提供的车机诊断报告为 Android 4.4.2 / API 19、Allwinner T3、armeabi-v7a。
Baseline 与 High 的 800×480 / 30 fps 短片各运行两次，四次均为输入 60 帧、
Surface 输出 60 帧，耗时 2039–2046 ms，使用 `OMX.allwinner.video.decoder.avc`。
用户此前已确认能看到测试色条；连续播放、音频和触控仍需新版本实测。

iPhone `05ac:12a8` 已取得 USB 授权，模式切换请求后能读取原始配置与端点：
配置 5 和 6 均包含 MUX、NCM，配置 5 的 MUX 为接口 1、NCM 控制/数据为接口 2/3。
连接因缺少认证身份停止，代码尚未执行配置选择与接口占用，所以报告中的当前配置 1
不能证明配置切换失败。USB bulk 传输、iAP2 认证、NCM 和真实 CarPlay 仍未验证。
公开记录仅保留验证结论，不提交用户原始报告或私人认证材料。

## 尚需设备验证的项目

### USB 接口自测（0.1.3）

连接一台 iPhone 后点击控制栏的「USB 接口自测」，授予 USB 权限。
测试选择与连接路径相同的第一个 CarPlay 配置，依次占用 MUX、NCM 控制和 NCM 数据接口，
设置备用接口并用 GET_CONFIGURATION / GET_INTERFACE 读回核对。无需认证资产或 VPN 授权，
不发送 bulk、认证或业务数据；成功仅证明配置和接口操作，不证明数据传输。

测试会临时改变手机 USB 配置，暂停其他软件对该手机的 USB 使用。
若尚无 CarPlay 配置，先点击「连接」触发模式切换，出现缺少认证提示后点击「停止/断开」，
等待清理完成再执行此自测。测试本身不请求模式切换或自动重试。

停止或后台退出后，工作线程释放接口并恢复原配置；原配置已是目标配置时，恢复被修改的备用接口。
原配置若有正在使用的非默认备用接口，自测拒绝切换，以免丢失其状态。
拔线或清理失败会记录具体错误，请重新插拔手机。成功日志应同时有接口读回、释放、
原配置恢复（发生配置切换时）和「USB 接口自测通过」；提供这些日志才能判断下一步。

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
API 19 / 21 启动销毁、认证缺失时拒绝连接、私有身份持久化、
有界日志、报告读写及分享 provider 的私有路径隔离。
新增触控 ID/抬起/坐标边界、PCM 排队结束与清理、离线测试取消、
无手机触控自测的前后台切换，以及独立 IPv6/UDP 校验和向量、奇数长度与损坏包检查。
USB 接口自测覆盖配置选择、先释放后恢复、已激活配置的备用接口恢复、部分失败、
取消、清理失败、无 CarPlay 配置和非默认原状态的拒绝操作；使用模拟 IO，仍需真机验证 ioctl。
Robolectric 使用 JVM 和 Android 模拟框架，不测试 native usbfs、真实 Dalvik、GPU 或 iPhone。
最终装机记录才用于判断这台 T3 是否已经实现可用的有线 CarPlay。
