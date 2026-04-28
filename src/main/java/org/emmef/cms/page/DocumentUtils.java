package org.emmef.cms.page;

import lombok.NonNull;
import org.emmef.cms.parameters.NodeExpectation;
import org.emmef.cms.parameters.ValidationException;
import org.emmef.cms.util.NodeHelper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class DocumentUtils {
	public static final Predicate<Element> TITLE = NodeHelper.elementByNameCaseInsensitive("title");
	public static final Pattern NULL_PATTERN = Pattern.compile("^(null|none|root)$", Pattern.CASE_INSENSITIVE);
	public static final String NOTE_ELEMENT = "aside";
	public static final String ANCHOR_ELEMENT = "a";
	public static final Character LOCAL_LINK = '#';
	public static final String ANCHOR_HREF = "href";
	public static final Predicate<Element> META = NodeHelper.elementByNameCaseInsensitive("meta");

	public static Element getNodeByTag(Document document, String tagName, NodeExpectation expectation) {

		Elements elementsByTagName = document.getElementsByTag(tagName);
		Element item;
		if (elementsByTagName.size() == 0) {
			if (expectation == NodeExpectation.OPTIONAL) {
				item = null;
			} else {
				throw new ValidationException("Expected element with tag \"" + tagName + "\"");
			}
		} else {
			if (elementsByTagName.size() > 1 && expectation == NodeExpectation.UNIQUE) {
				throw new ValidationException("Expected exactly one element with tag \"" + tagName + "\"");
			}
			item = elementsByTagName.get(0);
		}
		return item;
	}

	public static String getTitle(Node head) {
		String title = getContent(head, TITLE);
		if (title == null || title.isEmpty()) {
			throw new PageException("Title must not be empty");
		}
		return title.trim().replaceAll("\\s+", " ").replaceAll("\\s", PageRecord.NBSP);
	}

	public static UUID getIdentifier(@NonNull Node head, @NonNull Predicate<Element> predicate, @NonNull String description, Pattern nullPattern) {
		String uuidText = getMetaValue(head, predicate);
		if (uuidText == null) {
			throw new PageException("Missing " + description);
		}
		UUID uuid;
		try {
			uuid = UUID.fromString(uuidText);
		} catch (IllegalArgumentException e) {
			if (nullPattern != null && nullPattern.matcher(uuidText).matches()) {
				return null;
			}
			throw new PageException("While parsing " + description + ": " + e.getMessage());
		}
		return uuid;
	}

	public static String getMetaValue(Node head, Predicate<Element> predicate) {
		Node meta = NodeHelper.searchFirst(head, predicate);
		if (meta == null) {
			return null;
		}
		String value = meta.attr("value");
		return value.isEmpty() ? null : value;
	}

	public static String getContent(Node head, Predicate<Element> predicate) {
		Element meta = NodeHelper.searchFirst(head, predicate);
		return meta != null ? meta.text() : null;
	}

	public static Element getAcceptedTagAndIdElementOrNull(@NonNull Node node, @NonNull String tagName, @NonNull String idValue) {
		return getAcceptedElementOrNull(node, NodeHelper.elementByNameCaseInsensitive(tagName).and((e) -> idValue.equalsIgnoreCase(e.attr("id"))));
	}

	public static Element getAcceptedTagElementOrNull(@NonNull Node node, @NonNull String tagName, @NonNull Predicate<Element> predicate) {
		return getAcceptedElementOrNull(node, NodeHelper.elementByNameCaseInsensitive(tagName).and(predicate));
	}

	public static Element getAcceptedElementOrNull(@NonNull Node node, @NonNull Predicate<Element> predicate) {
		if (!(node instanceof Element)) {
			return null;
		}
		Element element = (Element) node;
		return predicate.test(element) ? element : null;
	}
}
