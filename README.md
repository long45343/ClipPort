# ClipPort

Windows ↔ Android 剪贴板同步软件。基于对小米妙享的逆向分析设计（决策与函数级规格见 [docs/](docs/)），全部代码独立实现。

## 架构

- **windows/** — C# / .NET 10 + WinUI 3（Windows App SDK，self-contained）
  - `ClipPort.Core`：协议帧（0xC1B0）/ 最小 Protobuf 编解码 / TLS 服务端（自签证书 PIN）/ BLE 扫描 / 剪贴板监听读写（`AddClipboardFormatListener` + 私有格式 `ClipPort.Self` 防回环）/ 同步引擎（16KB 懒阈值预取、seq LRU 去重、180s 本地持有）
  - `ClipPort.App`：托盘应用（配对码 / 状态 / 日志 / 同步开关）
- **android/** — Kotlin + Compose Material 3，minSdk 29
  - TLS 客户端（指纹固定 + 配对模式）/ BLE 广播发现（广播 IP+端口+指纹前缀）
  - 前台服务 connectedDevice / 开机自启 / 锁屏缓存解锁补写 / 远端内容 120s 可配自动清除
  - **LSPosed 模块**：hook `ClipboardService.isDefaultIme`（libxposed API 102，仅本应用包名），后台读剪贴板；无 root 降级通道（READ_LOGS + 悬浮获焦）

## 使用

1. **PC**：启动 `ClipPort.App.exe` → 点「开始配对」记下 6 位配对码
2. **手机**：装 APK（LSPosed 中启用模块并勾选系统作用域后重启），填 PC 的 IP + 配对码 → 配对
3. 之后两端复制即同步；文本/HTML/图片全支持，超过 16KB 走按需拉取

## 构建

```bash
# Windows（需 .NET 10 SDK）
cd windows && dotnet publish src/ClipPort.App/ClipPort.App.csproj -c Release -r win-x64 \
  -p:Platform=x64 -p:WindowsAppSDKSelfContained=true -p:SelfContained=true -o ../dist/windows

# Android（需 JDK 17 + Android SDK 35；产物 android/ClipPort-v0.1.0.apk）
export JAVA_HOME=<jdk17> ANDROID_HOME=<sdk>
cd android && ./gradlew assembleRelease
# 签名：apksigner sign --ks clipport.keystore --ks-pass pass:clipport2026 ...
```

## 许可

GPL-3.0（Android 端剪贴板读取组件参考本人项目 TextCascade-v2，其派生自 ClipCascade）。
