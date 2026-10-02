using System.Security.Cryptography;
using System.Text.Json;

namespace ClipPort.Core.Net;

/// <summary>
/// 配置持久化（%LOCALAPPDATA%\ClipPort\config.json）。
/// 纯对等模式（D-35=B + D-37）：无旧数据兼容路径；配对事实源为 PeerStore.Paired。
/// </summary>
public sealed class AppConfig
{
    public string DeviceId { get; set; } = "";
    public string DeviceName { get; set; } = Environment.MachineName;
    public ushort TcpPort { get; set; } = 47190;
    public bool SyncEnabled { get; set; } = true;
    /// <summary>配对码（内存态用，不持久化明文；持久化仅存 SHA-256 用于断线重配对校验）。</summary>
    public byte[]? PairingCodeHash { get; set; }
    public bool PairingOpen { get; set; }

    private static string Path_ => Path.Combine(CertManager.StoreDir, "config.json");

    public static AppConfig Load()
    {
        try
        {
            if (File.Exists(Path_))
                return JsonSerializer.Deserialize(File.ReadAllText(Path_), ClipPortJsonContext.Default.AppConfig) ?? new AppConfig();
        }
        catch { }
        var cfg = new AppConfig { DeviceId = Guid.NewGuid().ToString("N") };
        cfg.Save();
        return cfg;
    }

    public void Save()
    {
        Directory.CreateDirectory(CertManager.StoreDir);
        File.WriteAllText(Path_, JsonSerializer.Serialize(this, ClipPortJsonContext.Default.AppConfig));
    }

    public static byte[] CodeHash(string code) => SHA256.HashData(System.Text.Encoding.UTF8.GetBytes("clipport:" + code.Trim()));
}
