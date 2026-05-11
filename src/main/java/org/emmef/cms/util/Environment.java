package org.emmef.cms.util;

import lombok.NonNull;

import java.util.Properties;

public interface Environment {
    Environment SYSTEM_ENVIRONMENT = System::getenv;
    Environment SYSTEM_PROPERTIES = System::getProperty;

    static Environment wrappedProperties(@NonNull Properties properties) {
        return (name) -> properties.getProperty(name);
    }

    String get(String variableName);
}
