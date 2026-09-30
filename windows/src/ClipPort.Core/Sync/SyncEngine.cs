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
        _server.SendToAll(FrameCodec.Encode(FrameCodec.ClipBroadcast, seq, bc.Encode()));
        Log?.Invoke($"publish seq={seq} needChannel={needChannel}");
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

    private bool OnPairRequest(Net.TlsLinkServer.PhoneLink link, byte[] codeHash)
    {
        if (!_cfg.PairingOpen || _cfg.PairingCodeHash is null) return false;
        if (!codeHash.AsSpan().SequenceEqual(_cfg.PairingCodeHash)) return false;
        _cfg.PairingOpen = false;
        _cfg.PairedPhoneId = Convert.ToHexString(link.PeerHello.DeviceId);
        _cfg.Save();
        StatusChanged?.Invoke("paired");
        return true;
    }

    /// <summary>已配对手机重连：凭 HELLO 中的设备ID恢复链接的 Paired 态（否则 PC 永远不向它推送）。</summary>
    private void OnPhoneHello(Net.TlsLinkServer.PhoneLink link, Hello hello)
    {
        var id = Convert.ToHexString(hello.DeviceId);
        if (!string.IsNullOrEmpty(_cfg.PairedPhoneId) &&
            string.Equals(_cfg.PairedPhoneId, id, StringComparison.OrdinalIgnoreCase))
        {
            link.Paired = true;
            Log?.Invoke($"paired phone reconnected: {hello.Name}");
        }
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
