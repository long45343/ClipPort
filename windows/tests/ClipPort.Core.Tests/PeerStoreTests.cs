using ClipPort.Core.Net;
using Xunit;

namespace ClipPort.Core.Tests;

public class PeerStoreTests
{
    private static string FullFp() => new string('a', 64);
    private static string ShortFp() => new string('b', 16);

    [Fact]
    public void Upsert_MarkPaired_SetsPairedFlag()
    {
        var store = new PeerStore();
        store.Upsert("AABBCCDD", "手机", FullFp(), "192.168.1.5:47191", markPaired: true);

        Assert.True(store.Find("AABBCCDD")!.Paired);
    }

    [Fact]
    public void Upsert_WithoutMarkPaired_DoesNotSetPairedFlag()
    {
        var store = new PeerStore();
        store.Upsert("AABBCCDD", "陌生设备", null, "192.168.1.6:47191");

        Assert.False(store.Find("AABBCCDD")!.Paired);
    }

    [Fact]
    public void Upsert_ShortFp_DoesNotOverwriteLongFp()
    {
        var store = new PeerStore();
        var full = FullFp();
        store.Upsert("AABBCCDD", "手机", full, null, markPaired: true);

        // 模拟旧版 UDP 截断指纹（16 hex）到达：不得污染库中的完整指纹
        store.Upsert("AABBCCDD", "手机", ShortFp(), "192.168.1.5:47191");

        Assert.Equal(full, store.FingerprintOf("AABBCCDD"));
    }

    [Fact]
    public void Upsert_LongFp_OverwritesShortFp()
    {
        var store = new PeerStore();
        store.Upsert("AABBCCDD", "手机", ShortFp(), null);
        var full = FullFp();
        store.Upsert("AABBCCDD", "手机", full, "192.168.1.5:47191");

        Assert.Equal(full, store.FingerprintOf("AABBCCDD"));
    }

    [Fact]
    public void Find_CaseInsensitive_DeviceIdLookup()
    {
        var store = new PeerStore();
        store.Upsert("AABBCCDD", "手机", FullFp(), null, markPaired: true);

        // 大小写不敏感查找：UDP JSON 小写 id 与 PeerStore 大写键互通
        Assert.NotNull(store.Find("aabbccdd"));
        Assert.True(store.Find("aabbccdd")!.Paired);
    }
}
