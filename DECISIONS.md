# ClipPort 设计决策记录

> **规则**：每次只提问一个问题；用户选择后**立刻**将「选择」字段更新进本文件；全部选完后执行一致性/冲突检查（结果写入 `SPEC/04-consistency-check.md`）。
> 状态标记：⬜ 未选 ｜ ✅ 已选 ｜ ⚠️ 与其他决策存在依赖/冲突（见一致性检查）

---

## D-01 传输协议
- 状态：✅
- 问题：两端之间用什么协议栈承载数据（广播消息 + 懒拉取文件流）？
- **A) TCP 长连接 + 自定义帧** — 优：零第三方依赖、延迟最低、帧格式完全可控、和"广播+按需拉取"模型贴合；劣：断线重连、心跳、粘包拆包全部自己实现
- **B) WebSocket** — 优：Android 侧 OkHttp / Windows 侧 `ClientWebSocket` 都是一等公民，断线重连半自动，调试工具多；劣：多一层帧封装开销，双向推送语义要自己设计（本质仍是 TCP）
- **C) MQTT（broker 内嵌 PC 端）** — 优：pub/sub 与剪切板广播模型天然同构；MQTTnet(C#)/Paho(Android) 生态成熟，keepalive/重连/TLS 白送；为将来云中继/多设备留扩展；劣：必须内嵌 broker（多一跳），大文件懒拉取需走 broker 中转效率低于直连，REQ/RESP 要靠主题约定+关联 ID
  - 注：原选项 QUIC(MsQuic) 经用户询问 MQTT 后已排除替换（2026-09-30）
- 选择：**A) TCP 长连接 + 自定义帧**（2026-09-30；用户先询问 MQTT/内嵌 broker 概念，经评估后选定）
  - 注：用户曾指示参考 TextCascade-v2，随即限定"只复用剪切板读取相关"，本决策（传输）**维持 TCP 不变，不复用** TextCascade 的 WebSocket 传输（2026-09-30 晚）

## D-02 设备发现机制
- 状态：✅
- 问题：PC 和手机如何互相找到对方（决定是否依赖 BLE）？
- **A) mDNS 为主 + 手动填 IP 兜底** — 优：实现最简单，两端各一个 mDNS 栈即可；劣：路由器 AP 隔离时失效，手机息屏后 mDNS 服务可能不可达
- **B) BLE 广播带 IP（小米方案）** — 优：息屏可达、跨网段、唤醒 PC 侧连接，最贴近小米体验；劣：必须做整套 BLE GATT 通道，权限多（SCAN/CONNECT/ADVERTISE），Android 12 以下要定位开关
- **C) 三层混合：mDNS → BLE → 手动 IP** — 优：任何环境都有路可走，鲁棒性最强；劣：三套代码路径，测试矩阵 ×3
- 选择：**B) BLE 广播为主（小米方案）**（2026-09-30）→ spec 影响：Discovery 以 BLE GATT 通道为主实现，需补手动 IP 兜底入口作为故障救援；Windows 端 BLE 用 WinRT API

## D-03 配对与加密
- 状态：✅
- 问题：两台设备首次如何建立信任？之后通信是否加密？
  - 注：用户先询问小米机制——小米信任根为小米账号（云端撮合密钥 + MiE2eeSdk/libsodium E2E 加密，扫码为本地入口）；ClipPort 无云端故需本地信任仪式，详见对话记录（2026-09-30）
- **A) 配对码 + 自签 TLS 证书 PIN** — 优：一次输入 6 位码完成信任，证书指纹固化后免交互，安全性对齐行业惯例；劣：证书生成/校验代码两端各一份
- **B) PSK 预共享密钥（libsodium secretbox / AES-GCM）** — 优：实现极简，无证书链概念；劣：密钥轮换麻烦，一端泄露全部泄露
- **C) 不加密（信任局域网）** — 优：零成本，剪贴板内容直接裸传；劣：同一 WiFi 下任何设备可嗅探/伪造，剪切板又是高敏感数据（密码、验证码），不建议
- 选择：**A) 配对码 + 自签 TLS 证书 PIN**（2026-09-30）

