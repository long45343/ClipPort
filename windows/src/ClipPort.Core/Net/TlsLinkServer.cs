using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using ClipPort.Core.Protocol;

namespace ClipPort.Core.Net;

/// <summary>
/// TLS(TcpListener+SslStream) 服务端（D-01/D-03）。
/// 职责：接受手机连接、完成 HELLO/PAIR、收发帧、心跳。
/// 帧回调在每连接读线程触发；发送方通过 Send 广播。
/// </summary>
public sealed class TlsLinkServer : IAsyncDisposable
{
    private TcpListener? _listener;
    private X509Certificate2? _cert;
    private readonly List<PhoneLink> _links = new();
    private readonly object _gate = new();
    private CancellationTokenSource? _cts;
    private uint _seq;

    public Action<PhoneLink, Hello>? OnHello { get; set; }                 // 设备ID就绪（恢复 Paired 态/登记对端）
    /// <summary>本端 HELLO 提供者（接受与连出链路都要互发 HELLO 完成对称识别）。</summary>
    public Func<Hello>? OwnHelloProvider { get; set; }
    /// <summary>本端作为加入方时收到 PAIR_OK（对端=acceptor）。</summary>
    public Action<PhoneLink, bool, byte[], string>? OnPairOk { get; set; }
    public Func<PhoneLink, byte[], byte[]?, bool>? OnPairRequest { get; set; }      // 入参 link+codeHash，返回是否配对成功
    public Action<ClipBroadcast>? OnBroadcast { get; set; }
    /// <summary>手机间中继开关（多设备同步：A 手机的内容转发给其他已配对链接）。</summary>
    public bool RelayEnabled { get; set; } = true;
    public Func<TextRequest, TextResponse>? OnTextRequest { get; set; }
    public Action<string>? Log { get; set; }

    public bool HasConnectedPhone => _links.Count > 0;
    public PhoneLink? PrimaryLink { get { lock (_gate) return _links.FirstOrDefault(l => l.Alive && l.Paired); } }

    public void Start(ushort port, X509Certificate2 cert)
    {
        _cert = cert;
        _cts = new CancellationTokenSource();
        _listener = new TcpListener(IPAddress.Any, port);
        _listener.Start();
        _ = Task.Run(() => AcceptLoop(_cts.Token));
        _ = Task.Run(() => PingLoop(_cts.Token));
    }

