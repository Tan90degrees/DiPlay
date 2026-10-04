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
普通模式自测无需认证资产，不执行 root 命令。0.1.4 的 Root 兼容须另行主动启用，详见下文。

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

### 0.1.2 真机补充

同日后续报告：Baseline 与 High 各连续 30 秒，均输入/输出 900 帧，耗时 30081 / 30053 ms。
native 堆由 3655 KiB 到 3615 KiB；仅能判断该次短时测试未见这一指标增长，不能代表全部 GPU 内存。
PCM / AAC 写入 352800 / 360448 字节，用户确认左右声道及 AAC 均能正常听到、无明显杂音。
触控记录 165 个事件、最大 2 个有效触点、8 个抬起/取消触点；手机端接收仍未验证。
网络已创建 `tun0`，但 UDP 第一包发送报 `EPERM`，随后 TUN 关闭；尚未验证 TUN 双向 IO。

## 尚需设备验证的项目

### KitKat IPv6 / Root 兼容（0.1.4）

0.1.2 真机报告中，TUN 已建立为 `tun0`，但第一包 UDP `sendto` 返回 `EPERM`。
Android 4.4.2 的 [SecondaryTableController 源码](https://android.googlesource.com/platform/system/netd/+/android-4.4.2_r2/SecondaryTableController.cpp)
在 IPv6 NAT 设置失败时为 VPN 标记添加 IPv6 REJECT；这是候选原因，需要 ROM 实测确认。
创建 TUN 成功不能证明其出站流量可用；本机链路本地 CarPlay 不需要 NAT 到互联网。

普通网络自测增加内核、INTERNET 权限、接口 IPv6 开关、路由条目数、作用域和失败阶段的只读日志。
TUN 名称由授权 fd 的 `TUNGETIFF` 读取，避免与其他接口上的同名 IPv6 地址混淆。

Android 4.4 上可点击「网络兼容」主动启用实验性 Root IPv6 模式，再运行网络自测并允许 `su`。
该设置默认关闭，保存在应用私有设置中，同时用于后续有线连接。
仅在 `st_filter_OUTPUT` 存在 REJECT 时，插入匹配本应用 UID、当前 `tunN`、
源 `fe80::2/128`、目的 `fe80::/64` 的 RETURN 规则；使用唯一注释标识，保留其他规则。
不改系统文件、默认路由或 DNS，不清空防火墙，不请求 socket 绕开 TUN。

Root shell 持有临时规则，停止后或应用管道 EOF 时按完整参数删除；不是永久系统补丁。
若 ROM 不支持 `su`、owner/comment 模块或该链，记录失败；不会改用宽泛规则。
SU 实现与强制结束行为仍需实测，不能保证所有 Root 管理器都正确传递管道 EOF。
无法确认清理时停止测试并重启车机。正常成功日志应包含 5/5 回包、
「Root IPv6：临时规则已删除」和「网络自测 TUN 已关闭」。可在同一菜单恢复普通模式。

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
释放后再次读回原状态，检测内核驱动重新连接时修改配置或备用接口的情况。
拔线或清理失败会记录具体错误，请重新插拔手机。成功日志应同时有接口读回、释放、
原配置恢复（发生配置切换时）和「USB 接口自测通过」；提供这些日志才能判断下一步。

### 0.1.5：T3 USB EBUSY 与 SU 诊断修正

0.1.4 真机已通过配置 5 选择与 MUX 1/0 占用。NCM 控制接口的占用前读取返回
`errno=16`，恢复配置 1 也失败；后续原配置为 5 的测试仍在相同位置失败。
Linux 3.10 的 [usbfs 实现](https://github.com/torvalds/linux/blob/v3.10/drivers/usb/core/devio.c)
会为接口控制请求隐式 claim，内核驱动占用时返回 EBUSY。这也意味着释放后
GET_INTERFACE 会重新占用接口，不能用于无副作用的最终状态检查。

0.1.5 在明确 claim 后才发送 GET_INTERFACE，claim 前和 release 后从该授权设备的
sysfs 读取 alternate。地址按 USB busnum/devnum 匹配；属性不可读时报告失败，不假定为零。
切换配置前释放自有接口并临时断开该设备的内核驱动，包括未参加自测的其他 NCM 功能；
拒绝夺取其他 usbfs 应用的接口。恢复配置后才重连临时断开的驱动，避免 EBUSY；
成功切换配置会使旧接口失效，不把旧驱动接口号重连到新配置。清理后的状态变化仍报告失败。
这些修改需要新一轮真机验证，不代表 NCM bulk 或认证已成功。

Root 自测同次报告中普通 UDP bind 通过，但 sendto 仍为 EPERM；IPv6 未禁用，
INTERNET 权限正常。启用 Root 后 su 约 70 ms 退出，未建立规则；该报告没有具体拒绝原因。
0.1.5 先用只读 `id` 验证实际 UID=0，检查 `/system/xbin/su`、`/system/bin/su` 的
`-c` 和旧式 `su 0 /system/bin/sh -c` 调用，只有检查成功才运行原有临时规则脚本。
显式权限拒绝时不对同一二进制重试其他参数。日志记录调用方式、退出码和已识别原因，
不发布任意 shell 输出；退出时无换行的错误也会保留用于分类。
[AOSP 的部分旧式 su](https://android.googlesource.com/platform/system/extras/+/906d825/su/su.c)
仅允许 root/shell 调用，所以 ADB 可执行 su 不代表普通应用也能获得 Root。

安装 0.1.5 后重新插拔 iPhone，再运行 USB 接口自测；预期分别出现 MUX、NCM 控制、
NCM 数据的占用与读回，以及清理通过。普通网络的已知 EPERM 无需反复测试；主动启用
Root 模式后运行网络自测，提供 Root UID 检查和后续规则/回包/清理日志。
若显示应用 UID 不允许调用，需支持应用授权的 Root 管理器；参数兼容不能改变该权限限制。
源码 APK 的缺少认证身份提示仍符合预期。

0.1.5 后续真机报告：连续两次完整通过配置 1→5、MUX 1/0、NCM 控制 2/0、
NCM 数据 3/1、释放接口和恢复配置 1，最终状态读回也通过。USB 接口操作与清理现已得到
这两轮实机验证；bulk、认证与会话仍未验证。网络 Root 失败已明确为
`/system/xbin/su` 拒绝应用 UID；普通模式 bind 成功但 sendto 仍为 EPERM。

### 0.1.6：有线会话与重连完善

NCM 控制/数据接口优先根据 CDC Union 配对，缺少 Union 时采用相邻或唯一数据接口；
无法确定配对时不猜测另一个 NCM 功能。CDC Ethernet 的 MAC 字符串索引也从对应控制
接口解析，通过语言描述符和设备级 GET_DESCRIPTOR 读取严格校验的单播地址；
无可用描述符时使用本机稳定地址，与网络帧和启动通知保持一致。

只对被 StartSession 明确拒绝的旧 InvalidHostID/InvalidPairRecord 自动清除并重新配对一次；
信任拒绝、TLS 错误和其他协议失败不会触发循环配对。媒体回调按 AirPlay 会话隔离，
旧连接的延迟启动/停止或关键帧回调不再影响新会话。断开后等待连接创建线程完成晚到
资源的释放，再等待音频与解码器清理；不在 UI 线程等待。关键帧请求也使用单独的有界队列。

USB/TUN 转换检查 IPv6 长度、单播来源和目的 MAC，剥离 Ethernet 填充后才注入 TUN；
记录首次双向流量、暂缓及启动前超时计数。NTB16 解析防止循环 NDP/重复条目放大内存，
并拒绝当前不支持的 CRC 格式。离线菜单新增内存中的分片/IPv6/UDP 回包转换自测；
它不打开 USB、VPN 或认证资产，不能证明真实 USB bulk 或 CarPlay 成功。

CI 同时运行原有 USBMUX 分片、iAP2、NTB 与 NDP 回归检查，以及 API 19/21 上用
合成 USBMUX peer 验证真实 host/TCP 实现的握手、大数据拆分、读回和取消。
该 peer 只存在于测试源码，生产 APK 不含可选假手机或假认证后端。

### ADB 临时 IPv6 辅助（0.1.6 调试版）

0.1.5 的车机日志已证明 su 拒绝应用 UID，不能通过重复参数重试解决。
优先使用能对应用明确授权的 Root 管理器；若仅 ADB shell 可用 Root，可由设备所有者在
电脑启动临时辅助。电脑必须通过 USB 或已授权的无线 ADB 连到车机；
同一 USB 端口无法同时连接电脑和 iPhone 时，可先用电脑连接车机运行本机网络自测。
电脑连接 iPhone 或 Android 模拟器不能验证车机的 USB/VPN。

在 PowerShell 7 中运行（adb 已加入 PATH）：

~~~powershell
pwsh -File .\legacy-wired\tools\Start-LegacyIpv6Helper.ps1 -Serial 车机ADB序列号
~~~

只有一个设备时可省略 -Serial；可用 -AdbPath 指定 adb.exe。
脚本先检查 API 19、调试 APK 的 run-as/实际 UID 和 shell 的真实 Root 权限，
然后把固定辅助脚本写入应用私有 files 目录并前台运行。
车机需要允许 shell 使用 su 和 run-as；厂商禁用这些能力时会停止并报告原因。
Android 的 [run-as 实现](https://github.com/aosp-mirror/platform_system_core/blob/android-4.4.2_r2/run-as/run-as.c)
只接受 root/shell 调用且要求目标包可调试，因此正式 APK 不支持本工具。

保持终端和 ADB 连接，在应用点“网络兼容”→“ADB 临时辅助”→“启用 ADB 辅助”，
再运行网络自测。预期看到临时规则就绪、五轮 IPv6/UDP 回包和“临时规则已删除”。
结束辅助按 Ctrl+C。默认最多 30 分钟，-MaxMinutes 可设 1–240 分钟。
应用模式会保存，但每次测试/连接都必须有正在运行的电脑辅助。

IPC 在应用私有目录，使用随机租约标签、实际应用 UID 和 8 秒心跳；
应用退出、请求超时、ADB 标准输入断开或辅助到期都删除该租约的完整规则。
只修改 st_filter_OUTPUT 中当前 UID/TUN/fe80::2→fe80::/64 的临时 RETURN 条目，
不修改 su 授权、不写系统分区、不清空规则、不更改默认策略。
删除失败会明确报告；无法确认时停止使用并重启车机。
若辅助上次被强制杀死且报告 STALE_LOCK，先重启车机，确认无旧进程后运行：
~~~text
adb shell run-as com.shihab.diplay.legacy rmdir files/adb-root-helper-lock
~~~
这只删除空的辅助锁目录。规则存在性由本次电脑/应用日志共同确认。
辅助测试使用伪 ip6tables/run-as，覆盖正常释放、EOF、心跳/硬超时、失败及畸形请求；
尚未证明本车机允许 shell Root，或临时例外可以消除当前 EPERM。
完整 CarPlay 仍需要可用认证身份、真实 USB bulk/NCM 与 iPhone 会话验证。

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
