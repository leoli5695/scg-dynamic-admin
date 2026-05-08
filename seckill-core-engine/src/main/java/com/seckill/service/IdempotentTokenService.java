package com.seckill.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * 请求幂等Token服务
 * ============================================================================
 *
 * 功能:
 * 1. 生成幂等Token - 用户秒杀前先获取Token
 * 2. 验证幂等Token - 秒杀请求必须携带有效Token
 * 3. 消费幂等Token - Token使用后立即失效
 *
 * 工作流程:
 * 1. 用户点击"立即秒杀"按钮
 * 2. 前端调用 /seckill/token 获取幂等Token
 * 3. 前端携带Token发送秒杀请求
 * 4. 后端验证Token有效性
 * 5. Token消费后立即失效（防止重复请求）
 *
 * 防止的问题:
 * 1. 用户重复点击秒杀按钮
 * 2. 前端网络超时重试
 * 3. 浏览器刷新重复提交
 *
 * Token结构:
 * - Key: seckill:token:{seckillId}:{userId}:{tokenValue}
 * - Value: {createTime}
 * - TTL: 30秒（足够一次秒杀请求）
 *
 * 注意:
 * - Token只用于请求级幂等，不是购买防重
 * - 购买防重由Lua脚本SISMEMBER实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentTokenService {

    private final StringRedisTemplate redisTemplate;

    /**
     * Token Key前缀
     */
    private static final String TOKEN_PREFIX = "seckill:token:";

    /**
     * Token有效期（秒）
     */
    private static final long TOKEN_TTL_SECONDS = 30;

    /**
     * ============================================================================
     * 生成幂等Token
     * ============================================================================
     *
     * 用户秒杀前调用此方法获取Token
     *
     * @param seckillId 秒杀活动ID
     * @param userId 用户ID
     * @return Token值
     */
    public String generateToken(Long seckillId, Long userId) {
        // 生成唯一Token
        String tokenValue = UUID.randomUUID().toString().replace("-", "");

        // 构建Redis Key
        String tokenKey = buildTokenKey(seckillId, userId, tokenValue);

        // 存储Token
        redisTemplate.opsForValue().set(
                tokenKey,
                String.valueOf(System.currentTimeMillis()),
                TOKEN_TTL_SECONDS,
                TimeUnit.SECONDS
        );

        log.info("生成幂等Token: seckillId={}, userId={}, token={}", seckillId, userId, tokenValue);

        return tokenValue;
    }

    /**
     * ============================================================================
     * 验证并消费幂等Token
     * ============================================================================
     *
     * 秒杀请求时验证Token有效性，并立即消费（删除）
     *
     * @param seckillId 秒杀活动ID
     * @param userId 用户ID
     * @param token Token值
     * @return Token是否有效
     */
    public boolean verifyAndConsumeToken(Long seckillId, Long userId, String token) {
        if (token == null || token.isEmpty()) {
            log.warn("幂等Token为空: seckillId={}, userId={}", seckillId, userId);
            return false;
        }

        String tokenKey = buildTokenKey(seckillId, userId, token);

        // 检查Token是否存在
        Boolean exists = redisTemplate.hasKey(tokenKey);

        if (Boolean.FALSE.equals(exists)) {
            log.warn("幂等Token无效或已过期: seckillId={}, userId={}, token={}",
                    seckillId, userId, token);
            return false;
        }

        // 立即消费Token（删除）
        Boolean deleted = redisTemplate.delete(tokenKey);

        if (Boolean.TRUE.equals(deleted)) {
            log.info("幂等Token消费成功: seckillId={}, userId={}, token={}", seckillId, userId, token);
            return true;
        } else {
            // 并发情况下可能已被其他请求消费
            log.warn("幂等Token消费失败（可能并发冲突）: seckillId={}, userId={}, token={}",
                    seckillId, userId, token);
            return false;
        }
    }

    /**
     * ============================================================================
     * 仅验证Token（不消费）
     * ============================================================================
     *
     * 用于预检查，不删除Token
     *
     * @param seckillId 秒杀活动ID
     * @param userId 用户ID
     * @param token Token值
     * @return Token是否有效
     */
    public boolean verifyToken(Long seckillId, Long userId, String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        String tokenKey = buildTokenKey(seckillId, userId, token);
        Boolean exists = redisTemplate.hasKey(tokenKey);

        return Boolean.TRUE.equals(exists);
    }

    /**
     * ============================================================================
     * 强制清除Token
     * ============================================================================
     *
     * 用于异常情况或测试
     */
    public void clearToken(Long seckillId, Long userId, String token) {
        String tokenKey = buildTokenKey(seckillId, userId, token);
        redisTemplate.delete(tokenKey);
        log.info("强制清除幂等Token: seckillId={}, userId={}, token={}", seckillId, userId, token);
    }

    /**
     * ============================================================================
     * 清除用户所有Token
     * ============================================================================
     *
     * 用于用户取消秒杀或活动结束时清理
     */
    public void clearUserTokens(Long seckillId, Long userId) {
        // 使用SCAN遍历删除（简化实现）
        String pattern = TOKEN_PREFIX + seckillId + ":" + userId + ":*";

        try {
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("清除用户所有Token: seckillId={}, userId={}, count={}",
                        seckillId, userId, keys.size());
            }
        } catch (Exception e) {
            log.error("清除用户Token失败: seckillId={}, userId={}, error={}",
                    seckillId, userId, e.getMessage());
        }
    }

    /**
     * ============================================================================
     * 获取Token剩余有效期
     * ============================================================================
     *
     * @param seckillId 秒杀活动ID
     * @param userId 用户ID
     * @param token Token值
     * @return 剩余秒数，-1表示不存在
     */
    public long getTokenTTL(Long seckillId, Long userId, String token) {
        String tokenKey = buildTokenKey(seckillId, userId, token);
        Long ttl = redisTemplate.getExpire(tokenKey, TimeUnit.SECONDS);

        return ttl != null ? ttl : -1;
    }

    /**
     * ============================================================================
     * 批量生成Token（用于压测）
     * ============================================================================
     *
     * @param seckillId 秒杀活动ID
     * @param userId 用户ID
     * @param count 数量
     * @return Token列表
     */
    public java.util.List<String> generateTokens(Long seckillId, Long userId, int count) {
        java.util.List<String> tokens = new java.util.ArrayList<>();

        for (int i = 0; i < count; i++) {
            tokens.add(generateToken(seckillId, userId));
        }

        log.info("批量生成Token: seckillId={}, userId={}, count={}", seckillId, userId, count);

        return tokens;
    }

    /**
     * ============================================================================
     * Key构建方法
     * ============================================================================
     */
    private String buildTokenKey(Long seckillId, Long userId, String token) {
        return TOKEN_PREFIX + seckillId + ":" + userId + ":" + token;
    }
}