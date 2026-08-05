package com.wenx.v3authserverstarter.mixin;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;

/**
 * UserDetail 序列化 Mixin（存入 oauth2_authorization 表 attributes 时使用）
 *
 * <p>authoritySet 已改为 List（UserDetail 类型变更，序列化为 allowlist 内的 ArrayList）。
 * teamIds/dataScopeList 若为 {@code List.of()}（ImmutableCollections$ListN，不在 allowlist）
 * 会触发 "not in the allowlist"，故保留字段级 {@code @JsonTypeInfo(Id.NONE)} + 自定义序列化器
 * 输出纯数组（无 @class），反序列化时按目标 List 字段自动转换。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
@JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE,
        isGetterVisibility = NONE, setterVisibility = NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class UserDetailMixin {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
    @JsonSerialize(using = SetToArraySerializer.class)
    abstract java.util.List<?> getTeamIds();

    @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
    @JsonSerialize(using = SetToArraySerializer.class)
    abstract java.util.List<?> getDataScopeList();
}
