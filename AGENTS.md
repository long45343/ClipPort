# ClipPort Workspace Instructions (AGENTS.md)

ClipPort is a decentralized peer-to-peer (P2P) clipboard synchronization tool connecting Windows and Android devices without central cloud infrastructure.

---

## 1. Directory Structure

- `windows/` — C# / .NET 10 solution (`ClipPort.sln`):
  - `src/ClipPort.Core/` — Protocol framing, hand-rolled Protobuf, TLS server/client, UDP discovery, clipboard listener/access, dedupe engine.
  - `src/ClipPort.App/` — Modern Fluent UI desktop shell (WPF-UI 4.3 + WPF-UI.Tray), single-instance mutex, system tray integration.
- `android/` — Kotlin + Jetpack Compose Android app (minSdk 29, targetSdk 35, compileSdk 35):
  - `app/src/main/java/com/clipport/app/`
    - `protocol/` — Frame codec and minimal Protobuf implementation (byte-aligned with Windows).
    - `transport/` — PeerServer (TLS), PcLink (TLS client), LanDiscovery (UDP), PeerBook.
    - `clip/` — Clipboard pipeline, debouncing, 3-tier filtering, RemoteClipApplier.
    - `service/` — `ClipPortService` (foreground service with `connectedDevice` type).
    - `xposed/` — LibXposed (API 102) hook on `ClipboardService.isDefaultIme` + floating window fallback.
- `docs/` — Architectural specifications and design records:
  - `docs/DECISIONS.md` — Authoritative architectural decisions (D-01 to D-16).
  - `docs/03-protocol-spec.md` — Wire framing, opcode tables, and Protobuf field schemas.
  - `docs/01-android-spec.md` / `docs/02-windows-spec.md` — Detailed module specs.
- `design/` — Standard app icon assets (`app.ico`, `icon_512.png`, `logo.svg`).
- `dist/` — Release binaries (`ClipPort.exe` self-contained single file, `ClipPort-v0.3.0.apk`).

---

## 2. Build & Verification Commands

### Windows (.NET 10 SDK)

```bash
# Debug build
dotnet build windows/ClipPort.sln

# Release self-contained single-file EXE (recommended for distribution)
dotnet publish windows/src/ClipPort.App/ClipPort.App.csproj -c Release -r win-x64 \
  -p:Platform=x64 -p:PublishSingleFile=true -p:SelfContained=true \
  -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o dist

# Release framework-dependent single-file EXE
dotnet publish windows/src/ClipPort.App/ClipPort.App.csproj -c Release -r win-x64 \
  -p:Platform=x64 -p:PublishSingleFile=true -p:SelfContained=false -o dist
```

### Android (JDK 17 + Android SDK 35)

```bash
# In android/ directory:
cd android

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Unit tests (when added)
./gradlew test
```

---

## 3. Protocol & Network Invariants

- **Topology & Ports**: Full mesh peer-to-peer.
  - Windows TLS Server default: `47190/tcp`
  - Android TLS Server default: `47191/tcp`
  - LAN UDP Discovery: `47192/udp` (10s periodic broadcast `{id, name, type, port, fp}`)
- **Binary Wire Frame**: 12-byte header with magic `0xC1B0`, version 1, little-endian:
  `magic(u16=0xC1B0) | version(u8=1) | type(u8) | seq(u32 LE) | payloadLen(u32 LE) | payload`
  Max payload size is 64MB (`MaxPayload`).
- **Minimal Protobuf**: Zero external compiler or library dependencies.
  - Hand-crafted minimal Protobuf encoders/decoders: `windows/.../Protocol/Proto.cs` and `android/.../protocol/Frame.kt` (`object Proto`).
  - **Rule**: Never import external Protobuf compilers/generators. Always keep field IDs and wire encodings synchronized between C# and Kotlin.
- **Security & Pairing**:
  - RSA-2048 self-signed certificates with SHA-256 fingerprint pinning (`PeerStore` / `PeerBook`).
  - Trust bootstrap via temporary 6-digit challenge code (`PAIR_REQ` / `PAIR_OK`).
- **Lifecycle & Cache Constants**:
  - Lazy pull threshold: 16KB (`LAZY_THRESHOLD_BYTES = 16384`). Below 16KB is inline in `CLIP_BROADCAST`; above 16KB only metadata is sent, and content is fetched via `REQ_TEXT`/`RESP_TEXT`.
  - Echo suppression window: 1500ms.
  - Dedupe window: LRU 128 / 30s expiry.
  - Local content cache TTL: 180s.
  - Remote clipboard auto-clear: configurable (default 120s).

---

## 4. Platform Implementation Guidelines

### Windows
- **STA & Threading**: All Win32 clipboard API calls (`OpenClipboard`, `GetClipboardData`, `SetClipboardData`) must execute on the dedicated single-threaded worker queue in `SyncEngine`. Never call clipboard APIs from thread pool or async tasks.
- **Loopback Prevention**: Writes to Windows clipboard must register and include the private format `"ClipPort.Self"` (`Native.RegisterClipboardFormatW("ClipPort.Self")`) so local listeners ignore echoed events.
- **Window Handling**: `ClipboardListener` uses an invisible message-only HWND (`ClipPortClipWnd`) for `AddClipboardFormatListener` and `WM_CLIPBOARDUPDATE`.

### Android
- **Foreground Service**: `ClipPortService` operates with `foregroundServiceType="connectedDevice"` to prevent background termination.
- **Loopback Prevention**: Android clipboard writes must set `ClipDescription.label` to `"clipportClipData"`. `ClipFilter` verifies both the label and the 1500ms echo window.
- **Background Clipboard Reading**:
  - *Tier 1 (LSPosed/Root)*: `XposedEntry.kt` hooks `com.android.server.clipboard.ClipboardService.isDefaultIme` (LibXposed API 102) and returns true **only** for `com.clipport.app`. Never hook globally.
  - *Tier 2 (Fallback)*: `ClipboardFloatingActivity.kt` temporarily requests translucent focus to read/write clipboard when background privileges are restricted.

---

## 5. Sensitive Areas & Collaboration Rules

1. **Protocol Synchronization**: Any modification to message payloads or frame types in `windows/src/ClipPort.Core/Protocol/` MUST be replicated identically in `android/app/src/main/java/com/clipport/app/protocol/Frame.kt` and documented in `docs/03-protocol-spec.md`.
2. **Specification Source of Truth**: Before altering discovery, crypto, or sync logic, consult `docs/DECISIONS.md` and `docs/03-protocol-spec.md`.
3. **Distribution Assets**: Pre-built executables in `dist/` should only be updated when compiling final tagged releases.
