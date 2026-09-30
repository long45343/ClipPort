using System.IO;
using System.Security.Cryptography.X509Certificates;
using System.Windows;
using ClipPort.Core.Clipboard;
using ClipPort.Core.Net;
using ClipPort.Core.Sync;

namespace ClipPort.App;

public partial class App : Application
{
    public static App? Instance { get; private set; }
    public AppConfig Config { get; private set; } = null!;
    public SyncEngine? Engine { get; private set; }
    public TlsLinkServer? Server { get; private set; }

    private ClipboardListener? _listener;
    private MainWindow? _main;
    private static Mutex? _singleInstance;
    private X509Certificate2? _ownCert;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        Instance = this;

        // 1. 单实例互斥
        _singleInstance = new Mutex(true, "ClipPort.SingleInstance", out var createdNew);
        if (!createdNew)
        {
            Shutdown();
            return;
        }

        try
        {
            Config = AppConfig.Load();
            _ownCert = CertManager.GetOrCreate();

            Server = new TlsLinkServer();
            Engine = new SyncEngine(Config, Server)
            {
                Log = OnEngineLog,
                StatusChanged = OnStatus,
                OwnFpProvider = () => System.Security.Cryptography.SHA256.HashData(_ownCert.RawData)
            };
            Server.Log = OnEngineLog;
            Server.OwnHelloProvider = () => new Core.Protocol.Hello
            {
                Name = Config.DeviceName,
                DeviceType = 0,
                ProtoVer = 1,
                DeviceId = Convert.FromHexString(Config.DeviceId),
            };

            _listener = new ClipboardListener();
            _listener.ClipChanged += () => Engine.OnLocalClipChanged();
            _listener.Start();

            _main = new MainWindow();
            MainWindow = _main;

            StartServer();
            ReconnectKnownPeers();

            if (e.Args.Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase)))
            {
                _main.Hide();
            }
            else
            {
                _main.Show();
            }
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(CertManager.StoreDir, "startup-crash.log"), ex.ToString());
            MessageBox.Show($"ClipPort 启动失败: {ex.Message}", "ClipPort 错误", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown();
        }
    }

    private void StartServer()
    {
        try
        {
            var cert = _ownCert ?? CertManager.GetOrCreate();
            _ownCert = cert;
            Server!.Start(Config.TcpPort, cert);
            var fp = CertManager.Fingerprint(cert);
            try
            {
                BlePublisher.StatusLog = OnEngineLog;
                BlePublisher.Start(Config.TcpPort, fp);
            }
            catch (Exception ex) { OnEngineLog("ble 广播不可用: " + ex.Message); }
            var ips = BlePublisher.AllLanIpv4().Select(a => a.ToString()).ToList();
            var addrText = string.Join("  ", ips.Select(ip => $"{ip}:{Config.TcpPort}"));
            OnEngineLog($"本机地址: {addrText}（UDP广播与BLE发现运行中）");
            Dispatcher.BeginInvoke(() => _main?.SetLocalIp(addrText));
        }
        catch (Exception ex) { OnEngineLog("server start failed: " + ex.Message); }
    }

    public void ConnectToPeer(string host, int port, string? code)
    {
        Engine!.OwnFpProvider ??= () => _ownCert is null ? null : System.Security.Cryptography.SHA256.HashData(_ownCert.RawData);
        Engine.ConnectPeer(host, port, code);
        OnEngineLog($"正在连接对端 {host}:{port}…");
    }

    public void ReconnectKnownPeers() => Engine?.ReconnectKnownPeers();

    public void OpenPairing(out string code)
    {
        code = Random.Shared.Next(100000, 999999).ToString();
        Config.PairingCodeHash = AppConfig.CodeHash(code);
        Config.PairingOpen = true;
        Config.Save();
        OnEngineLog($"配对窗口已开启，code={code}");
    }

    public void ShutdownApp()
    {
        _listener?.Dispose();
        Engine?.Dispose();
        Server?.DisposeAsync().AsTask().Wait(TimeSpan.FromSeconds(2));
        _singleInstance?.ReleaseMutex();
        Shutdown();
    }

    private void OnEngineLog(string msg) => Dispatcher.BeginInvoke(() => _main?.AppendLog(msg));

    private void OnStatus(string s) => Dispatcher.BeginInvoke(() => _main?.SetStatus(s));
}
