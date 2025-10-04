package com.wenx.v3gateway.starter.enums;

/**
 * 用户类型枚举
 * 用于区分不同类型用户的限流策略
 * 
 * @author wenx
 */
public enum UserType {
    
    /**
     * 匿名用户 - 未认证用户
     * 最严格的限流策略
     */
    ANONYMOUS("anonymous", "匿名用户", 1),
    
    /**
     * 系统用户 - 内部系统用户
     * 较宽松的限流策略
     */
    SYSTEM("system", "系统用户", 2),
    
    /**
     * 平台用户 - 平台管理员用户
     * 宽松的限流策略
     */
    PLATFORM("platform", "平台用户", 3),
    
    /**
     * 租户用户 - 租户下的普通用户
     * 中等限流策略
     */
    TENANT("tenant", "租户用户", 4);
    
    private final String code;
    private final String description;
    private final int priority;
    
    UserType(String code, String description, int priority) {
        this.code = code;
        this.description = description;
        this.priority = priority;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    public int getPriority() {
        return priority;
    }
    
    /**
     * 根据用户名判断用户类型
     * 
     * @param username 用户名
     * @param isPlatformUser 是否为平台用户
     * @param tenantId 租户ID
     * @return 用户类型
     */
    public static UserType determineUserType(String username, Boolean isPlatformUser, String tenantId) {
        // 匿名用户
        if (username == null || username.trim().isEmpty()) {
            return ANONYMOUS;
        }
        
        // 平台用户
        if (Boolean.TRUE.equals(isPlatformUser)) {
            return PLATFORM;
        }
        
        // 系统用户（通过用户名前缀判断）
        if (username.startsWith("sys_") || username.startsWith("system_")) {
            return SYSTEM;
        }
        
        // 租户用户（有租户ID的普通用户）
        if (tenantId != null && !tenantId.trim().isEmpty()) {
            return TENANT;
        }
        
        // 默认为匿名用户
        return ANONYMOUS;
    }
}