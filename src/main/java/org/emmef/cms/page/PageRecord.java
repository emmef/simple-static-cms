package org.emmef.cms.page;

import com.google.common.collect.Multimap;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.parameters.NodeExpectation;
import org.emmef.cms.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.nodes.Document;
import org.jsoup.select.NodeVisitor;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

@Slf4j
public class PageRecord {
	private static final String reservedChars = "|\\?*<:>+[]/";
	private static final int MAX_NAME_LENGTH = 255;
	private static final String HTML_SUFFIX = ".html";
	private static final int MAX_GENERATED_LENGTH = MAX_NAME_LENGTH - HTML_SUFFIX.length();

	public static final String PAGE_SCHEME = "page:";
	public static final String REF_SCHEME = "ref:";
	public static final String NOTE_SCHEME = "note:";
	public static final String SUMMARY_ELEMENT = "p";
	public static final String SUMMARY_ID = "article-summary";

	public static final Predicate<Element> HTML = NodeHelper.elementByNameCaseInsensitive("html");
	public static final Predicate<Element> LANGUAGE = HTML.and(ByAttributeValue.literal("name", "lang", false));


	public static final Predicate<Element> ANCHOR = NodeHelper.elementByNameCaseInsensitive("a");
	public static final Predicate<Element> ANCHOR_REF = ANCHOR.and(ByAttributeValue.startsWith("href", REF_SCHEME));
	public static final Predicate<Element> ELEMENT_WITH_ID = NodeHelper.elementByNamesCaseInsensitive("h1", "h2", "h3", "dt", "figcaption").and(ByAttributeValue.isUuid("id"));

	public static final String NBSP = "" + Entities.NBSP;
	public static final String STYLE_CSS = "./style/simple-static-cms.css";
	public static final String CSS_TARGET_TYPE = "simple-static-cms-style-type";
	public static final String PAGE_COPYRIGHT = "copyright";
	public static final String REFERENCE_LIST = "reference-list";
	public static final String NOTE_NUMBER = "note-number";

	private final Element header;

	@Getter
	private final @NonNull IndexedPage indexedPage;
	@NonNull
	@Getter
	private final Path path;
	@NonNull
	@Getter
	private final Path rootPath;
	@NonNull
	private final Document document;
	@NonNull
	private final Element article;
	private List<Node> summary;
	@NonNull
	private final Element footer;
	private boolean index;

	@Getter
	private PageRecord parent = null;
	private final SortedSet<PageRecord> children = createPageSet();
	private SortedSet<PageRecord> siblings = null;
	private String dynamicFilename = null;
	private boolean duplicate = false;

	public static final Comparator<PageRecord> COMPARE_BY_NAME = (p1, p2) -> {
		String title1 = p1.getIndexedPage().getTitle();
		String title2 = p2.getIndexedPage().getTitle();
		int i = title1.compareToIgnoreCase(title2);
		if (i != 0) {
			return i;
		}
		return p1.getIndexedPage().getId().hashCode() - p2.getIndexedPage().getId().hashCode();
	};

	public static final Comparator<PageRecord> createDateComparator(long mostRecentCreated, long mostRecentModified) {
		return new Comparator<PageRecord>() {
			@Override
			public int compare(PageRecord p1, PageRecord p2) {
				double createP1 = Math.log(Math.max(1, mostRecentCreated - p1.getIndexedPage().getTimePublished().toMillis()));
				double createP2 = Math.log(Math.max(1, mostRecentCreated - p2.getIndexedPage().getTimePublished().toMillis()));
				double createValue = createP1 - createP2;
				double modP1 = Math.log(Math.max(1, mostRecentModified - p1.getIndexedPage().getTimeModified().toMillis()));
				double modP2 = Math.log(Math.max(1, mostRecentModified - p2.getIndexedPage().getTimeModified().toMillis()));
				double modValue = modP1 - modP2;

				double value = createValue * 10 + modValue;
				return value < 0 ? -1 : value > 0 ? 1 : p1.getIndexedPage().getId().hashCode() - p2.getIndexedPage().getId().hashCode();
			}
		};
	}

	public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd");

