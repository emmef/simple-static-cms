package org.emmef.cms.util;

import lombok.NonNull;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

public class IteratorWithPredicate<T> implements Iterator<T> {
    private final Iterator<T> iterator;
    private final Predicate<T> predicate;
    private T next;

    IteratorWithPredicate(@NonNull Iterator<T> iterator, @NonNull Predicate<T> predicate) {
        this.iterator = iterator;
        this.predicate = predicate;
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
            T candidate = iterator.next();
            if (predicate.test(candidate)) {
                this.next = candidate;
                return true;
            }
        }
        return false;
    }
}
