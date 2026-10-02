using ClipPort.Core.Net;
using ClipPort.Core.Protocol;
using Xunit;

namespace ClipPort.Core.Tests;

public class NetworkWatcherTests
{
    [Fact]
    public void Constructor_NullAction_ThrowsArgumentNullException()
    {
        Assert.Throws<ArgumentNullException>(() => new NetworkWatcher(null!));
    }

    [Fact]
    public void Lifecycle_StartStopDispose_IdempotentAndSafe()
    {
        int callbackCount = 0;
        using var watcher = new NetworkWatcher(() => callbackCount++, debounceMs: 50);

        // 多次调用 Start / Stop 不应抛出异常
        watcher.Start();
        watcher.Start();
        watcher.Stop();
        watcher.Stop();
        watcher.Dispose();
        watcher.Dispose();

        Assert.Equal(0, callbackCount);
    }

    [Fact]
    public void CancelFrame_EncodeAndDecode_RoundTrip()
    {
        uint cancelSeq = 0x40001234;
        byte[] frame = FrameCodec.Encode(FrameCodec.Cancel, cancelSeq, ReadOnlySpan<byte>.Empty);

        Assert.Equal(FrameCodec.HeaderSize, frame.Length);
        Assert.True(FrameCodec.TryDecode(frame, out var type, out var seq, out var payload, out var consumed));
        Assert.Equal(FrameCodec.Cancel, type);
        Assert.Equal(cancelSeq, seq);
        Assert.Empty(payload);
        Assert.Equal(FrameCodec.HeaderSize, consumed);
    }
}
