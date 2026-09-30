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
                Pump.Run(Ops, () => PumpReady.Set());
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
        Enqueue(() =>
        {
            try { tcs.TrySetResult(CreateOnPumpThread(className, proc)); }
            catch (Exception ex) { tcs.TrySetException(ex); }
        });
        return tcs.Task.GetAwaiter().GetResult();
    }

    public static void DestroyWindow(IntPtr hwnd) => Enqueue(() =>
    {
        try { if (hwnd != IntPtr.Zero) Native.DestroyWindow(hwnd); } catch { }
    });

    /// <summary>入队并在泵线程唤醒执行（先入队再 Post，保证操作可见）。</summary>
    private static void Enqueue(Action op)
    {
        Ops.Add(op);
        Pump.Wake();
    }

    private static IntPtr CreateOnPumpThread(string className, WndProc proc)
    {
        // 保活委托防 GC
        KeepAliveProcs.Add(proc);
        var wc = new Native.WNDCLASS
        {
            lpfnWndProc = Marshal.GetFunctionPointerForDelegate(proc),
            lpszClassName = className,
            hInstance = Native.GetModuleHandleW(null),
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

/// <summary>
/// 消息泵：常驻 GetMessageW 循环；队列操作经 PostThreadMessage(WM_APP_OP) 唤醒执行。
/// 严禁在进入消息循环前阻塞等队列——那会让窗口消息永远得不到分发（托盘点击失灵的根因）。
/// </summary>
internal static class Pump
{
    private const uint WM_APP_OP = 0x8001; // WM_APP+1
    private static volatile uint _threadId;

    [DllImport("user32.dll")]
    private static extern int GetMessageW(out Msg msg, IntPtr hwnd, uint min, uint max);

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref Msg msg);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessageW(ref Msg msg);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();

    [DllImport("user32.dll")]
    private static extern bool PostThreadMessageW(uint threadId, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct Msg
    {
        public IntPtr hwnd; public uint message; public IntPtr wParam; public IntPtr lParam;
        public uint time; public int ptX, ptY;
    }

    public static void Run(BlockingCollection<Action> ops, Action onThreadReady)
    {
        _threadId = GetCurrentThreadId();
        onThreadReady();
        while (GetMessageW(out var msg, IntPtr.Zero, 0, 0) > 0)
        {
            if (msg.message == WM_APP_OP && msg.hwnd == IntPtr.Zero)
            {
                while (ops.TryTake(out var op)) op();
            }
            else
            {
                _ = TranslateMessage(ref msg);
                _ = DispatchMessageW(ref msg);
            }
        }
    }

    public static void Wake()
    {
        var tid = _threadId;
        if (tid != 0) PostThreadMessageW(tid, WM_APP_OP, IntPtr.Zero, IntPtr.Zero);
    }
}
