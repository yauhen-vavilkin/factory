package org.folio.factory.core;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hibernate JSON format mapper backed by Jackson 3 (tools.jackson). Hibernate's
 * automatic detection only recognises Jackson 2, which Spring Boot 4 no longer
 * provides. JSON-typed entity attributes in this codebase are raw JSON strings,
 * which pass through unchanged; other types are round-tripped through Jackson.
 */
public class Jackson3JsonFormatMapper implements FormatMapper {

    private final JsonMapper jsonMapper;

    public Jackson3JsonFormatMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T fromString(CharSequence charSequence, JavaType<T> javaType, WrapperOptions wrapperOptions) {
        if (charSequence == null) {
            return null;
        }
        if (javaType.getJavaTypeClass() == String.class) {
            return (T) charSequence.toString();
        }
        return (T) jsonMapper.readValue(charSequence.toString(), javaType.getJavaTypeClass());
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions wrapperOptions) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        return jsonMapper.writeValueAsString(value);
    }
}
