using System.Drawing;
using System.Runtime.InteropServices;
using ClipPort.Core.Clipboard;

namespace ClipPort.App;

/// <summary>托盘图标（零依赖 Shell_NotifyIcon 实现；spec §5）。左键/双击打开主窗口，右键上下文菜单。</summary>
public sealed class TrayIcon : IDisposable
{
    private const uint WM_APP_TRAY = 0x8000; // WM_APP
    private const uint NIM_ADD = 0, NIM_DELETE = 2, NIM_MODIFY = 1;
    private const uint NIF_MESSAGE = 1, NIF_ICON = 2, NIF_TIP = 4, NIF_INFO = 0x10;

    // 上下文菜单命令 ID
    private const int CMD_OPEN = 1;
    private const int CMD_SYNC = 2;
    private const int CMD_EXIT = 3;

    public event Action? Click;            // 打开主窗口（在消息线程触发，需自行 marshal 到 UI）
    public event Action? SyncToggle;       // 同步开关翻转
    public event Action? ExitRequested;    // 退出
    public Func<bool>? SyncEnabledState;   // 菜单勾选状态查询

    private readonly IntPtr _hwnd;
    private Icon _icon;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NOTIFYICONDATA
    {
        public int cbSize; public IntPtr hWnd; public uint uID; public uint uFlags;
        public uint uCallbackMessage; public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string szTip;
        public uint dwState; public uint dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string szInfo;
        public uint uVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string szInfoTitle;
        public uint dwInfoFlags;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool Shell_NotifyIconW(uint msg, ref NOTIFYICONDATA data);

    [DllImport("user32.dll")]
    private static extern IntPtr CreatePopupMenu();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool AppendMenuW(IntPtr menu, uint flags, nuint id, string text);

    [DllImport("user32.dll")]
    private static extern int TrackPopupMenu(IntPtr menu, uint flags, int x, int y, int reserved, IntPtr hwnd, IntPtr prcRect);

    [DllImport("user32.dll")]
    private static extern bool DestroyMenu(IntPtr menu);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hwnd);

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out POINT pt);

    [DllImport("user32.dll")]
    private static extern IntPtr PostMessageW(IntPtr hwnd, uint msg, IntPtr wp, IntPtr lp);

    [DllImport("user32.dll")]
    private static extern IntPtr DefWindowProcW(IntPtr hwnd, uint msg, IntPtr wp, IntPtr lp);

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X, Y; }

    private const uint MF_STRING = 0x0, MF_CHECKED = 0x8, MF_SEPARATOR = 0x800;
    private const uint TPM_RETURNCMD = 0x100, TPM_NONOTIFY = 0x80, TPM_RIGHTBUTTON = 0x2;

    public TrayIcon()
    {
        _icon = MakeIcon();
        _hwnd = MessageWindowHost.CreateWindow("ClipPortTrayWnd", WndProc);
        Update(NIM_ADD, NIF_MESSAGE | NIF_ICON | NIF_TIP, "ClipPort");
    }

    private IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == WM_APP_TRAY)
        {
            long l = lParam.ToInt64();
            if (l == 0x0202 || l == 0x0203) // WM_LBUTTONUP / WM_LBUTTONDBLCLK
            {
                try { Click?.Invoke(); } catch { }
            }
            else if (l == 0x0205) // WM_RBUTTONUP → 上下文菜单（必须在窗口所在线程 TrackPopupMenu）
            {
                ShowContextMenu(hwnd);
            }
        }
        return DefWindowProcW(hwnd, msg, wParam, lParam);
    }

    private void ShowContextMenu(IntPtr hwnd)
    {
        var menu = CreatePopupMenu();
        if (menu == IntPtr.Zero) return;
        bool sync = SyncEnabledState?.Invoke() ?? true;
        AppendMenuW(menu, MF_STRING, CMD_OPEN, "打开主窗口");
        AppendMenuW(menu, (uint)(MF_STRING | (sync ? MF_CHECKED : 0)), CMD_SYNC, "剪贴板同步");
        AppendMenuW(menu, MF_SEPARATOR, 0, "");
        AppendMenuW(menu, MF_STRING, CMD_EXIT, "退出");
        SetForegroundWindow(hwnd); // 让菜单在点击别处时正确消失
        _ = GetCursorPos(out var pt);
        int cmd = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_NONOTIFY | TPM_RIGHTBUTTON, pt.X, pt.Y, 0, hwnd, IntPtr.Zero);
        DestroyMenu(menu);
        switch (cmd)
        {
            case CMD_OPEN: try { Click?.Invoke(); } catch { } break;
            case CMD_SYNC: try { SyncToggle?.Invoke(); } catch { } break;
            case CMD_EXIT: try { ExitRequested?.Invoke(); } catch { } break;
        }
        _ = PostMessageW(hwnd, 0, IntPtr.Zero, IntPtr.Zero); // 收尾，避免菜单残留焦点问题
    }

    private void Update(uint op, uint flags, string tip, string info = "", string title = "")
    {
        var nid = new NOTIFYICONDATA
        {
            cbSize = Marshal.SizeOf<NOTIFYICONDATA>(),
            hWnd = _hwnd,
            uID = 1,
            uFlags = flags,
            uCallbackMessage = WM_APP_TRAY,
            hIcon = _icon.Handle,
            szTip = tip,
            szInfo = info,
            szInfoTitle = title,
        };
        if (!Shell_NotifyIconW(op, ref nid)) { }
    }

    /// <summary>托盘气泡提示（重用 NIM_ADD 更新提示文案）。</summary>
    public void ShowBalloon(string title, string info) => Update(NIM_MODIFY, NIF_INFO, "", info, title);

    private static Icon MakeIcon()
    {
        using var bmp = new Bitmap(16, 16);
        using (var g = Graphics.FromImage(bmp))
        {
            g.Clear(Color.Transparent);
            using var br = new SolidBrush(Color.FromArgb(0x21, 0x96, 0xF3));
            g.FillRectangle(br, 1, 1, 14, 14);
            using var font = new Font("Segoe UI", 9, FontStyle.Bold);
            g.DrawString("C", font, Brushes.White, 2, 0);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }

    public void Dispose()
    {
        Update(NIM_DELETE, 0, "");
        _icon.Dispose();
        MessageWindowHost.DestroyWindow(_hwnd);
    }
}
