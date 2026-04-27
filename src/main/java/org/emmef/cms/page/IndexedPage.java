package org.emmef.cms.page;

import com.google.common.collect.ImmutableSortedSet;
import lombok.Getter;
import lombok.NonNull;
import org.emmef.cms.parameters.NodeExpectation;
import org.emmef.cms.util.ByAttributeValue;
import org.joda.time.format.ISODateTimeFormat;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.emmef.cms.page.DocumentUtils.*;
import static org.emmef.cms.page.DocumentUtils.NOTE_ELEMENT;

/**
 * TODO Page summary
 * The page summary is cloned and will replace local links with absolute links that will be visited.
 * TODO Links last
 * The replacement of links will happen at the last possible moment on all pages.
 */
public class IndexedPage {
	public static final Predicate<Element> META_UUID = META.and(ByAttributeValue.literal("name", "scms-uuid", true));
	public static final Predicate<Element> META_PARENT_UUID = META.and(ByAttributeValue.literal("name", "scms-parent-uuid", true));
	public static final Predicate<Element> META_MATH = META.and(ByAttributeValue.literal("name", "scms-uses-math", true));
	public static final Predicate<Element> META_INDEX = META.and(ByAttributeValue.literal("name", "scms-is-index", true));
	public static final Predicate<Element> META_PUBLISH_DATE = META.and(ByAttributeValue.literal("name", "scms-published-date", true));
	public static final Predicate<Element> META_REPUBLISH_DATE = META.and(ByAttributeValue.literal("name", "scms-republish-date", true));
	public static final Set<String> CAPTION_ELEMENTS = new ImmutableSortedSet.Builder<>(String.CASE_INSENSITIVE_ORDER).add("h1", "h2", "h3", "h4", "h5", "h6", "figcaption").build();
	public static final String LATEST_ARTICLE_ELEMENT = "section";
	public static final String LATEST_ARTICLE_ID = "latest-articles";
	public static final String SUMMARY_ELEMENT = "p";
	public static final String SUMMARY_ID = "article-summary";

	@Getter
	private final PageReferrals pageReferrals;
	@Getter
	private final @NonNull UUID id;
	@Getter
	private final UUID parentId;
	@Getter
	private final @NonNull String title;
	@Getter
	private final boolean math;
	@Getter
	private final boolean index;
	@Getter
	private final @NonNull Element article;
	@Getter
	private final @NonNull Element latestArticles;
	@Getter
	private final @NonNull Element summary;
	@Getter
	private final @NonNull Map<String, Note> noteById;
	@Getter
	private final @NonNull Set<PageLink> pageLinks;
	@Getter
	private final @NonNull Map<String, String> captionById;
	@Getter
	private final FileTime timeModified;
	@Getter
	private final FileTime timePublished;

	public IndexedPage(Document document, @NonNull PageReferrals pageReferrals) {
		this.pageReferrals = pageReferrals;
		Document sourceDocument = document.clone();
		Node head = getNodeByTag(sourceDocument, "head", NodeExpectation.UNIQUE);
		this.id = getIdentifier(head, META_UUID, "page identifier", null);
		this.parentId = getIdentifier(head, META_PARENT_UUID, "parent identifier", NULL_PATTERN);
		this.title = DocumentUtils.getTitle(head);
		this.math = Boolean.parseBoolean(DocumentUtils.getMetaValue(head, META_MATH));
		this.index = Boolean.parseBoolean(DocumentUtils.getMetaValue(head, META_INDEX));
		this.timePublished = getFileTime(head, META_PUBLISH_DATE);
		this.timeModified = getFileTime(head, META_REPUBLISH_DATE);
		if (id == parentId) {
			throw new PageException("Parent identifier cannot be your own identifier");
		}
		this.article = getArticle(sourceDocument);
		this.article.tagName("article");

		Map<String, String> captions = createCaptionById(article);
		this.captionById = Collections.unmodifiableMap(captions);

		List<Element> notes = new ArrayList<>();
		Set<PageLink> pages = new HashSet<>();
		scanReferences(pages, sourceDocument, notes);
		this.pageLinks = Collections.unmodifiableSet(pages);
		this.noteById = createNotesById(notes);
		this.latestArticles = searchForLatestArticles(article);
		this.summary = searchForSummary(article);
		removeNotes(sourceDocument);
	}

	public void visitAnchors(@NonNull Consumer<Element> onAnchor) {
		article.getElementsByTag(ANCHOR_ELEMENT).forEach(onAnchor);
		noteById.values().forEach(v -> v.node.getElementsByTag(ANCHOR_ELEMENT).forEach(onAnchor));
	}

