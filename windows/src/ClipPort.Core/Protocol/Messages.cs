using System.Text;

namespace ClipPort.Core.Protocol;

public static class Mime
{
    public const uint Text = 0;
    public const uint Html = 1;
    public const uint BothTextHtml = 2;
    public const uint ImagePng = 3;
    public const uint File = 4;
}

/// <summary>CLIP_BROADCAST 负载：ClipBroadcast（docs/03-protocol-spec.md §3）。</summary>
public sealed class ClipBroadcast
{
    public byte[] DeviceId = Array.Empty<byte>();
    public uint Seq;
    public bool NeedChannel;
    public List<uint> MimeCodes = new();
    public ClipInline? Inline;

    public byte[] Encode()
    {
        var o = new List<byte>(64);
        Proto.WriteBytes(o, 1, DeviceId);
        Proto.WriteUint(o, 2, Seq);
        Proto.WriteBool(o, 3, NeedChannel);
        foreach (var m in MimeCodes) Proto.WriteUint(o, 4, m, omitZero: false);
        if (Inline != null) Proto.WriteMessage(o, 5, Inline.Encode());
        return o.ToArray();
    }

    public static ClipBroadcast Decode(byte[] payload)
    {
        var r = new Proto.Reader(payload);
        var m = new ClipBroadcast();
        while (r.ReadTag(out var f, out var wt))
        {
            switch (f)
            {
                case 1: m.DeviceId = r.ReadBytes(); break;
                case 2: m.Seq = r.ReadUint32(); break;
                case 3: m.NeedChannel = r.ReadBool(); break;
                case 4: m.MimeCodes.Add(r.ReadUint32()); break;
                case 5: m.Inline = ClipInline.Decode(r.ReadRawMessage()); break;
                default: r.Skip(wt); break;
            }
        }
        return m;
    }
}

public sealed class ClipInline
{
    public string? Text;
    public string? Html;
    public byte[]? ImagePng;
    public List<InlineItem> Items = new();

    public byte[] Encode()
    {
        var o = new List<byte>();
        Proto.WriteString(o, 1, Text);
        Proto.WriteString(o, 2, Html);
        Proto.WriteBytes(o, 3, ImagePng);
        foreach (var it in Items) Proto.WriteMessage(o, 4, it.Encode());
        return o.ToArray();
    }

    public static ClipInline Decode(byte[] payload)
    {
        var r = new Proto.Reader(payload);
        var m = new ClipInline();
        while (r.ReadTag(out var f, out var wt))
        {
            switch (f)
            {
                case 1: m.Text = r.ReadString(); break;
                case 2: m.Html = r.ReadString(); break;
                case 3: m.ImagePng = r.ReadBytes(); break;
                case 4: m.Items.Add(InlineItem.Decode(r.ReadRawMessage())); break;
                default: r.Skip(wt); break;
            }
        }
        return m;
    }
}

public sealed class InlineItem
{
    public uint Index;
    public uint Mime;
    public string? Text;
    public string? Html;

    public byte[] Encode()
    {
        var o = new List<byte>();
        Proto.WriteUint(o, 1, Index);
        Proto.WriteUint(o, 2, Mime);
        Proto.WriteString(o, 3, Text);
        Proto.WriteString(o, 4, Html);
        return o.ToArray();
    }

    public static InlineItem Decode(byte[] payload)
    {
        var r = new Proto.Reader(payload);
        var m = new InlineItem();
        while (r.ReadTag(out var f, out var wt))
        {
            switch (f)
            {
                case 1: m.Index = r.ReadUint32(); break;
                case 2: m.Mime = r.ReadUint32(); break;
                case 3: m.Text = r.ReadString(); break;
                case 4: m.Html = r.ReadString(); break;
                default: r.Skip(wt); break;
            }
        }
        return m;
    }
}

/// <summary>HELLO：设备名(1) 设备类型(2: 0=pc,1=phone) 协议版本(3) 设备ID(4)。</summary>
public sealed class Hello
{
    public string Name = "";
    public int DeviceType;
    public int ProtoVer = 1;
    public byte[] DeviceId = Array.Empty<byte>();

    public byte[] Encode()
    {
        var o = new List<byte>();
        Proto.WriteString(o, 1, Name);
        Proto.WriteUint(o, 2, (uint)DeviceType);
        Proto.WriteUint(o, 3, (uint)ProtoVer);
        Proto.WriteBytes(o, 4, DeviceId);
        return o.ToArray();
    }

    public static Hello Decode(byte[] payload)
    {
        var r = new Proto.Reader(payload);
        var m = new Hello();
        while (r.ReadTag(out var f, out var wt))
        {
            switch (f)
            {
                case 1: m.Name = r.ReadString(); break;
                case 2: m.DeviceType = (int)r.ReadUint32(); break;
                case 3: m.ProtoVer = (int)r.ReadUint32(); break;
                case 4: m.DeviceId = r.ReadBytes(); break;
                default: r.Skip(wt); break;
            }
        }
        return m;
    }
}

/// <summary>PAIR_REQ{sha256(code)=1} / PAIR_OK{ok=1, cert_fp=2}。</summary>
public static class Pairing
{
    /// <summary>PAIR_REQ{codeHash=1, joinerFp=2(二期: 加入方证书指纹, 可空)}。</summary>
    public static byte[] EncodePairReq(byte[] codeHash, byte[]? joinerFp = null)
    {
        var o = new List<byte>();
        Proto.WriteBytes(o, 1, codeHash);
        Proto.WriteBytes(o, 2, joinerFp);
        return o.ToArray();
    }

