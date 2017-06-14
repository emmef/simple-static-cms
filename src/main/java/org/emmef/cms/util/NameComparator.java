package org.emmef.cms.util;

import lombok.NonNull;

import java.util.Comparator;
import java.util.function.Function;

public enum NameComparator implements Comparator<String> {
    INSTANCE;

    @Override
    public int compare(String n1, String n2) {
        return compareNonNull(n1, n2);
    }

    public static int compareNonNull(String n1, String n2) {
        int cmp = n1.compareToIgnoreCase(n2);
        if (cmp != 0) {
            return cmp;
        }
        return n1.compareTo(n2);
    }
}
