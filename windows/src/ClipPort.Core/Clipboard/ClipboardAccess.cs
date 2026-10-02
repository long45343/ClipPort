using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;

namespace ClipPort.Core.Clipboard;

/// <summary>剪贴板快照（首版：文本/HTML/图片，D-06）。</summary>
public sealed class ClipboardSnapshot
{
    public string? Text;
    public string? Html;
    public byte[]? ImagePng;
    public bool IsEmpty => Text is null && Html is null && ImagePng is null;

    /// <summary>内容指纹比对（第 4 道过滤核心，D-07-B"内容没变不重发"）：三元组完全一致视为同一内容。</summary>
    public bool ContentEquals(ClipboardSnapshot? other) =>
        other is not null &&
        Text == other.Text && Html == other.Html &&
        ((ImagePng is null && other.ImagePng is null) ||
         (ImagePng is not null && other.ImagePng is not null && ImagePng.AsSpan().SequenceEqual(other.ImagePng)));
}

/// <summary>
/// 读取/写入系统剪贴板（spec §1.2/1.3）。
/// 读取带 3 次重试（50ms 间隔，对齐小米重试策略）；写入后写 ClipPort.Self 私有格式防回环（writeSelfLabel）。
/// 注意：ReadAll 全程持剪贴板锁完成，避免两次 Open 之间内容被改。
/// </summary>
public static class ClipboardAccess
{
    public static readonly uint SelfFormat = Native.RegisterClipboardFormatW("ClipPort.Self");
    public static readonly uint PngFormat = Native.RegisterClipboardFormatW("PNG");
    private static readonly uint HtmlFormat = Native.RegisterClipboardFormatW("HTML Format");
    private static readonly byte[] PngMagic = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];

    private static bool OpenRetry(int retries = 3)
    {
        for (int i = 0; i < retries; i++)
        {
            if (Native.OpenClipboard(IntPtr.Zero)) return true;
            Thread.Sleep(50);
        }
        return false;
    }

    /// <summary>读当前剪贴板为快照（读取阶段持锁完成）。</summary>
    public static ClipboardSnapshot ReadAll()
    {
        var snap = new ClipboardSnapshot();
        if (!OpenRetry()) return snap;
        try
        {
            // D-25=A：检测常规文件（CF_HDROP）。若包含文件且没有图片格式，严格静默忽略
            bool hasDrop = Native.GetClipboardData(Native.CF_HDROP) != IntPtr.Zero;
            bool hasImage = Native.GetClipboardData(PngFormat) != IntPtr.Zero ||
                            Native.GetClipboardData(Native.CF_DIB) != IntPtr.Zero ||
                            Native.GetClipboardData(Native.CF_DIBV5) != IntPtr.Zero ||
                            Native.GetClipboardData(Native.CF_BITMAP) != IntPtr.Zero;
            if (hasDrop && !hasImage)
            {
                // 用户复制的是纯文件（视频/压缩包/安装包等），直接静默忽略
                return snap;
            }

            var td = GetDataNoOpen(Native.CF_UNICODETEXT);
            if (td is { Length: >= 2 }) snap.Text = Encoding.Unicode.GetString(td).TrimEnd('\0');
            if (snap.Text is { Length: 0 }) snap.Text = null;
            snap.Html = ReadHtmlNoOpen();
            snap.ImagePng = ReadImageNoOpen();
        }
        finally { Native.CloseClipboard(); }
        return snap;
    }

    private static byte[]? GetDataNoOpen(uint fmt)
    {
        var h = Native.GetClipboardData(fmt);
        if (h == IntPtr.Zero) return null;
        var size = Native.GlobalSize(h);
        if (size == 0) return null;
        var ptr = Native.GlobalLock(h);
        if (ptr == IntPtr.Zero) return null;
        try { var buf = new byte[size]; Marshal.Copy(ptr, buf, 0, (int)size); return buf; }
        finally { Native.GlobalUnlock(h); }
    }

    /// <summary>"HTML Format" 头解析（StartHTML/EndHTML 字节偏移），spec §1.2 ReadHtml。</summary>
    private static string? ReadHtmlNoOpen()
    {
        var data = GetDataNoOpen(HtmlFormat);
        if (data is null) return null;
        var s = Encoding.ASCII.GetString(data);
        try
        {
            int start = int.Parse(ExtractHeader(s, "StartHTML:") ?? "");
            int end = int.Parse(ExtractHeader(s, "EndHTML:") ?? "");
            if (start < 0 || end <= start || end > s.Length) return null;
            return s[start..end].TrimEnd('\0');
        }
        catch { return null; }
    }

    private static string? ExtractHeader(string s, string key)
    {
        int i = s.IndexOf(key, StringComparison.Ordinal);
        if (i < 0) return null;
        int begin = i + key.Length;
        int e = begin;
        while (e < s.Length && char.IsDigit(s[e])) e++;
        return s[begin..e];
    }

    private static byte[]? ReadImageNoOpen()
    {
        // 1. 优先读取注册的 "PNG" 格式（Snipaste / 微信截图专用，完美保留 32 位 ARGB 透明度与阴影，杜绝黑底）
        var pngData = GetDataNoOpen(PngFormat);
        if (pngData is { Length: >= 8 } && pngData.AsSpan(0, 8).SequenceEqual(PngMagic))
        {
            return pngData;
        }

        // 2. 次级探测 CF_DIBV5 或 CF_DIB
        var dibData = GetDataNoOpen(Native.CF_DIBV5) ?? GetDataNoOpen(Native.CF_DIB);
        if (dibData is null || dibData.Length < 40) return null;
        try
        {
            using var bmpMs = DibToBmpFile(dibData);
            using var bmp = new System.Drawing.Bitmap(bmpMs);
            using var outMs = new MemoryStream();
            bmp.Save(outMs, ImageFormat.Png);
            return outMs.ToArray();
        }
        catch { return null; }
    }

    /// <summary>CF_DIB → BMP 文件容器（补 14 字节 BITMAPFILEHEADER）。</summary>
    private static MemoryStream DibToBmpFile(byte[] dib)
    {
        var outMs = new MemoryStream();
        uint biSize = BitConverter.ToUInt32(dib, 0);
        int bitCount = BitConverter.ToUInt16(dib, 14);
        uint clrUsed = BitConverter.ToUInt32(dib, 32);
        int paletteEntries = clrUsed != 0 ? (int)clrUsed : (bitCount <= 8 ? 1 << bitCount : 0);
        int offset = (int)biSize + paletteEntries * 4;
        outMs.Write("BM"u8);
        WriteU32(outMs, (uint)(14 + dib.Length));
        WriteU32(outMs, 0);
        WriteU32(outMs, (uint)(14 + offset));
        outMs.Write(dib);
        outMs.Position = 0;
        return outMs;
    }

    /// <summary>写入快照 + 自标记（防回环，spec §1.3 writeSelfLabel）。成功返回 true。</summary>
    public static bool Write(ClipboardSnapshot snap, long selfSeq)
    {
        if (!OpenRetry()) return false;
        try
        {
            Native.EmptyClipboard();
            if (snap.Text is not null) SetString(Native.CF_UNICODETEXT, snap.Text);
            if (snap.Html is not null) SetString(HtmlFormat, BuildHtmlFormatHeader(snap.Html));
            if (snap.ImagePng is not null)
            {
                // 同时写入 "PNG" 注册格式，确保 Office 与新版微信支持 Alpha 透明通道
                SetBytes(PngFormat, snap.ImagePng);
                try
                {
                    using var bmp = new System.Drawing.Bitmap(new MemoryStream(snap.ImagePng));
                    using var ms = new MemoryStream();
                    bmp.Save(ms, ImageFormat.Bmp);
                    if (ms.Length > 14)
                    {
                        var dib = new byte[ms.Length - 14];
                        Array.Copy(ms.GetBuffer(), 14, dib, 0, dib.Length);
                        SetBytes(Native.CF_DIB, dib);
                    }
                }
                catch { }
            }
            SetBytes(SelfFormat, BitConverter.GetBytes(selfSeq));
            return true;
        }
        finally { Native.CloseClipboard(); }
    }

    /// <summary>构造 "HTML Format" 全文（定长 10 位数字头 + fragment）。</summary>
    private static string BuildHtmlFormatHeader(string html)
    {
        const string headerFmt =
            "Version:0.9\r\nStartHTML:{0:D10}\r\nEndHTML:{1:D10}\r\nStartFragment:{2:D10}\r\nEndFragment:{3:D10}\r\n";
        string body = "<html><body><!--StartFragment-->" + html + "<!--EndFragment--></body></html>";
        string probe = string.Format(headerFmt, 0, 0, 0, 0);
        int startHtml = Encoding.ASCII.GetByteCount(probe);
        int endHtml = startHtml + Encoding.UTF8.GetByteCount(body);
        string header = string.Format(headerFmt, startHtml, endHtml, startHtml, endHtml);
        byte[] head = Encoding.ASCII.GetBytes(header);
        byte[] bodyB = Encoding.UTF8.GetBytes(body);
        var all = new byte[head.Length + bodyB.Length];
        head.CopyTo(all, 0);
        bodyB.CopyTo(all, head.Length);
        return Encoding.ASCII.GetString(all);
    }

    private static void SetString(uint fmt, string s)
    {
        var bytes = fmt == Native.CF_UNICODETEXT ? Encoding.Unicode.GetBytes(s + "\0") : Encoding.ASCII.GetBytes(s + "\0");
        SetBytes(fmt, bytes);
    }

    private static void SetBytes(uint fmt, byte[] bytes)
    {
        var h = Native.GlobalAlloc(Native.GMEM_MOVEABLE, (nuint)bytes.Length);
        if (h == IntPtr.Zero) return;
        var ptr = Native.GlobalLock(h);
        if (ptr == IntPtr.Zero) { Native.GlobalFree(h); return; }
        try { Marshal.Copy(bytes, 0, ptr, bytes.Length); }
        finally { Native.GlobalUnlock(h); }
        if (Native.SetClipboardData(fmt, h) == IntPtr.Zero) Native.GlobalFree(h);
    }

    /// <summary>检查是否自标记写入（spec SelfGate：私有格式存在即自己写的）。</summary>
    public static bool IsSelfWrite()
    {
        if (!OpenRetry(1)) return false;
        try { return GetDataNoOpen(SelfFormat) is { Length: >= 4 }; }
        finally { Native.CloseClipboard(); }
    }

    /// <summary>剪贴板是否为空（无任何格式）。</summary>
    public static bool IsEmpty()
    {
        if (!OpenRetry(1)) return true;
        try
        {
            return Native.EnumClipboardFormats(0) == 0;
        }
        finally { Native.CloseClipboard(); }
    }

    private static void WriteU32(MemoryStream s, uint v) => s.Write(BitConverter.GetBytes(v));
}
