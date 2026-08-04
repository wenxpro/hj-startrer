package com.wenx.v3authserverstarter.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.wenx.v3authserverstarter.properties.CloudAuthServerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 云JWT自动配置
 *
 * @author wenx
 * @description 提供JWT编码器和解码器的自动配置（P1.3：RSA 密钥改为外部配置加载，重启不失效、多实例一致）
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass({JwtEncoder.class, JwtDecoder.class})
@ConditionalOnProperty(prefix = "cloud.auth.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@EnableConfigurationProperties(CloudAuthServerProperties.class)
public class CloudJwtConfiguration {

    private final CloudAuthServerProperties properties;

    /**
     * JWT密钥源（P1.3）
     * 从配置加载 RSA 密钥对：值为 PEM 内容或本地文件路径；
     * 未配置密钥则拒绝启动，杜绝随机密钥导致的重启失效/多实例不一致。
     */
    @Bean
    @ConditionalOnMissingBean
    public JWKSource<SecurityContext> jwkSource() {
        String privateKeyPem = resolveKeyContent(properties.getJwt().getPrivateKey(), "cloud.auth.server.jwt.private-key");
        String publicKeyPem = resolveKeyContent(properties.getJwt().getPublicKey(), "cloud.auth.server.jwt.public-key");

        RSAPrivateKey privateKey = parsePrivateKey(privateKeyPem);
        RSAPublicKey publicKey = parsePublicKey(publicKeyPem);

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(properties.getJwt().getKid())
                .build();

        JWKSet jwkSet = new JWKSet(rsaKey);
        log.info("JWT RSA 密钥已从配置加载，kid: {}", rsaKey.getKeyID());

        return new ImmutableJWKSet<>(jwkSet);
    }

    /**
     * JWT编码器
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * JWT解码器
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * 解析密钥配置：值为现有文件路径则读取文件内容，否则按 PEM 内容处理
     */
    private String resolveKeyContent(String configured, String propertyName) {
        if (!StringUtils.hasText(configured)) {
            throw new IllegalStateException("未配置 " + propertyName + "（P1.3 起必须显式配置 RSA 密钥，禁止随机生成）。"
                    + "本地开发请将密钥文件放入 gitignore 目录并在 application-dev.yml 配置路径，生产经 Nacos/挂载注入。");
        }
        Path path = Paths.get(configured.trim());
        if (Files.isRegularFile(path)) {
            try {
                return Files.readString(path);
            } catch (IOException e) {
                throw new IllegalStateException("读取密钥文件失败: " + configured, e);
            }
        }
        return configured;
    }

    /**
     * 解析 PKCS8 PEM 私钥
     */
    private RSAPrivateKey parsePrivateKey(String pem) {
        try {
            String base64 = stripPem(pem);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalStateException("解析 RSA 私钥失败（需 PKCS8 PEM）", e);
        }
    }

    /**
     * 解析 SPKI PEM 公钥
     */
    private RSAPublicKey parsePublicKey(String pem) {
        try {
            String base64 = stripPem(pem);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.getDecoder().decode(base64));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("解析 RSA 公钥失败（需 SPKI PEM）", e);
        }
    }

    /**
     * 去掉 PEM 头尾标记与换行（兼容 PKCS8 / PKCS1 / SPKI 标记）
     */
    private String stripPem(String pem) {
        StringBuilder sb = new StringBuilder();
        for (String line : pem.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-----BEGIN") || trimmed.startsWith("-----END")) {
                continue;
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }
}
