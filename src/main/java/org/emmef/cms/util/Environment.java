package org.emmef.cms.util;

import com.google.common.collect.ImmutableMap;
import lombok.NonNull;

import java.util.Map;
import java.util.Properties;

public interface Environment {
    Environment SYSTEM_ENVIRONMENT = System::getenv;
    Environment SYSTEM_PROPERTIES = System::getProperty;

    static Environment wrappedProperties(@NonNull Properties properties) {
        return (name) -> properties.getProperty(name);
    }

    static Environment wrappedMap(@NonNull Map<String,String> map) {
        return (name) -> map.get(name);
    }

    static Builder builder() {
        return new Builder();
    }

    static Environment systemFallback(Environment environment) {
        return environment != null ? environment : SYSTEM_ENVIRONMENT;
    }

    String get(String variableName);

    class Builder {
        private final ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

        public Builder with(String key, String value) {
            builder.put(key, value);
            return this;
        }

        public Environment build() {
            return wrappedMap(builder.build());
        }
    }
}
