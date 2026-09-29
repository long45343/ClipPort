using System.Drawing;
using System.Runtime.InteropServices;
using ClipPort.Core.Clipboard;

namespace ClipPort.App;

/// <summary>托盘图标（零依赖 Shell_NotifyIcon 实现；spec §5）。</summary>
public sealed class TrayIcon : IDisposable
{
    private const uint WM_APP_TRAY = 0x8000; // WM_APP
    private const uint NIM_ADD = 0, NIM_DELETE = 2;
    private const uint NIF_MESSAGE = 1, NIF_ICON = 2, NIF_TIP = 4, NIF_INFO = 0x10;

    public event Action? Click;

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
        }
        return MessageWindowHostDefWindow(hwnd, msg, wParam, lParam);
    }

    [DllImport("user32.dll")]
    private static extern IntPtr DefWindowProcW(IntPtr hwnd, uint msg, IntPtr wp, IntPtr lp);

    private static IntPtr MessageWindowHostDefWindow(IntPtr h, uint m, IntPtr w, IntPtr l) => DefWindowProcW(h, m, w, l);

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
        if (!Shell_NotifyIconW(op, ref nid)) { /* 图标失败不致命 */ }
    }

    public void ShowBalloon(string title, string info) => Update(NIM_ADD, NIF_INFO, "", info, title);

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
