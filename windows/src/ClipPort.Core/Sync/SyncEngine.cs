using System.Collections.Concurrent;
using ClipPort.Core.Clipboard;
using ClipPort.Core.Net;
using ClipPort.Core.Protocol;

namespace ClipPort.Core.Sync;

/// <summary>
/// 同步引擎（spec §2）：发布/接收/去重/内容服务/懒预取。
/// 线程模型：剪贴板操作全部 marshal 到单线程队列（Windows 剪贴板串行约束）。
/// </summary>
public sealed class SyncEngine : IDisposable
{
    public const int LazyThresholdBytes = 16 * 1024; // D-05

    private readonly AppConfig _cfg;
    private readonly TlsLinkServer _server;
    private readonly DedupeWindow _dedupe = new();
    private readonly BlockingCollection<Action> _queue = new(new ConcurrentQueue<Action>(), 256);
    private readonly Thread _worker;
    private readonly CancellationTokenSource _cts = new();

    private volatile bool _selfWriting;
    private long _selfSeq;
    private ClipboardSnapshot? _lastLocal;      // LocalHolder（对齐小米 180s TTL）
    private DateTime _lastLocalAt = DateTime.MinValue;
    private ClipboardSnapshot? _lastRemote;     // 回声比对窗（1.5s）
    private DateTime _lastRemoteAt = DateTime.MinValue;
    private readonly ScreenGate _screenGate = new();
    public Net.PeerStore Peers { get; } = Net.PeerStore.Load();
    /// <summary>本端证书指纹提供者（SHA256(DER)，App 注入）。</summary>
    public Func<byte[]?>? OwnFpProvider { get; set; }
    private Net.UdpDiscovery? _udp;
    private readonly Dictionary<string, DateTime> _connectAttempts = new();   // deviceId → 上次尝试时间
    private ClipboardSnapshot? _lockedPending;  // D-16：锁屏缓存（仅最新一条），解锁补写

    public Action<string>? Log { get; set; }
    public Action<string>? StatusChanged { get; set; }

    public SyncEngine(AppConfig cfg, TlsLinkServer server)
    {
        _cfg = cfg;
        _server = server;
        _server.OnBroadcast = OnBroadcastFrame;
        _server.OnTextRequest = OnTextRequest;
        _server.OnPairRequest = OnPairRequest;
        _server.OnHello = OnPhoneHello;
        _server.OnPairOk = OnPairOkResult;
        _server.OwnHelloProvider = OwnHello;
        StartUdpDiscovery();
        _worker = new Thread(WorkerLoop) { IsBackground = true, Name = "clipport-sync" };
        _worker.Start();
        _screenGate.Unlocked += () => _queue.TryAdd(() =>
        {
            if (_lockedPending is { } snap)
            {
                _lockedPending = null;
                WriteRemoteSnap(snap);
                Log?.Invoke("unlock: 补写锁屏期间收到的剪切板");
            }
        });
        _screenGate.Start();
    }

    // ---- 本地剪贴板监听 → 发布（spec §2.1）----
    public void OnLocalClipChanged()
    {
        _queue.TryAdd(() =>
        {
            try { HandleLocalClip(); }
            catch (Exception ex) { Log?.Invoke("local clip error: " + ex.Message); }
        });
    }

    private void HandleLocalClip()
    {
        if (!_cfg.SyncEnabled) return;
        if (_selfWriting || ClipboardAccess.IsSelfWrite()) return;          // 防回环第1道：自标记
        if (ClipboardAccess.IsEmpty()) return;
        var snap = ClipboardAccess.ReadAll();
        if (snap.IsEmpty) return;
        // 防回环第2道：1.5s 内与刚写入的远端内容一致 → 回声
        if (_lastRemote is not null && DateTime.UtcNow - _lastRemoteAt < TimeSpan.FromMilliseconds(1500)
            && SameText(snap, _lastRemote)) return;

        var seq = (uint)Interlocked.Increment(ref _selfSeq) - 1;
        bool needChannel = EstimateSize(snap) > LazyThresholdBytes;
        var bc = BuildBroadcast(_cfg.DeviceId, seq, needChannel, snap);
        _lastLocal = snap;
        _lastLocalAt = DateTime.UtcNow;   // TTL 180s（LocalHolder）
        int n = _server.SendToAll(FrameCodec.Encode(FrameCodec.ClipBroadcast, seq, bc.Encode()));
        Log?.Invoke(n > 0
            ? $"publish seq={seq} needChannel={needChannel} → 已推送至 {n} 台设备"
            : $"publish seq={seq} 但无已配对的已连接设备，广播未送达（重连后内容仍在 180s 持有期内可拉取）");
    }

