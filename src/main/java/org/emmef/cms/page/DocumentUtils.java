package org.emmef.cms.page;

import lombok.NonNull;
import org.emmef.cms.document.Attributes;
import org.emmef.cms.parameters.ValidationException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.util.Optional;
import java.util.function.Function;

public class DocumentUtils {

	public static Element getNodeByTag(Element document, String tagName) {
		Elements elementsByTagName = document.getElementsByTag(tagName);
		switch (elementsByTagName.size()) {
			case 0:
				throw new ValidationException("Expected element with tag \"" + tagName + "\"");
			case 1:
				return elementsByTagName.first();
			default:
				Element first = elementsByTagName.removeFirst();
				elementsByTagName.remove();
				return first;
		}
	}

	public static String getTitle(Element head) {
		Element title = head.getElementsByTag(org.emmef.cms.document.Elements.TITLE).first();
		if (title == null) {
			throw new PageException("Title must not be empty");
		}
		String text = title.text();
		if (text.isBlank()) {
			return "???";
		}
		return text.trim().replaceAll("\\s+", " ");
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

	public static <T> T getMetaValue(@NonNull Node head, @NonNull String nameValue, @NonNull Function<String, T> converter) {
		return converter.apply(getRawMetaValue(head, nameValue));
	}

	private static String getRawMetaValue(@NonNull Node head, @NonNull String nameValue) {
		Optional<Node> node = head.childNodes()
				.stream()
				.filter(e -> org.emmef.cms.document.Elements.META.equalsIgnoreCase(e.nodeName()))
				.filter(e -> nameValue.equalsIgnoreCase(e.attr(Attributes.NAME)))
				.findFirst();


		return node.isPresent() ? node.get().attr(Attributes.META_VALUE) : null;
	}

	public static @NonNull Element getHtmlElement(@NonNull Document document) {
		Element html = document.getElementsByTag("html").first();
		if (html == null) {
			throw new IllegalStateException("No html element!");
		}
		return html.clone();
	}

	public static @NonNull Document htmlDeclarationFromElement(@NonNull Element html) {
		String language = html.attr(Attributes.HTML_LANGUAGE);
		return Jsoup.parse("<!DOCTYPE html>" + (language.isBlank() ? "<html></html>" : "<html lang=\"" + language + "\"</html>"));
	}
}
