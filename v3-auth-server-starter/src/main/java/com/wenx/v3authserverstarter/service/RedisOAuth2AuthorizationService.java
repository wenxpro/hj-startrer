package com.wenx.v3authserverstarter.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Redis缓存的OAuth2授权服务
 * 实现MySQL持久化 + Redis缓存的混合存储策略
 */
@Slf4j
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
        // 1. 保存到MySQL数据库（持久化）
        jdbcService.save(authorization);

        // 2. 缓存到Redis
        cacheAuthorization(authorization);

        // 3. 缓存令牌映射
        cacheTokenMappings(authorization);

        log.debug("OAuth2Authorization saved: id={}, principalName={}",
                authorization.getId(), authorization.getPrincipalName());
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        // 1. 从MySQL删除
        jdbcService.remove(authorization);

        // 2. 从Redis删除
        removeFromCache(authorization);

        log.debug("OAuth2Authorization removed: id={}", authorization.getId());
    }

    @Override
    public OAuth2Authorization findById(String id) {
        // 1. 先从Redis缓存查找
        OAuth2Authorization cached = findFromCache(id);
        if (cached != null) {
            log.debug("OAuth2Authorization found in cache: id={}", id);
            return cached;
        }

        // 2. 缓存未命中，从MySQL查找
        OAuth2Authorization authorization = jdbcService.findById(id);
        if (authorization != null) {
            // 重新缓存
            cacheAuthorization(authorization);
            log.debug("OAuth2Authorization found in database and cached: id={}", id);
        }

        return authorization;
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (!StringUtils.hasText(token)) {
            return null;
        }

        // 1. 先从Redis令牌映射查找授权ID
        String authorizationId = findAuthorizationIdByToken(token, tokenType);
        if (authorizationId != null) {
            OAuth2Authorization authorization = findById(authorizationId);
            if (authorization != null) {
                log.debug("OAuth2Authorization found by token in cache: token={}, type={}",
                        maskToken(token), tokenType.getValue());
                return authorization;
            }
        }

        // 2. 缓存未命中，从MySQL查找
        OAuth2Authorization authorization = jdbcService.findByToken(token, tokenType);
        if (authorization != null) {
            // 重新缓存
            cacheAuthorization(authorization);
            cacheTokenMappings(authorization);
            log.debug("OAuth2Authorization found by token in database and cached: token={}, type={}",
                    maskToken(token), tokenType.getValue());
        }

        return authorization;
    }

    /**
     * 缓存授权信息到Redis
     */
    private void cacheAuthorization(OAuth2Authorization authorization) {
        try {
            String key = REDIS_PREFIX + authorization.getId();
            String json = objectMapper.writeValueAsString(serializeAuthorization(authorization));
            redisTemplate.opsForValue().set(key, json, CACHE_TTL);
        } catch (Exception e) {
            log.error("Failed to cache OAuth2Authorization: id={}", authorization.getId(), e);
        }
    }

    /**
     * 缓存令牌到授权ID的映射
     */
    private void cacheTokenMappings(OAuth2Authorization authorization) {
        try {
            // 缓存访问令牌映射
            OAuth2Authorization.Token<OAuth2AccessToken> accessToken =
                    authorization.getToken(OAuth2AccessToken.class);
            if (accessToken != null && accessToken.getToken().getTokenValue() != null) {
                String tokenKey = TOKEN_PREFIX + "access:" + accessToken.getToken().getTokenValue();
                redisTemplate.opsForValue().set(tokenKey, authorization.getId(), TOKEN_CACHE_TTL);
            }

            // 缓存刷新令牌映射
            OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken =
                    authorization.getToken(OAuth2RefreshToken.class);
            if (refreshToken != null && refreshToken.getToken().getTokenValue() != null) {
                String tokenKey = TOKEN_PREFIX + "refresh:" + refreshToken.getToken().getTokenValue();
                redisTemplate.opsForValue().set(tokenKey, authorization.getId(), TOKEN_CACHE_TTL);
            }

            // 缓存授权码映射
            OAuth2Authorization.Token<OAuth2AuthorizationCode> authorizationCode =
                    authorization.getToken(OAuth2AuthorizationCode.class);
            if (authorizationCode != null && authorizationCode.getToken().getTokenValue() != null) {
                String tokenKey = TOKEN_PREFIX + "code:" + authorizationCode.getToken().getTokenValue();
                redisTemplate.opsForValue().set(tokenKey, authorization.getId(), Duration.ofMinutes(10));
            }

        } catch (Exception e) {
            log.error("Failed to cache token mappings: id={}", authorization.getId(), e);
        }
    }

    /**
     * 从Redis缓存查找授权信息
     */
    private OAuth2Authorization findFromCache(String id) {
        try {
            String key = REDIS_PREFIX + id;
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                Map<String, Object> data = objectMapper.readValue(cached.toString(),
                        new TypeReference<Map<String, Object>>() {});
                return deserializeAuthorization(data);
            }
        } catch (Exception e) {
            log.error("Failed to find OAuth2Authorization from cache: id={}", id, e);
        }
        return null;
    }

    /**
     * 通过令牌查找授权ID
     */
    private String findAuthorizationIdByToken(String token, OAuth2TokenType tokenType) {
        try {
            String tokenKey = TOKEN_PREFIX + tokenType.getValue() + ":" + token;
            Object cached = redisTemplate.opsForValue().get(tokenKey);
            return cached != null ? cached.toString() : null;
        } catch (Exception e) {
            log.error("Failed to find authorization ID by token: token={}, type={}",
                    maskToken(token), tokenType.getValue(), e);
            return null;
        }
    }

    /**
     * 从缓存中删除授权信息
     */
    private void removeFromCache(OAuth2Authorization authorization) {
        try {
            // 删除授权信息缓存
            String key = REDIS_PREFIX + authorization.getId();
            redisTemplate.delete(key);

            // 删除令牌映射缓存
            removeTokenMappings(authorization);

        } catch (Exception e) {
            log.error("Failed to remove OAuth2Authorization from cache: id={}", authorization.getId(), e);
        }
    }

    /**
     * 删除令牌映射缓存
     */
    private void removeTokenMappings(OAuth2Authorization authorization) {
        try {
            // 删除访问令牌映射
            OAuth2Authorization.Token<OAuth2AccessToken> accessToken =
                    authorization.getToken(OAuth2AccessToken.class);
            if (accessToken != null && accessToken.getToken().getTokenValue() != null) {
                String tokenKey = TOKEN_PREFIX + "access:" + accessToken.getToken().getTokenValue();
                redisTemplate.delete(tokenKey);
            }

            // 删除刷新令牌映射
            OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken =
                    authorization.getToken(OAuth2RefreshToken.class);
            if (refreshToken != null && refreshToken.getToken().getTokenValue() != null) {
                String tokenKey = TOKEN_PREFIX + "refresh:" + refreshToken.getToken().getTokenValue();
                redisTemplate.delete(tokenKey);
            }

            // 删除授权码映射
            OAuth2Authorization.Token<OAuth2AuthorizationCode> authorizationCode =
                    authorization.getToken(OAuth2AuthorizationCode.class);
            if (authorizationCode != null && authorizationCode.getToken().getTokenValue() != null) {
                String tokenKey = TOKEN_PREFIX + "code:" + authorizationCode.getToken().getTokenValue();
                redisTemplate.delete(tokenKey);
            }

        } catch (Exception e) {
            log.error("Failed to remove token mappings from cache: id={}", authorization.getId(), e);
        }
    }

    /**
     * 序列化授权对象用于缓存
     */
    private Map<String, Object> serializeAuthorization(OAuth2Authorization authorization) {
        // 这里简化实现，实际项目中需要完整的序列化逻辑
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
     * 反序列化授权对象
     */
    private OAuth2Authorization deserializeAuthorization(Map<String, Object> data) {
        // 这里简化实现，实际项目中需要完整的反序列化逻辑
        // 如果缓存的数据不完整，返回null，将从数据库重新加载
        return null;
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