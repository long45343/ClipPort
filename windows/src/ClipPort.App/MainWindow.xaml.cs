using System.ComponentModel;
using System.Windows;
using ClipPort.Core.Util;
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
        AutoStartToggle.IsChecked = AutoStartManager.IsAutoStartEnabled();
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
        PairHintText.Text = "请在手机端点击「扫码连接」，或在其他电脑输入此配对码：";
        try
        {
            var uri = App.Instance.BuildPairingUri(code);
            var pngBytes = QrCodeHelper.GenerateQrPngBytes(uri, 4);
            QrImage.Source = ToBitmapImage(pngBytes);
            QrBorder.Visibility = Visibility.Visible;
        }
        catch (Exception ex)
        {
            AppendLog("生成二维码失败: " + ex.Message);
        }
        AppendLog("配对窗口已开启，支持手机扫码或输入 6 位码配对");
    }

    private static System.Windows.Media.Imaging.BitmapImage ToBitmapImage(byte[] pngBytes)
    {
        var bitmap = new System.Windows.Media.Imaging.BitmapImage();
        using (var stream = new System.IO.MemoryStream(pngBytes))
        {
            bitmap.BeginInit();
            bitmap.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
            bitmap.StreamSource = stream;
            bitmap.EndInit();
        }
        bitmap.Freeze();
        return bitmap;
    }

    private void ConnectPeer_Click(object sender, RoutedEventArgs e)
    {
        var host = PeerHostBox.Text.Trim();
        var port = int.TryParse(PeerPortBox.Text, out var p) ? p : 47190;
        var code = PeerCodeBox.Text.Trim();
        if (host.Length == 0) { AppendLog("请填写对端 IP"); return; }
        App.Instance!.ConnectToPeer(host, port, code.Length == 6 ? code : null);
    }

    private void AutoStartToggle_Click(object sender, RoutedEventArgs e)
    {
        var enable = AutoStartToggle.IsChecked ?? false;
        var ok = AutoStartManager.SetAutoStart(enable);
        AppendLog(ok ? $"已{(enable ? "开启" : "关闭")}开机自启" : "设置开机自启失败");
    }

    private void FileDrop_DragOver(object sender, DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
        e.Handled = true;
    }

    private void FileDrop_Drop(object sender, DragEventArgs e)
    {
        if (e.Data.GetDataPresent(DataFormats.FileDrop) && e.Data.GetData(DataFormats.FileDrop) is string[] files)
        {
            foreach (var file in files)
            {
                _ = App.Instance!.Engine!.FileShare.SendFileAsync(file, App.Instance.Server!);
            }
        }
    }

    private void SendFileBtn_Click(object sender, RoutedEventArgs e)
    {
        var ofd = new Microsoft.Win32.OpenFileDialog
        {
            Title = "选择要发送给对端的文件",
            Multiselect = true
        };
        if (ofd.ShowDialog() == true)
        {
            foreach (var file in ofd.FileNames)
            {
                _ = App.Instance!.Engine!.FileShare.SendFileAsync(file, App.Instance.Server!);
            }
        }
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
