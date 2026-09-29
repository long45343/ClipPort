using System.Collections.Concurrent;
using System.Runtime.InteropServices;

namespace ClipPort.Core.Clipboard;

/// <summary>
/// 进程级消息窗口宿主：单一后台线程 + GetMessage 泵，支持注册多个 message-only 窗口
/// （剪贴板监听与托盘图标共用，spec §1.1 / §5）。Win32 窗口有线程亲和性，创建必须入泵线程。
/// </summary>
public static class MessageWindowHost
{
    public delegate IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

    private static readonly object Gate = new();
    private static Thread? _pump;
    private static readonly BlockingCollection<Action> Ops = new();
    private static readonly ManualResetEventSlim PumpReady = new(false);

    public static void EnsurePump()
    {
        lock (Gate)
        {
            if (_pump is { IsAlive: true }) return;
            PumpReady.Reset();
            _pump = new Thread(() =>
            {
                PumpReady.Set();
                Pump.Run(Ops);
            })
            { IsBackground = true, Name = "clipport-msgwnd" };
            _pump.Start();
            PumpReady.Wait(TimeSpan.FromSeconds(5));
        }
    }

    public static IntPtr CreateWindow(string className, WndProc proc)
    {
        EnsurePump();
        var tcs = new TaskCompletionSource<IntPtr>(TaskCreationOptions.RunContinuationsAsynchronously);
        Ops.Add(() =>
        {
            try { tcs.TrySetResult(CreateOnPumpThread(className, proc)); }
            catch (Exception ex) { tcs.TrySetException(ex); }
        });
        return tcs.Task.GetAwaiter().GetResult();
    }

    public static void DestroyWindow(IntPtr hwnd) => Ops.Add(() =>
    {
        try { if (hwnd != IntPtr.Zero) Native.DestroyWindow(hwnd); } catch { }
    });

    private static IntPtr CreateOnPumpThread(string className, WndProc proc)
    {
        // 保活委托防 GC
        KeepAliveProcs.Add(proc);
        var wc = new Native.WNDCLASS
        {
            lpfnWndProc = Marshal.GetFunctionPointerForDelegate(proc),
            lpszClassName = className,
            hInstance = Marshal.GetHINSTANCE(typeof(MessageWindowHost).Module),
        };
        _ = Native.RegisterClassW(ref wc);
        // 同名类已注册（重复启动）→ RegisterClass 失败也继续，CreateWindow 仍可用
        var hwnd = Native.CreateWindowExW(0, className, "", 0x80000000u /*WS_POPUP*/, 0, 0, 0, 0,
            new IntPtr(-3) /*HWND_MESSAGE*/, IntPtr.Zero, wc.hInstance, IntPtr.Zero);
        if (hwnd == IntPtr.Zero)
            throw new InvalidOperationException($"CreateWindow({className}) failed: {Marshal.GetLastWin32Error()}");
        return hwnd;
    }

    private static readonly List<WndProc> KeepAliveProcs = new();
}

internal static class Pump
{
    [DllImport("user32.dll")]
    private static extern int GetMessageW(out Msg msg, IntPtr hwnd, uint min, uint max);

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref Msg msg);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessageW(ref Msg msg);

    [StructLayout(LayoutKind.Sequential)]
    private struct Msg
    {
        public IntPtr hwnd; public uint message; public IntPtr wParam; public IntPtr lParam;
        public uint time; public int ptX, ptY;
    }

    public static void Run(BlockingCollection<Action> ops)
    {
        // 先执行排队的窗口创建，再进入消息循环
        while (ops.TryTake(out var op, Timeout.Infinite)) op();
        // 消息循环中继续处理后续注册
        _ = Task.Run(() =>
        {
            foreach (var op in ops.GetConsumingEnumerable()) op();
        });
        while (GetMessageW(out var msg, IntPtr.Zero, 0, 0) > 0)
        {
            _ = TranslateMessage(ref msg);
            _ = DispatchMessageW(ref msg);
        }
    }
}
