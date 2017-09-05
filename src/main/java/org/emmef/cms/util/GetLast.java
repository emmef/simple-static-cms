package org.emmef.cms.util;

import lombok.Getter;

import java.util.function.Consumer;

public class GetLast<T> extends GetOne<T> {
    @Override
    protected boolean mustSet(T currentValue, T newValue) {
        return newValue != null;
    }
}
