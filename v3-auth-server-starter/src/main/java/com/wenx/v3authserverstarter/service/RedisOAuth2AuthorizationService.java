package com.wenx.v3authserverstarter.service;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenx.v3authserverstarter.mixin.UserDetailMixin;
import com.wenx.v3secure.user.UserDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module; // 引入 OAuth2AuthorizationServerJackson2Module
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Redis缓存的OAuth2授权服务
 *
 * 提供OAuth2授权的缓存功能，通过Redis缓存token映射来提升查询性能。
 * 为了避免序列化复杂对象的安全风险，只缓存token到authorization ID的映射，
 * 完整的authorization对象从数据库获取。
 *
 * @author wenx
 */
@Slf4j
@RequiredArgsConstructor
public class RedisOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private final JdbcOAuth2AuthorizationService jdbcService;
    private final RedisTemplate<String, Object> redisTemplate;

    // Redis键前缀
    private static final String REDIS_PREFIX = "oauth2:authorization:";
    private static final String TOKEN_PREFIX = "oauth2:token:";
    private static final String METRICS_PREFIX = "oauth2:metrics:";

    // 缓存TTL配置
    private static final Duration TOKEN_CACHE_TTL = Duration.ofHours(2);
    private static final Duration AUTH_CODE_TTL = Duration.ofMinutes(10);

    // 性能监控
    private static final String CACHE_HIT_KEY = METRICS_PREFIX + "cache:hit";
    private static final String CACHE_MISS_KEY = METRICS_PREFIX + "cache:miss";

    public RedisOAuth2AuthorizationService(JdbcTemplate jdbcTemplate,
                                           RegisteredClientRepository registeredClientRepository,
                                           RedisTemplate<String, Object> redisTemplate) {
        // 创建一个自定义的 OAuth2AuthorizationRowMapper 并注入到 JdbcOAuth2AuthorizationService 中
        // 关键在于这里要配置 ObjectMapper，使其能够识别并反序列化 UserDetail
        CustomOAuth2AuthorizationRowMapper rowMapper = new CustomOAuth2AuthorizationRowMapper(registeredClientRepository);
        this.jdbcService = new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
        this.jdbcService.setAuthorizationRowMapper(rowMapper);
        this.redisTemplate = redisTemplate;
    }

    /**
     * 自定义的 OAuth2AuthorizationRowMapper，用于配置 Jackson ObjectMapper
     * 以便能够正确反序列化 OAuth2Authorization 对象中可能包含的 UserDetail
     */
    private static class CustomOAuth2AuthorizationRowMapper extends JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper {
        public CustomOAuth2AuthorizationRowMapper(RegisteredClientRepository registeredClientRepository) {
            super(registeredClientRepository);

            ObjectMapper objectMapper = new ObjectMapper();
            ClassLoader classLoader = JdbcOAuth2AuthorizationService.class.getClassLoader();

            List<Module> securityModules = SecurityJackson2Modules.getModules(classLoader);
            objectMapper.registerModules(securityModules);
            objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());

            // 解决 "not in the allowlist" 的问题。
            objectMapper.addMixIn(UserDetail.class, UserDetailMixin.class);

            setObjectMapper(objectMapper);
        }
    }


    @Override
    public void save(OAuth2Authorization authorization) {
        if (authorization == null) {
            log.warn("尝试保存空的OAuth2Authorization对象");
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            jdbcService.save(authorization);
            // 按照设计，这里不缓存完整的authorization对象，只缓存token映射
            cacheAuthorization(authorization); // 这是一个空操作，但保留以示意图
            cacheTokenMappings(authorization);

            long duration = System.currentTimeMillis() - startTime;
            log.debug("OAuth2授权对象保存成功: id={}, 耗时={}ms", authorization.getId(), duration);

            // 记录保存操作指标
            recordMetric("save", duration);
        } catch (Exception e) {
            log.error("保存OAuth2授权对象失败: id={}", authorization.getId(), e);
            throw e;
        }
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        if (authorization == null) {
            log.warn("尝试删除空的OAuth2Authorization对象");
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            jdbcService.remove(authorization);
            removeFromCache(authorization);

            long duration = System.currentTimeMillis() - startTime;
            log.debug("OAuth2授权对象删除成功: id={}, 耗时={}ms", authorization.getId(), duration);

            // 记录删除操作指标
            recordMetric("remove", duration);
        } catch (Exception e) {
            log.error("删除OAuth2授权对象失败: id={}", authorization.getId(), e);
            throw e;
        }
    }

    @Override
    public OAuth2Authorization findById(String id) {
        if (!StringUtils.hasText(id)) {
            log.debug("尝试使用空的ID查找授权对象");
            return null;
        }

        long startTime = System.currentTimeMillis();
        try {
            // 从数据库查找并尝试缓存 (如果缓存策略允许)
            OAuth2Authorization authorization = findFromDatabaseAndCache(id);
            long duration = System.currentTimeMillis() - startTime;

            if (authorization != null) {
                log.debug("根据ID查找授权对象成功: id={}, 耗时={}ms", id, duration);
            } else {
                log.debug("根据ID未找到授权对象: id={}, 耗时={}ms", id, duration);
            }

            recordMetric("findById", duration);
            return authorization;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("根据ID查找授权对象时发生错误: id={}, 耗时={}ms", id, duration, e);
            return null;
        }
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (!StringUtils.hasText(token)) {
            log.debug("尝试使用空的Token查找授权对象");
            return null;
        }

        long startTime = System.currentTimeMillis();
        try {
            // 首先尝试从缓存中查找授权ID
            String authorizationId = findAuthorizationIdByToken(token, tokenType);
            if (authorizationId != null) {
                OAuth2Authorization authorization = findById(authorizationId); // 通过ID从数据库获取完整对象
                if (authorization != null) {
                    recordCacheHit();
                    long duration = System.currentTimeMillis() - startTime;
                    log.debug("从缓存中根据Token查找授权对象成功: token={}, id={}, 耗时={}ms",
                            maskToken(token), authorizationId, duration);
                    return authorization;
                }
                // 缓存的映射无效，清理它
                cleanupInvalidTokenMapping(token, tokenType);
            }

            // 缓存未命中，从数据库查找
            recordCacheMiss();
            OAuth2Authorization authorization = findByTokenFromDatabaseAndCache(token, tokenType);

            long duration = System.currentTimeMillis() - startTime;
            if (authorization != null) {
                log.debug("从数据库中根据Token查找授权对象成功: token={}, id={}, 耗时={}ms",
                        maskToken(token), authorization.getId(), duration);
            } else {
                log.debug("根据Token未找到授权对象: token={}, 耗时={}ms",
                        maskToken(token), duration);
            }

            return authorization;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("根据Token查找授权对象时发生错误: token={}, 耗时={}ms",
                    maskToken(token), duration, e);
            return null;
        }
    }

    private OAuth2Authorization findFromDatabaseAndCache(String id) {
        try {
            OAuth2Authorization authorization = jdbcService.findById(id);
            if (authorization != null) {
                // 按照设计，这里不缓存完整的authorization对象，只缓存token映射
                cacheAuthorization(authorization); // 这是一个空操作，但保留以示意图
            }
            return authorization;
        } catch (Exception e) {
            log.error("从数据库查找授权对象时发生错误: id={}", id, e);
            return null;
        }
    }

    private OAuth2Authorization findByTokenFromDatabaseAndCache(String token, OAuth2TokenType tokenType) {
        try {
            OAuth2Authorization authorization = jdbcService.findByToken(token, tokenType);
            if (authorization != null) {
                // 按照设计，这里不缓存完整的authorization对象，只缓存token映射
                cacheAuthorization(authorization); // 这是一个空操作，但保留以示意图
                cacheTokenMappings(authorization); // 缓存 token 到 authorization ID 的映射
            }
            return authorization;
        } catch (Exception e) {
            log.error("从数据库根据Token查找授权对象时发生错误", e);
            return null;
        }
    }

    private String findAuthorizationIdByToken(String token, OAuth2TokenType tokenType) {
        try {
            if (tokenType == null) {
                return findAuthorizationIdByAllTypes(token);
            }

            String tokenKey = TOKEN_PREFIX + tokenType.getValue() + ":" + token;
            Object cached = redisTemplate.opsForValue().get(tokenKey);
            return cached != null ? cached.toString() : null;
        } catch (Exception e) {
            log.warn("根据Token查找授权ID失败", e);
            return null;
        }
    }

    private String findAuthorizationIdByAllTypes(String token) {
        // 按照 Spring Security 的 OAuth2TokenType 值，通常有 "access", "refresh", "code"
        String[] tokenTypes = {"access", "refresh", "code"};

        for (String type : tokenTypes) {
            try {
                String tokenKey = TOKEN_PREFIX + type + ":" + token;
                Object cached = redisTemplate.opsForValue().get(tokenKey);
                if (cached != null) {
                    return cached.toString();
                }
            } catch (Exception e) {
                log.warn("检查Token类型时发生错误: {}", type);
            }
        }
        return null;
    }

    private void cacheAuthorization(OAuth2Authorization authorization) {
        // 不缓存完整的authorization对象，只缓存token映射
        // 这样避免了序列化UserDetail等复杂对象的安全风险
        if (authorization != null) {
            log.debug("跳过授权对象缓存，使用Token映射缓存+数据库回退策略: id={}",
                    authorization.getId());
        }
    }

    private void cacheTokenMappings(OAuth2Authorization authorization) {
        if (authorization == null) {
            return;
        }

        try {
            cacheTokenMapping(authorization, OAuth2AccessToken.class, "access", TOKEN_CACHE_TTL);
            cacheTokenMapping(authorization, OAuth2RefreshToken.class, "refresh", TOKEN_CACHE_TTL);
            cacheTokenMapping(authorization, OAuth2AuthorizationCode.class, "code", AUTH_CODE_TTL);

            log.debug("授权对象的Token映射缓存成功: id={}", authorization.getId());
        } catch (Exception e) {
            log.warn("缓存Token映射失败: id={}", authorization.getId(), e);
        }
    }

    private <T extends OAuth2Token> void cacheTokenMapping(OAuth2Authorization authorization,
                                                           Class<T> tokenClass, String type, Duration ttl) {
        OAuth2Authorization.Token<T> token = authorization.getToken(tokenClass);
        if (token != null && token.getToken().getTokenValue() != null) {
            String tokenKey = TOKEN_PREFIX + type + ":" + token.getToken().getTokenValue();
            redisTemplate.opsForValue().set(tokenKey, authorization.getId(), ttl);
        }
    }

    private void removeFromCache(OAuth2Authorization authorization) {
        if (authorization == null) {
            return;
        }
        try {
            // 虽然我们不缓存完整对象，但为了一致性仍然尝试删除可能存在的键
            redisTemplate.delete(REDIS_PREFIX + authorization.getId());

            // 删除所有相关的token映射
            removeTokenMappings(authorization);

            log.debug("从缓存中删除授权对象成功: id={}", authorization.getId());
        } catch (Exception e) {
            log.warn("从缓存中删除授权对象失败: id={}", authorization.getId(), e);
        }
    }

    private void removeTokenMappings(OAuth2Authorization authorization) {
        if (authorization == null) {
            return;
        }

        try {
            removeTokenMapping(authorization, OAuth2AccessToken.class, "access");
            removeTokenMapping(authorization, OAuth2RefreshToken.class, "refresh");
            removeTokenMapping(authorization, OAuth2AuthorizationCode.class, "code");

            log.debug("删除授权对象的所有Token映射成功: id={}", authorization.getId());
        } catch (Exception e) {
            log.warn("删除部分Token映射失败: id={}", authorization.getId(), e);
        }
    }

    private <T extends OAuth2Token> void removeTokenMapping(OAuth2Authorization authorization,
                                                            Class<T> tokenClass, String type) {
        OAuth2Authorization.Token<T> token = authorization.getToken(tokenClass);
        if (token != null && token.getToken().getTokenValue() != null) {
            String tokenKey = TOKEN_PREFIX + type + ":" + token.getToken().getTokenValue();
            redisTemplate.delete(tokenKey);
        }
    }

    private void cleanupInvalidTokenMapping(String token, OAuth2TokenType tokenType) {
        try {
            if (tokenType != null) {
                String tokenKey = TOKEN_PREFIX + tokenType.getValue() + ":" + token;
                redisTemplate.delete(tokenKey);
                log.debug("清理无效Token映射成功: type={}, token={}", tokenType.getValue(), maskToken(token));
            } else {
                String[] tokenTypes = {"access", "refresh", "code"};
                for (String type : tokenTypes) {
                    redisTemplate.delete(TOKEN_PREFIX + type + ":" + token);
                }
                log.debug("清理所有类型的无效Token映射成功: token={}", maskToken(token));
            }
        } catch (Exception e) {
            log.warn("清理无效Token映射失败: token={}", maskToken(token), e);
        }
    }

    /**
     * 记录操作指标
     */
    private void recordMetric(String operation, long duration) {
        try {
            String metricKey = METRICS_PREFIX + operation + ":" + getCurrentHour();
            redisTemplate.opsForValue().increment(metricKey);
            redisTemplate.expire(metricKey, Duration.ofDays(1));

            // 记录平均响应时间
            String durationKey = METRICS_PREFIX + operation + ":duration:" + getCurrentHour();
            redisTemplate.opsForValue().set(durationKey, duration, Duration.ofDays(1));
        } catch (Exception e) {
            log.debug("记录操作指标失败: {}", operation);
        }
    }

    /**
     * 记录缓存命中
     */
    private void recordCacheHit() {
        try {
            redisTemplate.opsForValue().increment(CACHE_HIT_KEY + ":" + getCurrentHour());
            redisTemplate.expire(CACHE_HIT_KEY + ":" + getCurrentHour(), Duration.ofDays(1));
        } catch (Exception e) {
            log.debug("记录缓存命中失败");
        }
    }

    /**
     * 记录缓存未命中
     */
    private void recordCacheMiss() {
        try {
            redisTemplate.opsForValue().increment(CACHE_MISS_KEY + ":" + getCurrentHour());
            redisTemplate.expire(CACHE_MISS_KEY + ":" + getCurrentHour(), Duration.ofDays(1));
        } catch (Exception e) {
            log.debug("记录缓存未命中失败");
        }
    }

    /**
     * 获取当前小时用于指标分组
     */
    private String getCurrentHour() {
        return String.valueOf(Instant.now().getEpochSecond() / 3600);
    }

    /**
     * 掩码token以保护敏感信息
     */
    private String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 4) + "***" + token.substring(token.length() - 4);
    }

    /**
     * 获取缓存统计信息（用于监控和调试）
     */
    public String getCacheStats() {
        try {
            String currentHour = getCurrentHour();
            Object hits = redisTemplate.opsForValue().get(CACHE_HIT_KEY + ":" + currentHour);
            Object misses = redisTemplate.opsForValue().get(CACHE_MISS_KEY + ":" + currentHour);

            long hitCount = hits != null ? Long.parseLong(hits.toString()) : 0;
            long missCount = misses != null ? Long.parseLong(misses.toString()) : 0;
            long total = hitCount + missCount;

            if (total == 0) {
                return "缓存统计: 当前小时暂无数据";
            }

            double hitRate = (double) hitCount / total * 100;
            return String.format("缓存统计 - 命中次数: %d, 未命中次数: %d, 命中率: %.2f%%",
                    hitCount, missCount, hitRate);
        } catch (Exception e) {
            return "缓存统计: 无法获取统计信息 - " + e.getMessage();
        }
    }

    /**
     * 清理过期的token映射（维护方法）
     */
    public void cleanupExpiredTokenMappings() {
        try {
            // 这个方法可以用于定期清理过期的token映射
            // 实际实现可能需要根据具体需求调整
            log.info("开始清理过期的Token映射");

            // 这里可以添加具体的清理逻辑
            // 例如：扫描所有token映射，检查对应的authorization是否仍然有效

            log.info("完成清理过期的Token映射");
        } catch (Exception e) {
            log.error("清理过期Token映射失败", e);
        }
    }
}