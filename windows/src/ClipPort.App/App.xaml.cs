using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using ClipPort.Core.Net;
using ClipPort.Core.Sync;
using ClipPort.Core.Clipboard;

namespace ClipPort.App;

public partial class App : Application
{
    public static App? Instance;
    public AppConfig Config = null!;
    public SyncEngine? Engine;
    public TlsLinkServer? Server;
    private ClipboardListener? _listener;
    private TrayIcon? _tray;
    private MainWindow? _main;
    public DispatcherQueue UiQueue = null!;
    private static Mutex? _singleInstance;

    public App()
    {
        Instance = this;
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        // 单实例互斥（spec §4）：防止新旧实例托盘图标并存造成"点了没反应"的假象
        _singleInstance = new Mutex(true, "ClipPort.SingleInstance", out var createdNew);
        if (!createdNew)
        {
            UiQueue = DispatcherQueue.GetForCurrentThread();
            Exit();
            return;
        }

        UiQueue = DispatcherQueue.GetForCurrentThread();
        Config = AppConfig.Load();

        Server = new TlsLinkServer();
        Engine = new SyncEngine(Config, Server) { Log = OnEngineLog, StatusChanged = OnStatus };
        Server.Log = OnEngineLog;
        _listener = new ClipboardListener();
        _listener.ClipChanged += () => Engine.OnLocalClipChanged();
        _listener.Start();

        _tray = new TrayIcon();
        _tray.Click += ShowMain;
        _tray.SyncEnabledState = () => Config.SyncEnabled;
        _tray.SyncToggle += () => UiQueue.TryEnqueue(() =>
        {
            Config.SyncEnabled = !Config.SyncEnabled;
            Config.Save();
        });
        _tray.ExitRequested += () => UiQueue.TryEnqueue(ShutdownApp);

        // 若被配对设备拉起（BLE 广播发现入口），v1 常开监听
        StartServer();

        _main = new MainWindow();
        _main.Activate();
        // 仅带 --minimized（开机自启）时收进托盘；正常启动保持窗口可见
        if (Environment.GetCommandLineArgs().Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase)))
            _main.HideToTray();
    }

    private void ShutdownApp()
    {
        _main?.CloseForExit();
        _tray?.Dispose();
        Engine?.Dispose();
        Server?.DisposeAsync().AsTask().Wait(TimeSpan.FromSeconds(2));
        _listener?.Dispose();
        _singleInstance?.ReleaseMutex();
        Exit();
    }

    private void StartServer()
    {
        try
        {
            var cert = CertManager.GetOrCreate();
            Server!.Start(Config.TcpPort, cert);
            Config.PairingCodeHash = null;
            OnEngineLog($"server listening on :{Config.TcpPort} fp={Convert.ToHexString(CertManager.Fingerprint(cert))[..12]}…");
        }
        catch (Exception ex) { OnEngineLog("server start failed: " + ex.Message); }
    }

    public void OpenPairing(out string code)
    {
        code = Random.Shared.Next(100000, 999999).ToString();
        Config.PairingCodeHash = AppConfig.CodeHash(code);
        Config.PairingOpen = true;
        Config.Save();
        // 手机在配对成功后广播 BLE；PC 侧 v1 由手机直连 IP 完成配对
    }

    public void CommitPairing(string phoneName)
    {
        Config.PairedPhoneName = phoneName;
        Config.Save();
        OnStatus($"paired: {phoneName}");
        StartBleWatcher();
    }

    public void StartBleWatcher()
    {
        try
        {
            var fp = CertManager.Fingerprint(CertManager.GetOrCreate());
            var watcher = new BleWatcher(fp);
            watcher.EndpointFound += ep => OnEngineLog($"ble peer {ep.Ip}:{ep.Port}");
            watcher.Start();
        }
        catch (Exception ex) { OnEngineLog("ble unavailable: " + ex.Message); }
    }

    private void OnEngineLog(string msg) => UiQueue.TryEnqueue(() => _main?.AppendLog(msg));

    private void OnStatus(string s) => UiQueue.TryEnqueue(() =>
    {
        _main?.SetStatus(s);
        _tray?.ShowBalloon("ClipPort", s);
    });

    /// <summary>托盘回调在消息泵线程触发，必须切回 UI 线程操作 XAML（托盘点不开窗口的修复）。</summary>
    private void ShowMain() => UiQueue.TryEnqueue(() => _main?.ShowFromTray());
}
