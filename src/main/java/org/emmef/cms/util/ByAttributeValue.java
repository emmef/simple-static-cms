package org.emmef.cms.util;

import lombok.NonNull;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

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
        if (node != null && node instanceof Element && node.attributes().size() > 0) {
            String value = node.attributes().get(attributeName);
            return valuePredicate.test(value);
        }
        return false;
    }
}
