using System.Security.Cryptography;
using System.Text.Json;

namespace ClipPort.Core.Net;

/// <summary>对等设备信息（二期对等模式）。</summary>
public sealed class PeerInfo
{
    public string DeviceId { get; set; } = "";      // 32 hex
    public string Name { get; set; } = "";
    /// <summary>对端证书指纹（SHA-256 of DER, 64 hex）；手机端 M6 阶段可为空。</summary>
    public string? CertFpHex { get; set; }
    public string? LastEndpoint { get; set; }        // "ip:port"
    public DateTime LastSeen { get; set; }
}

/// <summary>对端库持久化（%LOCALAPPDATA%\ClipPort\peers.json）。</summary>
public sealed class PeerStore
{
    public List<PeerInfo> Peers { get; set; } = new();
    private static string Path_ => System.IO.Path.Combine(CertManager.StoreDir, "peers.json");
    private readonly object _gate = new();

    public static PeerStore Load()
    {
        try
        {
            if (File.Exists(Path_))
                return JsonSerializer.Deserialize(File.ReadAllText(Path_), ClipPortJsonContext.Default.PeerStore) ?? new PeerStore();
        }
        catch { }
        return new PeerStore();
    }

    public void Save()
    {
        lock (_gate)
        {
            Directory.CreateDirectory(CertManager.StoreDir);
            File.WriteAllText(Path_, JsonSerializer.Serialize(this, ClipPortJsonContext.Default.PeerStore));
        }
    }

    /// <summary>按 HELLO/PAIR 信息登记或更新对端；返回条目。</summary>
    public PeerInfo Upsert(string deviceId, string name, string? certFpHex, string? endpoint)
    {
        lock (_gate)
        {
            var p = Peers.FirstOrDefault(x => string.Equals(x.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));
            if (p is null)
            {
                p = new PeerInfo { DeviceId = deviceId };
                Peers.Add(p);
            }
            if (!string.IsNullOrEmpty(name)) p.Name = name;
            if (!string.IsNullOrEmpty(certFpHex)) p.CertFpHex = certFpHex;
            if (!string.IsNullOrEmpty(endpoint)) p.LastEndpoint = endpoint;
            p.LastSeen = DateTime.UtcNow;
            return p;
        }
    }

    public PeerInfo? Find(string deviceId)
    {
        lock (_gate) return Peers.FirstOrDefault(x => string.Equals(x.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));
    }

    public string? FingerprintOf(string deviceId) => Find(deviceId)?.CertFpHex;

    public static string FingerprintOfCert(System.Security.Cryptography.X509Certificates.X509Certificate2 cert) =>
        Convert.ToHexString(SHA256.HashData(cert.RawData));
}
