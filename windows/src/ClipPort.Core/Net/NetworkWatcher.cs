using System.Net.NetworkInformation;

namespace ClipPort.Core.Net;

/// <summary>
/// Windows 端网卡与网络变动感知器（D-32=A）：
/// 监听 NetworkChange.NetworkAddressChanged，通过 500ms 防抖消除过渡态，
/// 在网络连通或 IP 变动时即刻触发回调宣告。
/// </summary>
public sealed class NetworkWatcher : IDisposable
{
    private readonly Action _onNetworkChanged;
    private readonly int _debounceMs;
    private readonly Timer _debounceTimer;
    private bool _running;
    private bool _disposed;

    public NetworkWatcher(Action onNetworkChanged, int debounceMs = 500)
    {
        _onNetworkChanged = onNetworkChanged ?? throw new ArgumentNullException(nameof(onNetworkChanged));
        _debounceMs = debounceMs;
        _debounceTimer = new Timer(OnTimerFired, null, Timeout.Infinite, Timeout.Infinite);
    }

    public void Start()
    {
        if (_running || _disposed) return;
        _running = true;
        try
        {
            NetworkChange.NetworkAddressChanged += OnNetworkAddressChanged;
        }
        catch { }
    }

    public void Stop()
    {
        if (!_running) return;
        _running = false;
        try
        {
            NetworkChange.NetworkAddressChanged -= OnNetworkAddressChanged;
            _debounceTimer.Change(Timeout.Infinite, Timeout.Infinite);
        }
        catch { }
    }

    private void OnNetworkAddressChanged(object? sender, EventArgs e)
    {
        if (!_running || _disposed) return;
        // 重置 500ms 防抖定时器
        _debounceTimer.Change(_debounceMs, Timeout.Infinite);
    }

    private void OnTimerFired(object? state)
    {
        if (!_running || _disposed) return;
        try
        {
            _onNetworkChanged();
        }
        catch { }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Stop();
        _debounceTimer.Dispose();
    }
}