## D-04 广播/控制消息序列化
- 状态：✅
- 问题：协议消息（广播元数据、请求/响应）用什么序列化？
  - 注：用户先询问侵权风险——已确认 Protobuf 为开放规范+BSD 宽松许可，商用无风险；仅需避免照抄小米自有 proto 文件（本设计字段表自主定义）
- **A) Protobuf** — 优：两端 schema 共享一处定义，向后兼容好，对齐小米通道消息（Messages.message）；劣：两端都要引入生成工具链，小消息也要带字段号开销
- **B) 手写紧凑二进制（抄小米 base/extend 布局）** — 优：广播消息可以压到十几字节，零依赖；劣：两端必须严格对齐字节布局，改协议易踩坑，需要详尽字段表
- **C) JSON** — 优：人眼可读、调试零成本、跨语言最省心；劣：体积最大（对 2KB 内联内容无所谓），性能敏感的文件流元数据略浪费
- 选择：**A) Protobuf**（2026-09-30）
  - 注：同 D-01——TextCascade 参考仅限剪切板读取相关，本决策（序列化）**维持 Protobuf 不变，不复用**其 JSON 协议

## D-05 大内容懒拉取阈值
- 状态：✅
- 问题：文本/HTML 超过多大转为"广播元数据 + 按需拉取"模式（小米用 20KB）？
- **A) 20KB** — 优：小米实测值，广播延迟已被验证可接受；劣：无
- **B) 32KB** — 优：稍多内容走即时通道，粘贴大段文本更"秒出"；劣：广播包变大，弱 WiFi 下首次推送变慢
- **C) 64KB** — 优：绝大多数复制场景一次广播搞定，懒拉取几乎只服务文件；劣：低带宽蓝牙兜底通道下广播明显变慢（若 D-02 选了 BLE 链路）
- 选择：**16KB（用户自定义，低于小米的 20KB）**（2026-09-30；考虑 D-02 已选 BLE 为主，广播宜小）→ spec 中 LAZY_THRESHOLD = 16384

## D-06 剪贴板内容类型范围
- 状态：✅
- 问题：第一版支持哪些内容类型？
  - 注：用户先询问小米支持范围——小米为全量（文本/HTML/图+文并存/图片/视频/任意文件/多条目复合），协议另有 v2.0 剪贴板历史+缩略图（PC 端未实现，预留）
- **A) 仅纯文本** — 优：最小可用，一周能跑通全链路，防回环/去重/懒加载逻辑全可验证；劣：复制截图、文件两大高频场景缺失
- **B) 文本 + 图片** — 优：覆盖日常 90% 场景（网页/聊天/截图），两端图片编码链路（PNG/DIB↔Bitmap）一次打通；劣：HTML 富文本和文件仍不支持
- **C) 文本 + HTML + 图片 + 文件（全量对齐小米）** — 优：功能完整，Windows 端 FileContents/HDROP、Android 端 content:// 懒加载全做；劣：工期最长，COM IDataObject 和 FileProvider 细节坑多
- 选择：**首版文本+HTML+图片；二期扩展文件**（2026-09-30）→ 影响：D-14 首版仅可 B/C；mime 表 code 3/4（图片/文件）预留，文件帧协议（REQ_FILE/FILE_CHUNK）首版实现、文件懒加载呈现（D-14=A）二期启用

## D-07 去重/防回声机制
- 状态：✅
- 问题：如何防止 A→B→A 的回声循环和重复广播？
  - 注：用户先询问小米做法——小米为四层防御：①自标记（两端）②时间戳比对（Android）③1.5s 内容回声窗（Android，防 MIUI 剪贴板管理器改写丢 label）④(deviceId,seq) LRU 时间窗（PC 端，协议层去重）。①~③在 ClipPort spec 中已作固定设计（ClipFilter/SelfGate），本题只定协议层
- **A) (deviceId, seq) LRU 时间窗（抄小米）** — 优：字节级精确、与内容无关、判重 O(1)；劣：两端都要维护窗口状态
- **B) 内容 hash 比对** — 优：实现直觉简单，顺带能做"内容没变不重发"；劣：大文件 hash 计算成本高，hash 碰撞需处理
- **C) 时间戳比对（ClipDescription.getTimestamp）** — 优：Android 侧零成本；劣：Windows 侧剪贴板没有可靠时间戳，无法两端统一，只能作辅助
- 选择：**A) (deviceId, seq) LRU 时间窗（LRU 128 / 30s 过期）**（2026-09-30）