    private async Task AcceptLoop(CancellationToken ct)
    {
        var listener = _listener!;
        while (!ct.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await listener.AcceptTcpClientAsync(ct); }
            catch (OperationCanceledException) { break; }
            catch (SocketException) { continue; }
            _ = Task.Run(() => ServeClient(client, ct), ct);
        }
    }

    private async Task ServeClient(TcpClient client, CancellationToken ct)
    {
        var link = new PhoneLink(this, client);
        var remote = (client.Client.RemoteEndPoint as System.Net.IPEndPoint)?.Address;
        try
        {
            Log?.Invoke($"link accepted from {remote}");
            await link.HandshakeAsync(_cert!, null, trustAny: true, remote?.ToString() ?? "", ct)
                .WaitAsync(TimeSpan.FromSeconds(15), ct);
            if (OwnHelloProvider != null)
                _ = link.SendAsync(FrameCodec.Encode(FrameCodec.Hello, NextSeq(), OwnHelloProvider().Encode()));
            Log?.Invoke($"tls done ({link.TlsProtocol}) from {remote}");
            lock (_gate) { _links.RemoveAll(l => !l.Alive); _links.Add(link); }
            await link.ReadLoop(ct);   // 阻塞直到断开
        }
        catch (Exception ex) { Log?.Invoke($"link error from {remote}: {ex.Message}"); }
        finally
        {
            lock (_gate) _links.Remove(link);
            link.Dispose();
        }
    }

    /// <summary>主动连出到对端设备（二期对等模式：PC 也可作为客户端）。</summary>
    public async Task<PhoneLink> ConnectOutAsync(string host, int port, byte[]? pinnedFp, bool trustAny, CancellationToken ct)
    {
        if (_cert is null) throw new InvalidOperationException("server not started (no cert)");
        var client = new TcpClient();
        client.NoDelay = true;
        await client.ConnectAsync(host, port, ct);
        var link = new PhoneLink(this, client);
        var remote = (client.Client.RemoteEndPoint as IPEndPoint)?.Address;
        try
        {
            Log?.Invoke($"connect-out to {host}:{port}");
            await link.HandshakeAsync(null, pinnedFp, trustAny, host, ct).WaitAsync(TimeSpan.FromSeconds(15), ct);
            Log?.Invoke($"tls done ({link.TlsProtocol}) to {host}:{port}");
            lock (_gate) { _links.RemoveAll(l => !l.Alive); _links.Add(link); }
            if (OwnHelloProvider != null)
                _ = link.SendAsync(FrameCodec.Encode(FrameCodec.Hello, NextSeq(), OwnHelloProvider().Encode()));
            _ = Task.Run(() => link.ReadLoopCompat(ct), ct);
        }
        catch (Exception ex)
        {
            Log?.Invoke($"connect-out failed to {host}:{port}: {ex.Message}");
            link.Dispose();
            throw;
        }
        return link;
    }

    private async Task PingLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(30_000, ct); } catch { break; }
            SendToAll(FrameCodec.Encode(FrameCodec.Ping, NextSeq(), Array.Empty<byte>()));
        }
    }

    public uint NextSeq() => Interlocked.Increment(ref _seq);

    public void SendToAllExcept(PhoneLink? except, byte[] frame)
    {
        lock (_gate)
        {
            foreach (var l in _links.Where(l => l.Alive && l.Paired && l != except).ToList())
                _ = l.SendAsync(frame);
        }
    }

    public int SendToAll(byte[] frame)
    {
        lock (_gate)
        {
            var targets = _links.Where(l => l.Alive && l.Paired).ToList();
            foreach (var l in targets)
                _ = l.SendAsync(frame);
            return targets.Count;
        }
    }

    public bool HandlePairOnCurrentLink(byte[] codeHash)
    {
        PhoneLink? pending;
        lock (_gate) pending = _links.FirstOrDefault(l => l.Alive && !l.Paired);
        if (pending is null) return false;
        pending.Paired = true;
        return true;
    }

    public async ValueTask DisposeAsync()
    {
        _cts?.Cancel();
        try { _listener?.Stop(); } catch { }
        lock (_gate) { foreach (var l in _links) l.Dispose(); _links.Clear(); }
        await Task.CompletedTask;
    }

    public sealed class PhoneLink : IDisposable
    {
        private readonly TcpClient _tcp;
        private readonly TlsLinkServer _owner;
        private SslStream? _ssl;
        private readonly SemaphoreSlim _sendGate = new(1, 1);
        private readonly Dictionary<uint, TaskCompletionSource<TextResponse>> _pending = new();
        public Hello PeerHello { get; private set; } = new();
        public volatile bool Paired;
        public string TlsProtocol => _ssl?.SslProtocol.ToString() ?? "?";
        public string RemoteEndpointText => (_tcp.Client.RemoteEndPoint as IPEndPoint)?.ToString() ?? "";
        public bool Alive => _tcp.Connected && _ssl is { };

        public PhoneLink(TlsLinkServer owner, TcpClient tcp) { _owner = owner; _tcp = tcp; }

        private string _host = "";
        private byte[]? _expectedFp;
        private bool _trustAny;

        /// <summary>server 模式：出本端证书；client 模式：按 pinnedFp 校验对端（trustAny 用于配对）。</summary>
        public async Task HandshakeAsync(X509Certificate2? cert, byte[]? pinnedFp, bool trustAny, string host, CancellationToken ct)
        {
            _host = host;
            _expectedFp = pinnedFp;
            _trustAny = trustAny;
            var raw = _tcp.GetStream();
            _ssl = new SslStream(raw, false);
            if (cert != null)
            {
                await _ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                {
                    ServerCertificate = cert,
                    EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls13 | System.Security.Authentication.SslProtocols.Tls12,
                }, ct);
            }
            else
            {
                await _ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
                {
                    TargetHost = host,
                    EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls13 | System.Security.Authentication.SslProtocols.Tls12,
                    RemoteCertificateValidationCallback = ValidateRemoteCert,
                }, ct);
            }
        }

        private bool ValidateRemoteCert(object sender, X509Certificate? cert, X509Chain? chain, System.Net.Security.SslPolicyErrors errors)
        {
            if (_trustAny) return true;
            if (cert is null || _expectedFp is null) return false;
            var fp = System.Security.Cryptography.SHA256.HashData(cert.GetRawCertData());
            return fp.AsSpan().SequenceEqual(_expectedFp);
        }

        public async Task ReadLoopCompat(CancellationToken ct)
        {
            await ReadLoop(ct);
        }

        public async Task ReadLoop(CancellationToken ct)
        {
            var buf = new byte[256 * 1024];
            var acc = new MemoryStream();
            while (!ct.IsCancellationRequested && Alive)
            {
                int n = await _ssl!.ReadAsync(buf, ct);
                if (n <= 0) break;
                acc.Position = acc.Length;
                foreach (var b in buf.AsSpan(0, n)) acc.WriteByte(b);
                ParseFrames(acc);
            }
        }

        private void ParseFrames(MemoryStream acc)
        {
            var buf = acc.GetBuffer().AsSpan(0, (int)acc.Length);
            int consumed = 0;
            while (FrameCodec.TryDecode(buf.Slice(consumed), out var type, out var seq, out var payload, out var used))
            {
                consumed += used;
                Dispatch(type, seq, payload);
            }
            if (consumed > 0)
            {
                var rest = buf.Slice(consumed).ToArray();
                acc.SetLength(0);
                acc.Write(rest);
                acc.Position = 0;
            }
        }

        private void Dispatch(byte type, uint seq, byte[] payload)
        {
            switch (type)
            {
                case FrameCodec.Pong: break;
                case FrameCodec.Ping:
                    _ = SendAsync(FrameCodec.Encode(FrameCodec.Pong, seq, payload));
                    break;
                case FrameCodec.Hello:
                    PeerHello = Hello.Decode(payload);
                    _owner.OnHello?.Invoke(this, PeerHello);   // ★ 设备ID就绪后才触发, 重连方能恢复 Paired 态
                    break;
                case FrameCodec.PairReq:
                    var (pairHash, joinerFp) = Pairing.DecodePairReq(payload);   // ★ 此前漏了解码, 整条 protobuf 消息被当成哈希比对
                    bool ok = _owner.OnPairRequest?.Invoke(this, pairHash, joinerFp) ?? false;
                    if (ok) Paired = true;   // ★ 配对成功立即标记，否则 PC 永远不会向该链接推送
                    _ = SendAsync(FrameCodec.Encode(FrameCodec.PairOk, seq,
                        Pairing.EncodePairOk(ok, _owner._cert != null ? CertManager.Fingerprint(_owner._cert) : Array.Empty<byte>())));
                    break;
                case FrameCodec.ClipBroadcast:
                    var bc = ClipBroadcast.Decode(payload);
                    _owner.OnBroadcast?.Invoke(bc);
                    if (_owner.RelayEnabled)
                        _owner.SendToAllExcept(this, FrameCodec.Encode(FrameCodec.ClipBroadcast, seq, payload));
                    break;
                case FrameCodec.PairOk:
                {
                    var (pOk, pFp, pName) = Pairing.DecodePairOk(payload);
                    _owner.OnPairOk?.Invoke(this, pOk, pFp, pName);
                    break;
                }
                case FrameCodec.RespText:
                {
                    var resp = TextResponse.Decode(payload);
                    TaskCompletionSource<TextResponse>? tcs;
                    lock (_pending)
                    {
                        if (_pending.Remove(resp.Seq, out tcs)) tcs.TrySetResult(resp);
                    }
                    break;
                }
                case FrameCodec.ReqText:
                    var resp2 = _owner.OnTextRequest?.Invoke(TextRequest.Decode(payload))
                               ?? new TextResponse { Status = 1 };
                    _ = SendAsync(FrameCodec.Encode(FrameCodec.RespText, seq, resp2.Encode()));
                    break;
            }
        }

        /// <summary>向手机发送 REQ_TEXT 并等待 RESP_TEXT（服务端主动请求，seq 作关联 ID）。</summary>
        public async Task<TextResponse> RequestTextAsync(TextRequest req, CancellationToken ct)
        {
            uint rid = _owner.NextSeq();
            var tcs = new TaskCompletionSource<TextResponse>(TaskCreationOptions.RunContinuationsAsynchronously);
            lock (_pending) _pending[rid] = tcs;
            try
            {
                await SendAsync(FrameCodec.Encode(FrameCodec.ReqText, rid, req.Encode()));
                var done = await Task.WhenAny(tcs.Task, Task.Delay(TimeSpan.FromSeconds(15), ct));
                if (done != tcs.Task) throw new TimeoutException("resp_text timeout");
                return tcs.Task.Result;
            }
            finally
            {
                lock (_pending) _pending.Remove(rid);
            }
        }

        public async Task SendAsync(byte[] frame)
        {
            var ssl = _ssl;
            if (ssl is null) return;
            await _sendGate.WaitAsync();
            try { await ssl.WriteAsync(frame); await ssl.FlushAsync(); }
            catch { _tcp.Close(); }
            finally { _sendGate.Release(); }
        }

        public void Dispose()
        {
            try { _tcp.Close(); } catch { }
            _ssl?.Dispose();
            _sendGate.Dispose();
        }
    }
}
