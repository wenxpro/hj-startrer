### V3 Starter

```
v3-starter
├── v3-auth-client-starter        # OAuth2客户端 - 资源服务器认证
├── v3-auth-server-starter        # OAuth2服务端 - 认证授权服务器
├── v3-dynamic-datasource-starter # 动态数据源 - 多租户数据源管理
├── v3-gateway-starter            # 网关组件 - API网关和路由
├── v3-log-starter               # 日志组件 - Jaeger链路追踪
├── v3-seata-starter             # 分布式事务 - Seata集成（开发中）
└── v3-storage-starter           # 文件存储 - MinIO/OSS集成（开发中）
```

### v3-auth-client-starter

**核心功能：**
- OAuth2客户端自动配置
- JWT令牌解码和验证
- 负载均衡支持
- Feign客户端OAuth2集成
- 服务间调用认证

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
- 资源服务器保护API端点

### v3-auth-server-starter

**核心功能：**
- OAuth2授权服务器配置
- JWT令牌生成和管理
- 客户端注册管理（基于JDBC）
- Redis授权信息存储
- OIDC用户信息端点
- 自定义认证处理器
- CORS跨域支持

**配置属性：**
```yaml
cloud:
  auth:
    server:
      enabled: true                          # 启用OAuth2授权服务器
      issuer-uri: http://localhost:8080      # 授权服务器地址
      jwk-set-uri: /oauth2/jwks             # JWK Set端点
      authorization-endpoint: /oauth2/authorize
      token-endpoint: /oauth2/token
      user-info-endpoint: /oauth2/userinfo
      cors:
        enabled: true                        # 启用CORS
        allowed-origins: "*"
```

**使用场景：**
- 统一认证授权中心
- 多应用单点登录（SSO）
- API访问控制
- 用户身份验证
- 第三方应用授权

---

### v3-dynamic-datasource-starter

**核心功能：**
- 多租户数据源动态切换
- 租户上下文自动检测
- 数据源运行时添加/移除
- HTTP请求拦截器
- 租户ID自动解析
- 数据源连接池管理

**配置属性：**
```yaml
cloud:
  dynamic-datasource:
    enabled: true                          # 启用动态数据源
    tenant-detection:
      enabled: true                        # 启用租户检测
      header-name: X-Tenant-Id            # 租户ID请求头
      parameter-name: tenantId             # 租户ID参数名
    interceptor:
      enabled: true                        # 启用拦截器
      include-patterns: "/**"              # 拦截路径
      exclude-patterns: "/health,/actuator/**"
```

**使用场景：**
- SaaS多租户应用
- 数据隔离需求
- 动态数据源管理
- 租户数据库分离

---

### v3-gateway-starter

**核心功能：**
- API网关路由配置
- CORS跨域支持
- OpenAPI文档聚合
- DDoS防护
- 负载均衡
- 服务发现集成

**配置属性：**
```yaml
cloud:
  gateway:
    cors:
      enabled: true                        # 启用CORS
      allowed-origins: "*"
      allowed-methods: "*"
      allowed-headers: "*"
    ddos-protection:
      enabled: true                        # 启用DDoS防护
      max-requests-per-minute: 1000
```

**使用场景：**
- 微服务API统一入口
- 请求路由和负载均衡
- 跨域请求处理
- API文档聚合
- 安全防护

---

### v3-log-starter

**核心功能：**
- Jaeger链路追踪
- OpenTelemetry集成
- 分布式追踪数据收集
- 性能监控
- 调用链分析
- 自动埋点

**配置属性：**
```yaml
cloud:
  jaeger:
    enabled: true                          # 启用Jaeger追踪
    service-name: v3-cloud-service         # 服务名称
    collector-endpoint: http://localhost:14250
    sampler-probability: 1.0               # 采样率
    flush-interval: 1000                   # 刷新间隔(ms)
    max-queue-size: 100                    # 最大队列大小
```

**使用场景：**
- 分布式系统调用链追踪
- 性能瓶颈分析
- 错误定位和调试
- 服务依赖关系分析
- 系统监控告警

---