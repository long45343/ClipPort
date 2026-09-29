namespace ClipPort.Core.Protocol;

/// <summary>最小 Protobuf 线格式编解码（varint / length-delimited），对应 docs/03-protocol-spec.md 字段表。</summary>
public static class Proto
{
    public static void WriteVarint(List<byte> outBuf, ulong v)
    {
        while (v >= 0x80) { outBuf.Add((byte)(v | 0x80)); v >>= 7; }
        outBuf.Add((byte)v);
    }

    public static void WriteTag(List<byte> o, int field, int wireType) => WriteVarint(o, (uint)((field << 3) | wireType));

    public static void WriteUint(List<byte> o, int field, uint v)
    {
        if (v == 0) return;
        WriteTag(o, field, 0); WriteVarint(o, v);
    }

    public static void WriteBool(List<byte> o, int field, bool v)
    {
        if (!v) return;
        WriteTag(o, field, 0); WriteVarint(o, 1);
    }

    public static void WriteString(List<byte> o, int field, string? s)
    {
        if (string.IsNullOrEmpty(s)) return;
        WriteBytes(o, field, System.Text.Encoding.UTF8.GetBytes(s));
    }

    public static void WriteBytes(List<byte> o, int field, ReadOnlySpan<byte> b)
    {
        if (b.IsEmpty) return;
        WriteTag(o, field, 2); WriteVarint(o, (uint)b.Length);
        o.AddRange(b.ToArray());
    }

    public static void WriteMessage(List<byte> o, int field, ReadOnlySpan<byte> msg)
    {
        WriteTag(o, field, 2); WriteVarint(o, (uint)msg.Length);
        o.AddRange(msg.ToArray());
    }

    // ---- reader ----
    public struct Reader
    {
        private readonly byte[] _buf; private int _pos;
        public Reader(byte[] buf) { _buf = buf; _pos = 0; }
        public readonly bool Done => _pos >= _buf.Length;

        public bool ReadTag(out int field, out int wireType)
        {
            if (!ReadVarint(out var v)) { field = 0; wireType = 0; return false; }
            field = (int)(v >> 3); wireType = (int)(v & 7); return true;
        }

        public bool ReadVarint(out ulong v)
        {
            v = 0; int shift = 0;
            while (_pos < _buf.Length)
            {
                byte b = _buf[_pos++];
                v |= (ulong)(b & 0x7F) << shift;
                if ((b & 0x80) == 0) return true;
                shift += 7;
                if (shift > 63) return false;
            }
            return false;
        }

        public uint ReadUint32() { ReadVarint(out var v); return (uint)v; }
        public bool ReadBool() { ReadVarint(out var v); return v != 0; }

        public byte[] ReadBytes()
        {
            ReadVarint(out var len);
            int n = (int)len;
            if (_pos + n > _buf.Length) throw new InvalidDataException("proto: bytes overrun");
            var r = new byte[n];
            Array.Copy(_buf, _pos, r, 0, n);
            _pos += n;
            return r;
        }

        public string ReadString() => System.Text.Encoding.UTF8.GetString(ReadBytes());

        public byte[] ReadRawMessage() => ReadBytes();

        public void Skip(int wireType)
        {
            switch (wireType)
            {
                case 0: ReadVarint(out _); break;
                case 1: _pos += 8; break;
                case 2: ReadVarint(out var len); _pos += (int)len; break;
                case 5: _pos += 4; break;
                default: throw new InvalidDataException($"proto: bad wire type {wireType}");
            }
        }
    }
}