	public static TreeSet<PageRecord> createPageSet() {
		return new TreeSet<PageRecord>(COMPARE_BY_NAME);
	}

	public PageRecord(Document sourceDocument, Path path, Path rootPath, @NonNull PageReferrals pageReferrals) {
		indexedPage = new IndexedPage(sourceDocument, pageReferrals);
		Node head = DocumentUtils.getNodeByTag(sourceDocument, "head", NodeExpectation.UNIQUE);
		Element html = sourceDocument.getElementsByTag("html").first();
		String language = html != null ? html.attr("lang") : null;
		String htmlDeclaration = language != null ? "<html lang=\"" + language + "\"></html>" : "<html></html>";
		if (index) {
			System.out.println("INDEX " + indexedPage.getTitle());
		}

		Element sourceBody = (Element) DocumentUtils.getNodeByTag(sourceDocument, "body", NodeExpectation.UNIQUE);
		if (sourceBody == null) {
			throw new PageException("Page has no article!");
		}
		this.document = Jsoup.parse("<!DOCTYPE html>" + htmlDeclaration);
		this.header = this.document.createElement("header");
		this.article = this.document.createElement("article");
		this.footer = this.document.createElement("footer");
		this.path = path;

		try {
			rootPath.relativize(path);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(this + ": path not relative to root-path " + rootPath);
		}
		this.rootPath = rootPath;
	}

	@Override
	public String toString() {
		return "Page \"" + indexedPage.getTitle() + "\" [" + indexedPage.getId() + "] (" + path.toString() + ")";
	}

	public void replacePageReferences(@NonNull Map<UUID, PageRecord> pages) {
		indexedPage.visitAnchors(anchor -> {
			String href = anchor.attr("href");
			if (href.isBlank()) {
				removeReference(anchor);
				return;
			}
			PageLink pageLink = getPageLink(href);
			if (pageLink == null) {
				return;
			}

			String localId = pageLink.getLocalId();
			IndexedPage page;
			if (pageLink.getUuid() == indexedPage.getId()) {
				if (localId == null) {
					removeReference(anchor);
					return;
				}
				page = indexedPage;
			}
			else {
				PageRecord pageRecord = pages.get(pageLink.getUuid());
				if (pageRecord == null) {
					removeReference(anchor);
					return;
				}
				page = pageRecord.getIndexedPage();
			}

			IndexedPage.Note note = page.getNoteById().get(localId);
			if (note != null) {
				if (anchor.text().isBlank()) {
					anchor.addClass("reference-ptr");
					if (pageLink.getUuid() != indexedPage.getId()) {
						anchor.text("*" + note.number());
					}
					else {
						anchor.text(Integer.toString(note.number()));
					}
				}
				return;
			}

			Map<String, String> captionById = page.getCaptionById();
			if (!captionById.containsKey(localId)) {
				removeReference(anchor);
				return;
			}
			if (anchor.text().isBlank()) {
				String caption = captionById.get(localId);
				anchor.text(caption == null || caption.isBlank() ? "???" : caption);
			}
		});

		appendReferences();
	}

	private static void removeReference(Element anchor) {
		anchor.tagName("span");
		anchor.removeAttr("href");
	}

	private PageLink getPageLink(@NonNull String href) {
		PageLink pageLink = indexedPage.getPageReferrals().of(href);
		if (pageLink != null) {
			return pageLink;
		}
		if (href.charAt(0) == DocumentUtils.LOCAL_LINK) {
			return PageLink.of(indexedPage.getId(), href.substring(1));
		}
		return null;
	}

	private void elementTextReplacement(String refPageTitle, Element n) {
		String content = n.text();
		if (content == null || content.isEmpty()) {
			n.text(refPageTitle);
		} else {
			switch (content.trim()) {
				case ":title":
					n.text(refPageTitle);
					break;
				case ":title-lower":
					n.text(refPageTitle.toLowerCase());
					break;
				default:
					// No replacement
			}
		}
	}

	public void setParent(PageRecord parent) {
		this.parent = parent;
	}

	public boolean addChild(@NonNull PageRecord rec) {
		return children.add(rec);
	}

	public SortedSet<PageRecord> getChildren() {
		return Collections.unmodifiableSortedSet(children);
	}

