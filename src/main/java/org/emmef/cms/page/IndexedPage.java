package org.emmef.cms.page;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSortedSet;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.parameters.NodeExpectation;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Tag;

import java.net.URLEncoder;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.emmef.cms.page.DocumentUtils.*;
import static org.emmef.cms.page.DocumentUtils.NOTE_ELEMENT;

/**
 * TODO Page summary
 * The page summary is cloned and will replace local links with absolute links that will be visited.
 * TODO Links last
 * The replacement of links will happen at the last possible moment on all pages.
 */
@Slf4j
public class IndexedPage implements PathInfo {
	public static final String META_UUID = "scms-uuid";
	public static final String META_MATH = "scms-uses-math";
	public static final String META_PUBLISH_DATE = "scms-published-date";
	public static final String META_REPUBLISH_DATE = "scms-republish-date";
	public static final Set<String> CAPTION_ELEMENTS = new ImmutableSortedSet.Builder<>(String.CASE_INSENSITIVE_ORDER).add("h1", "h2", "h3", "h4", "h5", "h6", "figcaption").build();
	public static final String LATEST_ARTICLE_ELEMENT = "section";
	public static final String LATEST_ARTICLE_ID = "latest-articles";
	public static final String SUMMARY_ELEMENT = "p";
	public static final String SUMMARY_ID = "article-summary";
	public static final DateTime ZERO_DATE = new DateTime(0);
	public static final String MAKE_LINK_FOOTNOTE = "ref:";
	public static final Function<String, UUID> TO_UUID = uuid -> UUID.fromString(uuid);

	@Getter
	private final PageReferrals pageReferrals;
	@Getter
	private final PathInfo pathInfo;
	@Getter
	private @NonNull UUID id;
	@Getter
	private final @NonNull String title;
	@Getter
	private final boolean math;
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
	private final DateTime timeModified;
	@Getter
	private final DateTime timePublished;
	private final String htmlDeclaration;

