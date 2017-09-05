package org.emmef.cms.util;

import lombok.Getter;

import java.util.function.Consumer;

public class GetFirst<T> extends GetOne<T> {
    @Override
    protected boolean mustSet(T currentValue, T newValue) {
        return currentValue == null;
    }
}