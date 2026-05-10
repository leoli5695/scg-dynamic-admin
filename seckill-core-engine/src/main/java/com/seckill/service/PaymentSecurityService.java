package com.seckill.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 支付安全验证服务
 * ============================================================================
 * <p>
 * 功能:
 * 1. 签名验证 - 验证支付平台回调签名
 * 2. 金额校验 - 验证支付金额与订单金额一致
 * 3. 时间戳验证 - 防止回调重放攻击
 * 4. 幂等性控制 - 防止重复处理同一回调
 * <p>
 * 安全措施:
 * 1. 签名算法: HMAC-SHA256 或 RSA
 * 2. 时间戳有效期: 5分钟
 * 3. 回调幂等Key: 支付流水号
 * <p>
 * 生产对接:
 * - 支付宝: 使用支付宝SDK验签
 * - 微信支付: 使用微信支付SDK验签
 * - 银联: 使用银联SDK验签
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSecurityService {

    private final StringRedisTemplate redisTemplate;

    /**
     * 时间戳有效期（毫秒）
     */
    private static final long TIMESTAMP_VALID_MS = 5 * 60 * 1000;  // 5分钟

    /**
     * 幂等Key前缀
     */
    private static final String PAYMENT_IDEMPOTENT_PREFIX = "seckill:payment:idempotent:";

    /**
     * 幂等Key有效期（小时）
     */
    private static final long IDEMPOTENT_TTL_HOURS = 24;

    /**
     * ============================================================================
     * 验证支付回调签名
     * ============================================================================
     *
     * @param sign   签名值
     * @param params 所有参数（不含sign）
     * @param secret 密钥或公钥
     * @return 签名是否有效
     */
    public boolean verifySignature(String sign, String params, String secret) {
        if (sign == null || sign.isEmpty()) {
            log.warn("签名值为空");
            return false;
        }

        try {
            // 计算期望签名
            String expectedSign = calculateHmacSha256(params, secret);

            boolean valid = sign.equals(expectedSign);
            if (!valid) {
                log.warn("签名验证失败: expected={}, actual={}", expectedSign, sign);
            }

            return valid;

        } catch (Exception e) {
            log.error("签名验证异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * ============================================================================
     * 验证时间戳（防止重放攻击）
     * ============================================================================
     *
     * @param timestamp 回调时间戳（毫秒）
     * @return 时间戳是否有效
     */
    public boolean verifyTimestamp(long timestamp) {
        long currentTime = System.currentTimeMillis();
        long diff = currentTime - timestamp;

        if (diff > TIMESTAMP_VALID_MS) {
            log.warn("时间戳过期: timestamp={}, diff={}ms", timestamp, diff);
            return false;
        }

        if (diff < -TIMESTAMP_VALID_MS) {
            log.warn("时间戳超前（可能伪造）: timestamp={}, diff={}ms", timestamp, diff);
            return false;
        }

        return true;
    }

    /**
     * ============================================================================
     * 幂等性检查
     * ============================================================================
     * <p>
     * 防止同一支付回调被重复处理
     *
     * @param transactionId 支付流水号
     * @return 是否首次处理
     */
    public boolean checkIdempotent(String transactionId) {
        String idempotentKey = PAYMENT_IDEMPOTENT_PREFIX + transactionId;

        try {
            Boolean isFirst = redisTemplate.opsForValue()
                    .setIfAbsent(idempotentKey, "1", IDEMPOTENT_TTL_HOURS, TimeUnit.HOURS);

            if (Boolean.FALSE.equals(isFirst)) {
                log.warn("支付回调重复（幂等）: transactionId={}", transactionId);
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("幂等性检查失败: transactionId={}, error={}", transactionId, e.getMessage());
            // Redis失败时允许继续处理（由数据库唯一索引兜底）
            return true;
        }
    }

    /**
     * ============================================================================
     * 清除幂等标记（用于处理失败后重试）
     * ============================================================================
     */
    public void clearIdempotent(String transactionId) {
        String idempotentKey = PAYMENT_IDEMPOTENT_PREFIX + transactionId;
        redisTemplate.delete(idempotentKey);
        log.info("清除幂等标记: transactionId={}", transactionId);
    }

    /**
     * ============================================================================
     * 金额校验
     * ============================================================================
     *
     * @param orderAmount 订单金额
     * @param paidAmount  支付金额
     * @return 金额是否一致
     */
    public boolean verifyAmount(java.math.BigDecimal orderAmount, java.math.BigDecimal paidAmount) {
        if (orderAmount == null || paidAmount == null) {
            log.warn("金额校验失败: 参数为空");
            return false;
        }

        // 允许小额误差（如0.01元，处理浮点精度问题）
        java.math.BigDecimal diff = orderAmount.subtract(paidAmount).abs();
        java.math.BigDecimal tolerance = new java.math.BigDecimal("0.01");

        if (diff.compareTo(tolerance) > 0) {
            log.warn("金额不一致: orderAmount={}, paidAmount={}, diff={}",
                    orderAmount, paidAmount, diff);
            return false;
        }

        return true;
    }

    /**
     * ============================================================================
     * 商户订单号校验
     * ============================================================================
     *
     * @param orderNo         商户订单号
     * @param expectedOrderNo 期望订单号
     * @return 订单号是否一致
     */
    public boolean verifyOrderNo(String orderNo, String expectedOrderNo) {
        if (orderNo == null || expectedOrderNo == null) {
            log.warn("订单号校验失败: 参数为空");
            return false;
        }

        if (!orderNo.equals(expectedOrderNo)) {
            log.warn("订单号不一致: orderNo={}, expected={}", orderNo, expectedOrderNo);
            return false;
        }

        return true;
    }

    /**
     * ============================================================================
     * 综合安全验证
     * ============================================================================
     *
     * @param sign          签名
     * @param params        参数
     * @param secret        密钥
     * @param timestamp     时间戳
     * @param transactionId 支付流水号
     * @return 是否全部验证通过
     */
    public boolean comprehensiveVerify(String sign, String params, String secret,
                                       long timestamp, String transactionId) {
        // 1. 签名验证
        if (!verifySignature(sign, params, secret)) {
            return false;
        }

        // 2. 时间戳验证
        if (!verifyTimestamp(timestamp)) {
            return false;
        }

        // 3. 幂等性检查
        if (!checkIdempotent(transactionId)) {
            return false;
        }

        log.info("支付回调综合验证通过: transactionId={}", transactionId);
        return true;
    }

    /**
     * ============================================================================
     * 计算HMAC-SHA256签名
     * ============================================================================
     */
    private String calculateHmacSha256(String data, String key) throws NoSuchAlgorithmException {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);

            byte[] hmacBytes = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // 转换为十六进制字符串
            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();

        } catch (java.security.InvalidKeyException e) {
            throw new RuntimeException("Invalid key", e);
        }
    }

    /**
     * ============================================================================
     * 生成测试签名（用于开发测试）
     * ============================================================================
     */
    public String generateTestSignature(String params, String secret) {
        try {
            return calculateHmacSha256(params, secret);
        } catch (Exception e) {
            log.error("生成签名失败: {}", e.getMessage());
            return null;
        }
    }
}