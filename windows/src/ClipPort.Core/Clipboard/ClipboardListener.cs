using System.Runtime.InteropServices;

namespace ClipPort.Core.Clipboard;

/// <summary>消息驱动剪贴板监听（spec §1.1）：AddClipboardFormatListener + WM_CLIPBOARDUPDATE。</summary>
public sealed class ClipboardListener : IDisposable
{
    private IntPtr _hwnd;
    public event Action? ClipChanged;

    public void Start()
    {
        _hwnd = MessageWindowHost.CreateWindow("ClipPortClipWnd", WndProc);
        if (!Native.AddClipboardFormatListener(_hwnd))
            throw new InvalidOperationException($"AddClipboardFormatListener failed: {Marshal.GetLastWin32Error()}");
    }

    private IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == Native.WM_CLIPBOARDUPDATE)
        {
            try { ClipChanged?.Invoke(); } catch { /* 监听器异常不得破坏消息泵 */ }
        }
        return Native.DefWindowProcW(hwnd, msg, wParam, lParam);
    }

    public void Dispose()
    {
        if (_hwnd != IntPtr.Zero)
        {
            Native.RemoveClipboardFormatListener(_hwnd);
            MessageWindowHost.DestroyWindow(_hwnd);
            _hwnd = IntPtr.Zero;
        }
    }
}
