package com.frameflags.sethome.core;

import java.util.UUID;

public final class Pending {
    public final UUID player;
    public final String homeName;
    public final long deadlineTick;

    public Pending(UUID player, String homeName, long deadlineTick) {
        this.player = player;
        this.homeName = homeName;
        this.deadlineTick = deadlineTick;
    }
}