	public boolean isDuplicate() {
		return duplicate;
	}

	public void markDuplicate() {
		duplicate = true;
	}

	public void resetIndex() {
		index = false;
	}

	public List<PageRecord> getParents(boolean reverse) {
		List<PageRecord> list = new ArrayList<>();
		PageRecord parent = this.parent;
		while (parent != null) {
			if (reverse) {
				list.add(parent);
			} else {
				list.add(0, parent);
			}
			parent = parent.parent;
		}

		return list;
	}

	public void setSiblings(@NonNull SortedSet<PageRecord> siblings) {
		this.siblings = Collections.unmodifiableSortedSet(siblings);
	}

	public boolean isIndex() {
		return parent == null && index;
	}

	public String getDynamicFilename() {
		if (dynamicFilename != null) {
			return dynamicFilename;
		}
		List<PageRecord> parents = getParents(true);
		StringBuilder name = new StringBuilder();
		String title = indexedPage.getTitle();
		appendNormalized(name, title);
		if (!parents.isEmpty()) {
			parents.forEach(p -> {
				if (Character.isUpperCase(p.getIndexedPage().getTitle().charAt(0))) {
					name.append("_-");
				} else {
					name.append("_-_");
				}
				appendNormalized(name, p.getIndexedPage().getTitle());
			});
		}
		if (name.length() > MAX_GENERATED_LENGTH) {
			name.setLength(MAX_GENERATED_LENGTH);
		}
		;
		name.append(".html");
		int i = 0;
		while (i < name.length() && name.charAt(i) == '_') {
			i++;
		}
		name.delete(0, i);
		name.insert(0, '/');
		name.insert(0, '.');
		dynamicFilename = name.toString();

		return dynamicFilename;
	}

	public void writePage(@NonNull Writer writer, @NonNull Map<String, Object> cache) throws IOException {
		addHead(cache);
		addBody((String) cache.get(PAGE_COPYRIGHT));

		Document.OutputSettings outputSettings = document.outputSettings();
		outputSettings.charset(StandardCharsets.UTF_8);
		outputSettings.escapeMode(org.jsoup.nodes.Entities.EscapeMode.base);
		writer.append(document.outerHtml());
	}

	private void addHead(@NonNull Map<String, Object> cache) {
		Element head = document.head();

		head.appendElement("meta").attr("charset", "UTF-8");

		head.appendElement("meta")
				.attr("name", "viewport")
				.attr("content", "width=device-width, initial-scale=1.0, maximum-scale=2, minimum-scale=0.5");

		long stamp = System.currentTimeMillis();

		head.appendElement("link")
				.attr("rel", "stylesheet")
				.attr("href", "https://fonts.googleapis.com/css?family=Open+Sans:400italic,600italic,400,600")
				.attr("type", "text/css");
		head.appendElement("link")
				.attr("rel", "stylesheet")
				.attr("href", STYLE_CSS + "?stamp=" + stamp)
				.attr("type", "text/css");
		if (indexedPage.isMath()) {
			head.appendElement("script")
					.attr("type", "text/javascript")
					.attr("src", "https://cdnjs.cloudflare.com/ajax/libs/mathjax/2.7.1/MathJax.js?config=TeX-AMS-MML_HTMLorMML")
					.appendChild(new DataNode("MathJax.Hub.Config({displayAlign: \"left\", displayIndent: \"2ex\" });", ""));
		}
		head.appendElement("script")
				.attr("type", "text/javascript")
				.attr("src", "./emmef-util.js?stamp=" + stamp);

		head.appendElement("title").text(generateTitleTrail());
	}

