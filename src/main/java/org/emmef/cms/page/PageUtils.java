package org.emmef.cms.page;

import com.google.common.collect.ImmutableSortedSet;
import lombok.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.emmef.cms.parameters.NodeExpectation;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.emmef.cms.page.DocumentUtils.ANCHOR_ELEMENT;
import static org.emmef.cms.page.DocumentUtils.getMetaValueOrNull;

public class PageUtils {

	public static final Set<String> CAPTION_ELEMENTS = new ImmutableSortedSet.Builder<>(String.CASE_INSENSITIVE_ORDER).add("h1", "h2", "h3", "h4", "h5", "h6", "figcaption").build();
	public static final String SUMMARY_ELEMENT = "p";
	public static final String SUMMARY_ID = "article-summary";
	public static final DateTime ZERO_DATE = new DateTime(0);

	public static @NonNull Element getArticle(@NonNull Element sourceDocument) {
		Element sourceBody = DocumentUtils.getNodeByTag(sourceDocument, "body", NodeExpectation.UNIQUE);
		if (sourceBody == null) {
			throw new PageException("Page has no article (in <body>)!");
		}
		sourceBody.remove();
		sourceBody.tagName("article");
		return sourceBody;
	}

	public static @NonNull Map<String, Element> createCaptionById(@NonNull Element body) {
		Map<String, Element> captionById = new HashMap<>();
		body.getAllElements().stream()
				.filter(node -> CAPTION_ELEMENTS.contains(node.nodeName()))
				.filter(node -> !node.attr("id").isBlank())
				.forEach(node -> {
					captionById.put(node.id(), node.clone());
				});
		return Collections.unmodifiableMap(captionById);
	}

	public static DateTime getTime(@NonNull Node head, @NonNull String name, @NonNull Collection<Supplier<DateTime>> fallbacks) {
		DateTime timeStamp = getMetaValueOrNull(head, name, "time stamp", v -> ISODateTimeFormat.dateTimeParser().parseDateTime(v));
		for (Supplier<DateTime> fallback : fallbacks) {
			if (timeStamp != null && ZERO_DATE != timeStamp) {
				break;
			}
			if (fallback != null) {
				timeStamp = fallback.get();
			}
		}
		return timeStamp != null ? timeStamp : ZERO_DATE;
	}

	public static @NonNull Element searchForSummary(@NonNull Element sourceBody ) {
		List<Element> elements = sourceBody.getAllElements().stream()
				.filter(e -> SUMMARY_ELEMENT.equalsIgnoreCase(e.tagName()))
				.filter(e -> SUMMARY_ID.equalsIgnoreCase(e.attr("id")))
				.collect(Collectors.toList());

		if (!elements.isEmpty()) {
			Element summary = elements.get(0);
			for (Element removeId : elements.subList(1, elements.size())) {
				removeId.removeAttr("id");
			}
			return summary;
		}
		Elements p = sourceBody.getElementsByTag(SUMMARY_ELEMENT);
		Element first = p.first();
		if (first != null) {
			first.attr("id", SUMMARY_ID);
			return first;
		}
		Element summary = sourceBody.ownerDocument().createElement(SUMMARY_ELEMENT);
		summary.remove();
		summary.attr("id", SUMMARY_ID);
		return summary;
	}

	public static void scanForManagedPageLinks(@NonNull PathResolver resolver, @NonNull Element element, @NonNull Consumer<PathResolver.PageLink> consumer) {
		element.getElementsByTag(ANCHOR_ELEMENT).stream().map(node -> {
			String href = node.attr("href").trim();
			if (href.isBlank()) {
				return null;
			}
			return resolver.fromSourceHref(href);
		}).forEach(e -> {
			consumer.accept(e);
		});
	}

	public static void scanForManagedAnchors(@NonNull PathResolver resolver, @NonNull Element element, @NonNull BiConsumer<Element, PathResolver.PageLink> consumer) {
		element.getElementsByTag(ANCHOR_ELEMENT).forEach(node -> {
			String href = node.attr("href").trim();
			if (href.isBlank()) {
				return;
			}
			PathResolver.PageLink link = resolver.fromSourceHref(href);
			if (link != null) {
				consumer.accept(node, link);
			}
		});
	}
}
