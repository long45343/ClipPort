using ClipPort.Core.Util;
using Xunit;

namespace ClipPort.Core.Tests;

public class QrCodeHelperTests
{
    private static readonly byte[] PngHeader = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];

    [Fact]
    public void GenerateQrPngBytes_ValidUri_ReturnsValidPngBytes()
    {
        string uri = "clipport://pair?host=192.168.1.100&port=47190&code=123456&fp=abcdef1234567890&name=PC";

        byte[] pngBytes = QrCodeHelper.GenerateQrPngBytes(uri, pixelsPerModule: 4);

        Assert.NotNull(pngBytes);
        Assert.True(pngBytes.Length > 64);
        // 校验 PNG 魔数文件头
        Assert.Equal(PngHeader, pngBytes[..8]);
    }
}
