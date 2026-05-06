package org.emmef.cms.page;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.util.*;
import org.joda.time.DateTime;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.nodes.Document;
import org.jsoup.select.NodeVisitor;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * TODO A page will contain tags in link tags in the header
 * TODO Links will be maintained in a html file with header levels that must have an id attribute that will be the tag-id
 * TODO Tags will be defined in the article with <link href="tags.html#tag-id" rel="bookmark">
 * TODO Navigation will be replaced with the most important tag on top and a hellip button to move to the bottom where all tags are
 * TODO Selecting a tag will go to the tag page that contains the tag name, its parent tags and its child tags, and then a list of articles either alphabetical or recent-based
 * TODO The tags.html will be regenerated with links to tag pages, with one pseudo tag for non-tagged pages.
 */
@Slf4j
public class PageRecord {
	private static final String reservedChars = "|\\?*<:>+[]/";
	private static final int MAX_NAME_LENGTH = 255;
	private static final String HTML_SUFFIX = ".html";

	public static final String SUMMARY_ELEMENT = "p";
	public static final String SUMMARY_ID = "article-summary";

	public static final String NBSP = "" + Entities.NBSP;
	public static final String STYLE_CSS = "/style/simple-static-cms.css";
	public static final String PAGE_COPYRIGHT = "copyright";
	public static final String REFERENCE_LIST = "reference-list";
	public static final Pattern PATTERN_NOT_ALPHANUMERIC = Pattern.compile("[^\\p{Alnum}]");
	public static final Pattern PATTERN_UPPER = Pattern.compile("[\\p{Upper}]");

	private final Element header;

	@Getter
	private final @NonNull IndexedPage indexedPage;
	@NonNull
	private final Document document;
	private List<Node> summary;
	@NonNull
	private final Element footer;
	private boolean index;

	private final SortedSet<PageRecord> children = createPageSet();
	private SortedSet<PageRecord> siblings = null;

	public static final Comparator<PageRecord> COMPARE_BY_NAME = (p1, p2) -> {
		String title1 = p1.getIndexedPage().getTitle();
		String title2 = p2.getIndexedPage().getTitle();
		int i = title1.compareToIgnoreCase(title2);
		if (i != 0) {
			return i;
		}
		return p1.getIndexedPage().getAbsoluteUrl().compareTo(p2.getIndexedPage().getAbsoluteUrl());
	};

	public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd");

	public static TreeSet<PageRecord> createPageSet() {
		return new TreeSet<PageRecord>(COMPARE_BY_NAME);
	}

	public PageRecord(IndexedPage page) {
		this.indexedPage = page;
		this.document = Jsoup.parse(indexedPage.getHtmlDeclaration());
		this.header = this.document.createElement("header");
		this.document.appendChild(indexedPage.getArticle());
		this.footer = this.document.createElement("footer");
	}

	@Override
	public String toString() {
		return "Page \"" + indexedPage.getTitle() + "\" (" + indexedPage.getRelativePath().toString() + ")";
	}

	public String getAbsoluteUrl() {
		return indexedPage.getAbsoluteUrl();
	}

	public void writePage(@NonNull Writer writer, @NonNull Map<String, Object> cache, String siteName) throws IOException {
		addHead(cache);
		addBody((String) cache.get(PAGE_COPYRIGHT), siteName);

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
				.attr("src", "/emmef-util.js?stamp=" + stamp);

		head.appendElement("title").text(generateTitleTrail());
	}

	private void addBody(String copyRight, String siteName) {
		Element body = document.body();
		body.attr("onload", "EmmefUtil.init();");
		body.appendChild(header);
		Element nav = header.appendElement("nav");

		nav.appendElement("span")
				.attr("onclick", "EmmefUtil.contrast()")
				.attr("class", "contrast-setter")
				.html("◩");
		nav.appendElement("a")
				.attr("class", "site-link")
				.attr("href", "/")
				.html(siteName);
		nav.appendElement("div").attr("class", "tag-navigation")
				.appendElement("span").text("TAG1").appendElement("span").text("TAG2").appendElement("span").text("TAG3");
		header.appendElement("div")
				.attr("id", "article-title")
				.text(generateTitleTrail());


		body.appendChild(indexedPage.getArticle());

		addDateAndCopyright(copyRight);
		if (footer.children().size() != 0) {
			body.appendChild(footer);
		}
	}

	private String generateTitleTrail() {
		return generateTitleTrail(true);
	}

