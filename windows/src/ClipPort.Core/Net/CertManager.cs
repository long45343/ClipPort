using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace ClipPort.Core.Net;

/// <summary>
/// 自签证书管理（D-03 配对码+TLS 证书 PIN）：首次运行生成 RSA-2048 自签 X.509 v3 证书，
/// 私钥以 DPAPI(当前用户) 加密存文件；对外提供 SHA-256 指纹。
/// 彻底移除 MachineKeySet 限制（根治普通权限下 SSPI 0x80090327“处理证书时出现未知错误”）。
/// </summary>
public static class CertManager
{
    public static string StoreDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ClipPort");

    private static string PfxPath => Path.Combine(StoreDir, "server.pfx");
    private static string SecretPath => Path.Combine(StoreDir, "server.key.bin");

    public static X509Certificate2 GetOrCreate()
    {
        Directory.CreateDirectory(StoreDir);
        if (File.Exists(PfxPath))
        {
            try
            {
                var secret = ReadSecret();
                var loaded = X509CertificateLoader.LoadPkcs12FromFile(
                    PfxPath,
                    secret,
                    X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
                if (loaded.HasPrivateKey) return loaded;
            }
            catch
            {
                try { File.Delete(PfxPath); } catch { }
            }
        }

        var (cert, pfxBytes, secretPass) = CreateSelfSigned();
        File.WriteAllBytes(PfxPath, pfxBytes);
        WriteSecret(secretPass);
        return cert;
    }

    /// <summary>证书指纹 = SHA-256(DER)，两端逐字节比对 Pin 码。</summary>
    public static byte[] Fingerprint(X509Certificate2 cert) => SHA256.HashData(cert.RawData);

    private static (X509Certificate2 Cert, byte[] PfxBytes, string Password) CreateSelfSigned()
    {
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest("CN=ClipPort", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);

        // 1. 终端实体证书（非 CA）
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));

        // 2. 密钥用途：数字签名 + 密钥加密（TLS 1.2/1.3 刚需）
        req.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));

        // 3. 增强密钥用途：服务端认证 + 客户端认证（双向通信互通）
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection
        {
            new("1.3.6.1.5.5.7.3.1"), // id-kp-serverAuth
            new("1.3.6.1.5.5.7.3.2")  // id-kp-clientAuth
        }, false));

        // 4. SAN 主题备用名称（覆盖本地与局域网 IP）
        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName("localhost");
        san.AddIpAddress(IPAddress.Loopback);
        try
        {
            foreach (var ip in NetworkHelper.AllLanIpv4()) san.AddIpAddress(ip);
        }
        catch { }
        req.CertificateExtensions.Add(san.Build());

        var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        var secret = Guid.NewGuid().ToString("N");
        var pfxBytes = cert.Export(X509ContentType.Pfx, secret);

        var loaded = X509CertificateLoader.LoadPkcs12(
            pfxBytes,
            secret,
            X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);

        return (loaded, pfxBytes, secret);
    }

    private static void WriteSecret(string s)
    {
        File.WriteAllText(SecretPath, Protected(s));
    }

    private static string ReadSecret() => Unprotect(File.ReadAllText(SecretPath));

    private static string Protected(string s) => Convert.ToBase64String(
        ProtectedData.Protect(
            System.Text.Encoding.UTF8.GetBytes(s),
            null,
            DataProtectionScope.CurrentUser));

    private static string Unprotect(string b64)
    {
        var bytes = Convert.FromBase64String(b64);
        try
        {
            // 优先 CurrentUser（标准用户级）
            return System.Text.Encoding.UTF8.GetString(
                ProtectedData.Unprotect(bytes, null, DataProtectionScope.CurrentUser));
        }
        catch
        {
            // 兜底兼容旧版 LocalMachine
            return System.Text.Encoding.UTF8.GetString(
                ProtectedData.Unprotect(bytes, null, DataProtectionScope.LocalMachine));
        }
    }
}