## D-08 Android 后台读剪贴板方案 ⚠️ 核心决策
- 状态：✅
- 问题：Android 10+ 后台读剪贴板的实现路线？（此前讨论的结论：Xposed 最优且你有 LSPosed 环境）
  - **用户特别指示：Xposed 模块开发时使用已有 skill「LSPosed-Mod-Dev」（位于 ~/.zcode/skills/LSPosed-Mod-Dev/SKILL.md）作为开发参考**
  - **复用基线（2026-09-30 晚，用户限定范围）**：剪切板读取相关组件直接参考本人项目 **TextCascade-v2**（`D:\EDCs\code\TextCascade-v2`，GPLv3）：
    - hook 点：**`ClipboardService.isDefaultIme`** 两个签名重载 `(int,String)` / `(int,String,int)`（libxposed 新 API `XposedModule` + `onSystemServerStarting`，按包名过滤 + PROTECTIVE 异常模式）——**R2 已解决**：hook 点经实机验证稳定，无需逐版本适配 `clipboardAccessAllowed`，无需 pull services.jar
    - 无 root 降级通道：`ClipboardSources` 的 **READ_LOGS 模式**——`logcat -b system` 监听 ClipboardService/SemClipboardService/**MiuiClipboardService**/HwClipboardService 拒绝日志 → 启动悬浮透明 Activity 获焦后读剪贴板（generation 生命周期、5s→300s 退避、6 次失败暂停、stderr 排水）
  - 降级链（更新）：LSPosed 可用 → hook（最优）；无 root 但可授 READ_LOGS（Shizuku/ADB）→ logcat 通道；都没有 → 仅接收方向
  - **复用边界：仅限上述剪切板读取组件；TextCascade 的传输/协议/加密/引擎/UI 一律不复用**
- **A) Xposed 模块 hook system_server** — 优：等价于持有 READ_CLIPBOARD_IN_BACKGROUND，真后台、不占输入法/无障碍，体验同小米；劣：要求 root+LSPosed，需做多 Android 版本方法签名兼容，改作用域要重启
- **B) 默认输入法（IME）** — 优：无需 root，系统官方允许 IME 读剪贴板；劣：要求用户把 ClipPort 设为输入法（日常打字体验必须做好，否则不可用）
- **C) 无障碍服务** — 优：无需 root，还能顺带保活；劣：读剪贴板是间接的（部分 ROM 限制），无障碍开启状态易被用户关掉、被省电策略杀
- 选择：**A) Xposed 模块 hook system_server（ClipboardService.clipboardAccessAllowed，按包名过滤）**（2026-09-30）；开发参考 skill：LSPosed-Mod-Dev

## D-09 Android 剪贴板监听方式
- 状态：✅
- 问题：剪贴板变化的监听入口用什么？
- **A) OnPrimaryClipChangedListener（标准 API）** — 优：事件驱动零延迟，与 D-08A 配合时后台回调不受限；劣：依赖 D-08 先打通"读"（监听本身不受限，读受限）
- **B) 无障碍事件轮询** — 优：与 D-08C 同一条服务通道，一石二鸟；劣：事件噪声大需要过滤，部分场景漏报
- **C) IME 内监听** — 优：与 D-08B 配套，IME 进程内读写天然合法；劣：仅在输入法唤起时活跃，后台复制（如浏览器菜单复制）可能漏
- 选择：**A) 标准 OnPrimaryClipChangedListener**（2026-09-30；与 D-08=A 配对）

