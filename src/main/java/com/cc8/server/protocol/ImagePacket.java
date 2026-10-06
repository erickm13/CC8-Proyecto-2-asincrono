package com.cc8.server.protocol;

/**
 * Cabecera de aplicacion que viaja dentro del payload de un DATA. Es
 * AUTODESCRIPTIVA: el cliente puede colocar el plano de bits y reconstruir sin
 * necesitar el indice del archivo (numPlanes y numCoeffs viajan en el paquete).
 *
 * <pre>
 *   tile(u32) comp(u8) level(u8) py(u16) px(u16) layer(u8)
 *   numPlanes(u8) numCoeffs(u32) dataLen(u16) data[dataLen]
 * </pre>
 * {@code data} es el plano de bits comprimido con DEFLATE de esa capa.
 */
public final class ImagePacket {

    public static final int HEADER = 18;

    public record Packet(int tile, int comp, int level, int py, int px, int layer,
                         int numPlanes, int numCoeffs, byte[] data) {
    }

    private ImagePacket() {
    }

    public static byte[] encode(int tile, int comp, int level, int py, int px,
                                int layer, int numPlanes, int numCoeffs, byte[] data) {
        byte[] b = new byte[HEADER + data.length];
        int p = 0;
        p = Wire.putInt(b, p, tile);
        b[p++] = (byte) comp;
        b[p++] = (byte) level;
        p = Wire.putShort(b, p, py);
        p = Wire.putShort(b, p, px);
        b[p++] = (byte) layer;
        b[p++] = (byte) numPlanes;
        p = Wire.putInt(b, p, numCoeffs);
        p = Wire.putShort(b, p, data.length);
        System.arraycopy(data, 0, b, p, data.length);
        return b;
    }

    public static Packet decode(byte[] b) {
        int p = 0;
        int tile = Wire.getInt(b, p); p += 4;
        int comp = b[p++] & 0xFF;
        int level = b[p++] & 0xFF;
        int py = Wire.getShort(b, p); p += 2;
        int px = Wire.getShort(b, p); p += 2;
        int layer = b[p++] & 0xFF;
        int numPlanes = b[p++] & 0xFF;
        int numCoeffs = Wire.getInt(b, p); p += 4;
        int dataLen = Wire.getShort(b, p); p += 2;
        byte[] data = new byte[dataLen];
        System.arraycopy(b, p, data, 0, dataLen);
        return new Packet(tile, comp, level, py, px, layer, numPlanes, numCoeffs, data);
    }
}
