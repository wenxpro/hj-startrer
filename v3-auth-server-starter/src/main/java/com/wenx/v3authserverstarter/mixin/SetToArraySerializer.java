package com.wenx.v3authserverstarter.mixin;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Collection;

/**
 * Set/Collection → 数组序列化器（配合 UserDetailMixin）
 *
 * <p>UserDetail 的 authoritySet 等为 {@code Set.of()}（java.util.ImmutableCollections$SetN），
 * 不在 Security Jackson allowlist 内。Jackson 的 {@code @JsonSerialize(as=ArrayList)} 对
 * Set 字段不合法（"types not related"），故用自定义序列化器将任意集合输出为 JSON 数组
 * （数组无 @class 类型标注，反序列化时按目标 Set/List 字段自动转换）。
 */
public class SetToArraySerializer extends JsonSerializer<Object> {

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        if (value instanceof Collection<?> collection) {
            gen.writeStartArray();
            for (Object item : collection) {
                gen.writeObject(item);
            }
            gen.writeEndArray();
        } else {
            gen.writeObject(value);
        }
    }
}
