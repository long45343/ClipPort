using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace ClipPort.Core.Net;

/// <summary>
/// 局域网网络地址与适配器探测工具（纯 .NET 基础库，零 WinRT 依赖）
/// </summary>
public static class NetworkHelper
{
    /// <summary>优先 192.168.* / 10.*（真实局域网物理网段），规避 Clash/Mihomo TUN、WSL、vEthernet 等虚拟适配器地址。</summary>
    public static IPAddress? PreferredLanIpv4() =>
        AllLanIpv4().OrderByDescending(a => a.ToString().StartsWith("192.168."))
            .ThenByDescending(a => a.ToString().StartsWith("10."))
            .FirstOrDefault();

    /// <summary>本机第一个局域网 IPv4（环回/隧道除外）。</summary>
    public static IPAddress? FirstLanIpv4() => PreferredLanIpv4() ?? AllLanIpv4().FirstOrDefault();

    public static IEnumerable<IPAddress> AllLanIpv4() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up
                        && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                        && n.NetworkInterfaceType != NetworkInterfaceType.Tunnel
                        && !n.Description.Contains("Virtual", StringComparison.OrdinalIgnoreCase)
                        && !n.Description.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase)
                        && !n.Description.Contains("Tunnel", StringComparison.OrdinalIgnoreCase)
                        && !n.Description.Contains("VPN", StringComparison.OrdinalIgnoreCase)
                        && !n.Description.Contains("TAP", StringComparison.OrdinalIgnoreCase)
                        && !n.Name.StartsWith("vEthernet", StringComparison.OrdinalIgnoreCase)
                        && !n.Name.Equals("Mihomo", StringComparison.OrdinalIgnoreCase))
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork
                        && !a.Address.ToString().StartsWith("169.254.")
                        && !a.Address.ToString().StartsWith("198.18."))
            .Select(a => a.Address);
}
