package org.emmef.cms.page;

import lombok.NonNull;
import org.emmef.cms.parameters.NodeExpectation;
import org.emmef.cms.parameters.ValidationException;
import org.emmef.cms.util.NodeHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class DocumentUtils {
	public static final Predicate<Element> TITLE = NodeHelper.elementByNameCaseInsensitive("title");
	public static final Pattern NULL_PATTERN = Pattern.compile("^(null|none|root)$", Pattern.CASE_INSENSITIVE);
	public static final String ANCHOR_HREF = "href";
	public static final String META_TAG = "meta";
	public static final Predicate<Element> META = NodeHelper.elementByNameCaseInsensitive(META_TAG);

	public static Element getNodeByTag(Element document, String tagName, NodeExpectation expectation) {

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
		return title.trim().replaceAll("\\s+", " ");
	}

	public static <T> @NonNull T getMetaValue(@NonNull Node head, @NonNull String nameValue, @NonNull String description, @NonNull Function<String, T> converter) {
		return getMetaValue(head, nameValue, v -> {
			if (v == null) {
				throw new PageException("Missing meta tag named \"" + nameValue + "\" for " + description);
			}
			else if (v.isBlank()) {
				throw new PageException("Empty meta tag named \"" + nameValue + "\" for " + description);
			}

			try {
				T apply = converter.apply(v.toString());
				if (apply == null) {
					throw new IllegalStateException("Null converted value for meta tag named \"" + nameValue + "\" for " + description + ":" + v);
				}
				return apply;
			} catch (IllegalArgumentException e) {
				throw new PageException("Invalid value for meta tag named \"" + nameValue + "\" for " + description + ":" + v + "\n" + e);
			}
		});
	}

	public static <T> T getMetaValueOrNull(@NonNull Node head, @NonNull String nameValue, @NonNull String description, @NonNull Function<String, T> converter) {
		return getMetaValue(head, nameValue, v -> {
			if (v == null || v.isBlank()) {
				return null;
			}

			try {
				return converter.apply(v.toString());
			} catch (IllegalArgumentException e) {
				throw new PageException("Invalid value for meta tag named \"" + nameValue + "\" for " + description + ":" + v + "\n" + e);
			}
		});
	}

	public static String getContent(Node head, Predicate<Element> predicate) {
		Element meta = NodeHelper.searchFirst(head, predicate);
		return meta != null ? meta.text() : null;
	}


	public static <T> T getMetaValue(@NonNull Node head, @NonNull String nameValue, @NonNull Function<String, T> converter) {
		return converter.apply(getRawMetaValue(head, nameValue));
	}

	private static String getRawMetaValue(@NonNull Node head, @NonNull String nameValue) {
		Optional<Node> node = head.childNodes()
				.stream()
				.filter(e -> META_TAG.equalsIgnoreCase(e.nodeName()))
				.filter(e -> nameValue.equalsIgnoreCase(e.attr("name")))
				.findFirst();


		return node.isPresent() ? node.get().attr("value") : null;
	}

	public static @NonNull Element getHtmlElement(@NonNull Document document) {
		Element html = document.getElementsByTag("html").first();
		if (html == null) {
			throw new IllegalStateException("No html element!");
		}
		return html.clone();
	}

	public static @NonNull Document htmlDeclarationFromElement(@NonNull Element html) {
		String language = html.attr("lang");
		return Jsoup.parse("<!DOCTYPE html>" + (language.isBlank() ? "<html></html>" : "<html lang=\"" + language + "\"</html>"));
	}
}
