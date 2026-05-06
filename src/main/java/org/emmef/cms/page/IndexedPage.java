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

	public static final Comparator<IndexedPage> createDateComparator(long mostRecentCreated, long mostRecentModified) {
		return (p1, p2) -> {
			double createP1 = Math.log(Math.max(1, mostRecentCreated - p1.getTimePublished().getMillis()));
			double createP2 = Math.log(Math.max(1, mostRecentCreated - p2.getTimePublished().getMillis()));
			double createValue = createP1 - createP2;
			double modP1 = Math.log(Math.max(1, mostRecentModified - p1.getTimeModified().getMillis()));
			double modP2 = Math.log(Math.max(1, mostRecentModified - p2.getTimeModified().getMillis()));
			double modValue = modP1 - modP2;

			double value = createValue * 10 + modValue;
			return value < 0 ? -1 : value > 0 ? 1 : p1.getAbsoluteUrl().compareTo(p2.getAbsoluteUrl());
		};
	}

	@Getter
	private final PageReferrals pageReferrals;
	@Getter
	private final PathInfo pathInfo;
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
		if (log.isInfoEnabled()) {
			log.info("{}:", getTitle());
			log.info("- Note identifiers: {}", notes.stream().map(Element::id).collect(Collectors.joining(", ")));
			log.info("- Pages linked:     {}", pages.stream().map(PageLink::toString).collect(Collectors.joining(", ")));
		}
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

	public void replacePageReferences(@NonNull List<IndexedPage> pages) {
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
			final IndexedPage foundPage = findPage(pages, pageLink);
			// TODO This must be replaced with a href comparison from perspective of the source file system and the page id.
			boolean isThisPage = foundPage == this;
			String relativeLink;
			if (isThisPage) {
				relativeLink = null;
			}
			else {
				if (foundPage == null) {
					removeReference(anchor);
					return;
				}
				relativeLink = foundPage.getAbsoluteUrl();
			}

			String localId = pageLink.getLocalId();
			if (localId == null || localId.isBlank()) {
				if (isThisPage) {
					removeReference(anchor);
					return;
				}
				else if (foundPage == null) {
					removeReference(anchor);
					return;
				}
				else {
					anchor.attr("href", relativeLink);
					if (anchor.text().isBlank()) {
						anchor.text(foundPage.getTitle());
					}
				}
				return;
			}

			String newRef = (relativeLink != null ? relativeLink : "") + DocumentUtils.LOCAL_LINK + localId;

			IndexedPage.Note note = foundPage.getNoteById().get(localId);
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

			Map<String, String> captionById = foundPage.getCaptionById();
			if (!captionById.containsKey(localId)) {
				removeReference(anchor);
				return;
			}
			if (!isThisPage) {
				anchor.attr("href", newRef);
			}
			if (anchor.text().isBlank() || "=".equals(anchor.text())) {
				createCaption(anchor, foundPage, localId, false);
			}
			else if ("_".equals(anchor.text())) {
				createCaption(anchor, foundPage, localId, true);
			}
		});
	}

	private IndexedPage findPage(@NonNull List<IndexedPage> pages, PageLink pageLink) {
		String myId = pageLink.getPage();
		if (this.isSame(myId)) {
			return this;
		}
		for (IndexedPage page : pages) {
			if (page.isSame(myId)) {
				return page;
			}
		}
		return null;
	}

	private static void createCaption(Element anchor, IndexedPage pageRecord, String localId, boolean lowerCase) {
		String caption = pageRecord.getCaptionById().get(localId);
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
			return PageLink.of(getPath().toString(), href.substring(1));
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

	@Override
	public boolean isSame(String href) {
		return pathInfo.isSame(href);
	}

	public record Note(@NonNull Element node, @NonNull Integer number) {
	}
}
