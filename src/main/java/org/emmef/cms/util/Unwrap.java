package org.emmef.cms.util;

import lombok.NonNull;

import java.util.Comparator;
import java.util.function.Function;

public class Unwrap {

    public static <T, R> int compareTo(T o1, T o2, @NonNull Comparator<R> comparator, @NonNull Function<T, R> unwrap) {
        return comparator.compare(unwrap.apply(o1), unwrap.apply(o2));
    }

    public static <T, R> int compareToNullSafe(T o1, T o2, @NonNull Comparator<R> comparator, @NonNull Function<T, R> unwrap) {
        return o1 != null ? o2 != null ? comparator.compare(unwrap.apply(o1), unwrap.apply(o2)) : 1 : o2 != null ? -1 : 0;
    }

    public static <T> Comparator<T> nullSafe(Comparator<T> base) {
        return (s1, s2) -> s1 != null ? s2 != null ? base.compare(s1, s2) : 1 : s2 != null ? -1 : 0;
    }

    public static <T, R> Comparator<T> nullSafe(Comparator<R> base, @NonNull Function<T, R> unwrap) {
        return (s1, s2) -> s1 != null ? s2 != null ? base.compare(unwrap.apply(s1), unwrap.apply(s2)) : 1 : s2 != null ? -1 : 0;
    }
}
