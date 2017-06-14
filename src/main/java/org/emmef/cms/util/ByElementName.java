package org.emmef.cms.util;

import lombok.NonNull;
import org.w3c.dom.Node;

import java.util.Comparator;
import java.util.function.Predicate;

public class ByElementName implements Predicate<Node> {
    private final String name;
    private final Comparator<String> comp;

    public ByElementName(@NonNull String name, boolean caseSensitive) {
        this.name = name;
        this.comp = Unwrap.nullSafe(caseSensitive ? (s1, s2) -> s1.compareTo(s2) : (s1, s2) -> s1.compareToIgnoreCase(s2));
    }

    @Override
    public boolean test(Node node) {
        return node != null && node.getNodeType() == Node.ELEMENT_NODE && comp.compare(name, node.getNodeName()) == 0;
    }
}
