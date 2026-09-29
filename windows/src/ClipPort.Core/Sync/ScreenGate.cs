using System.Runtime.InteropServices;
using ClipPort.Core.Clipboard;
using static ClipPort.Core.Clipboard.Native;

namespace ClipPort.Core.Sync;

/// <summary>
/// PC 端锁屏门（D-16：锁屏缓存、解锁补写，两端统一策略）。
/// WTSQuerySessionInformation 查询锁态 + WM_WTSSESSION_CHANGE 解锁事件。
/// </summary>
public sealed class ScreenGate : IDisposable
{
    private const uint WM_WTSSESSION_CHANGE = 0x02B1;
    private const int NOTIFY_FOR_THIS_SESSION = 0;
    private const int WTS_SESSION_UNLOCK = 0x8;
    private const int WTS_SESSION_LOCK = 0x7;

    [DllImport("wtsapi32.dll")]
    private static extern bool WTSRegisterSessionNotification(IntPtr hwnd, int flags);

    [DllImport("wtsapi32.dll")]
    private static extern bool WTSUnRegisterSessionNotification(IntPtr hwnd);

    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSQuerySessionInformationW(IntPtr server, int sessionId, int infoClass, out IntPtr buffer, out int bytes);

    [DllImport("wtsapi32.dll")]
    private static extern void WTSFreeMemory(IntPtr mem);

    private IntPtr _hwnd;
    public event Action? Unlocked;

    public void Start()
    {
        _hwnd = MessageWindowHost.CreateWindow("ClipPortSessWnd", WndProc);
        WTSRegisterSessionNotification(_hwnd, NOTIFY_FOR_THIS_SESSION);
    }

    private IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == WM_WTSSESSION_CHANGE && lParam.ToInt32() == WTS_SESSION_UNLOCK)
        {
            try { Unlocked?.Invoke(); } catch { }
        }
        return Native.DefWindowProcW(hwnd, msg, wParam, lParam);
    }

    /// <summary>当前会话是否锁屏（WTS_INFO_CLASS 25 = SessionInfoEx，flags bit0 = SessionIsLocked）。</summary>
    public static bool IsLocked()
    {
        // WTS_CURRENT_SERVER_HANDLE = IntPtr.Zero, WTS_CURRENT_SESSION = -1, class 25
        if (!WTSQuerySessionInformationW(IntPtr.Zero, -1, 25, out var buf, out var _)) return false;
        try
        {
            // struct WTSINFOW { ULONG SessionId; ULONG State; ... } SessionFlags 在 offset 12 (ULONG)
            int flags = Marshal.ReadInt32(buf, 12);
            return (flags & 1) != 0;
        }
        finally { WTSFreeMemory(buf); }
    }

    public void Dispose()
    {
        if (_hwnd != IntPtr.Zero)
        {
            WTSUnRegisterSessionNotification(_hwnd);
            MessageWindowHost.DestroyWindow(_hwnd);
            _hwnd = IntPtr.Zero;
        }
    }
}
