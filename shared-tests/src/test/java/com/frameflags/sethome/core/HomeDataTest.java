package com.frameflags.sethome.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HomeDataTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final HomeRecord RECORD = new HomeRecord(1.5, 64.0, -2.5f, 90f, -10f, "minecraft:overworld");

    @Test
    void overwriteAllowedAtLimitButNewNameRejected() {
        HomeData data = new HomeData();
        assertEquals(HomeResult.OK, data.set(PLAYER, "alpha", RECORD, 1));
        assertEquals(HomeResult.OK, data.set(PLAYER, "ALPHA", RECORD, 1));
        assertEquals(HomeResult.LIMIT_REACHED, data.set(PLAYER, "beta", RECORD, 1));
        assertEquals(1, data.list(PLAYER).size());
    }

    @Test
    void reservedAndEmptyNamesRejectedCaseInsensitively() {
        HomeData data = new HomeData();
        assertEquals(HomeResult.RESERVED_NAME, data.set(PLAYER, " LiSt ", RECORD, -1));
        assertEquals(HomeResult.INVALID_NAME, data.set(PLAYER, "  ", RECORD, -1));
    }

    @Test
    void countdownFiresOnceAndIsAnUpsert() {
        CountdownQueue queue = new CountdownQueue();
        queue.schedule(PLAYER, "alpha", 100);
        queue.schedule(PLAYER, "beta", 100);
        assertEquals(100L, queue.get(PLAYER).deadlineTick);
        List<Pending> due = queue.tick(100);
        assertEquals(1, due.size());
        assertEquals("beta", due.get(0).homeName);
        assertTrue(queue.tick(101).isEmpty());
    }

    @Test
    void jsonRoundTripPreservesAwkwardNames() {
        HomeData data = new HomeData();
        String name = "quote\"comma,\u00e9";
        assertEquals(HomeResult.OK, data.set(PLAYER, name, RECORD, -1));
        HomeData restored = HomeData.fromJson(data.toJson());
        assertNotNull(restored.get(PLAYER, name));
        assertEquals(RECORD.yaw, restored.get(PLAYER, name).yaw, 0.001f);
    }
}
