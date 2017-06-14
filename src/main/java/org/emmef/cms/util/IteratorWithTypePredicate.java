package org.emmef.cms.util;

import lombok.NonNull;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

public class IteratorWithTypePredicate<T> implements Iterator<T> {
    private final Iterator<?> iterator;
    private final Predicate<Object> predicate;
    private final Class<T> type;
    private T next;

    public IteratorWithTypePredicate(@NonNull Iterator<?> iterator, @NonNull Class<T> type) {
        this.iterator = iterator;
        this.predicate = n -> type.isInstance(n);
        this.type = type;
    }

    public static <U, T extends U> Iterator<T> of(@NonNull Iterator<U> iterator, @NonNull Class<T> type) {
        return new IteratorWithTypePredicate<T>(iterator, type);
    }

    @Override
    public boolean hasNext() {
        return next != null || ensureNext();
    }

    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return getAndReset();
    }

    private T getAndReset() {
        T result = next;
        next = null;
        return result;
    }

    private boolean ensureNext() {
        while (iterator.hasNext()) {
            Object candidate = iterator.next();
            if (predicate.test(candidate)) {
                this.next = type.cast(candidate);
                return true;
            }
        }
        return false;
    }
}