    private static bool SameText(ClipboardSnapshot a, ClipboardSnapshot b) =>
        a.Text == b.Text && a.Html == b.Html;

    private static int EstimateSize(ClipboardSnapshot s) =>
        (s.Text?.Length ?? 0) * 2 + (s.Html?.Length ?? 0) + (s.ImagePng?.Length ?? 0);

    private static ClipBroadcast BuildBroadcast(string selfId, uint seq, bool needChannel, ClipboardSnapshot snap)
    {
        var mimes = new List<uint>();
        var inline = new ClipInline();
        if (snap.Text is not null) mimes.Add(snap.Html is not null ? Mime.BothTextHtml : Mime.Text);
        else if (snap.Html is not null) mimes.Add(Mime.Html);
        if (snap.ImagePng is not null) mimes.Add(Mime.ImagePng);

        if (!needChannel)
        {
            inline.Text = snap.Text;
            inline.Html = snap.Html;
            inline.ImagePng = snap.ImagePng;
        }
        return new ClipBroadcast
        {
            DeviceId = Convert.FromHexString(selfId),
            Seq = seq,
            NeedChannel = needChannel,
            MimeCodes = mimes,
            Inline = needChannel ? null : inline,
        };
    }

    // ---- 远端广播 → 接收（spec §2.2 / D-14=B 预下载）----
    private void OnBroadcastFrame(ClipBroadcast bc)
    {
        _queue.TryAdd(() =>
        {
            try { HandleRemote(bc); }
            catch (Exception ex) { Log?.Invoke("remote clip error: " + ex.Message); }
        });
    }

    private void HandleRemote(ClipBroadcast bc)
    {
        if (!_cfg.SyncEnabled) return;
        var devId = Convert.ToHexString(bc.DeviceId);
        if (_dedupe.Seen(bc.DeviceId, bc.Seq)) { Log?.Invoke($"dup filtered dev={devId} seq={bc.Seq}"); return; }

        var snap = new ClipboardSnapshot();
        if (!bc.NeedChannel && bc.Inline is not null)
        {
            snap.Text = bc.Inline.Text;
            snap.Html = bc.Inline.Html;
            snap.ImagePng = bc.Inline.ImagePng;
        }
        else
        {
            // D-14=B 首版：预下载（大文本/图片经 REQ_TEXT 拉取）后一次性写入
            var link = _server.PrimaryLink;
            if (link is not null)
            {
                foreach (var mime in bc.MimeCodes.Distinct())
                {
                    try
                    {
                        var resp = link.RequestTextAsync(new TextRequest { Seq = bc.Seq, ItemId = 0, Mime = mime }, _cts.Token).GetAwaiter().GetResult();
                        if (resp.Status != 0) continue;
                        switch (mime)
                        {
                            case Mime.Text: snap.Text = System.Text.Encoding.UTF8.GetString(resp.Content); break;
                            case Mime.Html: snap.Html = System.Text.Encoding.UTF8.GetString(resp.Content); break;
                            case Mime.BothTextHtml:
                                var r2 = link.RequestTextAsync(new TextRequest { Seq = bc.Seq, ItemId = 0, Mime = Mime.Text }, _cts.Token).GetAwaiter().GetResult();
                                snap.Html = System.Text.Encoding.UTF8.GetString(resp.Content);
                                if (r2.Status == 0) snap.Text = System.Text.Encoding.UTF8.GetString(r2.Content);
                                break;
                            case Mime.ImagePng: snap.ImagePng = resp.Content; break;
                        }
                    }
                    catch (Exception ex) { Log?.Invoke($"lazy fetch failed: {ex.Message}"); }
                }
            }
        }
        if (snap.IsEmpty) return;

        // D-16 锁屏门：锁屏缓存最新一条，解锁补写
        if (ScreenGate.IsLocked())
        {
            _lockedPending = snap;
            Log?.Invoke("screen locked, clip cached");
            return;
        }
        WriteRemoteSnap(snap);
        _lastRemote = snap;
        _lastRemoteAt = DateTime.UtcNow;
        Log?.Invoke($"remote clip applied dev={devId} seq={bc.Seq}");
    }

    private void WriteRemoteSnap(ClipboardSnapshot snap)
    {
        _selfWriting = true;
        try
        {
            _selfSeq = Environment.TickCount64;
            ClipboardAccess.Write(snap, _selfSeq);
        }
        finally { _selfWriting = false; }
    }

