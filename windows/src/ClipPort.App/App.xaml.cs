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

    public App()
    {
        Instance = this;
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
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

        // 若被配对设备拉起（BLE 广播发现入口），v1 常开监听
        StartServer();

        _main = new MainWindow();
        _main.Activate();
        // 仅带 --minimized（开机自启）时收进托盘；正常启动保持窗口可见
        if (Environment.GetCommandLineArgs().Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase)))
            _main.HideToTray();
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

    private void ShowMain()
    {
        _main?.ShowFromTray();
    }
}
