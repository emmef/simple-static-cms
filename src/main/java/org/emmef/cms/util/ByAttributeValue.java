package org.emmef.cms.util;

import lombok.NonNull;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.util.function.Predicate;
import java.util.regex.Pattern;

public class ByAttributeValue implements Predicate<Node> {
    public static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);
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

    public static ByAttributeValue isUuid(@NonNull String attributeName) {
        return new ByAttributeValue(attributeName, (s) -> UUID_PATTERN.matcher(s).matches());
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