	private void addBody(String copyRight) {
		Element body = document.body();
		body.attr("onload", "EmmefUtil.init();");
		body.appendChild(header);
		Element nav = header.appendElement("nav");

		List<PageRecord> parents = getParents(false);
		List<PageRecord> self = Collections.singletonList(this);
		List<PageRecord> siblings = new ArrayList<>();
		getSiblings().forEach(p -> {
			if (p != this) {
				siblings.add(p);
			}
		});

		ArrayList<PageRecord> children = new ArrayList<>(getChildren());
		addPermaLink(nav);
		nav.appendElement("span")
				.attr("onclick", "EmmefUtil.contrast()")
				.attr("class", "contrast-setter")
				.html("&#x25E9");


		if (!parents.isEmpty()) {
			writeLinks(null, nav, parents, "parents");
		}
		writeLinks(this, nav, self, "current");
		if (!children.isEmpty()) {
			writeLinks(null, nav, children, "children");
		}

//        writeLinks(null, nav, siblings, "siblings");

		header.appendElement("div")
				.attr("id", "article-title")
				.text(generateTitleTrail());

		body.appendChild(article);

		addDateAndCopyright(copyRight);
		if (footer.children().size() != 0) {
			body.appendChild(footer);
		}
	}

	private void addPermaLink(Element nav) {
		String imageStyles = duplicate ? "permalink-disabled" : "permalink-enabled";

		if (!duplicate) {
			nav.appendElement("a")
					.attr("href", indexedPage.getId().toString() + ".html")
					.attr("class", imageStyles)
					.attr("title", "Permanent link")
					.text("" + Entities.ODOT);
		} else {
			nav.appendElement("span")
					.attr("class", imageStyles)
					.attr("title", "Permanent link")
					.text("" + Entities.ODOT);
		}
	}

	private String generateTitleTrail() {
		return generateTitleTrail(true);
	}

	private String generateTitleTrail(boolean showTopmost) {
		StringBuilder output = new StringBuilder();
		output.append(indexedPage.getTitle());

		PageRecord parent = getParent();
		if (parent != null) {
			output.append(Entities.NBSP).append(Entities.MDASH).append(" ");
			parentTitle(output, parent, showTopmost);
		}

		return output.toString();
	}

	public String parentTitle(boolean showTopmost) {
		if (parent != null) {
			StringBuilder output = new StringBuilder();
			parentTitle(output, parent, showTopmost);
			return output.toString();
		}
		return "";
	}

	private static void parentTitle(StringBuilder output, PageRecord page, boolean showTopmost) {
		PageRecord parent = page.getParent();
		if (parent != null) {
			if (parent.parent != null || showTopmost) {
				parentTitle(output, parent, showTopmost);
				output.append(Entities.NBSP).append("/ ");
			}
		}
		output.append(page.getIndexedPage().getTitle());
	}

	private void writeLinks(PageRecord self, Element nav, List<PageRecord> pages, String baseClass) {
		int size = pages.size();
		if (size == 0) {
			return;
		}
		for (int i = 0; i < size; i++) {
			PageRecord page = pages.get(i);
			boolean isFirst = i == 0;
			boolean isLast = i == size - 1;
			boolean isSelf = self != null && PageRecord.COMPARE_BY_NAME.compare(page, self) == 0;

			if (isFirst) {
				nav.appendElement("span").attr(
						"class", createClasses(
								baseClass, "separator", true, false, false));
			}
			nav.appendElement("a")
					.attr("href", page.getDynamicFilename())
					.attr("class", createClasses(baseClass, "element", isFirst, isLast, isSelf))
					.text(page.getIndexedPage().getTitle());

			nav.appendElement("span").attr(
					"class", createClasses(
							baseClass, "separator", false, isLast, false));
		}
	}

	private String createClasses(String baseClass, String subClass, boolean isFirst, boolean isLast, boolean isSelf) {
		StringBuilder classes = new StringBuilder();

		classes.append(baseClass).append(" ").append(subClass).append(" ").append(baseClass).append("-").append(subClass);
		if (isFirst) {
			addPositionClasses(classes, baseClass, subClass, "first");
		}
		if (isLast) {
			addPositionClasses(classes, baseClass, subClass, "last");
		}
		if (!isFirst && !isLast) {
			addPositionClasses(classes, baseClass, subClass, "inner");
		}
		if (isSelf) {
			addPositionClasses(classes, baseClass, subClass, "self");
		}
		return classes.toString();
	}

	private StringBuilder addPositionClasses(StringBuilder classes, String baseClass, String subClass, String position) {
		return classes
				.append(" ").append(baseClass).append("-").append(position)
				.append(" ").append(subClass).append("-").append(position)
				.append(" ").append(baseClass).append("-").append(subClass).append("-").append(position);
	}

