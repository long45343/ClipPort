using QRCoder;

namespace ClipPort.Core.Util;

/// <summary>
/// 二维码生成工具（D-22=A 决策：轻量引入 QRCoder 库，生成 PNG 字节流）
/// </summary>
public static class QrCodeHelper
{
    /// <summary>
    /// 将文本内容编码为 PNG 格式字节数组
    /// </summary>
    public static byte[] GenerateQrPngBytes(string payload, int pixelsPerModule = 4)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        using var pngQr = new PngByteQRCode(data);
        return pngQr.GetGraphic(pixelsPerModule);
    }
}