## D-10 Android 常驻服务形态
- 状态：✅
- 问题：Android 端常驻用什么形态保活？
- **A) 前台服务 foregroundServiceType="connectedDevice"** — 优：语义准确（就是连接外部设备），14+ 审核友好；劣：部分 ROM 会询问权限
- **B) 前台服务 foregroundServiceType="dataSync"** — 优：声明最宽松，传输文件名正言顺；劣：Android 15 对 dataSync 有 6 小时限时策略风险
- **C) 无障碍服务常驻（若 D-08=C）** — 优：无障碍进程优先级最高、几乎不被杀；劣：被依赖用户关掉就全灭，且语义上"伪装"
- 选择：**A) 前台服务 foregroundServiceType="connectedDevice"**（2026-09-30；小米同款；配静默通知渠道 + 开机广播 + 电池优化白名单引导）

## D-11 Android minSdk
- 状态：✅
- 问题：最低支持的 Android 版本？
- **A) minSdk 29（Android 10）** — 优：从剪贴板受限的起点做起，权限代码只写一套新式逻辑；劣：放弃少量老设备
- **B) minSdk 26（Android 8）** — 优：覆盖更广，FGS 机制可用；劣：要写 BLUETOOTH/P2P/通知渠道三套版本分支，ClipDescription.getTimestamp（API 26+）刚好可用
- **C) minSdk 31（Android 12）** — 优：新权限模型（BLUETOOTH_SCAN 等）单套代码，无定位开关坑；劣：只覆盖近 4 年设备
- 选择：**A) minSdk 29**（2026-09-30）；注：minSdk 29 < 31 时 BLE 权限仍需双分支（BLUETOOTH_SCAN 运行时 vs 旧版+定位开关），spec 已含兼容声明

## D-12 Android UI 形态
- 状态：✅
- 问题：Android 端界面做到什么程度？
  - 注：用户确认 Compose 默认即 Material Design 3（动态取色/深色模式内建）
- **A) Jetpack Compose 完整设置页** — 优：设备管理/配对/日志/权限引导一站配齐，长期维护舒服；劣：初期工作量最大
- **B) 传统 View 极简设置页** — 优：几天可完成，功能足够；劣：技术栈老旧，后续重构成本
- **C) 无 UI：通知栏开关 + 网页设置** — 优：最省事，PC 浏览器打开手机端口即可配置；劣：手机本地看不到状态，Web 服务又要占一个端口
- 选择：**A) Jetpack Compose 完整设置页（Material 3）**（2026-09-30）；兼作 Xposed 模块配置宿主（XSharedPreferences 写入端）

## D-13 Windows 技术栈
- 状态：✅
- 问题：Windows 端用什么实现？
  - 用户要求：WinUI 3，性能尽可能好 → 性能措施（写入 spec）：NativeAOT（WindowsAppSDK 1.6+ 支持）或 ReadyToRun+trimming；逻辑层与 UI 层分离，热路径（帧编解码/去重）零 UI 依赖
  - 技术细节：托盘图标 WinUI3 无内建 → H.NotifyIcon.WinUI；剪贴板监听用隐藏 message-only 窗口（与 UI 框架无关）
  - 注：小米 PC 端同为 WinUI 3 + .NET（其目录含 Microsoft.WinUI.dll/WindowsAppRuntime），栈同源便于对照
- **A) C# / .NET 8 WinForms 托盘应用（P/Invoke 剪贴板）** — 优：你熟 C#，AddClipboardFormatListener 一组 P/Invoke 就够，self-contained 单 exe 发布；劣：需带运行时（~70MB，可裁剪）
- **B) C++ Win32** — 优：零运行时依赖、与小米同路（可直接对照其 dist_clipboard 设计）；劣：开发效率低，COM/内存管理细节坑多
- **C) Rust（windows-rs）** — 优：内存安全 + 单文件体积小；劣：学习/迭代成本最高，GUI 生态一般
- 选择：**C# / .NET 10 + WinUI 3（Windows App SDK），NativeAOT/ReadyToRun 性能优化**（2026-09-30，用户指定；同日由 .NET 8 升级为 .NET 10 LTS）

## D-14 Windows 懒加载呈现方式 ⚠️ 依赖 D-06
- 状态：✅
- 问题：PC 收到"大内容广播"后如何挂到系统剪贴板？
  - 背景：D-06 已定首版文本+HTML+图片（懒内容=大文本+图片），文件二期
