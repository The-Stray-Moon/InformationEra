package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 筛选使用历史的独立测试：次数累计、高频/最近排序、序列化往返与非法行容错。
 * 历史按类别隔离，标签和模组不会互相挤占。
 */
public class ExplorerFilterHistoryTest {

    @Test
    public void recordAccumulatesUsesAndKeepsLastAccess() {
        ExplorerFilterHistory history = new ExplorerFilterHistory();
        history.record("tag", "minecraft:planks", 100L);
        history.record("tag", "minecraft:planks", 300L);

        assertEquals(List.of("minecraft:planks"), history.top("tag", 8));
        assertEquals(List.of("minecraft:planks"), history.recent("tag", 8));
    }

    @Test
    public void topSortsByUsesThenRecency() {
        ExplorerFilterHistory history = new ExplorerFilterHistory();
        history.record("mod", "rare", 100L);
        history.record("mod", "common", 100L);
        history.record("mod", "common", 200L);
        history.record("mod", "common", 300L);
        history.record("mod", "recent", 400L);
        history.record("mod", "recent", 500L);
        history.record("mod", "recent", 600L);

        // common 与 recent 都是 3 次，recent 的最后使用更晚。
        assertEquals(List.of("recent", "common", "rare"), history.top("mod", 8));
    }

    @Test
    public void recentSortsByLastAccess() {
        ExplorerFilterHistory history = new ExplorerFilterHistory();
        history.record("tab", "minecraft:building_blocks", 100L);
        history.record("tab", "minecraft:tools", 300L);
        history.record("tab", "minecraft:building_blocks", 200L);
        history.record("tab", "minecraft:food", 500L);

        assertEquals(List.of("minecraft:food", "minecraft:tools", "minecraft:building_blocks"),
                history.recent("tab", 8));
    }

    @Test
    public void kindsAreIsolated() {
        ExplorerFilterHistory history = new ExplorerFilterHistory();
        history.record("tag", "minecraft:planks", 100L);
        history.record("mod", "minecraft", 200L);
        history.record("tab", "minecraft:building_blocks", 300L);

        assertEquals(List.of("minecraft:planks"), history.top("tag", 8));
        assertEquals(List.of("minecraft"), history.top("mod", 8));
        assertEquals(List.of("minecraft:building_blocks"), history.top("tab", 8));
        assertTrue(history.top("unknown", 8).isEmpty());
    }

    @Test
    public void limitIsApplied() {
        ExplorerFilterHistory history = new ExplorerFilterHistory();
        for (int i = 0; i < 5; i++) {
            history.record("tag", "tag:" + i, i);
        }
        assertEquals(2, history.top("tag", 2).size());
        assertEquals(2, history.recent("tag", 2).size());
        assertEquals(5, history.recent("tag", 0).size());
    }

    @Test
    public void encodeDecodeRoundTripAndMalformedLines() {
        ExplorerFilterHistory history = new ExplorerFilterHistory();
        history.record("tag", "minecraft:planks", 100L);
        history.record("tag", "minecraft:planks", 200L);
        history.record("mod", "sample", 300L);

        ExplorerFilterHistory decoded = ExplorerFilterHistory.decode(history.encode());
        assertEquals(List.of("minecraft:planks"), decoded.top("tag", 8));
        assertEquals(List.of("sample"), decoded.top("mod", 8));

        ExplorerFilterHistory tolerant = ExplorerFilterHistory.decode(List.of(
                "bad-line",
                "tag|||",
                "tag|2|100|minecraft:logs",
                "tag|9|999|minecraft:logs"));
        assertEquals(List.of("minecraft:logs"), tolerant.top("tag", 8));
    }
}
