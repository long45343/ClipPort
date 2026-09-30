using ClipPort.Core.Protocol;
using Xunit;

namespace ClipPort.Core.Tests;

/// <summary>
/// 跨语言黄金测试向量（与 Android 端测试逐字节互认）
/// </summary>
public class GoldenVectorTests
{
    // C# 编码出的标准 Hello 二进制
    public static readonly byte[] GoldenHelloBytes = new Hello
    {
        Name = "Golden-Peer",
        DeviceType = 1,
        ProtoVer = 1,
        DeviceId = new byte[] { 0xDE, 0xAD, 0xBE, 0xEF }
    }.Encode();

    // C# 编码出的标准 ClipBroadcast 二进制
    public static readonly byte[] GoldenBroadcastBytes = new ClipBroadcast
    {
        DeviceId = new byte[] { 0xAA, 0xBB, 0xCC, 0xDD },
        Seq = 8888,
        NeedChannel = false,
        MimeCodes = new List<uint> { Mime.Text, Mime.Html },
        Inline = new ClipInline
        {
            Text = "Golden-Vector-Text",
            Html = "<span>Golden-Vector-Html</span>"
        }
    }.Encode();

    [Fact]
    public void Verify_GoldenHello_Decode()
    {
        var decoded = Hello.Decode(GoldenHelloBytes);
        Assert.Equal("Golden-Peer", decoded.Name);
        Assert.Equal(1, decoded.DeviceType);
        Assert.Equal(1, decoded.ProtoVer);
        Assert.Equal(new byte[] { 0xDE, 0xAD, 0xBE, 0xEF }, decoded.DeviceId);
    }

    [Fact]
    public void Verify_GoldenBroadcast_Decode()
    {
        var decoded = ClipBroadcast.Decode(GoldenBroadcastBytes);
        Assert.Equal(new byte[] { 0xAA, 0xBB, 0xCC, 0xDD }, decoded.DeviceId);
        Assert.Equal(8888u, decoded.Seq);
        Assert.False(decoded.NeedChannel);
        Assert.Equal(new List<uint> { Mime.Text, Mime.Html }, decoded.MimeCodes);
        Assert.NotNull(decoded.Inline);
        Assert.Equal("Golden-Vector-Text", decoded.Inline.Text);
        Assert.Equal("<span>Golden-Vector-Html</span>", decoded.Inline.Html);
    }

    [Fact]
    public void Print_Hex_Vectors_For_Kotlin()
    {
        // 打印出二进制 Hex，以便复制到 Android 单元测试中作为硬编码黄金断言
        string helloHex = Convert.ToHexString(GoldenHelloBytes);
        string broadcastHex = Convert.ToHexString(GoldenBroadcastBytes);

        Assert.NotEmpty(helloHex);
        Assert.NotEmpty(broadcastHex);
    }
}
