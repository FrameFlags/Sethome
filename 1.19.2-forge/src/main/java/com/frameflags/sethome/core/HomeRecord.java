package com.frameflags.sethome.core;

public final class HomeRecord {
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public String dimension;

    public HomeRecord() {
    }

    public HomeRecord(double x, double y, double z, float yaw, float pitch, String dimension) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.dimension = dimension;
    }

    public String format() {
        return String.format("%.1f, %.1f, %.1f", x, y, z);
    }
}
