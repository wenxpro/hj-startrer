package com.wenx.v3gateway.starter.domain;

import com.wenx.v3gateway.starter.enums.UserType;

/**
 * 用户上下文对象
 * 存储从JWT Token解析出的用户信息，用于限流策略判断
 * 
 * @author wenx
 */
public class UserContext {
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 组织ID
     */
    private String departmentId;
    
    /**
     * 是否为平台用户
     */
    private Boolean isPlatformUser;
    
    /**
     * 用户类型
     */
    private UserType userType;
    
    /**
     * 客户端IP
     */
    private String clientIp;
    
    /**
     * 请求路径
     */
    private String requestPath;
    
    /**
     * 请求方法
     */
    private String requestMethod;
    
    /**
     * 是否为匿名用户
     */
    private boolean anonymous;
    
    public UserContext() {
        this.anonymous = true;
        this.userType = UserType.ANONYMOUS;
    }
    
    public UserContext(String userId, String username, String tenantId, Boolean isPlatformUser) {
        this.userId = userId;
        this.username = username;
        this.tenantId = tenantId;
        this.isPlatformUser = isPlatformUser;
        this.anonymous = (username == null || username.trim().isEmpty());
        this.userType = UserType.determineUserType(username, isPlatformUser, tenantId);
    }
    
    /**
     * 创建匿名用户上下文
     */
    public static UserContext createAnonymous(String clientIp, String requestPath, String requestMethod) {
        UserContext context = new UserContext();
        context.setClientIp(clientIp);
        context.setRequestPath(requestPath);
        context.setRequestMethod(requestMethod);
        return context;
    }
    
    /**
     * 创建认证用户上下文
     */
    public static UserContext createAuthenticated(String userId, String username, String tenantId, 
                                                 Boolean isPlatformUser, String clientIp, 
                                                 String requestPath, String requestMethod) {
        UserContext context = new UserContext(userId, username, tenantId, isPlatformUser);
        context.setClientIp(clientIp);
        context.setRequestPath(requestPath);
        context.setRequestMethod(requestMethod);
        return context;
    }
    
    /**
     * 获取限流键
     * 根据用户类型生成不同的限流键策略
     */
    public String getRateLimitKey(String pathPattern) {
        StringBuilder keyBuilder = new StringBuilder("rate_limit:");
        
        switch (userType) {
            case ANONYMOUS:
                // 匿名用户使用IP作为限流键
                keyBuilder.append("anonymous:").append(clientIp);
                break;
            case SYSTEM:
                // 系统用户使用用户名作为限流键
                keyBuilder.append("system:").append(username);
                break;
            case PLATFORM:
                // 平台用户使用用户ID作为限流键
                keyBuilder.append("platform:").append(userId);
                break;
            case TENANT:
                // 租户用户使用租户ID+用户ID作为限流键
                keyBuilder.append("tenant:").append(tenantId).append(":").append(userId);
                break;
        }
        
        // 添加路径模式
        if (pathPattern != null && !pathPattern.isEmpty()) {
            keyBuilder.append(":").append(pathPattern.replaceAll("[^a-zA-Z0-9_-]", "_"));
        }
        
        return keyBuilder.toString();
    }
    
    // Getters and Setters
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
        this.anonymous = (username == null || username.trim().isEmpty());
        this.userType = UserType.determineUserType(username, isPlatformUser, tenantId);
    }
    
    public String getTenantId() {
        return tenantId;
    }
    
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
        this.userType = UserType.determineUserType(username, isPlatformUser, tenantId);
    }
    
    public String getDepartmentId() {
        return departmentId;
    }
    
    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
    }
    
    public Boolean getIsPlatformUser() {
        return isPlatformUser;
    }
    
    public void setIsPlatformUser(Boolean isPlatformUser) {
        this.isPlatformUser = isPlatformUser;
        this.userType = UserType.determineUserType(username, isPlatformUser, tenantId);
    }
    
    public UserType getUserType() {
        return userType;
    }
    
    public void setUserType(UserType userType) {
        this.userType = userType;
    }
    
    public String getClientIp() {
        return clientIp;
    }
    
    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }
    
    public String getRequestPath() {
        return requestPath;
    }
    
    public void setRequestPath(String requestPath) {
        this.requestPath = requestPath;
    }
    
    public String getRequestMethod() {
        return requestMethod;
    }
    
    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }
    
    public boolean isAnonymous() {
        return anonymous;
    }
    
    public void setAnonymous(boolean anonymous) {
        this.anonymous = anonymous;
    }
    
    @Override
    public String toString() {
        return "UserContext{" +
                "userId='" + userId + '\'' +
                ", username='" + username + '\'' +
                ", tenantId='" + tenantId + '\'' +
                ", userType=" + userType +
                ", clientIp='" + clientIp + '\'' +
                ", requestPath='" + requestPath + '\'' +
                ", anonymous=" + anonymous +
                '}';
    }
}