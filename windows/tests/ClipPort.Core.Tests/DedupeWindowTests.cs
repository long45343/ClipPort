using ClipPort.Core.Sync;
using Xunit;

namespace ClipPort.Core.Tests;

public class DedupeWindowTests
{
    [Fact]
    public void Seen_FirstTime_ReturnsFalse_SecondTime_ReturnsTrue()
    {
        var dedupe = new DedupeWindow(maxSize: 128, windowMs: 30_000);
        byte[] device1 = new byte[] { 1, 2, 3, 4 };
        uint seq1 = 100;

        Assert.False(dedupe.Seen(device1, seq1)); // 首次未见
        Assert.True(dedupe.Seen(device1, seq1));  // 再次见到 -> 重复
    }

    [Fact]
    public void Seen_DifferentDevice_Or_DifferentSeq_ReturnsFalse()
    {
        var dedupe = new DedupeWindow(maxSize: 128, windowMs: 30_000);
        byte[] devA = new byte[] { 1, 1, 1, 1 };
        byte[] devB = new byte[] { 2, 2, 2, 2 };

        Assert.False(dedupe.Seen(devA, 1));
        Assert.False(dedupe.Seen(devB, 1)); // 不同设备相同 seq -> 不重复
        Assert.False(dedupe.Seen(devA, 2)); // 相同设备不同 seq -> 不重复
    }

    [Fact]
    public void Lru_EvictsOldest_WhenExceedingMaxSize()
    {
        var dedupe = new DedupeWindow(maxSize: 3, windowMs: 30_000);
        byte[] dev = new byte[] { 1, 2, 3, 4 };

        Assert.False(dedupe.Seen(dev, 1));
        Assert.False(dedupe.Seen(dev, 2));
        Assert.False(dedupe.Seen(dev, 3));

        // 插入第 4 条，此时最旧的 1 应该被淘汰
        Assert.False(dedupe.Seen(dev, 4));

        // 验证 1 再次被见时不再被判定为重复（已被驱逐）
        Assert.False(dedupe.Seen(dev, 1));
        // 但 2, 3, 4 仍然能去重（或者看驱逐顺序）
        Assert.True(dedupe.Seen(dev, 1));
    }

    [Fact]
    public void Expired_Entries_AreRemoved()
    {
        // 极短窗口 50ms
        var dedupe = new DedupeWindow(maxSize: 10, windowMs: 50);
        byte[] dev = new byte[] { 1 };

        Assert.False(dedupe.Seen(dev, 999));
        Assert.True(dedupe.Seen(dev, 999)); // 立即重复

        Thread.Sleep(70); // 等待窗口过期

        Assert.False(dedupe.Seen(dev, 999)); // 过期后重新视作新条目
    }
}