	public IndexedPage(Document document, @NonNull PageReferrals pageReferrals, PathInfo pathInfo) {
		this.pageReferrals = pageReferrals;
		this.pathInfo = pathInfo;
		Document sourceDocument = document.clone();

		Element html = sourceDocument.getElementsByTag("html").first();
		String language = html != null ? html.attr("lang") : null;
		this.htmlDeclaration = "<!DOCTYPE html>" + (language != null ? "<html lang=\"" + language + "\"</html>" : "<html></html>");

		Node head = getNodeByTag(sourceDocument, "head", NodeExpectation.UNIQUE);
		this.id = DocumentUtils.getMetaValue(head, META_UUID, "page identifier", TO_UUID);
		this.title = DocumentUtils.getTitle(head);
		this.math = Boolean.parseBoolean(DocumentUtils.getMetaValueOrNull(head, META_MATH, "tex support", Function.identity()));
		this.timePublished = getTime(head, META_PUBLISH_DATE);
		this.timeModified = getTime(head, META_REPUBLISH_DATE);
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
		removeNotes(article);
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

	private void removeNotes(Element sourceDocument) {
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
		Set<String> referred = new HashSet<>();
		handleReferences(article, pages, notesFirstScan, notes, referred);
		int noteCount;
		do {
			noteCount = notes.size();
			new ArrayList<>(notes).forEach(note -> {
				handleReferences(note, pages, notesFirstScan, notes, referred);
			});
		} while (noteCount != notes.size());
	}

	private void handleReferences(Element element, Set<PageLink> pages, @NonNull Map<String, Element> notesFirstScan, List<Element> notes, Set<String> referred) {
		element.getElementsByTag(ANCHOR_ELEMENT).forEach(node -> {
			String href = node.attr(ANCHOR_HREF).trim();
			if (href == null || href.isBlank()) {
				return;
			}
			PageLink pageLink = pageReferrals.of(href);
			if (pageLink != null)
				pages.add(pageLink);
			else {
				if (href.startsWith(MAKE_LINK_FOOTNOTE)) {
					if (notesFirstScan.containsKey(href)) {
						node.attr("href", DocumentUtils.LOCAL_LINK + notesFirstScan.get(href).id());
						node.text("");
					}
					else {
						String url = href.substring(MAKE_LINK_FOOTNOTE.length());
						String id = URLEncoder.encode( "footnotes::" + notes.size(), Charsets.UTF_8);
						Element note = new Element(Tag.valueOf("aside"), "top");
						Element clone = node.clone();
						clone.attr("href", url);
						note.appendChild(clone);
						note.attr("id", id);
						node.attr(ANCHOR_HREF, DocumentUtils.LOCAL_LINK + id);
						node.text("");
						notes.add(note);
						referred.add(id);
						notesFirstScan.put(href, note);
						notesFirstScan.put(id, note);
					}
				}
				else {
					String id = href.substring(1);
					Element note = notesFirstScan.get(id);
					if (note != null && !referred.contains(id)) {
						notes.add(note);
						referred.add(id);
					}
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
		return results;
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

	public void replacePageReferences(@NonNull Map<UUID, PageRecord> pages) {
		AtomicInteger refCounter  = new AtomicInteger(0);
		visitAnchors(anchor -> {
			String href = anchor.attr("href");
			if (href.isBlank()) {
				removeReference(anchor);
				return;
			}
			PageLink pageLink = getPageLink(href);
			if (pageLink == null) {
				return;
			}
			boolean isThisPage = pageLink.getUuid() == getId();
			PageRecord pageRecord = pages.get(pageLink.getUuid());
			String relativeLink;
			IndexedPage page;
			if (isThisPage) {
				relativeLink = null;
				page = this;
			}
			else {
				if (pageRecord == null) {
					removeReference(anchor);
					return;
				}
				relativeLink = pageRecord.getAbsoluteUrl();
				page = pageRecord.getIndexedPage();
			}

			String localId = pageLink.getLocalId();
			if (localId == null || localId.isBlank()) {
				if (isThisPage) {
					removeReference(anchor);
					return;
				}
				else if (pageRecord == null) {
					removeReference(anchor);
					return;
				}
				else {
					anchor.attr("href", relativeLink);
					if (anchor.text().isBlank()) {
						anchor.text(pageRecord.getIndexedPage().getTitle());
					}
				}
				return;
			}

			String newRef = (relativeLink != null ? relativeLink : "") + DocumentUtils.LOCAL_LINK + localId;

			IndexedPage.Note note = page.getNoteById().get(localId);
			if (note != null) {
				if (anchor.text().isBlank()) {
					anchor.addClass("reference-ptr");
					if (isThisPage) {
						anchor.text(Integer.toString(note.number()));
					}
					else {
						anchor.text("*" + note.number());
						anchor.attr("href", newRef);
					}
				}
				return;
			}

			Map<String, String> captionById = page.getCaptionById();
			if (!captionById.containsKey(localId)) {
				removeReference(anchor);
				return;
			}
			if (!isThisPage) {
				anchor.attr("href", newRef);
			}
			if (anchor.text().isBlank() || "=".equals(anchor.text())) {
				createCaption(anchor, pageRecord, localId, false);
			}
			else if ("_".equals(anchor.text())) {
				createCaption(anchor, pageRecord, localId, true);
			}
		});
	}

	private static void createCaption(Element anchor, PageRecord pageRecord, String localId, boolean lowerCase) {
		String caption = pageRecord.getIndexedPage().getCaptionById().get(localId);
		if (caption == null || caption.isBlank()) {
			anchor.text("???");
		}
		else if (lowerCase) {
			anchor.text(caption.toLowerCase());
		}
		else {
			anchor.text(caption);
		}
	}

	private static void removeReference(Element anchor) {
		anchor.tagName("span");
		anchor.removeAttr("href");
	}

	private PageLink getPageLink(@NonNull String href) {
		PageLink pageLink = getPageReferrals().of(href);
		if (pageLink != null) {
			return pageLink;
		}
		if (href.charAt(0) == DocumentUtils.LOCAL_LINK) {
			return PageLink.of(getId(), href.substring(1));
		}
		return null;
	}

	private void visitAnchors(@NonNull Consumer<Element> onAnchor) {
		article.getElementsByTag(ANCHOR_ELEMENT).forEach(onAnchor);
		noteById.values().forEach(v -> v.node.getElementsByTag(ANCHOR_ELEMENT).forEach(onAnchor));
	}

	private static DateTime getTime(@NonNull Node head, @NonNull String name) {
		DateTime timeStamp = getMetaValueOrNull(head, name, "time stamp", v -> ISODateTimeFormat.dateTimeParser().parseDateTime(v));
		return timeStamp != null ? timeStamp : ZERO_DATE;
	}

	public void replaceId(@NonNull UUID newId) {
		id = newId;
	}

	public String getHtmlDeclaration() {
		return htmlDeclaration;
	}

	@Override
	public Path getPath() {
		return pathInfo.getPath();
	}

	@Override
	public Path getRelativePath() {
		return pathInfo.getRelativePath();
	}

	@Override
	public String getAbsoluteUrl() {
		return pathInfo.getAbsoluteUrl();
	}

	@Override
	public boolean isIndex() {
		return pathInfo.isIndex();
	}

	@Override
	public boolean isRoot() {
		return pathInfo.isRoot();
	}

	@Override
	public List<String> getMainTag() {
		return pathInfo.getMainTag();
	}

	@Override
	public Path getRootPath() {
		return pathInfo.getRootPath();
	}

	public record Note(@NonNull Element node, @NonNull Integer number) {
	}
}