	private String generateTitleTrail(boolean showTopmost) {
		return indexedPage.getTitle();
	}
//
//	private void writeLinks(PageRecord self, Element nav, List<PageRecord> pages, String baseClass) {
//		int size = pages.size();
//		if (size == 0) {
//			return;
//		}
//		for (int i = 0; i < size; i++) {
//			PageRecord page = pages.get(i);
//			boolean isFirst = i == 0;
//			boolean isLast = i == size - 1;
//			boolean isSelf = self != null && PageRecord.COMPARE_BY_NAME.compare(page, self) == 0;
//
//			if (isFirst) {
//				nav.appendElement("span").attr(
//						"class", createClasses(
//								baseClass, "separator", true, false, false));
//			}
//			nav.appendElement("a")
//					.attr("href", page.getDynamicFilename())
//					.attr("class", createClasses(baseClass, "element", isFirst, isLast, isSelf))
//					.text(page.getIndexedPage().getTitle());
//
//			nav.appendElement("span").attr(
//					"class", createClasses(
//							baseClass, "separator", false, isLast, false));
//		}
//	}

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

	public Element appendReferences() {
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
					.attr("class", "reference reference-item");
//					.attr("id", node.id());
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
		DateTime timePublished = indexedPage.getTimePublished();
		DateTime timeModified = indexedPage.getTimeModified();
		fileDating.append(formatFileDateInGMT(timeModified));
		if (timeModified.getMillis() - timePublished.getMillis() > 60000) {
			fileDating.append("\u00a0~(").append(formatFileDateInGMT(timePublished)).append(")");
		}
		fileDating.append("\u00a0GMT");
		fileData.appendElement("div").attr("class", "source-modification")
				.appendElement("span").attr("class", "milliseconds-date")
				.text(fileDating.toString());
		if (copyRight != null) {
			String years;
			int yearCreated = getGMTYear(timePublished.getMillis());
			int yearModified = getGMTYear(timeModified.getMillis());
			if (yearCreated >= yearModified) {
				years = String.format("%04d", yearModified);
			} else {
				years = String.format("%04d\u2013%04d", yearCreated, yearModified);
			}
			fileData.appendElement("span").attr("class", "source-copyright")
					.text(String.format("\u00a9\u00a0%s\u00a0%s.", years, copyRight.replaceAll("\\s", "\u00a0")));
		}
	}

	private String formatFileDateInGMT(DateTime timeModified1) {
		return DATE_TIME_FORMATTER.format(getCalendarInGMT(timeModified1.getMillis()).toZonedDateTime());
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
//			if (p.isChildOf(self) && orderedChildren.size() < 10 && s != null && !s.isEmpty()) {
//				orderedChildren.add(p);
//			}
		});
		if (orderedChildren.isEmpty()) {
			return;
		}
		latestArticlesElement.tagName("div");
		latestArticlesElement.attr("class", "latest-articles");
		orderedChildren.forEach((page) -> addArticle(latestArticlesElement, page));
	}

	private void addArticle(Element articleList, PageRecord page) {
//		List<Node> s = page.ensureSummary();
//		if (s == null) {
//			return;
//		}
//		Element item = articleList.appendElement("div");
//		if (articleList.children().size() == 1) {
//			item.attr("class", "latest-articles-item latest-articles-item-first");
//		} else {
//			item.attr("class", "latest-articles-item latest-articles-item-subsequent");
//		}
//		String categoryLink = page.parent != null ? page.parent.getDynamicFilename() : null;
//		Element categoryDiv = item
//				.appendElement("div")
//				.attr("class", "latest-article-category");
//		if (categoryLink != null) {
//			categoryDiv.appendElement("a")
//					.attr("href", categoryLink)
//					.attr("class", "latest-article-category")
//					.text(page.parentTitle(false));
//		} else {
//			categoryDiv.text(page.parentTitle(false));
//		}
//
//
//		item
//				.appendElement("div").attr("class", "latest-article-date")
//				.appendElement("span").attr("class", "milliseconds-age")
//				.text(Long.toString(indexedPage.getTimeModified().getMillis()));
//
//		item
//				.appendElement("div")
//				.attr("class", "latest-article-title")
//				.appendElement("a")
//				.attr("class", "latest-article-link")
//				.attr("href", page.getDynamicFilename())
//				.text(page.getIndexedPage().getTitle());
//
////        Element summaryAndDate = item.appendElement("div").attr("class", "latest-article-content");
//
//		Element summary = item
//				.appendElement("div").attr("class", "latest-article-summary");
//		for (Node n : s) {
//			summary.appendChild(n.clone());
//		}
//
	}

	public List<Node> ensureSummary() {
		if (summary != null) {
			return summary;
		}
		summary = summarizeText();
		return summary;
	}

	private List<Node> summarizeText() {
		Element p = NodeHelper.deepGetFirst(indexedPage.getArticle(),
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

	public String getId() {
		return indexedPage.getAbsoluteUrl();
	}

	public String getTitle() {
		return indexedPage.getTitle();
	}

	public DateTime getTimePublished() {
		return indexedPage.getTimePublished();
	}

	public DateTime getTimeModified() {
		return indexedPage.getTimeModified();
	}

	public boolean isIndex() {
		return indexedPage.isIndex();
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
			String newHref = getAbsoluteUrl() + href;
			e.attr("href", newHref);
		}
	}
}
