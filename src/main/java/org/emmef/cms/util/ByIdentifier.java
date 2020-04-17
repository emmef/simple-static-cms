package org.emmef.cms.util;

import lombok.NonNull;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.util.function.Predicate;

public class ByIdentifier implements Predicate<Node> {
	private final Predicate<String> valuePredicate;

	public ByIdentifier(@NonNull Predicate<String> valuePredicate) {
		this.valuePredicate = valuePredicate;
	}

	public static ByIdentifier literal(@NonNull String value, boolean caseSensitive) {

		return new ByIdentifier(caseSensitive ? (s) -> value.compareTo(s) == 0 : (s) -> String.CASE_INSENSITIVE_ORDER.compare(value, s) == 0);
	}

	public static ByIdentifier isUuid(@NonNull String attributeName) {
		return new ByIdentifier((s) -> ByAttributeValue.UUID_PATTERN.matcher(s).matches());
	}
	@Override
	public boolean test(Node node) {
		if (node != null && node instanceof Element && node.attributes().size() > 0) {
			String value = node.attributes().get("ID");
			return valuePredicate.test(value);
		}
		return false;
	}
}
