using ClipPort.Core.Clipboard;
using Xunit;

namespace ClipPort.Core.Tests;

public class ClipboardSnapshotTests
{
    [Fact]
    public void ContentEquals_IdenticalText_ReturnsTrue()
    {
        var a = new ClipboardSnapshot { Text = "hello" };
        var b = new ClipboardSnapshot { Text = "hello" };
        Assert.True(a.ContentEquals(b));
    }

    [Fact]
    public void ContentEquals_DifferentText_ReturnsFalse()
    {
        var a = new ClipboardSnapshot { Text = "hello" };
        var b = new ClipboardSnapshot { Text = "world" };
        Assert.False(a.ContentEquals(b));
    }

    [Fact]
    public void ContentEquals_NullVsNonNull_ReturnsFalse()
    {
        var a = new ClipboardSnapshot { Text = null, Html = null };
        var b = new ClipboardSnapshot { Text = "x" };
        Assert.False(a.ContentEquals(b));
        Assert.False(b.ContentEquals(a));
    }

    [Fact]
    public void ContentEquals_ImageByteArrayContentComparison()
    {
        var a = new ClipboardSnapshot { ImagePng = [0x89, 0x50, 0x4E, 0x47] };
        var b = new ClipboardSnapshot { ImagePng = [0x89, 0x50, 0x4E, 0x47] };
        var c = new ClipboardSnapshot { ImagePng = [0x89, 0x50, 0x4E, 0x48] };
        // 内容相同但引用不同 → 视为一致（字节级比对，而非引用比对）
        Assert.True(a.ContentEquals(b));
        Assert.False(a.ContentEquals(c));
        Assert.False(a.ContentEquals(new ClipboardSnapshot()));
        Assert.False(new ClipboardSnapshot().ContentEquals(a));
    }

    [Fact]
    public void ContentEquals_NullOther_ReturnsFalse()
    {
        var a = new ClipboardSnapshot { Text = "x" };
        Assert.False(a.ContentEquals(null));
    }

    [Fact]
    public void ContentEquals_BothEmpty_ReturnsTrue()
    {
        Assert.True(new ClipboardSnapshot().ContentEquals(new ClipboardSnapshot()));
    }
}
