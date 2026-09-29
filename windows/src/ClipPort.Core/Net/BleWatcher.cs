using Windows.Devices.Bluetooth.Advertisement;

namespace ClipPort.Core.Net;

/// <summary>
/// BLE 扫描发现（D-02，PC 固定为扫描者；手机为广播者）。
/// 广播载荷（厂商自定义段，公司ID 0xFFFF）：
/// [0..1]='C','P' 魔数 | [2..5]=IPv4 | [6..7]=端口(LE) | [8..11]=证书指纹前4字节
/// </summary>
public sealed class BleWatcher
{
    private BluetoothLEAdvertisementWatcher? _watcher;
    public event Action<(string Ip, ushort Port, byte[] FpPrefix)>? EndpointFound;

    private readonly byte[] _expectedFpPrefix;

    public BleWatcher(byte[] certFingerprint)
    {
        _expectedFpPrefix = certFingerprint.Length >= 4 ? certFingerprint[..4] : certFingerprint;
    }

    public void Start()
    {
        _watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active,
        };
        _watcher.Received += OnReceived;
        _watcher.Start();
    }

    public void Stop()
    {
        if (_watcher is not null)
        {
            try { _watcher.Stop(); } catch { }
            _watcher.Received -= OnReceived;
            _watcher = null;
        }
    }

    public static byte[] BuildPayload(System.Net.IPAddress ip, ushort port, byte[] fp)
    {
        var buf = new List<byte> { (byte)'C', (byte)'P' };
        buf.AddRange(ip.GetAddressBytes());
        buf.AddRange(BitConverter.GetBytes(port));
        buf.AddRange(fp.Length >= 4 ? fp[..4] : fp);
        return buf.ToArray();
    }

    private void OnReceived(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args)
    {
        foreach (var section in args.Advertisement.ManufacturerData)
        {
            if (section.CompanyId != 0xFFFF) continue;
            var reader = Windows.Storage.Streams.DataReader.FromBuffer(section.Data);
            var bytes = new byte[section.Data.Length];
            reader.ReadBytes(bytes);
            if (bytes.Length < 12 || bytes[0] != (byte)'C' || bytes[1] != (byte)'P') continue;
            if (!bytes.AsSpan(8, 4).SequenceEqual(_expectedFpPrefix)) continue;
            var ip = new System.Net.IPAddress(bytes.AsSpan(2, 4).ToArray());
            ushort port = BitConverter.ToUInt16(bytes, 6);
            try { EndpointFound?.Invoke((ip.ToString(), port, bytes[8..12])); } catch { }
        }
    }
}
