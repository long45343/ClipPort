using System;
using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using ClipPort.Core.Net;
using Xunit;

namespace ClipPort.Core.Tests;

public class CertManagerTests
{
    [Fact]
    public void Test_GetOrCreate_ReturnsValidCertWithPrivateKey()
    {
        var cert = CertManager.GetOrCreate();
        Assert.NotNull(cert);
        Assert.True(cert.HasPrivateKey);
        Assert.Equal("CN=ClipPort", cert.Subject);

        var fp = CertManager.Fingerprint(cert);
        Assert.Equal(32, fp.Length);

        // 重新调用 GetOrCreate（走从文件读取流程），验证读取出的证书私钥依然有效
        var cert2 = CertManager.GetOrCreate();
        Assert.NotNull(cert2);
        Assert.True(cert2.HasPrivateKey);
        Assert.Equal(fp, CertManager.Fingerprint(cert2));
    }
}
