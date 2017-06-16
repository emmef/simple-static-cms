package org.emmef.cms.util;

import lombok.NonNull;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.util.Comparator;
import java.util.function.Predicate;

public class ByElementName implements Predicate<Element> {
    private final String name;
    private final Comparator<String> comp;

    public ByElementName(@NonNull String name, boolean caseSensitive) {
        this.name = name;
        this.comp = Unwrap.nullSafe(caseSensitive ? (s1, s2) -> s1.compareTo(s2) : (s1, s2) -> s1.compareToIgnoreCase(s2));
    }

    @Override
    public boolean test(Element node) {
        return node != null && comp.compare(name, node.nodeName()) == 0;
    }
}