	private SortedSet<PageRecord> getSiblings() {
		if (siblings != null) {
			return siblings;
		}
		if (parent != null) {
			return parent.getChildren();
		}
		return Collections.emptySortedSet();
	}

	private Element appendReferences() {
		if (indexedPage.getNoteById().isEmpty()) {
			return null;
		}
		Element referenceList = footer.appendElement("div")
				.attr("class", "reference references")
				.appendElement("table")
				.attr("class", "reference reference-list")
				.attr("id", REFERENCE_LIST);

		SortedSet<IndexedPage.Note> notes = new TreeSet<>(Comparator.comparingInt(IndexedPage.Note::number));
		notes.addAll(indexedPage.getNoteById().values());
		for (IndexedPage.Note note : notes) {
			Element node = note.node();
			Element reference = referenceList.appendElement("tr")
					.attr("class", "reference reference-item")
					.attr("id", node.id());
			reference.appendElement("td")
					.attr("class", "reference reference-item-number")
					.text(Integer.toString(note.number()));
			Element content = reference.appendElement("td")
					.attr("class", "reference reference-item-content");
			node.attr("class", "reference reference-item-content-link");
			content.appendChild(node);
		}
		return referenceList;
	}

	private void addDateAndCopyright(String copyRight) {
		Element fileData = footer.appendElement("div").attr("class", "file-data");
		StringBuilder fileDating = new StringBuilder();
		FileTime timePublished = indexedPage.getTimePublished();
		FileTime timeModified = indexedPage.getTimeModified();
		fileDating.append(formatFileDateInGMT(timeModified));
		if (timeModified.toMillis() - timePublished.toMillis() > 60000) {
			fileDating.append("\u00a0~(").append(formatFileDateInGMT(timePublished)).append(")");
		}
		fileDating.append("\u00a0GMT");
		fileData.appendElement("div").attr("class", "source-modification")
				.appendElement("span").attr("class", "milliseconds-date")
				.text(fileDating.toString());
		if (copyRight != null) {
			String years;
			int yearCreated = getGMTYear(timePublished.toMillis());
			int yearModified = getGMTYear(timeModified.toMillis());
			if (yearCreated >= yearModified) {
				years = String.format("%04d", yearModified);
			} else {
				years = String.format("%04d\u2013%04d", yearCreated, yearModified);
			}
			fileData.appendElement("span").attr("class", "source-copyright")
					.text(String.format("\u00a9\u00a0%s\u00a0%s.", years, copyRight.replaceAll("\\s", "\u00a0")));
		}
	}

	private String formatFileDateInGMT(FileTime timeModified1) {
		return DATE_TIME_FORMATTER.format(getCalendarInGMT(timeModified1.toMillis()).toZonedDateTime());
	}

	public void replaceLastArticlesReference(List<PageRecord> sortedPages) {
		Element latestArticlesElement = indexedPage.getLatestArticles();
		if (latestArticlesElement == null) {
			return;
		}
		List<PageRecord> orderedChildren = new ArrayList<>();
		PageRecord self = this;
		sortedPages.forEach((p) -> {
			List<Node> s = p.ensureSummary();
			if (p.isChildOf(self) && orderedChildren.size() < 10 && s != null && !s.isEmpty()) {
				orderedChildren.add(p);
			}
		});
		if (orderedChildren.isEmpty()) {
			return;
		}
		latestArticlesElement.tagName("div");
		latestArticlesElement.attr("class", "latest-articles");
		orderedChildren.forEach((page) -> addArticle(latestArticlesElement, page));
	}

