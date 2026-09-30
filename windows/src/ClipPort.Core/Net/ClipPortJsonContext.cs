using System.Text.Json.Serialization;

namespace ClipPort.Core.Net;

/// <summary>UDP 广播发现数据包实体（供源生成器静态生成序列化代码）</summary>
public sealed class UdpAnnouncePacket
{
    public string id { get; set; } = "";
    public string name { get; set; } = "";
    public int type { get; set; }
    public int port { get; set; }
    public string fp { get; set; } = "";
    public int v { get; set; } = 1;
}

/// <summary>
/// .NET 10 编译期 JSON 源生成器上下文（彻底避免反射，保证裁剪绝对安全且提升性能）
/// </summary>
[JsonSourceGenerationOptions(WriteIndented = false, DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull)]
[JsonSerializable(typeof(AppConfig))]
[JsonSerializable(typeof(PeerStore))]
[JsonSerializable(typeof(UdpAnnouncePacket))]
public partial class ClipPortJsonContext : JsonSerializerContext
{
}
