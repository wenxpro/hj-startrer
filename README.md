# V3 Starter 项目文档

本项目提供了一套完整的微服务认证与网关解决方案，包含以下三个核心模块：

- `v3-auth-client-starter`: 微服务客户端认证模块
- `v3-auth-server-starter`: 认证服务器模块
- `v3-gateway-starter`: API 网关模块，包含 DDoS 防护与 OpenAPI 文档聚合功能

## 模块功能概览

### v3-auth-client-starter
为微服务客户端提供 OAuth2 认证支持，主要功能包括：
- 自动配置 OAuth2 认证管理器
- 提供负载均衡的 WebClient 和 RestTemplate
- 支持 JWT 解码配置
- 提供环境配置后处理器

### v3-auth-server-starter
提供完整的认证服务器功能，主要功能包括：
- OAuth2 授权服务器配置
- JWT 生成与解码支持
- 安全配置（CORS、密码编码、用户认证等）
- 基于 Redis 的 OAuth2 授权服务
- 自定义 Token 服务
- 用户认证与权限控制

### v3-gateway-starter
提供 API 网关功能，主要功能包括：
- CORS 配置支持
- DDoS 防护功能
- OpenAPI 文档聚合与展示

## 快速开始

### 依赖要求
- Java 17 或更高版本
- Spring Boot 2.7+
- Spring Cloud Gateway
- Spring Security
- Redis（用于 DDoS 防护和 OAuth2 授权服务）

### 配置说明

#### 启用认证客户端
在客户端应用的 `application.yml` 中添加以下配置：
```yaml
cloud:
  auth:
    oauth2:
      enabled: true
      load-balancer-enabled: true
      jwt:
        default-jwk-set-uri: "http://auth-server/.well-known/jwks.json"
        default-issuer-uri: "http://auth-server"
      default-service:
        auth-service-name: "auth-server"
```

#### 启用认证服务器
在认证服务器应用的 `application.yml` 中添加以下配置：
```yaml
cloud:
  auth:
    server:
      enabled: true
      issuer: "http://auth-server"
      jwt:
        access-token-expires-in: 3600
        refresh-token-expires-in: 86400
      security:
        bcrypt-strength: 10
        remember-me-key: "remember-me"
        remember-me-token-validity-seconds: 86400
        public-paths:
          - "/login"
          - "/logout"
        admin-paths:
          - "/api/admin/**"
      cors:
        allowed-origins:
          - "*"
        allowed-methods:
          - "GET"
          - "POST"
        allowed-headers:
          - "*"
        exposed-headers:
          - "*"
        allow-credentials: true
        max-age: 3600
      oidc:
        enabled: true
        user-info-enabled: true
        client-registration-enabled: true
        default-email-domain: "example.com"
```

#### 启用网关模块
在网关应用的 `application.yml` 中添加以下配置：

##### CORS 配置
```yaml
cloud:
  gateway:
    cors:
      enabled: true
```

##### DDoS 防护配置
```yaml
cloud:
  gateway:
    ddos:
      enabled: true
      max-requests-per-minute: 100
      max-requests-per-second: 10
      blacklist-duration-minutes: 10
      suspicious-threshold: 50
      whitelist-ips: "127.0.0.1,192.168.1.0/24"
      check-interval-seconds: 60
```

##### OpenAPI 配置
```yaml
cloud:
  gateway:
    openapi:
      enabled: true
      discovery-enabled: true
      excluded-services:
        - "auth-server"
      services:
        - service-id: "user-service"
          display-name: "User Service"
          url: "/user-service/v3/api-docs"
          enabled: true
          version: "1.0"
```

## 使用说明

### 认证服务器
认证服务器提供标准的 OAuth2 授权流程，支持以下功能：
- 用户登录与认证
- JWT 令牌生成与验证
- 客户端注册与管理
- OIDC 用户信息支持

### 客户端认证
客户端通过自动配置的 `OAuth2AuthorizedClientManager` 与认证服务器交互，自动处理令牌获取与刷新。

### API 网关
网关模块提供以下功能：
- 跨域请求处理（CORS）
- DDoS 攻击防护，自动识别并限制异常请求
- OpenAPI 文档聚合，支持多个服务的文档统一展示

## 扩展与定制

### 自定义 CORS 策略
可以通过修改 `cloud.auth.server.cors` 配置项来自定义认证服务器的 CORS 策略。

### 调整 DDoS 防护参数
根据实际需求调整 `cloud.gateway.ddos` 下的配置参数，以适应不同的流量模式。

### 添加 OpenAPI 服务
在 `cloud.gateway.openapi.services` 配置项中添加新的服务配置，即可将服务的 OpenAPI 文档聚合到网关中。

## 贡献指南
欢迎贡献代码和文档。请遵循以下步骤：
1. Fork 本项目
2. 创建新分支 (`git checkout -b feature/new-feature`)
3. 提交更改 (`git commit -am 'Add new feature'`)
4. 推送分支 (`git push origin feature/new-feature`)
5. 创建 Pull Request

## 许可证
本项目采用 Apache-2.0 许可证。详情请参阅 [LICENSE](LICENSE) 文件。