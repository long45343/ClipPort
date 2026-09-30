using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Storage.Streams;

namespace ClipPort.Core.Net;

/// <summary>
/// BLE 常驻广播（D-02 自动发现，方向修正版）：PC 广播自己的 IP+端口+证书指纹前缀，
/// 手机扫描后自动回填并作为 TLS 客户端连入。
/// 载荷（厂商段，公司ID 0xFFFF）：[0..1]='C','P' | [2..5]=IPv4 | [6..7]=端口(LE) | [8..11]=指纹前4字节
/// </summary>
public static class BlePublisher
{
    private static BluetoothLEAdvertisementPublisher? _publisher;
    public static Action<string>? StatusLog;

    public static void Start(ushort port, byte[] certFingerprint)
    {
        Stop();
        var ip = PreferredLanIpv4();
        if (ip is null) { StatusLog?.Invoke("BLE 广播跳过: 未找到局域网 IPv4"); return; }
        var payload = BuildPayload(ip, port, certFingerprint);
        var writer = new DataWriter();
        writer.WriteBytes(payload);
        var publisher = new BluetoothLEAdvertisementPublisher();
        publisher.Advertisement.ManufacturerData.Add(new BluetoothLEManufacturerData
        {
            CompanyId = 0xFFFF,
            Data = writer.DetachBuffer(),
        });
        publisher.StatusChanged += (p, args) =>
            StatusLog?.Invoke($"BLE 广播状态: {args.Status}" +
                (args.Error == Windows.Devices.Bluetooth.BluetoothError.Success ? "" : $" 错误={args.Error}"));
        publisher.Start();
        _publisher = publisher;
        StatusLog?.Invoke($"BLE 广播请求已发出: {ip}:{port} fp={Convert.ToHexString(certFingerprint[..4])} payload={Convert.ToHexString(payload.ToArray())}");
    }

    public static void Stop()
    {
        try { _publisher?.Stop(); } catch { }
        _publisher = null;
    }

    /// <summary>优先 192.168.*（真实网段），规避 Clash TUN/WSL/vEthernet 等虚拟适配器地址。</summary>
    public static IPAddress? PreferredLanIpv4() =>
        AllLanIpv4().OrderByDescending(a => a.ToString().StartsWith("192.168."))
            .ThenByDescending(a => a.ToString().StartsWith("10."))
            .FirstOrDefault();

    public static byte[] BuildPayload(IPAddress ip, ushort port, byte[] fp)
    {
        var buf = new List<byte> { (byte)'C', (byte)'P' };
        buf.AddRange(ip.GetAddressBytes());
        buf.AddRange(BitConverter.GetBytes(port));
        buf.AddRange(fp.Length >= 4 ? fp[..4] : fp);
        return buf.ToArray();
    }

    /// <summary>本机第一个局域网 IPv4（环back/隧道除外）。</summary>
    public static IPAddress? FirstLanIpv4() => AllLanIpv4().FirstOrDefault();

    public static IEnumerable<IPAddress> AllLanIpv4() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up
                        && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                        && n.NetworkInterfaceType != NetworkInterfaceType.Tunnel)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(a => a.Address);
}
