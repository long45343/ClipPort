using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace ClipPort.Core.Net;

/// <summary>
/// UDP 广播发现（M8，全设备通用、无蓝牙依赖）：
/// 所有设备每 10s 向 47192/udp 广播 {id,name,type,port,fp}，同时监听对端通告。
/// 已配对对端出现时由上层自动建链；未配对对端仅登记供配对。
/// </summary>
public sealed class UdpDiscovery : IDisposable
{
    public const int UdpPort = 47192;

    private UdpClient? _rx;
    private System.Threading.Timer? _tx;
    private string _selfId = "";
    private Func<(string Name, int Type, int Port, string FpHex)>? _selfInfo;
    private CancellationTokenSource? _cts;

    /// <summary>(deviceId, name, deviceType, tcpListenPort, fpHexFull, senderIp)</summary>
    public event Action<string, string, int, int, string, string>? OnAnnounced;
    public event Action<string>? StatusLog;

    public void Start(string selfId, Func<(string Name, int Type, int Port, string FpHex)> selfInfo)
    {
        _selfId = selfId;
        _selfInfo = selfInfo;
        _cts = new CancellationTokenSource();
        try
        {
            _rx = new UdpClient();
            _rx.EnableBroadcast = true;
            _rx.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _rx.Client.Bind(new IPEndPoint(IPAddress.Any, UdpPort));
            _ = Task.Run(() => RxLoop(_cts.Token));
        }
        catch (Exception ex)
        {
            StatusLog?.Invoke($"UDP 发现监听失败: {ex.Message}");
            return;
        }
        _tx = new Timer(_ => SendAnnounce(), null, TimeSpan.Zero, TimeSpan.FromSeconds(10));
        StatusLog?.Invoke("UDP 发现已启动 (47192/udp, 10s 周期)");
    }

    /// <summary>即刻触发一次全网卡广播宣告（D-32=A）</summary>
    public void BroadcastNow()
    {
        SendAnnounce();
    }

    private async void SendAnnounce()
    {
        if (_selfInfo is null) return;
        try
        {
            var (name, type, port, fp) = _selfInfo();
            var packet = new UdpAnnouncePacket
            {
                id = _selfId,
                name = name,
                type = type,
                port = port,
                fp = fp,
                v = 1
            };
            var json = JsonSerializer.Serialize(packet, ClipPortJsonContext.Default.UdpAnnouncePacket);
            var data = Encoding.UTF8.GetBytes(json);
            // 1) 受限广播
            try
            {
                using var c = new UdpClient();
                c.EnableBroadcast = true;
                _ = await c.SendAsync(data, data.Length, new IPEndPoint(IPAddress.Broadcast, UdpPort));
            }
            catch { }
            // 2) 每接口的子网定向广播（更可靠穿越 AP 隔离策略）
            foreach (var nif in NetworkInterface.GetAllNetworkInterfaces()
                         .Where(n => n.OperationalStatus == OperationalStatus.Up
                                     && n.NetworkInterfaceType != NetworkInterfaceType.Loopback))
            {
                foreach (var u in nif.GetIPProperties().UnicastAddresses)
                {
                    if (u.Address.AddressFamily != AddressFamily.InterNetwork || u.IPv4Mask is null) continue;
                    var ip = u.Address.GetAddressBytes();
                    var mask = u.IPv4Mask.GetAddressBytes();
                    var bcast = new byte[4];
                    for (int i = 0; i < 4; i++) bcast[i] = (byte)(ip[i] | ~mask[i]);
                    try
                    {
                        using var c = new UdpClient(new IPEndPoint(u.Address, 0));
                        c.EnableBroadcast = true;
                        _ = await c.SendAsync(data, data.Length, new IPEndPoint(new IPAddress(bcast), UdpPort));
                    }
                    catch { }
                }
            }
        }
        catch { }
    }

    private async Task RxLoop(CancellationToken ct)
    {
        var ep = new IPEndPoint(IPAddress.Any, 0);
        while (!ct.IsCancellationRequested)
        {
            byte[] data;
            try { data = _rx!.Receive(ref ep); }
            catch (OperationCanceledException) { break; }
            catch (Exception) { continue; }
            try
            {
                using var doc = JsonDocument.Parse(data);
                var root = doc.RootElement;
                var id = root.GetProperty("id").GetString();
                if (id is null || id == _selfId) continue;   // 跳过自己
                var name = root.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                var type = root.TryGetProperty("type", out var t) ? t.GetInt32() : 0;
                var port = root.TryGetProperty("port", out var p) ? p.GetInt32() : 0;
                var fp = root.TryGetProperty("fp", out var f) ? f.GetString() ?? "" : "";
                if (port <= 0) continue;
                OnAnnounced?.Invoke(id, name, type, port, fp, ep.Address.ToString());
            }
            catch { /* 非本协议包，忽略 */ }
        }
    }

    public void Dispose()
    {
        _cts?.Cancel();
        _tx?.Dispose();
        try { _rx?.Close(); } catch { }
        _rx = null;
    }
}
