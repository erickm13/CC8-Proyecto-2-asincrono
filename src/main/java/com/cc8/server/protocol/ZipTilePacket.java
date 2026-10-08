package com.cc8.server.protocol;

/** Fragment of one PNG tile inside a RAPID DATA payload. */
public final class ZipTilePacket {
    public static final int HEADER = 22;
    public static final int FRAGMENT = 16_000;
    private ZipTilePacket() { }

    public static byte[] encode(int id, int level, int tx, int ty, int part, byte[] png) {
        int parts = (png.length + FRAGMENT - 1) / FRAGMENT;
        if (parts == 0 || parts > 0xffff || part < 0 || part >= parts
                || id < 0 || level < 0 || level > 255
                || tx < 0 || tx > 0xffff || ty < 0 || ty > 0xffff) {
            throw new IllegalArgumentException("ZIP tile fragment fields out of range");
        }
        int from = part * FRAGMENT;
        int len = Math.min(FRAGMENT, png.length - from);
        byte[] packet = new byte[HEADER + len];
        packet[0] = 'Z'; packet[1] = 'T'; packet[2] = 1;
        Wire.putInt(packet, 3, id);
        packet[7] = (byte) level;
        Wire.putShort(packet, 8, tx);
        Wire.putShort(packet, 10, ty);
        Wire.putShort(packet, 12, part);
        Wire.putShort(packet, 14, parts);
        Wire.putInt(packet, 16, png.length);
        Wire.putShort(packet, 20, len);
        System.arraycopy(png, from, packet, HEADER, len);
        return packet;
    }

    public static int tileId(byte[] packet) {
        if (packet.length < HEADER || packet[0] != 'Z' || packet[1] != 'T'
                || packet[2] != 1 || packet.length != HEADER + Wire.getShort(packet, 20)
                || Wire.getShort(packet, 20) == 0
                || Wire.getShort(packet, 20) > FRAGMENT
                || Wire.getShort(packet, 14) == 0
                || Wire.getShort(packet, 12) >= Wire.getShort(packet, 14)
                || Wire.getInt(packet, 3) < 0
                || Wire.getInt(packet, 16) <= 0) {
            throw new IllegalArgumentException("Invalid ZIP tile fragment");
        }
        return Wire.getInt(packet, 3);
    }
}
