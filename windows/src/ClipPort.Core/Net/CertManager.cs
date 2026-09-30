using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace ClipPort.Core.Net;

/// <summary>
/// 自签证书管理（D-03 配对码+TLS 证书 PIN）：首次运行生成 RSA-2048 自签证书，
/// 私钥以 DPAPI(本地机器) 加密存文件；对外提供 SHA-256 指纹。
/// </summary>
public static class CertManager
{
    public static string StoreDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ClipPort");

    private static string PfxPath => Path.Combine(StoreDir, "server.pfx");

    public static X509Certificate2 GetOrCreate()
    {
        Directory.CreateDirectory(StoreDir);
        if (File.Exists(PfxPath))
        {
            try
            {
                return new X509Certificate2(PfxPath, ReadSecret(),
                    X509KeyStorageFlags.Exportable | X509KeyStorageFlags.MachineKeySet);
            }
            catch { File.Delete(PfxPath); }
        }
        var cert = CreateSelfSigned();
        File.WriteAllBytes(PfxPath, cert.Export(X509ContentType.Pfx, WriteSecret()));
        return cert;
    }

    /// <summary>证书指纹 = SHA-256( DER )。注意 GetCertHash() 返回的是原始 DER 而非哈希,
    /// 此前两端定义不一致(手机按 SHA256(der) 比对)导致指纹固定永远失败。</summary>
    public static byte[] Fingerprint(X509Certificate2 cert) => System.Security.Cryptography.SHA256.HashData(cert.RawData);

    private static X509Certificate2 CreateSelfSigned()
    {
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest("CN=ClipPort", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(true, false, 0, true));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, true));
        var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        return new X509Certificate2(cert.Export(X509ContentType.Pfx), (string?)null,
            X509KeyStorageFlags.Exportable | X509KeyStorageFlags.MachineKeySet);
    }

    private static string SecretPath => Path.Combine(StoreDir, "server.key.bin");

    private static string WriteSecret()
    {
        var s = Guid.NewGuid().ToString("N");
        File.WriteAllText(SecretPath, Protected(s));
        return s;
    }

    private static string ReadSecret() => Unprotect(File.ReadAllText(SecretPath));

    private static string Protected(string s) => Convert.ToBase64String(
        System.Security.Cryptography.ProtectedData.Protect(System.Text.Encoding.UTF8.GetBytes(s), null, System.Security.Cryptography.DataProtectionScope.LocalMachine));

    private static string Unprotect(string b64) => System.Text.Encoding.UTF8.GetString(
        System.Security.Cryptography.ProtectedData.Unprotect(Convert.FromBase64String(b64), null, System.Security.Cryptography.DataProtectionScope.LocalMachine));
}
