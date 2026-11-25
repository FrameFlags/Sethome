package com.frameflags.sethome.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CountdownQueue {
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    public synchronized void schedule(UUID player, String homeName, long deadlineTick) {
        pending.put(player, new Pending(player, HomeData.normalize(homeName), deadlineTick));
    }

    public synchronized List<Pending> tick(long now) {
        List<Pending> due = new ArrayList<>();
        Iterator<Map.Entry<UUID, Pending>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending entry = iterator.next().getValue();
            if (entry.deadlineTick <= now) {
                due.add(entry);
                iterator.remove();
            }
        }
        return due;
    }

    public synchronized void cancel(UUID player) {
        pending.remove(player);
    }

    public synchronized Pending get(UUID player) {
        return pending.get(player);
    }
}
