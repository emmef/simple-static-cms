package org.emmef.cms.util;

import lombok.NonNull;
import org.w3c.dom.Node;

import java.util.function.Predicate;

public class ByAttributeValue implements Predicate<Node> {
    private final String attributeName;
    private final Predicate<String> valuePredicate;

    public ByAttributeValue(@NonNull String attributeName, @NonNull Predicate<String> valuePredicate) {
        this.attributeName = attributeName;
        this.valuePredicate = valuePredicate;
    }

    public static ByAttributeValue literal(@NonNull String attributeName, @NonNull String value, boolean caseSensitive) {
        return new ByAttributeValue(attributeName, caseSensitive ? (s) -> value.compareTo(s) == 0 : (s) -> String.CASE_INSENSITIVE_ORDER.compare(value, s) == 0);
    }

    public static ByAttributeValue startsWith(@NonNull String attributeName, @NonNull String value) {
        return new ByAttributeValue(attributeName, (s) -> s.startsWith(value));
    }

    @Override
    public boolean test(Node node) {
        if (node != null && node.getNodeType() == Node.ELEMENT_NODE && node.hasAttributes()) {
            Node namedItem = node.getAttributes().getNamedItem(attributeName);
            return namedItem != null && namedItem.getNodeType() == Node.ATTRIBUTE_NODE && valuePredicate.test(namedItem.getNodeValue());
        }
        return false;
    }
}
