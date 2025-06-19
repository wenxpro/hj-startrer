### V3 Starter

```
v3-starter
├── v3-auth-client-starter     # OAuth2客户端 - 资源服务器认证
├── v3-auth-server-starter     # OAuth2服务端 - 认证授权服务器
├── v3-gateway-starter         # 网关组件 - API网关和路由
├── v3-seata-starter          # 分布式事务 - Seata集成
└── v3-storage-starter        # 文件存储 - MinIO/OSS集成
```

### v3-auth-client-starter

**配置属性：**
```yaml
cloud:
  auth:
    oauth2:
      enabled: true                          # 启用OAuth2功能
      load-balancer-enabled: true            # 启用负载均衡
      jwt:
        default-jwk-set-uri: http://v3-auth/oauth2/jwks
        default-issuer-uri: http://v3-auth
      default-service:
        auth-service-name: v3-auth
```

**使用场景：**
- 微服务需要调用其他受保护的服务
- 需要验证JWT访问令牌
- 集成服务发现和负载均衡

### v3-auth-server-starter 

**配置属性：**

```yaml
cloud:
  auth:
    server:
      enabled: true
      issuer: http://v3-auth
      jwt:
        access-token-expires-in: 3600      # 访问令牌过期时间(秒)
        refresh-token-expires-in: 604800   # 刷新令牌过期时间(秒)
      security:
        bcrypt-strength: 12
        public-paths:                       # 公开访问路径
          - "/oauth2/**"
          - "/login"
          - "/logout"
      cors:
        allowed-origins:
          - "http://localhost:*"
        allowed-methods:
          - "GET"
          - "POST"
          - "PUT"
          - "DELETE"
```

**使用场景：**
- 作为认证授权中心
- 颁发和验证JWT令牌
- 管理OAuth2客户端
- 用户登录和权限验证

### v3-gateway-starter - API网关

**配置属性：**
```yaml
cloud:
  gateway:
    # CORS配置
    cors:
      enabled: true
    
    # DDoS防护配置
    ddos:
      enabled: true
      max-requests-per-minute: 100
      max-requests-per-second: 10
      blacklist-duration-minutes: 30
      whitelist-ips: "127.0.0.1,::1"
    
    # OpenAPI文档聚合
    openapi:
      enabled: true
      discovery-enabled: true
      services:
        - service-id: v3-auth
          display-name: 认证授权服务
          url: /auth/v3/api-docs
        - service-id: v3-system
          display-name: 系统管理服务
          url: /system/v3/api-docs
```

**使用场景：**
- API网关统一入口
- 微服务路由和负载均衡
- 限流和安全防护
- API文档统一展示
