package com.leoli.gateway.admin.service.knowledge;

import java.nio.ByteBuffer;

/**
 * float[] 与字节数组互转（embedding 在 MySQL BLOB 里的存储格式）。
 * 采用 ByteBuffer 默认大端序，读写两侧一致即可。
 *
 * @author leoli
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static byte[] toBytes(float[] vector) {
        ByteBuffer buf = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float f : vector) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    public static float[] toFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % Float.BYTES != 0) {
            return new float[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buf.getFloat();
        }
        return vector;
    }
}
