using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
#if ENABLE_WINRT_BLE
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Storage.Streams;
#endif

namespace ClipPort.Core.Net;

/// <summary>
/// BLE 广播适配器（主干发现已全面由 UdpDiscovery 接管；本类保留向后兼容与网络探测）
/// </summary>
public static class BlePublisher
{
#if ENABLE_WINRT_BLE
    private static BluetoothLEAdvertisementPublisher? _publisher;
#endif
    public static Action<string>? StatusLog;

    public static void Start(ushort port, byte[] certFingerprint)
    {
#if ENABLE_WINRT_BLE
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
#else
        // 纯净 net10.0-windows 模式下由 UDP 广播与二维码一键扫码接管发现，免去 24MB WinRT SDK 依赖
#endif
    }

    public static void Stop()
    {
#if ENABLE_WINRT_BLE
        try { _publisher?.Stop(); } catch { }
        _publisher = null;
#endif
    }

    public static IPAddress? PreferredLanIpv4() => NetworkHelper.PreferredLanIpv4();

    public static byte[] BuildPayload(IPAddress ip, ushort port, byte[] fp)
    {
        var buf = new List<byte> { (byte)'C', (byte)'P' };
        buf.AddRange(ip.GetAddressBytes());
        buf.AddRange(BitConverter.GetBytes(port));
        buf.AddRange(fp.Length >= 4 ? fp[..4] : fp);
        return buf.ToArray();
    }

    public static IPAddress? FirstLanIpv4() => NetworkHelper.FirstLanIpv4();

    public static IEnumerable<IPAddress> AllLanIpv4() => NetworkHelper.AllLanIpv4();
}
