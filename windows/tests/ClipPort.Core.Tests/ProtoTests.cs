using ClipPort.Core.Protocol;
using Xunit;

namespace ClipPort.Core.Tests;

public class ProtoTests
{
    [Theory]
    [InlineData(0u)]
    [InlineData(1u)]
    [InlineData(127u)]
    [InlineData(128u)]
    [InlineData(300u)]
    [InlineData(16384u)]
    [InlineData(uint.MaxValue)]
    public void Varint_RoundTrip_Success(uint value)
    {
        var buffer = new List<byte>();
        Proto.WriteVarint(buffer, value);

        var reader = new Proto.Reader(buffer.ToArray());
        bool ok = reader.ReadVarint(out ulong decoded);

        Assert.True(ok);
        Assert.Equal(value, (uint)decoded);
    }

    [Fact]
    public void HelloMessage_RoundTrip_Success()
    {
        var original = new Hello
        {
            Name = "My-Windows-PC",
            DeviceType = 0,
            ProtoVer = 1,
            DeviceId = Convert.FromHexString("0123456789ABCDEF0123456789ABCDEF")
        };

        byte[] encoded = original.Encode();
        var decoded = Hello.Decode(encoded);

        Assert.Equal(original.Name, decoded.Name);
        Assert.Equal(original.DeviceType, decoded.DeviceType);
        Assert.Equal(original.ProtoVer, decoded.ProtoVer);
        Assert.Equal(original.DeviceId, decoded.DeviceId);
    }

    [Fact]
    public void ClipBroadcast_InlineMessage_RoundTrip_Success()
    {
        var original = new ClipBroadcast
        {
            DeviceId = new byte[] { 1, 2, 3, 4 },
            Seq = 42,
            NeedChannel = false,
            MimeCodes = new List<uint> { Mime.Text, Mime.Html },
            Inline = new ClipInline
            {
                Text = "测试文本 123",
                Html = "<p>测试 HTML</p>",
                ImagePng = new byte[] { 0x89, 0x50, 0x4E, 0x47 }
            }
        };

        byte[] encoded = original.Encode();
        var decoded = ClipBroadcast.Decode(encoded);

        Assert.Equal(original.DeviceId, decoded.DeviceId);
        Assert.Equal(original.Seq, decoded.Seq);
        Assert.False(decoded.NeedChannel);
        Assert.Equal(original.MimeCodes, decoded.MimeCodes);
        Assert.NotNull(decoded.Inline);
        Assert.Equal(original.Inline.Text, decoded.Inline.Text);
        Assert.Equal(original.Inline.Html, decoded.Inline.Html);
        Assert.Equal(original.Inline.ImagePng, decoded.Inline.ImagePng);
    }

    [Fact]
    public void FileShareMetaAndChunk_RoundTrip_Success()
    {
        var meta = new FileShareMeta
        {
            FileName = "presentation.pdf",
            FileSize = 10485760, // 10MB
            Sha256 = new byte[32]
        };
        Array.Fill(meta.Sha256, (byte)0xAB);

        byte[] metaBytes = meta.Encode();
        var decodedMeta = FileShareMeta.Decode(metaBytes);

        Assert.Equal(meta.FileName, decodedMeta.FileName);
        Assert.Equal(meta.FileSize, decodedMeta.FileSize);
        Assert.Equal(meta.Sha256, decodedMeta.Sha256);

        var chunk = new FileShareChunk
        {
            FileName = "presentation.pdf",
            Offset = 65536,
            IsLast = false,
            Data = new byte[] { 1, 2, 3, 4, 5 }
        };

        byte[] chunkBytes = chunk.Encode();
        var decodedChunk = FileShareChunk.Decode(chunkBytes);

        Assert.Equal(chunk.FileName, decodedChunk.FileName);
        Assert.Equal(chunk.Offset, decodedChunk.Offset);
        Assert.Equal(chunk.IsLast, decodedChunk.IsLast);
        Assert.Equal(chunk.Data, decodedChunk.Data);
    }

    [Fact]
    public void SkipUnknownField_DoesNotCrash()
    {
        // 构造一个带有未知 field 99 的 Protobuf 载荷
        var list = new List<byte>();
        Proto.WriteString(list, 1, "KnownName");
        Proto.WriteString(list, 99, "UnknownValueToSkip");

        var hello = Hello.Decode(list.ToArray());
        Assert.Equal("KnownName", hello.Name);
    }
}
