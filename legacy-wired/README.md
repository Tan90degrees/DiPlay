# Android 4.4 / 5.x 有线实验版

这个独立 Gradle 工程面向 Android **4.4.2 / API 19、ARMv7** 老车机，首个目标设备为
Allwinner T3、四核 Cortex-A7、1 GB RAM、1024×600 屏幕。最低版本为 API 19，
也在 API 21 / 22 的模拟框架中检查网络配置。**T3 真机已验证解码、音频和 USB 接口操作；用户确认 T3 与 SM-P600 的 0.1.9 免 Root 网络自测均通过，完整连接仍需验证**。
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
内置身份实机包会自动加载其证书和私钥，不需要 Root、认证服务器或用户手动导入。
文件匹配只证明本地签名一致性，不代表 iPhone 信任该证书。
上游公开发布 APK 内置了来自公开 Carlinkit 固件的实验身份；来源及尚未解决的
持续有效性/分发适用性见 [上游第三方说明](https://github.com/shihabal3amri/DiPlay/blob/main/docs/THIRD_PARTY_NOTICES.md)。
源码和公共 CI 构建仍不含这两个文件，也不提供伪造身份。

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

若已取得经过测试的源码 APK，也可用 `tools/package_standalone.py` 本地打包这两个外部资产。
它验证 P-256 证书/密钥匹配及预哈希签名，使用官方 SDK zipalign/apksigner 对齐并进行
API 19 所需的 v1（同时保留 v2）签名，然后逐项比较 manifest、DEX、原生库和资源
与源码 APK 完全相同。此方式无需再次安装 NDK；需要 Python `cryptography`、Java、
SDK build-tools 和自行保管的 PKCS12 Android 签名密钥：

```sh
python tools/package_standalone.py --source-apk /private/app-debug.apk \
  --assets /private/runtime-assets --output /private/DiPlay-Wired-Legacy-standalone.apk \
  --java /tools/java --zipalign /sdk/build-tools/34.0.0/zipalign \
  --apksigner-jar /sdk/build-tools/34.0.0/lib/apksigner.jar \
  --keystore /private/local-signing.p12 --password-file /private/signing-password.txt \
  --alias local-signing
```

本地实机包使用持久保存的本地 Android 签名密钥，与 CI 临时诊断包可能不同。
若覆盖安装提示签名冲突，需先卸载旧诊断版；卸载会清除旧配对和授权，新包需要重新授权。
后续使用同一本地签名密钥的实机包可正常升级。

### 0.1.11：更新上游基线与内置身份实机打包

适配分支已 rebase 到 `main` 的 `2fc876e`（上游 0.2.12）。保留全部 28 个适配提交，
README 冲突同时保留上游 Same LAN 说明与 legacy 入口。公共 CI 仍生成源码诊断包；
本地实机包选用上游 `v0.2.12` 发布 APK 的两项实验资产，并校验下载摘要与资产匹配。
连接日志新增本地身份加载/签名自检阶段；只有后续 iPhone 认证和影音实测才能证明可用。

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
| 网络自测（免 Root） | 同意系统 VPN 授权；Android 4.4 / 5.x 均使用 IPv4 TUN 和应用内 IPv6 转换；测试后关闭 TUN | 检查 UDP 5/5、分片/大包转换和 TCP 双向收发；不经过 USB/NCM 或 iPhone |

音频测试前把车机音量调到适中。网络自测可能替换正在使用的其他 VPN，测试前结束其他 VPN；
Android 4.4 / 5.x 添加应用内部使用的 198.18.0.0/24 IPv4 路由；
不配置默认路由或 DNS。停止后日志应出现「网络自测 TUN 已关闭」。
网络自测无需认证资产、Root 或电脑；0.1.7 已移除旧版 Root/ADB 辅助。

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

### KitKat IPv6 发送失败（历史记录）

0.1.2–0.1.5 真机建立了 IPv6 TUN，UDP bind 成功但 sendto 返回 EPERM。
[KitKat netd](https://android.googlesource.com/platform/system/netd/+/android-4.4.2_r2/SecondaryTableController.cpp)
在 IPv6 NAT 不可用时可能为 VPN UID 设置 REJECT，仍未确认这台厂商 ROM 的具体规则。
0.1.4–0.1.6 曾提供主动启用的临时 Root 规则与电脑辅助，但车机 su 明确拒绝应用 UID，
使用过程也不便。0.1.7 按用户选择改用应用内 IPv4/IPv6 转换，并移除这些代码、菜单和工具。
新版本不读取旧 Root 模式偏好，不执行 su 或防火墙命令。

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

上述是 0.1.5 的实现记录；当前按以下免 Root 步骤验证。源码 APK 的缺少认证身份提示仍符合预期。

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

### 0.1.7：应用内免 Root 网络转换

安装 APK，点击“离线自测”→“网络自测（免 Root）”，接受 Android 标准 VPN 授权即可；
不需要电脑辅助、su 授权或额外网络模式配置。先检查 IPv4 UDP bind、UDP 5/5 回包、
TCP 握手与应用 Socket 双向收发，最后确认 TUN 已关闭。五次 UDP 负载为
17、64、1232、4097、32769 字节，同时检查 MTU 边界、分片和大包 TUN 写入。失败时导出完整日志。
旧版保存的 Root/ADB 设置不会生效；Root 执行代码、菜单及辅助脚本已从当前源码移除。

当前 Android 4.4 / 5.x 的系统 Socket/TUN 使用 198.18.0.2/24，iPhone USB 链路仍看到 fe80::2。
应用将两侧 IP 头、TCP/UDP/ICMP 校验和进行转换，保留端口、TCP 序号与应用有效载荷。
对手机链路本地地址建立每次连接独立、最多 16 项的映射，不转发普通局域网或 Internet。
AirPlay 控制、音频、时钟、保活、事件、视频及数据通道使用相同本地地址族；
其他 DiPlay 后端的默认绑定方式不变。0.1.7 中 Android 5 仍使用原生 IPv6，0.1.8 已统一为转换通路。

转换规则参考 [RFC 7915](https://www.rfc-editor.org/rfc/rfc7915.html)，邻居发现参考
[RFC 4861](https://www.rfc-editor.org/rfc/rfc4861.html)。这是固定 USB 端点转换器，不是完整的
通用 SIIT/NAT64 路由器：支持 TCP/UDP、ICMP echo 以及部分不可达/超时/MTU 错误，
IPv4 无选项报头、IPv6 基础与 Fragment 报头；其他扩展、路由报头和组播数据不猜测转换。
应用自行响应 fe80::2 的 NS/DAD，校验跳数、目标、选项和 ICMP 校验和，地址冲突会中止连接。

IPv4 TUN MTU=1260、USB IPv6 MTU=1280，为转换后的报头预留 20 字节。
IPv4/IPv6 分片重组最多 8 组、合计 256 KiB、每组最多 128 片、5 秒过期；
重叠分片丢弃整个数据报，UDP 超过 MTU 时在 USB 侧输出 IPv6 分片。
重组后的 IPv4 数据报最多 65535 字节，TUN 写入使用有界原生堆缓冲；
USB 原生单次传输上限仍为 16 KiB。

自测中的 IPv6 peer 是本机测试数据：UDP/TCP 使用真实 Android Socket 与授权 TUN fd，
IPv6 回包由应用生成再转换回 IPv4，不打开 iPhone USB，不进行认证。
它能验证这台 ROM 的免 Root IPv4 通路，不能替代实际 NCM、认证与完整 CarPlay 会话。
T3 与 SM-P600 的通过记录见下文；其他 ROM 若阻断 IPv4/TUN，自测仍可能失败。

### 0.1.8：Android 5.1 也使用免 Root 转换

用户在 Samsung SM-P600、Android 5.1.1 / API 22 上测试 0.1.7：
原生 IPv6 UDP bind 成功，但 sendto 返回 ENETUNREACH；TUN 随后关闭。
566 个 NCM 协议离线场景及 IPv4/IPv6 转换通过。这证明离线转换通过，
不证明真实 Socket/TUN、USB bulk 或完整连接成功，也不能仅凭此日志确定厂商路由错误的根因。

0.1.8 不再根据 API 21 分界选择原生 IPv6。连接、USB 桥和网络自测在所有支持版本上
使用同一 IPv4/IPv6 转换通路；Android 5 的 VPN 仍仅允许本应用 UID。
API 19、21、22 回归测试检查真实 Builder 调用的 IPv4 地址、唯一 /24 路由、MTU 与应用范围。
没有自动退回 Root 或修改系统规则。复测应显示“模式=免 Root IPv4/IPv6 转换”、
IPv4 UDP bind、UDP 5/5、TCP 双向回包和 TUN 已关闭。无需连接 iPhone。

### 0.1.9：等待并选择本次 VPN 网络，分阶段统计 TUN

同一 Samsung SM-P600 / API 22 的 0.1.8 报告显示 IPv4 UDP bind 与发送成功，
3 秒后读取超时且 TUN 关闭。旧报告没有记录原始包数，因此不能确认流量是否进入 TUN，
也不能排除转换校验或预期包匹配失败。这个结果不证明厂商已拒绝免 Root IPv4。

Android 5 起，创建接口与绑定本地地址不能替代 Android 的网络选择。
0.1.9 最多等待 5 秒，从可见网络中检查 VPN 类型、当前接口名、198.18.0.2/24 地址和
198.18.0.0/24 路由，选择唯一匹配网络，然后为当前应用进程设置网络绑定。
参考 [ConnectivityManager 的进程网络绑定 API](https://developer.android.com/reference/android/net/ConnectivityManager#setProcessDefaultNetwork(android.net.Network))。
它作用于之后新建的 Socket，因此连接、自测和媒体通道共用这个范围；不绑定 Wi-Fi/其他 VPN，
不请求 INTERNET 默认路由或 DNS，不修改系统网络规则。新增 ACCESS_NETWORK_STATE 是普通权限。
API 19 不加载 API 21 的 Network 类型，继续使用系统 UID 路由并输出相同 TUN 统计。

结束自测、连接清理完成或设置失败后恢复原进程绑定；旧网络失效时清除本次绑定，
若绑定已被其他代码改变则保留新选择。USB 桥先停止 IO，再释放网络选择。
日志应出现“VPN 网络选择：已绑定本次 …”、每个 UDP/TCP 阶段的原始/转换/完整/匹配数，
以及“VPN 网络选择已恢复”和“TUN 已关闭”。若原始=0，排查路由/网络选择；
原始>0 且转换=0，检查首包格式/校验或分片；完整>0 且匹配=false，检查自测协议匹配。
首包摘要仅保留长度、协议、分片字段及校验结果，不输出有效载荷。
这次修复针对缺失的网络选择与诊断；SM-P600 的后续实测已通过，记录如下。

### 0.1.9 真机网络通过（2026-10-05）

用户在 Samsung SM-P600、Android 5.1.1 / API 22、ARMv7 上提供完整网络自测报告：

- 本次 tun0 的 VPN 网络选择成功；IPv4 UDP bind 和实际 TUN 读写通过。
- UDP 17、64、1232、4097、32769 字节五次回包全部匹配；前三次各为一个原始包，
  4097 字节为四个 IPv4 原始分片，32769 字节为 27 个。均完成 IPv4→IPv6→IPv4 转换与回包。
- TCP SYN/ACK 与应用 Socket 双向 echo 通过；报头和传输校验和检查通过。
- 自测结束后进程 VPN 网络选择恢复，TUN 关闭，报告没有清理异常。

这验证了该平板的免 Root Socket/TUN 通路、应用内地址转换、分片及本轮清理。
它不经过 iPhone USB/NCM 或真实 CarPlay 认证，也不能证明持续运行/拔线重连已通过。
Android 4.4 的 API 19 通路不使用进程网络选择；T3 的网络自测通过由用户另行确认，
不外推或复用平板的分片计数、耗时与进程绑定数据。
公开记录保留测试结论，不提交用户原始报告或应用 UID。

### 0.1.9 T3 USB 与网络复测（2026-10-05）

用户在 Allwinner T3、Android 4.4.2 / API 19、ARMv7 上复测：iPhone USB 授权通过，
模式切换后可见含 MUX/NCM 的 CarPlay 配置 5/6。两轮接口自测均通过配置 1→5，
MUX 1/0、NCM 控制 2/0、NCM 数据 3/1 的占用与读回；随后释放接口，恢复配置 1、
恢复临时断开的内核驱动，并通过原状态读回。没有报告 USB 清理错误。

连接仍在缺少认证身份时按预期停止，尚未执行真实 USB bulk/iAP2/CarPlay 会话。
所提供附件中可见设备诊断、连接尝试与 USB 接口自测段；用户随后明确确认
**T3 的免 Root 网络自测也已通过**。据此记录 API 19 网络自测通过，无需重复同项测试。
这项结论来自用户的实机确认；不补写未提供的分片计数、耗时或清理细节。
下一阶段验证真实 USB bulk/NCM、有效外部认证及完整 CarPlay 会话。

### 0.1.10：无需认证身份的 USB 数据自测

新增「USB 数据自测」：复用已验证的配置/接口事务，在真实 MUX bulk 端点上执行
USBMUX v2 握手、连接 Lockdown TCP 62078，再发送一次只读 `QueryType` 并校验
`Type=com.apple.mobile.lockdown`。它复用实际连接使用的 USBMUX 和 plist 实现，
不访问认证资产、不配对、不启动会话或服务，不记录手机标识或响应有效载荷。
QueryType 的请求/响应格式参考
[libimobiledevice 的 Lockdown 实现](https://github.com/libimobiledevice/libimobiledevice/blob/master/src/lockdown.c)。

实机步骤：

1. 安装新 APK，解锁并有线连接 iPhone，允许 USB 授权。
2. 点击「USB 数据自测」。若提示尚无 CarPlay 配置，先点击「连接」触发模式切换；
   出现缺少认证身份后点「停止/断开」，等待清理，再点击「USB 数据自测」。
3. 观察 USBMUX、TCP、QueryType 三个阶段及 bulk IN/OUT 字节数。
4. 只有数据通道关闭、接口释放、原配置/驱动恢复及最终读回全部通过，才报告
   「USB 数据自测通过」。可连续运行两次验证重复打开与恢复；随后导出日志。

数据阶段总时限 20 秒，每次 bulk 调用最多 250 ms；单条 plist 上限 16 KiB，
累计接收上限 64 KiB，避免非预期手机数据无限积压。停止时阻止新 IO，等待在途 IO
结束后才恢复配置，避免后台读线程与配置切换竞争。失败时记录所在阶段并执行恢复。
成功仅证明 MUX bulk 与 Lockdown 的真实双向通信；没有发送 NCM 业务数据、
执行 iAP2/MFi 认证或建立 CarPlay 会话。T3/SM-P600 已通过的免 Root 网络自测无需重做。

| 环节 | 实现与验证边界 |
| --- | --- |
| USB 配置/alternate | 从原始描述符读取；通过授权 USB fd 的 usbfs ioctl 切换，不调用 API 21 的 UsbConfiguration/setInterface |
| 旧内核传输 | 每次最多 16 KiB，IO 使用原生缓冲区，避免在阻塞期间固定 Java 数组；需要检查 T3 内核/SELinux 是否允许相关 ioctl |
| NCM 网络 | NTB16 有界分片重组；TUN 用 poll；T3 与 SM-P600 的免 Root Socket/TUN 自测通过；真实 USB/NCM 网络仍需验证 |
| 超时 | 启动前的 NCM NAK 超时丢弃该数据报；CarPlay 启动后超时终止连接，不重发可能部分发送的块 |
| 解码 | API 16 的 MediaCodec 缓冲区数组；Surface 变化重建解码器；队列溢出等待关键帧；需确认厂商解码器实际输出 |
| 音频 | API 19 AudioTrack 构造器和缓冲区写入；PCM 字节序转换、AAC ADTS；车机 DSP/通话/媒体路由未验证 |
| Android 5 | SM-P600 / API 22 的 0.1.9 免 Root 网络自测已通过；完整连接和影音仍需实机验证 |

若 ROM 裁掉或拒绝 VPN/TUN，当前方案不能连接；免 Root 转换仍依赖 Android 的标准 VPN 能力。
应用不修改系统网络规则。认证身份仍由用户自行提供，不能用网络转换代替认证。

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