- **A) 占位 IDataObject（COM，抄小米）** — 优：复制手机端大文件 → PC 上 Ctrl+V 那一刻才下载，体验与小米一致；劣：要手写 COM IDataObject + FormatEtc，D-06 必须含文件/图片
- **B) 后台预下载完成后一次性写入** — 优：实现简单，剪贴板里始终是真数据；劣：大文件在用户粘贴前就占带宽和磁盘，可能白下
- **C) 文本立即写入 + 文件/图片弹进度通知按需下** — 优：折中，文本零延迟，文件有感知但可控；劣：两种模式并存，UI 逻辑复杂一点
- 选择：**B) 后台预下载完成后一次性写入**（2026-09-30，首版）；二期文件场景再评估升级到 A（占位 IDataObject，小米同款）

## D-15 远端剪切板在手机上的保留策略
- 状态：✅
- 问题：PC 内容写入手机剪贴板后，何时清除（小米是 120 秒）？
- **A) 120 秒自动清除（同小米）** — 优：隐私好（粘贴完就走），防止旧内容一直躺在剪贴板被误粘；劣：慢一秒粘贴就没了，用户可能困惑
- **B) 不自动清除** — 优：符合 Android 用户直觉（剪切板就该留着）；劣：PC 密码会长期留在手机剪贴板，隐私差
- **C) 可配置，默认 120 秒** — 优：两头兼顾，设置页一个开关；劣：多一个配置项和对应逻辑分支
- 选择：**C) 可配置（关/30s/120s/5min，默认 120s）**（2026-09-30）；固定不变的安全底线：clearPrimaryClip 前校验 label=SELF_LABEL，绝不触碰用户自己的剪贴板

## D-16 锁屏行为
- 状态：✅
- 问题：设备锁屏时收到远端剪切板怎么办（两端都要定策略）？
- **A) 缓存，解锁后补写（同小米，USER_PRESENT / SessionUnlock）** — 优：不错过任何同步，解锁即得；劣：需监听解锁广播，缓存有时效问题
- **B) 立即写入，不判断锁屏** — 优：实现最简单；劣：手机锁屏时写入，解锁后用户看到的是几分钟前的"幽灵剪切板"，且 Android 13+ 会弹"已读取剪贴板"提示在锁屏上
- **C) 锁屏直接丢弃** — 优：隐私最干净；劣：锁屏期间复制的内容丢失，同步不完整
- 选择：**A) 缓存+解锁补写（两端统一）**（2026-09-30）；对小米的改进见 04-consistency-check.md 风险 R4：缓存完整 ClipData 而非仅 sessionId，避免解锁时对端 TTL 过期导致丢失

---

## 复用基线声明（2026-09-30，用户指示）

**只复用剪切板读取相关，不要复用这个之外的！**

- 参考项目：`D:\EDCs\code\TextCascade-v2`（本人所有，GPLv3）
- ✅ 允许复用（剪切板读取相关）：
  - `XposedEntry.kt` — isDefaultIme hook（libxposed API，双签名，包名过滤）
  - `ClipboardSources.kt` — 标准 Listener + READ_LOGS logcat 降级双通道
  - `ClipboardFloatingActivity` — 悬浮获焦读剪贴板技巧
- ❌ 禁止复用：OkHttpTransport / TextSyncEngine / ConnectionManager / Protocol.kt(JSON) / CryptoManager / AuthManager / 前台服务实现 / UI —— ClipPort 这些模块按本 spec 自研（TCP+Protobuf，D-01/D-04 维持原决策）
- 许可提示：复用文件含 GPLv3 头（TextCascade 基于 ClipCascade），ClipPort 发布时随代码一并采用 GPLv3 即可（本人持有 TextCascade 版权）

---

## 决策依赖提示（提问前已知）
- D-09 依赖 D-08：A↔A / B↔C / C↔B 是自然配对
- D-10 的 C 选项仅在 D-08=C 时可选
- D-14 的 A 选项要求 D-06 包含文件或图片
- D-05 的 C 选项在 D-02=B（BLE 链路）时需谨慎
- D-13=B（C++）时 D-14=A 成本更高（纯 COM 手写）

## 一致性检查
- 状态：⬜ 未执行（全部决策完成后填写，结果输出至 SPEC/04-consistency-check.md）
