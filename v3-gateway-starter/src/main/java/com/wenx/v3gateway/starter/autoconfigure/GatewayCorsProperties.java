package com.wenx.v3gateway.starter.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关 CORS 配置
 * P0.2：allowed-origin-patterns 收敛为白名单，未配置时保持通配以兼容存量环境
 *
 * @author wenx
 */
@Data
@ConfigurationProperties(prefix = "cloud.gateway.cors")
public class GatewayCorsProperties {

    /**
     * 是否启用 CORS
     */
    private boolean enabled = true;

    /**
     * 允许的源（Ant 风格），配合 allowCredentials 使用时应配置具体白名单
     */
    private List<String> allowedOriginPatterns = new ArrayList<>(List.of("*"));
}
