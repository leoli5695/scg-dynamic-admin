package com.leoli.gateway.admin.service.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VectorCodec 往返测试：float[] -> byte[] -> float[] 必须无损。
 */
class VectorCodecTest {

    @Test
    @DisplayName("float[] 与 byte[] 往返一致")
    void roundTrip() {
        float[] v = {0.1f, -0.5f, 1.0f, 0.0f, 3.14159f};
        byte[] bytes = VectorCodec.toBytes(v);
        assertEquals(v.length * Float.BYTES, bytes.length);
        assertArrayEquals(v, VectorCodec.toFloats(bytes), 1e-6f);
    }

    @Test
    @DisplayName("空/非法字节返回空向量，不抛异常")
    void handlesEmptyAndInvalid() {
        assertEquals(0, VectorCodec.toFloats(null).length);
        assertEquals(0, VectorCodec.toFloats(new byte[0]).length);
        assertEquals(0, VectorCodec.toFloats(new byte[]{1, 2, 3}).length); // 非 4 的倍数
    }
}
