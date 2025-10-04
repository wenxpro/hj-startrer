package com.wenx.v3authserverstarter.service;

import com.wenx.v3authserverstarter.properties.CloudAuthServerProperties;
import com.wenx.v3secure.user.UserDetail;
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
        JwtClaimsSet claims = buildAccessTokenClaims(authentication);
        return encodeJwt(claims);
    }
    
    /**
     * 生成刷新令牌
     */
    public String generateRefreshToken(Authentication authentication) {
        JwtClaimsSet claims = buildRefreshTokenClaims(authentication);
        return encodeJwt(claims);
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
        response.put("scope", buildScopeString(authentication));
        response.put("user_info", buildUserInfo(authentication));
        
        return response;
    }
    
    /**
     * 构建访问令牌Claims
     */
    private JwtClaimsSet buildAccessTokenClaims(Authentication authentication) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getJwt().getAccessTokenExpiresIn(), ChronoUnit.SECONDS);
        
        JwtClaimsSet.Builder claimsBuilder = buildBaseClaims(authentication, now, expiresAt)
                .claim("authorities", extractAuthorities(authentication))
                .claim("token_type", "access_token");
        
        // 添加用户详细信息到JWT claims中
        addUserDetailsToClaims(claimsBuilder, authentication);
        
        return claimsBuilder.build();
    }
    
    /**
     * 构建刷新令牌Claims
     */
    private JwtClaimsSet buildRefreshTokenClaims(Authentication authentication) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getJwt().getRefreshTokenExpiresIn(), ChronoUnit.SECONDS);
        
        return buildBaseClaims(authentication, now, expiresAt)
                .claim("token_type", "refresh_token")
                .build();
    }
    
    /**
     * 构建基础Claims
     */
    private JwtClaimsSet.Builder buildBaseClaims(Authentication authentication, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(authentication.getName())
                .id(UUID.randomUUID().toString());
    }
    
    /**
     * 添加用户详细信息到Claims中
     */
    private void addUserDetailsToClaims(JwtClaimsSet.Builder claimsBuilder, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof UserDetail)) {
            log.warn("认证主体不是UserDetail类型，无法添加完整用户信息到JWT: principalType={}", 
                    authentication.getPrincipal().getClass().getSimpleName());
            return;
        }
        
        UserDetail userDetail = (UserDetail) authentication.getPrincipal();
        
        // 添加用户基本信息 - CustomJwtConverter需要这些信息来查询权限
        addClaimIfNotNull(claimsBuilder, "userId", userDetail.getId());
        addClaimIfNotNull(claimsBuilder, "username", userDetail.getUsername());
        claimsBuilder.claim("isPlatformUser", userDetail.isPlatformUser());
        
        // 添加用户扩展信息
        addClaimIfNotNull(claimsBuilder, "email", userDetail.getEmail());
        addClaimIfNotNull(claimsBuilder, "mobile", userDetail.getMobile());
        addClaimIfNotNull(claimsBuilder, "avatar", userDetail.getAvatar());
        addClaimIfNotNull(claimsBuilder, "status", userDetail.getStatus());
        addClaimIfNotNull(claimsBuilder, "departmentId", userDetail.getDepartmentId());
        addClaimIfNotNull(claimsBuilder, "superAdmin", userDetail.getSuperAdmin());
        
        log.info("JWT生成包含完整用户信息: userId={}, username={}, isPlatformUser={}", 
                userDetail.getId(), userDetail.getUsername(), userDetail.isPlatformUser());
    }
    
    /**
     * 添加非空Claim
     */
    private void addClaimIfNotNull(JwtClaimsSet.Builder claimsBuilder, String key, Object value) {
        if (value != null) {
            claimsBuilder.claim(key, value);
        }
    }
    
    /**
     * 提取权限列表
     */
    private java.util.List<String> extractAuthorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
    }
    
    /**
     * 构建权限范围字符串
     */
    private String buildScopeString(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(" "));
    }
    
    /**
     * 构建用户信息
     */
    private Map<String, Object> buildUserInfo(Authentication authentication) {
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("username", authentication.getName());
        userInfo.put("authorities", authentication.getAuthorities());
        return userInfo;
    }
    
    /**
     * 编码JWT
     */
    private String encodeJwt(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(() -> "RS256").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}