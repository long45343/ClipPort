# ClipPort

Windows ↔ Android 剪贴板同步软件。设计基于对小米妙享（PcContinuity 1.1.2.36 + 设备互联 17.2.6.0）的逆向分析。

## 工作流程

1. **DECISIONS.md**（本目录）— 16 个设计决策，每题 3 选项含优劣。规则：一次问一题，选择即时回写本文件。
2. **SPEC/** — 函数级规格，决策点以 `[D-xx]` 挂钩，选择完成后同步更新 spec 并执行一致性检查（`04-consistency-check.md`）。
3. 逆向参考材料在 `../clip_sync_re/`（反编译源码 + 架构图）。

## 当前进度

- [x] 问题清单全部写入 DECISIONS.md
- [x] 函数级 spec 骨架（00-overview / 01-android / 02-windows / 03-protocol）
- [x] 逐题决策完成（D-01 ~ D-16 全部✅，含 3 次中途答疑：MQTT 评估 / 小米账号鉴权 / Protobuf 许可证）
- [x] 一致性/冲突检查：**16 项决策无硬冲突**，5 项风险与改进项见 SPEC/04-consistency-check.md
- [x] 决策结果回写三份 spec（阈值 16KB / WinUI3 / Compose M3 / 预下载懒加载 / 锁屏缓存改进）
- [x] 复用基线（2026-09-30）：**仅剪切板读取相关**参考 TextCascade-v2（isDefaultIme hook + READ_LOGS 降级通道）；R2 已解决；D-01/D-04 维持 TCP+Protobuf 不变

## 关键决策速览

TCP 自定义帧 ｜ BLE 广播发现 ｜ 配对码+TLS 证书 PIN ｜ Protobuf ｜ 16KB 懒阈值 ｜
首版文+HTML+图 ｜ seq LRU 去重 ｜ Xposed 后台读（skill: LSPosed-Mod-Dev）｜ 标准 Listener ｜
FGS connectedDevice ｜ minSdk 29 ｜ Compose M3 ｜ WinUI 3 + NativeAOT ｜ 预下载写入 ｜
清除可配置默认 120s ｜ 锁屏缓存解锁补写