	private void addArticle(Element articleList, PageRecord page) {
		List<Node> s = page.ensureSummary();
		if (s == null) {
			return;
		}
		Element item = articleList.appendElement("div");
		if (articleList.children().size() == 1) {
			item.attr("class", "latest-articles-item latest-articles-item-first");
		} else {
			item.attr("class", "latest-articles-item latest-articles-item-subsequent");
		}
		String categoryLink = page.parent != null ? page.parent.getDynamicFilename() : null;
		Element categoryDiv = item
				.appendElement("div")
				.attr("class", "latest-article-category");
		if (categoryLink != null) {
			categoryDiv.appendElement("a")
					.attr("href", categoryLink)
					.attr("class", "latest-article-category")
					.text(page.parentTitle(false));
		} else {
			categoryDiv.text(page.parentTitle(false));
		}


		item
				.appendElement("div").attr("class", "latest-article-date")
				.appendElement("span").attr("class", "milliseconds-age")
				.text(Long.toString(indexedPage.getTimeModified().toMillis()));

		item
				.appendElement("div")
				.attr("class", "latest-article-title")
				.appendElement("a")
				.attr("class", "latest-article-link")
				.attr("href", page.getDynamicFilename())
				.text(page.getIndexedPage().getTitle());

//        Element summaryAndDate = item.appendElement("div").attr("class", "latest-article-content");

		Element summary = item
				.appendElement("div").attr("class", "latest-article-summary");
		for (Node n : s) {
			summary.appendChild(n.clone());
		}

	}

	public List<Node> ensureSummary() {
		if (summary != null) {
			return summary;
		}
		summary = summarizeText();
		return summary;
	}

	private List<Node> summarizeText() {
		Element p = NodeHelper.deepGetFirst(article,
				Element.class, e -> {
					if (e == null) {
						return false;
					}
					if (!SUMMARY_ELEMENT.equalsIgnoreCase(e.tagName())) {
						return false;
					}
					String id = e.attr("id");
					return SUMMARY_ID.equalsIgnoreCase(id);
				});
		if (p == null) {
			return null;
		}
		LocalToRelativeLinkVisitor visitor = new LocalToRelativeLinkVisitor();
		ArrayList<Node> summary = new ArrayList<>();
		for (Node child : p.childNodes()) {
			summary.add(child.clone().traverse(visitor));
		}
		return summary.isEmpty() ? Collections.emptyList() : summary;
	}

	private boolean isChildOf(PageRecord supposedParent) {
		if (supposedParent == null) {
			return false;
		}
		PageRecord p = this.parent;
		while (p != null) {
			if (p.getIndexedPage().getId().equals(supposedParent.getIndexedPage().getId())) {
				return true;
			}
			p = p.parent;
		}
		return false;
	}


	private int getGMTYear(long millis) {
		GregorianCalendar calendar = getCalendarInGMT(millis);
		return calendar.get(Calendar.YEAR);
	}

	private GregorianCalendar getCalendarInGMT(long millis) {
		GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
		calendar.setTimeInMillis(millis);
		return calendar;
	}


	private void appendNormalized(@NonNull StringBuilder output, @NonNull String name) {
		name.chars().forEach(c -> {
			char chr = (char) c;
			if (chr <= ' ' || chr >= '\u007f' || reservedChars.indexOf(c) != -1) {
				output.append('_');
			} else if (chr >= 'A' && chr <= 'Z') {
				output.append('_').append((char) Character.toLowerCase(c));
			} else {
				output.append(chr);
			}
		});
	}

	private static UUID getPageRefId(Node pageRef, String scheme) {
		String refIdText = getReferenceValue(pageRef, scheme);
		UUID refId;
		try {
			refId = UUID.fromString(refIdText);
		} catch (IllegalArgumentException e) {
			throw new PageException("A href with scheme " + scheme + " requires a uuid");
		}
		return refId;
	}

	private static String getReferenceValue(Node pageRef, String scheme) {
		String href = pageRef.attr("href");
		return href.substring(scheme.length());
	}

	private static UUID getUuidorNull(String reference) {
		try {
			return UUID.fromString(reference);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private class LocalToRelativeLinkVisitor implements NodeVisitor {
		@Override
		public void head(Node node, int depth) {

		}

		@Override
		public void tail(Node node, int depth) {
			if (!(node instanceof Element)) {
				return;
			}
			Element e = (Element) node;
			if (!"a".equalsIgnoreCase(e.tagName())) {
				return;
			}
			String href = e.attr("href");
			if (href == null || !href.startsWith("#")) {
				return;
			}
			String newHref = getDynamicFilename() + href;
			e.attr("href", newHref);
		}
	}
}