    // ---- 二期对等模式：连出对端（配对加入或已知对端重连）----
    public void ConnectPeer(string host, int port, string? code)
    {
        _ = Task.Run(async () =>
        {
            try
            {
                byte[]? pinned = null;
                var trustAny = true;
                if (string.IsNullOrEmpty(code))
                {
                    // 已知对端重连：端点匹配库中条目 → 用其证书指纹固定校验
                    var known = Peers.Peers.FirstOrDefault(p =>
                        string.Equals(p.LastEndpoint, $"{host}:{port}", StringComparison.OrdinalIgnoreCase));
                    pinned = known?.CertFpHex is { Length: 64 } hex ? Convert.FromHexString(hex) : null;
                    trustAny = pinned is null;
                }
                var link = await _server.ConnectOutAsync(host, port, pinned, trustAny, _cts.Token);
                Peers.Upsert("", "", null, $"{host}:{port}");  // 仅刷新端点占位；正式登记在 HELLO/PAIR 后
                if (!string.IsNullOrEmpty(code))
                {
                    var hash = AppConfig.CodeHash(code);
                    var req = Pairing.EncodePairReq(hash, OwnFpProvider?.Invoke());
                    await link.SendAsync(FrameCodec.Encode(FrameCodec.PairReq, _server.NextSeq(), req));
                    Log?.Invoke($"已发送配对请求至 {host}:{port}");
                }
            }
            catch (Exception ex) { Log?.Invoke($"connect peer {host}:{port} failed: {ex.Message}"); }
        });
    }

    /// <summary>M8: UDP 广播发现——通告自己 + 监听对端；已配对对端出现时自动建链。</summary>
    private void StartUdpDiscovery()
    {
        try
        {
            _udp = new Net.UdpDiscovery();
            if (Log != null) _udp.StatusLog += Log;
            _udp.OnAnnounced += (id, name, type, port, fp, ip) =>
                _queue.TryAdd(() => HandleAnnounced(id, name, type, port, fp, ip));
            _udp.Start(_cfg.DeviceId, () =>
            {
                var fpHex = OwnFpProvider?.Invoke();
                return (_cfg.DeviceName, 0, _cfg.TcpPort, fpHex is null ? "" : Convert.ToHexString(fpHex));
            });
        }
        catch (Exception ex) { Log?.Invoke("udp discovery failed: " + ex.Message); }
    }

    private void HandleAnnounced(string id, string name, int type, int port, string fp, string ip)
    {
        try
        {
            var knownFp = Peers.FingerprintOf(id);
            var paired = knownFp is not null || _cfg.PairedPhoneId == id;
            Peers.Upsert(id, name, fp.Length >= 16 ? fp : knownFp, $"{ip}:{port}");
            if (!paired)
            {
                Log?.Invoke($"发现未配对设备 {name} ({ip}:{port})——配对后才可同步");
                return;
            }
            // 已配对：自动建链。确定性仲裁：仅 deviceId 较小的一方主动连出，避免双向重复建链
            if (string.CompareOrdinal(_cfg.DeviceId, id) > 0) return;
            if (_server.HasAliveLinkTo(id)) return;
            if (_connectAttempts.TryGetValue(id, out var last) && DateTime.UtcNow - last < TimeSpan.FromSeconds(15)) return;
            _connectAttempts[id] = DateTime.UtcNow;
            Log?.Invoke($"自动连接已配对设备 {name} ({ip}:{port})");
            var ep = ip.Split(':');
            ConnectPeer(ip, port, null);
        }
        catch (Exception ex) { Log?.Invoke("announce handle error: " + ex.Message); }
    }

    /// <summary>启动时对已知端点的对端发起重连（二期自动组网的第一步）。</summary>
    public void ReconnectKnownPeers()
    {
        foreach (var p in Peers.Peers.Where(p => !string.IsNullOrEmpty(p.LastEndpoint)).ToList())
        {
            var ep = p.LastEndpoint!.Split(':');
            if (ep.Length == 2 && int.TryParse(ep[1], out var port))
                ConnectPeer(ep[0], port, null);
        }
    }

