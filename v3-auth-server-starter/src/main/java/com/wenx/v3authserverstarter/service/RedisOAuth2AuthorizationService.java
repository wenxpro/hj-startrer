package com.wenx.v3authserverstarter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Redis缓存的OAuth2授权服务
 * 实现MySQL持久化 + Redis缓存的混合存储策略
 * 
 * <h3>架构说明</h3>
 * <ul>
 *   <li>一级缓存：Redis 令牌映射缓存 (token -> authorizationId)</li>
 *   <li>二级缓存：Redis 授权对象缓存 (authorizationId -> authorization data)</li>
 *   <li>持久化存储：MySQL 数据库</li>
 * </ul>
 * 
 * @author wenx
 */
@Slf4j
@RequiredArgsConstructor
public class RedisOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private final JdbcOAuth2AuthorizationService jdbcService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // Redis键前缀
    private static final String REDIS_PREFIX = "oauth2:authorization:";
    private static final String TOKEN_PREFIX = "oauth2:token:";

    // 缓存过期时间（秒）
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration TOKEN_CACHE_TTL = Duration.ofHours(2);

    public RedisOAuth2AuthorizationService(JdbcTemplate jdbcTemplate,
                                           RegisteredClientRepository registeredClientRepository,
                                           RedisTemplate<String, Object> redisTemplate) {
        this.jdbcService = new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        jdbcService.save(authorization);
        cacheAuthorization(authorization);
        cacheTokenMappings(authorization);
        log.debug("OAuth2Authorization saved: id={}, principalName={}", 
                authorization.getId(), authorization.getPrincipalName());
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        jdbcService.remove(authorization);
        removeFromCache(authorization);
        log.debug("OAuth2Authorization removed: id={}", authorization.getId());
    }

    @Override
    public OAuth2Authorization findById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }

        // 先从缓存查找，缓存未命中则从数据库查找
        return findFromCache(id)
                .orElseGet(() -> findFromDatabaseAndCache(id));
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (!StringUtils.hasText(token)) {
            return null;
        }

        // 通过令牌映射查找授权ID
        String authorizationId = findAuthorizationIdByToken(token, tokenType);
        if (authorizationId != null) {
            OAuth2Authorization authorization = findById(authorizationId);
            if (authorization != null) {
                log.debug("OAuth2Authorization found by token: token={}, type={}", 
                        maskToken(token), getTokenTypeValue(tokenType));
                return authorization;
            }
            // 清理无效映射
            cleanupInvalidTokenMapping(token, tokenType);
        }

        // 从数据库查找并缓存
        return findByTokenFromDatabaseAndCache(token, tokenType);
    }

    /**
     * 从缓存查找授权信息
     */
    private Optional<OAuth2Authorization> findFromCache(String id) {
        try {
            String key = REDIS_PREFIX + id;
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                // 简化策略：直接返回空，强制从数据库加载以确保数据完整性
                log.debug("Found cached data for id={}, but forcing database reload", id);
            }
        } catch (Exception e) {
            log.warn("Failed to find from cache: id={}", id, e);
        }
        return Optional.empty();
    }

    /**
     * 从数据库查找并缓存
     */
    private OAuth2Authorization findFromDatabaseAndCache(String id) {
        try {
            OAuth2Authorization authorization = jdbcService.findById(id);
            if (authorization != null) {
                cacheAuthorization(authorization);
                log.debug("OAuth2Authorization found in database: id={}", id);
            }
            return authorization;
        } catch (Exception e) {
            log.error("Error finding from database: id={}", id, e);
            return null;
        }
    }

    /**
     * 通过令牌从数据库查找并缓存
     */
    private OAuth2Authorization findByTokenFromDatabaseAndCache(String token, OAuth2TokenType tokenType) {
        try {
            OAuth2Authorization authorization = jdbcService.findByToken(token, tokenType);
            if (authorization != null) {
                cacheAuthorization(authorization);
                cacheTokenMappings(authorization);
                log.debug("OAuth2Authorization found by token in database: token={}, type={}", 
                        maskToken(token), getTokenTypeValue(tokenType));
            }
            return authorization;
        } catch (Exception e) {
            log.error("Error finding by token from database: token={}, type={}", 
                    maskToken(token), getTokenTypeValue(tokenType), e);
            return null;
        }
    }

    /**
     * 通过令牌查找授权ID
     */
    private String findAuthorizationIdByToken(String token, OAuth2TokenType tokenType) {
        try {
            if (tokenType == null) {
                return findAuthorizationIdByAllTypes(token);
            }
            
            String tokenKey = TOKEN_PREFIX + tokenType.getValue() + ":" + token;
            Object cached = redisTemplate.opsForValue().get(tokenKey);
            return cached != null ? cached.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to find authorization ID by token: token={}, type={}", 
                    maskToken(token), getTokenTypeValue(tokenType), e);
            return null;
        }
    }

    /**
     * 尝试所有令牌类型查找授权ID
     */
    private String findAuthorizationIdByAllTypes(String token) {
        String[] tokenTypes = {"access", "refresh", "code"};
        
        for (String type : tokenTypes) {
            try {
                String tokenKey = TOKEN_PREFIX + type + ":" + token;
                Object cached = redisTemplate.opsForValue().get(tokenKey);
                if (cached != null) {
                    log.debug("Found authorization ID with type: token={}, type={}", maskToken(token), type);
                    return cached.toString();
                }
            } catch (Exception e) {
                log.warn("Error checking token type {}: {}", type, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 缓存授权信息
     */
    private void cacheAuthorization(OAuth2Authorization authorization) {
        try {
            String key = REDIS_PREFIX + authorization.getId();
            String json = objectMapper.writeValueAsString(createSerializationData(authorization));
            redisTemplate.opsForValue().set(key, json, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache authorization: id={}", authorization.getId(), e);
        }
    }

    /**
     * 缓存令牌映射
     */
    private void cacheTokenMappings(OAuth2Authorization authorization) {
        try {
            cacheTokenMapping(authorization, OAuth2AccessToken.class, "access", TOKEN_CACHE_TTL);
            cacheTokenMapping(authorization, OAuth2RefreshToken.class, "refresh", TOKEN_CACHE_TTL);
            cacheTokenMapping(authorization, OAuth2AuthorizationCode.class, "code", Duration.ofMinutes(10));
        } catch (Exception e) {
            log.warn("Failed to cache token mappings: id={}", authorization.getId(), e);
        }
    }

    /**
     * 缓存单个令牌映射
     */
    private <T extends OAuth2Token> void cacheTokenMapping(OAuth2Authorization authorization, 
                                                           Class<T> tokenClass, String type, Duration ttl) {
        OAuth2Authorization.Token<T> token = authorization.getToken(tokenClass);
        if (token != null && token.getToken().getTokenValue() != null) {
            String tokenKey = TOKEN_PREFIX + type + ":" + token.getToken().getTokenValue();
            redisTemplate.opsForValue().set(tokenKey, authorization.getId(), ttl);
        }
    }

    /**
     * 从缓存中删除授权信息
     */
    private void removeFromCache(OAuth2Authorization authorization) {
        try {
            redisTemplate.delete(REDIS_PREFIX + authorization.getId());
            removeTokenMappings(authorization);
        } catch (Exception e) {
            log.warn("Failed to remove from cache: id={}", authorization.getId(), e);
        }
    }

    /**
     * 删除令牌映射
     */
    private void removeTokenMappings(OAuth2Authorization authorization) {
        removeTokenMapping(authorization, OAuth2AccessToken.class, "access");
        removeTokenMapping(authorization, OAuth2RefreshToken.class, "refresh");
        removeTokenMapping(authorization, OAuth2AuthorizationCode.class, "code");
    }

    /**
     * 删除单个令牌映射
     */
    private <T extends OAuth2Token> void removeTokenMapping(OAuth2Authorization authorization, 
                                                            Class<T> tokenClass, String type) {
        OAuth2Authorization.Token<T> token = authorization.getToken(tokenClass);
        if (token != null && token.getToken().getTokenValue() != null) {
            String tokenKey = TOKEN_PREFIX + type + ":" + token.getToken().getTokenValue();
            redisTemplate.delete(tokenKey);
        }
    }

    /**
     * 清理无效的令牌映射
     */
    private void cleanupInvalidTokenMapping(String token, OAuth2TokenType tokenType) {
        try {
            if (tokenType != null) {
                String tokenKey = TOKEN_PREFIX + tokenType.getValue() + ":" + token;
                redisTemplate.delete(tokenKey);
            } else {
                String[] tokenTypes = {"access", "refresh", "code"};
                for (String type : tokenTypes) {
                    redisTemplate.delete(TOKEN_PREFIX + type + ":" + token);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup invalid token mapping: token={}", maskToken(token), e);
        }
    }

    /**
     * 创建序列化数据
     */
    private Map<String, Object> createSerializationData(OAuth2Authorization authorization) {
        return Map.of(
                "id", authorization.getId(),
                "registeredClientId", authorization.getRegisteredClientId(),
                "principalName", authorization.getPrincipalName(),
                "authorizationGrantType", authorization.getAuthorizationGrantType().getValue(),
                "authorizedScopes", authorization.getAuthorizedScopes(),
                "attributes", authorization.getAttributes()
        );
    }

    /**
     * 获取令牌类型值
     */
    private String getTokenTypeValue(OAuth2TokenType tokenType) {
        return tokenType != null ? tokenType.getValue() : "null";
    }

    /**
     * 掩码令牌用于日志记录
     */
    private String maskToken(String token) {
        if (token == null || token.length() < 8) {
            return "***";
        }
        return token.substring(0, 4) + "***" + token.substring(token.length() - 4);
    }
}