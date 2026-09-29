using Microsoft.UI.Xaml;

namespace ClipPort.App;

public sealed partial class MainWindow : Window
{
    private bool _closingToTray;

    public MainWindow()
    {
        InitializeComponent();
        Title = "ClipPort";
        AppWindow.Closing += (s, e) =>
        {
            if (!_closingToTray)
            {
                e.Cancel = true;
                HideToTray();
            }
        };
    }

    public void HideToTray() { _closingToTray = true; AppWindow.Hide(); _closingToTray = false; }
    public void ShowFromTray() { AppWindow.Show(); Activate(); }
    /// <summary>退出前真正关闭（绕过"关闭=收托盘"拦截）。</summary>
    public void CloseForExit() { _closingToTray = true; Close(); }

    public void SetStatus(string s) => StatusText.Text = s;

    public void SetLocalIp(string s) => LocalIpText.Text = "本机地址: " + s;

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
        AppendLog($"配对码已生成，请在手机端输入（IP 见下方日志）");
    }

    private void SyncToggle_Toggled(object sender, RoutedEventArgs e)
    {
        App.Instance!.Config.SyncEnabled = SyncToggle.IsOn;
        App.Instance.Config.Save();
    }
}
