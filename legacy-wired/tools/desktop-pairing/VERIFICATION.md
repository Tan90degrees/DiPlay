# 真实手机桌面验证：2026-10-05

设备为 iPhone8,1，iOS 15.8.8，通过 USB 连接 Windows 官方 Apple Devices 服务。
本探针直接编译 APK 使用的共享 Kotlin 协议源文件，桌面适配层只提供 Android
工具桩、Apple usbmux 字节通道和桌面 BC/BCJSSE provider。没有使用电脑已有配对记录。

首次 `pair` 结果：

```text
Real iPhone Lockdown QueryType passed.
ProductType=iPhone8,1
ProductVersion=15.8.8
Pair passed.
Real Pair accepted; record saved only to private local storage.
Real StartSession accepted the same record and requested TLS.
```

随后 `iap2` 重用该私有记录，并使用与 0.1.12 本地 standalone APK 相同的实验认证资产：

```text
Compiled shared pairing source SHA256: 783943fce8157690d4dc49852af52f58f8edf3c24cfcc537334b9e37de030b1c
Real StartSession accepted the persisted record; starting shared TLS handshake.
Real TLS handshake and encrypted Lockdown QueryType roundtrip passed.
TLS session stopped and closed.
Real CarKit service startup and port connection passed.
Private accessory certificate/key consistency self-check passed; starting shared iAP2.
IAP2 READY [desktop-wired] ready=true
Real iAP2 link negotiation passed.
IAP2 RX [desktop-wired] 0x1d02 IdentificationAccepted frame=6B body=0 params
Real iAP2 identification accepted (advertised NCM interface 3; desktop does not open NCM).
IAP2 RX [desktop-wired] 0xaa00 RequestAuthenticationCertificate frame=6B body=0 params
IAP2 TX [desktop-wired] 0xaa01 AuthenticationCertificate frame=617B body=1 params
IAP2 RX [desktop-wired] 0xaa02 RequestAuthenticationChallengeResponse frame=42B body=1 params
IAP2 TX [desktop-wired] 0xaa03 AuthenticationResponse frame=74B body=1 params
IAP2 RX [desktop-wired] 0xaa05 AuthenticationSucceeded frame=6B body=0 params
Real iPhone sent MFi AuthenticationSucceeded for the locally packaged experimental identity.
IAP2 CLOSE [desktop-wired]
NCM and complete CarPlay media are not tested by this probe.
```

原始 Pair 的中文进度在 PowerShell 输出中出现编码问题，因此这里只记录成功节点；
主协议结果和 iAP2 日志均来自真实手机。手机标识、配对私钥、证书和挑战/签名正文均未收录。

验证边界：电脑默认 JDK 25/17 都在解析空 issuer 时失败；使用桌面 BC/BCJSSE 1.79
后，共享 TLS 状态机和真实加密回包通过。这不是 Android TLS provider 的实测。
桌面没有选择 CarPlay USB 配置、打开 USB NCM、发送 CarPlayStartSession、接收画面或播放声音。
当前配对源码在此手机上未复现 `InvalidPairRecord`；不能据此确定旧版本在 T3 上失败的唯一原因。
实验身份在此手机上的接受结果不代表其他 iOS 版本持续接受或 Apple 官方认证。
