package org.emmef.cms.util;

import lombok.Getter;

import java.util.function.Consumer;

public abstract class GetOne<T> implements Consumer<T> {
    @Getter
    private T value;

    protected abstract boolean mustSet(T currentValue, T newValue);

    @Override
    public void accept(T t) {
        if (mustSet(value, t)) {
            value = t;
        }
    }
}