    // ---- 手机端 REQ_TEXT → 从本地 holder 取内容（spec §2.3 ContentServer）----
    private TextResponse OnTextRequest(TextRequest req)
    {
        var local = Volatile.Read(ref _lastLocal);
        if (local is null || DateTime.UtcNow - _lastLocalAt > TimeSpan.FromSeconds(180))
            return new TextResponse { Seq = req.Seq, ItemId = req.ItemId, Mime = req.Mime, Status = 1 };
        uint status = req.Mime switch
        {
            Mime.Text => local.Text is null ? 1u : 0u,
            Mime.Html => local.Html is null ? 1u : 0u,
            Mime.ImagePng => local.ImagePng is null ? 1u : 0u,
            _ => 1u,
        };
        return new TextResponse
        {
            Seq = req.Seq,
            ItemId = req.ItemId,
            Mime = req.Mime,
            Status = status,
            Content = req.Mime switch
            {
                Mime.Text => System.Text.Encoding.UTF8.GetBytes(local.Text ?? ""),
                Mime.Html => System.Text.Encoding.UTF8.GetBytes(local.Html ?? ""),
                Mime.ImagePng => local.ImagePng ?? Array.Empty<byte>(),
                _ => Array.Empty<byte>(),
            },
        };
    }

    private bool OnPairRequest(Net.TlsLinkServer.PhoneLink link, byte[] codeHash, byte[]? joinerFp)
    {
        // 幂等：已配对设备再次发起配对（重装/换码后重试）直接放行
        var helloId = Convert.ToHexString(link.PeerHello.DeviceId);
        if (!string.IsNullOrEmpty(_cfg.PairedPhoneId) &&
            string.Equals(_cfg.PairedPhoneId, helloId, StringComparison.OrdinalIgnoreCase))
        {
            StatusChanged?.Invoke("paired (idempotent)");
            return true;
        }
        if (!_cfg.PairingOpen || _cfg.PairingCodeHash is null)
        {
            Log?.Invoke($"pair rejected: 窗口未开启或已失效 (open={_cfg.PairingOpen}, hashNull={_cfg.PairingCodeHash is null})");
            return false;
        }
        if (!codeHash.AsSpan().SequenceEqual(_cfg.PairingCodeHash))
        {
            Log?.Invoke($"pair rejected: 码不匹配 got={Convert.ToHexString(codeHash)[..16]}… expected={Convert.ToHexString(_cfg.PairingCodeHash)[..16]}…");
            return false;
        }
        _cfg.PairingOpen = false;
        _cfg.PairedPhoneId = Convert.ToHexString(link.PeerHello.DeviceId);
        // 二期: 登记对端（含加入方证书指纹，用于本端连出时校验）
        var jfp = joinerFp is { Length: > 0 } ? Convert.ToHexString(joinerFp) : null;
        Peers.Upsert(Convert.ToHexString(link.PeerHello.DeviceId), link.PeerHello.Name, jfp, null);
        Peers.Save();
        StatusChanged?.Invoke("paired");
        return true;
    }

    /// <summary>对端 HELLO：恢复 Paired 态 + 对端库登记（二期对等模式）。</summary>
    private void OnPhoneHello(Net.TlsLinkServer.PhoneLink link, Hello hello)
    {
        var id = Convert.ToHexString(hello.DeviceId);
        if (!string.IsNullOrEmpty(_cfg.PairedPhoneId) &&
            string.Equals(_cfg.PairedPhoneId, id, StringComparison.OrdinalIgnoreCase))
        {
            link.Paired = true;
            Log?.Invoke($"paired peer reconnected: {hello.Name}");
        }
        var endpoint = link.RemoteEndpointText;
        Peers.Upsert(id, hello.Name, null, endpoint);
    }

    private Hello OwnHello() => new Hello
    {
        Name = _cfg.DeviceName,
        DeviceType = 0,
        ProtoVer = 1,
        DeviceId = Convert.FromHexString(_cfg.DeviceId),
    };

    /// <summary>本端作为加入方：PAIR_OK 结果处理（登记对端 + 指纹固定 + 标记链接）。</summary>
    private void OnPairOkResult(Net.TlsLinkServer.PhoneLink link, bool ok, byte[] peerFp, string peerName)
    {
        if (!ok) { Log?.Invoke("加入方配对被拒绝（配对码不匹配/窗口未开）"); StatusChanged?.Invoke("pair-rejected"); return; }
        link.Paired = true;
        var id = Convert.ToHexString(link.PeerHello.DeviceId);
        Peers.Upsert(id,
            string.IsNullOrEmpty(peerName) ? link.PeerHello.Name : peerName,
            peerFp is { Length: > 0 } ? Convert.ToHexString(peerFp) : null,
            link.RemoteEndpointText);
        Peers.Save();
        Log?.Invoke($"已加入对端: {peerName} ({id[..12]}…)");
        StatusChanged?.Invoke("paired");
    }

    private void WorkerLoop()
    {
        foreach (var act in _queue.GetConsumingEnumerable(_cts.Token))
        {
            try { act(); } catch (Exception ex) { Log?.Invoke(ex.Message); }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _queue.CompleteAdding();
    }
}
