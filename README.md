# ClipPort

Windows ↔ Android 对等去中心化剪贴板同步软件。基于对小米妙享的逆向分析设计（决策与函数级规格见 [docs/](docs/)），全部代码独立实现。

## 架构（二期对等模式）

全网状对等拓扑（每台设备既是服务端也是客户端，广播直发、懒取直连源设备，无单点瓶颈）：

- **windows/** — C# / .NET 10 + WinUI 3（Windows App SDK）
  - `ClipPort.Core`：协议帧（0xC1B0）/ 最小 Protobuf 编解码 / TLS 服务端+客户端 / 对等证书 PIN 验证 / UDP 广播发现（47192/udp，无蓝牙依赖）/ 剪贴板监听读写（`AddClipboardFormatListener` + 私有格式 `ClipPort.Self` 防回环）/ 同步引擎（16KB 懒阈值预取、seq LRU 去重、180s 本地持有）/ PeerStore 对端库
  - `ClipPort.App`：托盘应用（配对码 / 状态 / 日志 / 同步开关 / 连接其他设备）
- **android/** — Kotlin + Compose Material 3，minSdk 29
  - 双模式对等 TLS（BouncyCastle RSA-2048 自签证书 + PeerServer 47191 监听）
  - UDP 广播发现（47192/udp，全设备通用无蓝牙依赖）+ 局域网对端列表 UI
  - 前台服务 connectedDevice / 开机自启 / 锁屏缓存解锁补写 / 远端内容 120s 可配自动清除
  - **LSPosed 模块**：hook `ClipboardService.isDefaultIme`（libxposed API 102，仅本应用包名），后台读剪贴板；无 root 降级通道（READ_LOGS + 悬浮获焦）

## 使用

1. **配对**：
   - 任意一端点击「开启配对」（或 PC 端「开始配对」）获取 6 位临时配对码。
   - 另一端在发现的设备列表点击「填入」或者手动输入对方 IP 及配对码，点击「连接」/「配对」。
   - 配对成功后双方互相固定证书指纹并持久化存储，之后免交互自动重连。
2. **同步**：
   - 任意设备复制内容即可自动推送至所有已连接对端。
   - 文本/HTML/图片均支持；超过 16KB 自动切换为按需拉取模式。
3. **支持的拓扑**：
   - PC ↔ 手机
   - PC ↔ PC
   - 手机 ↔ 手机（无需 PC）
   - 多设备混合对等网络

## 构建

```bash
# Windows（需 .NET 10 SDK）
# 模式A 框架依赖（38MB，要求目标机已装 .NET 桌面运行时 10 + Windows App SDK Runtime 1.7）
cd windows && dotnet publish src/ClipPort.App/ClipPort.App.csproj -c Release -r win-x64 \
  -p:Platform=x64 -p:SelfContained=false -p:WindowsAppSDKSelfContained=false -o ../dist/windows-fx

# 模式B 自包含（172MB，零依赖，免装运行时）
cd windows && dotnet publish src/ClipPort.App/ClipPort.App.csproj -c Release -r win-x64 \
  -p:Platform=x64 -p:WindowsAppSDKSelfContained=true -p:SelfContained=true -o ../dist/windows

# Android（需 JDK 17 + Android SDK 35；产物 android/ClipPort-v0.3.0.apk）
export JAVA_HOME=<jdk17> ANDROID_HOME=<sdk>
cd android && ./gradlew assembleRelease
# 签名：apksigner sign --ks clipport.keystore --ks-pass pass:clipport2026 ...
```

## 许可

GPL-3.0（Android 端剪贴板读取组件参考本人项目 TextCascade-v2，其派生自 ClipCascade）。
