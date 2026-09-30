using System.IO;
using System.Security.Cryptography;
using ClipPort.Core.Net;
using ClipPort.Core.Protocol;

namespace ClipPort.Core.Sync;

/// <summary>
/// 独立文件共享管理器（D-26=A 决策：与剪贴板解耦，支持拖拽推流与接收落盘 Downloads）
/// </summary>
public class FileShareManager
{
    public const int ChunkSize = 64 * 1024; // 64KB
    private readonly string _downloadDir;
    private readonly Action<string> _log;
    private readonly Dictionary<string, FileStream> _incomingFiles = new();

    public FileShareManager(Action<string> log)
    {
        _log = log;
        var userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        _downloadDir = Path.Combine(userProfile, "Downloads", "ClipPort");
        Directory.CreateDirectory(_downloadDir);
    }

    /// <summary>
    /// 发送端：读取本地文件按 64KB 切片流式发送
    /// </summary>
    public async Task SendFileAsync(string filePath, TlsLinkServer server, CancellationToken ct = default)
    {
        if (!File.Exists(filePath))
        {
            _log($"发送失败：文件不存在 {filePath}");
            return;
        }

        var fi = new FileInfo(filePath);
        var fileName = fi.Name;
        var fileSize = (ulong)fi.Length;
        _log($"开始发送文件：{fileName} ({fileSize / 1024} KB)…");

        using var fs = File.OpenRead(filePath);
        var buffer = new byte[ChunkSize];
        ulong offset = 0;
        int bytesRead;

        while ((bytesRead = await fs.ReadAsync(buffer, 0, buffer.Length, ct)) > 0)
        {
            offset += (ulong)bytesRead;
            bool isLast = offset >= fileSize;

            var chunkData = new byte[bytesRead];
            Array.Copy(buffer, 0, chunkData, 0, bytesRead);

            var chunk = new FileShareChunk
            {
                FileName = fileName,
                Offset = offset,
                IsLast = isLast,
                Data = chunkData
            };

            var frame = FrameCodec.Encode(FrameCodec.FileShareChunk, 0, chunk.Encode());
            server.SendToAll(frame);

            if (isLast) break;
        }

        _log($"文件已发送完毕：{fileName}");
    }

    /// <summary>
    /// 接收端：收到分块数据写入下载目录
    /// </summary>
    public void HandleIncomingChunk(FileShareChunk chunk)
    {
        try
        {
            if (!_incomingFiles.TryGetValue(chunk.FileName, out var fs))
            {
                var targetPath = Path.Combine(_downloadDir, chunk.FileName);
                fs = new FileStream(targetPath, FileMode.Create, FileAccess.Write, FileShare.Read);
                _incomingFiles[chunk.FileName] = fs;
                _log($"正在接收文件：{chunk.FileName}…");
            }

            fs.Write(chunk.Data, 0, chunk.Data.Length);

            if (chunk.IsLast)
            {
                fs.Flush();
                fs.Dispose();
                _incomingFiles.Remove(chunk.FileName);
                var savePath = Path.Combine(_downloadDir, chunk.FileName);
                _log($"文件接收完成！已保存至：{savePath}");
            }
        }
        catch (Exception ex)
        {
            _log($"写入接收文件失败 ({chunk.FileName}): {ex.Message}");
            if (_incomingFiles.TryGetValue(chunk.FileName, out var fs))
            {
                fs.Dispose();
                _incomingFiles.Remove(chunk.FileName);
            }
        }
    }
}