    public static (byte[] CodeHash, byte[]? JoinerFp) DecodePairReq(byte[] payload)
    {
        byte[] hash = Array.Empty<byte>(); byte[]? jfp = null;
        var r = new Proto.Reader(payload);
        while (r.ReadTag(out var f, out var wt))
        {
            switch (f) { case 1: hash = r.ReadBytes(); break; case 2: jfp = r.ReadBytes(); break; default: r.Skip(wt); break; }
        }
        return (hash, jfp);
    }

    /// <summary>PAIR_OK{ok=1, certFp=2, name=3(二期)}。</summary>
    public static byte[] EncodePairOk(bool ok, byte[] certFp, string? name = null)
    {
        var o = new List<byte>();
        Proto.WriteBool(o, 1, ok);
        Proto.WriteBytes(o, 2, certFp);
        Proto.WriteString(o, 3, name);
        return o.ToArray();
    }

    public static (bool Ok, byte[] Fp, string Name) DecodePairOk(byte[] payload)
    {
        bool ok = false; byte[] fp = Array.Empty<byte>(); string name = "";
        var r = new Proto.Reader(payload);
        while (r.ReadTag(out var f, out var wt))
        {
            switch (f) { case 1: ok = r.ReadBool(); break; case 2: fp = r.ReadBytes(); break; case 3: name = r.ReadString(); break; default: r.Skip(wt); break; }
        }
        return (ok, fp, name);
    }
}

public sealed class TextRequest
{
    public uint Seq;
    public uint ItemId;
    public uint Mime;

    public byte[] Encode()
    {
        var o = new List<byte>();
        Proto.WriteUint(o, 1, Seq);
        Proto.WriteUint(o, 2, ItemId);
        Proto.WriteUint(o, 3, Mime);
        return o.ToArray();
    }

    public static TextRequest Decode(byte[] payload)
    {
        var r = new Proto.Reader(payload);
        var m = new TextRequest();
        while (r.ReadTag(out var f, out var wt))
        {
            switch (f) { case 1: m.Seq = r.ReadUint32(); break; case 2: m.ItemId = r.ReadUint32(); break; case 3: m.Mime = r.ReadUint32(); break; default: r.Skip(wt); break; }
        }
        return m;
    }
}

public sealed class TextResponse
{
    public uint Seq;
    public uint ItemId;
    public uint Mime;
    public uint Status; // 0=ok 1=fail
    public byte[] Content = Array.Empty<byte>();

    public byte[] Encode()
    {
        var o = new List<byte>();
        Proto.WriteUint(o, 1, Seq);
        Proto.WriteUint(o, 2, ItemId);
        Proto.WriteUint(o, 3, Mime);
        Proto.WriteUint(o, 4, Status);
        Proto.WriteBytes(o, 5, Content);
        return o.ToArray();
    }

    public static TextResponse Decode(byte[] payload)
    {
        var r = new Proto.Reader(payload);
        var m = new TextResponse();
        while (r.ReadTag(out var f, out var wt))
        {
            switch (f)
            {
                case 1: m.Seq = r.ReadUint32(); break;
                case 2: m.ItemId = r.ReadUint32(); break;
                case 3: m.Mime = r.ReadUint32(); break;
                case 4: m.Status = r.ReadUint32(); break;
                case 5: m.Content = r.ReadBytes(); break;
                default: r.Skip(wt); break;
            }
        }
        return m;
    }
}

/// <summary>独立文件共享元信息（D-26=A：与剪贴板解耦）</summary>
public sealed class FileShareMeta
{
    public string FileName = "";
    public ulong FileSize;
    public byte[] Sha256 = Array.Empty<byte>();

    public byte[] Encode()
    {
        var o = new List<byte>();
        Proto.WriteString(o, 1, FileName);
        Proto.WriteUint(o, 2, (uint)(FileSize & 0xFFFFFFFF));
        Proto.WriteUint(o, 3, (uint)(FileSize >> 32));
        Proto.WriteBytes(o, 4, Sha256);
        return o.ToArray();
    }

    public static FileShareMeta Decode(byte[] payload)
    {
        var r = new Proto.Reader(payload);
        var m = new FileShareMeta();
        uint low = 0, high = 0;
        while (r.ReadTag(out var f, out var wt))
        {
            switch (f)
            {
                case 1: m.FileName = r.ReadString(); break;
                case 2: low = r.ReadUint32(); break;
                case 3: high = r.ReadUint32(); break;
                case 4: m.Sha256 = r.ReadBytes(); break;
                default: r.Skip(wt); break;
            }
        }
        m.FileSize = ((ulong)high << 32) | low;
        return m;
    }
}

/// <summary>独立文件共享 64KB 分块数据</summary>
public sealed class FileShareChunk
{
    public string FileName = "";
    public ulong Offset;
    public bool IsLast;
    public byte[] Data = Array.Empty<byte>();

    public byte[] Encode()
    {
        var o = new List<byte>();
        Proto.WriteString(o, 1, FileName);
        Proto.WriteUint(o, 2, (uint)(Offset & 0xFFFFFFFF));
        Proto.WriteUint(o, 3, (uint)(Offset >> 32));
        Proto.WriteBool(o, 4, IsLast);
        Proto.WriteBytes(o, 5, Data);
        return o.ToArray();
    }

    public static FileShareChunk Decode(byte[] payload)
    {
        var r = new Proto.Reader(payload);
        var m = new FileShareChunk();
        uint low = 0, high = 0;
        while (r.ReadTag(out var f, out var wt))
        {
            switch (f)
            {
                case 1: m.FileName = r.ReadString(); break;
                case 2: low = r.ReadUint32(); break;
                case 3: high = r.ReadUint32(); break;
                case 4: m.IsLast = r.ReadBool(); break;
                case 5: m.Data = r.ReadBytes(); break;
                default: r.Skip(wt); break;
            }
        }
        m.Offset = ((ulong)high << 32) | low;
        return m;
    }
}