	private static @NonNull Element getArticle(Document sourceDocument) {
		Element sourceBody = DocumentUtils.getNodeByTag(sourceDocument, "body", NodeExpectation.UNIQUE);
		if (sourceBody == null) {
			throw new PageException("Page has no article!");
		}
		Element clone = sourceBody.clone();
		clone.tagName("article");
		return clone;
	}

	private Map<String, String> createCaptionById(Element sourceBody) {
		Map<String, String> captionById = new HashMap<>();
		sourceBody.getAllElements().stream()
				.filter(node -> CAPTION_ELEMENTS.contains(node.nodeName()))
				.filter(node -> !node.attr("id").isBlank())
				.forEach(node -> {
					captionById.put(node.attr("id"), node.text());
				});
		return captionById;
	}

	private static @NonNull Map<String, Element> createNotesById(Element sourceDocument) {
		Map<String, Element> notes = new HashMap<>();
		sourceDocument.getAllElements().forEach(node -> {
			if (!NOTE_ELEMENT.equalsIgnoreCase(node.nodeName())) {
				return;
			}
			String id = node.attr("id");
			if (node == null) {
				return;
			}
			notes.put(node.attr("id"), node.clone());
			node.remove();
		});
		return notes;
	}

	private void removeNotes(Document sourceDocument) {
		List<Node> nodes = new ArrayList<>();
		sourceDocument.getAllElements().forEach(node -> {
			if (!NOTE_ELEMENT.equalsIgnoreCase(node.nodeName())) {
				return;
			}
			nodes.add(node);
		});
		for (Node node : nodes) {
			node.remove();
		}
	}

	private @NonNull Element searchForLatestArticles(Element sourceBody) {
		List<Element> elements = sourceBody.getAllElements().stream()
				.filter(e -> LATEST_ARTICLE_ELEMENT.equalsIgnoreCase(e.tagName()))
				.filter(e -> LATEST_ARTICLE_ID.equalsIgnoreCase(e.attr("id")))
				.collect(Collectors.toList());
		if (elements.isEmpty()) {
			return null;
		}
		Element latestArticle = elements.get(0);
		for (Element remove : elements.subList(1, elements.size())) {
			remove.remove();
		}
		return latestArticle;
	}

	private void scanReferences(Set<PageLink> pages, @NonNull Element sourceDocument, List<Element> notes) {
		@NonNull Map<String, Element> notesFirstScan = createNotesById(sourceDocument);
		handleReferences(article, pages, notesFirstScan, notes);
		int noteCount;
		do {
			noteCount = notes.size();
			new ArrayList<>(notes).forEach(note -> {
				handleReferences(note, pages, notesFirstScan, notes);
			});
		} while(noteCount != notes.size());
	}

	private void handleReferences(Element element, Set<PageLink> pages, @NonNull Map<String, Element> notesFirstScan, List<Element> notes) {
		element.getElementsByTag(ANCHOR_ELEMENT).forEach(node -> {
			String href = node.attr(ANCHOR_HREF);
			if (href == null || href.isBlank()) {
				return;
			}
			PageLink pageLink = pageReferrals.of(href);
			if (pageLink != null)
				pages.add(pageLink);
			else {
				String id = href.substring(1);
				Element note = notesFirstScan.get(id);
				if (note != null) {
					notes.add(note);
				}
			}
		});
	}

	private static @NonNull Map<String, Note> createNotesById(List<Element> notes) {
		Map<String, Note> results = new HashMap<>();
		for (int i = 0; i < notes.size(); i++) {
			Element element = notes.get(i);
			String id = element.attr("id");
			results.put(id, new Note(element, i + 1));
		}
		return Collections.unmodifiableMap(results);
	}

	private @NonNull Element searchForSummary(Element sourceBody) {
		List<Element> elements = sourceBody.getAllElements().stream()
				.filter(e -> SUMMARY_ELEMENT.equalsIgnoreCase(e.tagName()))
				.filter(e -> SUMMARY_ID.equalsIgnoreCase(e.attr("id")))
				.collect(Collectors.toList());
		if (elements.isEmpty()) {
			return null;
		}
		Element summary = elements.get(0);
		for (Element removeId : elements.subList(1, elements.size())) {
			removeId.removeAttr("id");
		}
		return summary;
	}

	private FileTime getFileTime(Node head, Predicate<Element> metaDatePredicate) {
		String metaPublishedDate = DocumentUtils.getMetaValue(head, metaDatePredicate);
		if (metaPublishedDate == null) {
			return null;
		}
		try {
			return FileTime.fromMillis(
					ISODateTimeFormat.dateTimeParser().parseDateTime(metaPublishedDate).toDate().getTime());
		} catch (RuntimeException e) {
			return null;
		}
	}

	public record Note(@NonNull Element node, @NonNull Integer number) {
	}
}
