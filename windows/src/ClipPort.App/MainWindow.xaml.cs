using System.ComponentModel;
using System.Windows;
using Wpf.Ui.Controls;
using Wpf.Ui.Tray.Controls;

namespace ClipPort.App;

public partial class MainWindow : FluentWindow
{
    private bool _realExit;

    public MainWindow()
    {
        InitializeComponent();
        Title = $"ClipPort v{GetType().Assembly.GetName().Version?.ToString(3)}";
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_realExit)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        base.OnClosing(e);
    }

    public void ShowFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    public void SetStatus(string s) => StatusText.Text = s;
    public void SetLocalIp(string s) => LocalIpText.Text = s;

    public void AppendLog(string msg)
    {
        LogList.Items.Add($"[{DateTime.Now:HH:mm:ss}] {msg}");
        while (LogList.Items.Count > 200) LogList.Items.RemoveAt(0);
        LogList.ScrollIntoView(LogList.Items[^1]);
    }

    private void PairBtn_Click(object sender, RoutedEventArgs e)
    {
        App.Instance!.OpenPairing(out var code);
        PairCodeText.Text = code;
        AppendLog("配对码已生成，请在其他设备端输入连接");
    }

    private void ConnectPeer_Click(object sender, RoutedEventArgs e)
    {
        var host = PeerHostBox.Text.Trim();
        var port = int.TryParse(PeerPortBox.Text, out var p) ? p : 47190;
        var code = PeerCodeBox.Text.Trim();
        if (host.Length == 0) { AppendLog("请填写对端 IP"); return; }
        App.Instance!.ConnectToPeer(host, port, code.Length == 6 ? code : null);
    }

    private void SyncToggle_Click(object sender, RoutedEventArgs e)
    {
        var isChecked = SyncToggle.IsChecked ?? true;
        App.Instance!.Config.SyncEnabled = isChecked;
        App.Instance.Config.Save();
        TraySyncMenuItem.IsChecked = isChecked;
    }

    private void RootNotifyIcon_LeftClick(NotifyIcon sender, RoutedEventArgs e)
    {
        ShowFromTray();
    }

    private void OpenMenuItem_Click(object sender, RoutedEventArgs e)
    {
        ShowFromTray();
    }

    private void SyncMenuItem_Click(object sender, RoutedEventArgs e)
    {
        var isChecked = TraySyncMenuItem.IsChecked;
        SyncToggle.IsChecked = isChecked;
        App.Instance!.Config.SyncEnabled = isChecked;
        App.Instance.Config.Save();
    }

    private void ExitMenuItem_Click(object sender, RoutedEventArgs e)
    {
        _realExit = true;
        App.Instance!.ShutdownApp();
    }
}
