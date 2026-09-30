using Microsoft.Win32;

namespace ClipPort.Core.Util;

/// <summary>
/// Windows 开机自启管理器（D-21=A 决策：写入 HKCU Run 注册表，带 --minimized 参数）
/// </summary>
public static class AutoStartManager
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string AppValueName = "ClipPort";

    /// <summary>
    /// 检查 HKCU\Software\Microsoft\Windows\CurrentVersion\Run 是否已配置 ClipPort 开机自启
    /// </summary>
    public static bool IsAutoStartEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, false);
            var val = key?.GetValue(AppValueName) as string;
            return !string.IsNullOrWhiteSpace(val);
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// 设置开机自启（写入或删除 HKCU Run 项，配合 --minimized 启动参数静默启动至托盘）
    /// </summary>
    public static bool SetAutoStart(bool enable)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, true);
            if (key == null) return false;

            if (enable)
            {
                var exePath = Environment.ProcessPath;
                if (string.IsNullOrEmpty(exePath)) return false;
                key.SetValue(AppValueName, $"\"{exePath}\" --minimized");
            }
            else
            {
                key.DeleteValue(AppValueName, false);
            }
            return true;
        }
        catch
        {
            return false;
        }
    }
}
