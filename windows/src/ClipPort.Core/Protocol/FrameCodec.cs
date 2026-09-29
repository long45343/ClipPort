namespace ClipPort.Core.Protocol;

/// <summary>
/// 线格式帧（docs/03-protocol-spec.md §1）：
/// magic(u16=0xC1B0) | version(u8=1) | type(u8) | seq(u32 LE) | payloadLen(u32 LE) | payload
/// </summary>
public static class FrameCodec
{
    public const ushort Magic = 0xC1B0;
    public const byte Version = 1;
    public const int HeaderSize = 12;

    public const byte Hello = 0x01;
    public const byte PairReq = 0x02;
    public const byte PairOk = 0x03;
    public const byte ClipBroadcast = 0x10;
    public const byte ReqText = 0x11;
    public const byte RespText = 0x12;
    public const byte Cancel = 0x18;
    public const byte Ping = 0x20;
    public const byte Pong = 0x21;

    public const int MaxPayload = 64 * 1024 * 1024;

    public static byte[] Encode(byte type, uint seq, ReadOnlySpan<byte> payload)
    {
        var buf = new byte[HeaderSize + payload.Length];
        buf[0] = (byte)(Magic & 0xFF);
        buf[1] = (byte)(Magic >> 8);
        buf[2] = Version;
        buf[3] = type;
        BitConverter.GetBytes(seq).AsSpan().CopyTo(buf.AsSpan(4));
        BitConverter.GetBytes(payload.Length).AsSpan().CopyTo(buf.AsSpan(8));
        payload.CopyTo(buf.AsSpan(HeaderSize));
        return buf;
    }

    /// <summary>尝试从缓冲区解析一帧。成功时返回 true 并给出 consumed；数据不足返回 false（consumed=0）；坏帧抛 InvalidDataException。</summary>
    public static bool TryDecode(ReadOnlySpan<byte> buffer, out byte type, out uint seq, out byte[] payload, out int consumed)
    {
        type = 0; seq = 0; payload = Array.Empty<byte>(); consumed = 0;
        if (buffer.Length < HeaderSize) return false;
        if (buffer[0] != (Magic & 0xFF) || buffer[1] != (Magic >> 8))
            throw new InvalidDataException("bad magic");
        if (buffer[2] != Version)
            throw new InvalidDataException($"bad version {buffer[2]}");
        int len = BitConverter.ToInt32(buffer.Slice(8, 4));
        if (len < 0 || len > MaxPayload)
            throw new InvalidDataException($"bad payload length {len}");
        if (buffer.Length < HeaderSize + len) return false;
        type = buffer[3];
        seq = BitConverter.ToUInt32(buffer.Slice(4, 4));
        payload = buffer.Slice(HeaderSize, len).ToArray();
        consumed = HeaderSize + len;
        return true;
    }
}
