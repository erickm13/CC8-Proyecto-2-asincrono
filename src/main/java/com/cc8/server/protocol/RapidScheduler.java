package com.cc8.server.protocol;

/** Common scheduling controls for H2K packets and ZIP pyramid tiles. */
public interface RapidScheduler extends SegmentSource {
    void setMode(int mode);
    void setLoad(double load);
    void setViewport(int x, int y, int width, int height, int level, int layers,
                     double dx, double dy, double confidence, double load);
    void forget(int tileId);
}
