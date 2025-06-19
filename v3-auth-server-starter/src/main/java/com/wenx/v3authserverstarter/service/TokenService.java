package com.wenx.v3authserverstarter.service;

import com.wenx.v3authserverstarter.properties.CloudAuthServerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Token服务
 * 
 * @author wenx
 * @description 处理JWT token的生成和管理
 */
@Slf4j
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final CloudAuthServerProperties properties;
    
    /**
     * 生成访问令牌
     */
    public String generateAccessToken(Authentication authentication) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getJwt().getAccessTokenExpiresIn(), ChronoUnit.SECONDS);
        
        // 构建claims
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(authentication.getName())
                .claim("authorities", authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList()))
                .claim("token_type", "access_token")
                .id(UUID.randomUUID().toString())
                .build();
        
        JwsHeader header = JwsHeader.with(() -> "RS256").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
    
    /**
     * 生成刷新令牌
     */
    public String generateRefreshToken(Authentication authentication) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getJwt().getRefreshTokenExpiresIn(), ChronoUnit.SECONDS);
        
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(authentication.getName())
                .claim("token_type", "refresh_token")
                .id(UUID.randomUUID().toString())
                .build();
        
        JwsHeader header = JwsHeader.with(() -> "RS256").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
    
    /**
     * 生成Token响应
     */
    public Map<String, Object> generateTokenResponse(Authentication authentication) {
        String accessToken = generateAccessToken(authentication);
        String refreshToken = generateRefreshToken(authentication);
        
        Map<String, Object> response = new HashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", properties.getJwt().getAccessTokenExpiresIn());
        response.put("refresh_token", refreshToken);
        response.put("scope", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(" ")));
        
        // 添加用户信息
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("username", authentication.getName());
        userInfo.put("authorities", authentication.getAuthorities());
        response.put("user_info", userInfo);
        
        return response;
    }
} 