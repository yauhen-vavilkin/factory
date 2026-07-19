package org.folio.factory.core;

import org.hibernate.type.descriptor.java.JavaType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Jackson3JsonFormatMapperTest {

    private final Jackson3JsonFormatMapper mapper =
            new Jackson3JsonFormatMapper(JsonMapper.builder().build());

    @SuppressWarnings("unchecked")
    private static <T> JavaType<T> javaTypeOf(Class<T> clazz) {
        JavaType<T> javaType = mock(JavaType.class);
        when(javaType.getJavaTypeClass()).thenReturn(clazz);
        return javaType;
    }

    @Test
    void nullPassesThroughBothDirections() {
        assertThat(mapper.<String>fromString(null, null, null)).isNull();
        assertThat(mapper.toString(null, null, null)).isNull();
    }

    @Test
    void rawJsonStringsPassThroughUnchanged() {
        String raw = "{\"issueKey\":\"ERM-1\"}";
        assertThat(mapper.fromString(raw, javaTypeOf(String.class), null)).isEqualTo(raw);
        assertThat(mapper.toString(raw, null, null)).isEqualTo(raw);
    }

    @Test
    void nonStringTypesRoundTripThroughJackson() {
        Map<String, Object> value = Map.of("key", "value");
        String json = mapper.toString(value, null, null);
        Object parsed = mapper.fromString(json, javaTypeOf(Map.class), null);
        assertThat(parsed).isEqualTo(value);
    }
}
