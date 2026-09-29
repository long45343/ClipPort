namespace ClipPort.Core.Sync;

/// <summary>(设备ID, seq) LRU 去重窗口（D-07，对齐小米第4层：LRU 128 / 30s 过期）。</summary>
public sealed class DedupeWindow
{
    private readonly int _maxSize;
    private readonly TimeSpan _window;
    private readonly object _gate = new();
    private readonly LinkedList<(byte[] Dev, uint Seq, DateTime At)> _list = new();

    public DedupeWindow(int maxSize = 128, int windowMs = 30_000)
    {
        _maxSize = maxSize;
        _window = TimeSpan.FromMilliseconds(windowMs);
    }

    /// <summary>已见过则 true（丢弃）；未见则记录并 false。</summary>
    public bool Seen(byte[] device, uint seq)
    {
        lock (_gate)
        {
            var now = DateTime.UtcNow;
            // 过期清理
            var node = _list.First;
            while (node != null)
            {
                var next = node.Next;
                if (now - node.Value.At > _window) _list.Remove(node);
                node = next;
            }
            // 查重
            node = _list.First;
            while (node != null)
            {
                if (node.Value.Seq == seq && node.Value.Dev.AsSpan().SequenceEqual(device))
                {
                    // 对齐小米 "Outside window, updating"
                    node.Value = (node.Value.Dev, node.Value.Seq, now);
                    return true;
                }
                node = node.Next;
            }
            _list.AddLast((device.ToArray(), seq, now));
            while (_list.Count > _maxSize) _list.RemoveFirst();
            return false;
        }
    }
}
