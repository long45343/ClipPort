using ClipPort.Core.Protocol;
using Xunit;

namespace ClipPort.Core.Tests;

public class FrameCodecTests
{
    [Fact]
    public void EncodeAndDecode_RoundTrip_Success()
    {
        byte type = FrameCodec.ClipBroadcast;
        uint seq = 1024;
        byte[] payload = "Hello ClipPort P2P"u8.ToArray();

        byte[] frame = FrameCodec.Encode(type, seq, payload);
        Assert.Equal(FrameCodec.HeaderSize + payload.Length, frame.Length);

        bool success = FrameCodec.TryDecode(frame, out var decodedType, out var decodedSeq, out var decodedPayload, out var consumed);

        Assert.True(success);
        Assert.Equal(type, decodedType);
        Assert.Equal(seq, decodedSeq);
        Assert.Equal(payload, decodedPayload);
        Assert.Equal(frame.Length, consumed);
    }

    [Fact]
    public void Decode_InsufficientHeader_ReturnsFalse()
    {
        byte[] buffer = new byte[FrameCodec.HeaderSize - 1];
        bool success = FrameCodec.TryDecode(buffer, out _, out _, out _, out var consumed);

        Assert.False(success);
        Assert.Equal(0, consumed);
    }

    [Fact]
    public void Decode_InsufficientPayload_ReturnsFalse()
    {
        byte[] frame = FrameCodec.Encode(FrameCodec.Ping, 1, new byte[20]);
        // 截断最后 5 字节
        byte[] truncated = frame[..^5];

        bool success = FrameCodec.TryDecode(truncated, out _, out _, out _, out var consumed);

        Assert.False(success);
        Assert.Equal(0, consumed);
    }

    [Fact]
    public void Decode_BadMagic_ThrowsInvalidDataException()
    {
        byte[] frame = FrameCodec.Encode(FrameCodec.Ping, 1, Array.Empty<byte>());
        frame[0] = 0x00; // 篡改 magic

        Assert.Throws<InvalidDataException>(() =>
            FrameCodec.TryDecode(frame, out _, out _, out _, out _));
    }

    [Fact]
    public void Decode_BadVersion_ThrowsInvalidDataException()
    {
        byte[] frame = FrameCodec.Encode(FrameCodec.Ping, 1, Array.Empty<byte>());
        frame[2] = 99; // 篡改版本号

        Assert.Throws<InvalidDataException>(() =>
            FrameCodec.TryDecode(frame, out _, out _, out _, out _));
    }

    [Fact]
    public void Decode_ConsecutiveFrames_SuccessiveConsumed()
    {
        byte[] frame1 = FrameCodec.Encode(FrameCodec.Ping, 10, "First"u8);
        byte[] frame2 = FrameCodec.Encode(FrameCodec.Pong, 20, "Second"u8);

        byte[] combined = new byte[frame1.Length + frame2.Length];
        frame1.CopyTo(combined, 0);
        frame2.CopyTo(combined, frame1.Length);

        // 解析第一帧
        bool ok1 = FrameCodec.TryDecode(combined, out var type1, out var seq1, out var p1, out var consumed1);
        Assert.True(ok1);
        Assert.Equal(FrameCodec.Ping, type1);
        Assert.Equal(10u, seq1);
        Assert.Equal("First"u8.ToArray(), p1);
        Assert.Equal(frame1.Length, consumed1);

        // 解析第二帧
        bool ok2 = FrameCodec.TryDecode(combined.AsSpan(consumed1), out var type2, out var seq2, out var p2, out var consumed2);
        Assert.True(ok2);
        Assert.Equal(FrameCodec.Pong, type2);
        Assert.Equal(20u, seq2);
        Assert.Equal("Second"u8.ToArray(), p2);
        Assert.Equal(frame2.Length, consumed2);
    }
}
