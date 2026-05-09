package org.emmef.cms.page;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.page.resolving.PageLink;
import org.joda.time.DateTime;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

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

	public static final String SUMMARY_ELEMENT = "p";
	public static final String SUMMARY_ID = "article-summary";

	public static final String NBSP = "" + Entities.NBSP;
	public static final String STYLE_CSS = "/style/simple-static-cms.css";
	public static final String PAGE_COPYRIGHT = "copyright";
	public static final String REFERENCE_LIST = "reference-list";

	private final Element header;

	@Getter
	private final @NonNull IndexedPage indexedPage;
	@NonNull
	private final Document document;
	@NonNull
	private final Element footer;

	public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd");

	public PageRecord(@NonNull IndexedPage page) {
		this.indexedPage = page;
		this.document = indexedPage.getDocument();
		this.header = this.document.createElement("header");
		this.document.appendChild(indexedPage.getArticle());
		this.footer = this.document.createElement("footer");
	}

	@Override
	public String toString() {
		return "Page \"" + indexedPage.getTitle() + "\" (" + indexedPage.getPageLink() + ")";
	}

	public String getAbsoluteUrl() {
		return indexedPage.getResolver().toTargetHref(indexedPage.getPageLink());
	}

	public void writePage(@NonNull Writer writer, @NonNull Map<String, Object> cache, String siteName, @NonNull SequencedCollection<IndexedPage> pages) throws IOException {
		addHead();
		addBody((String) cache.get(PAGE_COPYRIGHT), siteName, pages);

		Document.OutputSettings outputSettings = document.outputSettings();
		outputSettings.charset(StandardCharsets.UTF_8);
		outputSettings.escapeMode(org.jsoup.nodes.Entities.EscapeMode.base);
		writer.append(document.outerHtml());
	}

	private void addHead() {
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

	private void addBody(String copyRight, String siteName, @NonNull SequencedCollection<IndexedPage> pages) {
		Element body = document.body();
		body.attr("onload", "EmmefUtil.init();");
		body.appendChild(header);
		Element nav = header.appendElement("nav");

		nav.appendElement("div")
				.attr("class", "article-title")
				.text(generateTitleTrail());


		Element tags = nav.appendElement("div").attr("class", "tag-navigation");

		// Add main tag navigation
		List<PageLink> mainTagList = getIndexedPage().getMainTagList();
		mainTagList.forEach(anchor -> {
			addTagLinkWithPadding(tags, anchor, pages, "main-tag-navigation-before", null, "main-tag-navigation-after");
		});

		nav.appendElement("span")
				.attr("onclick", "EmmefUtil.contrast()")
				.attr("class", "contrast-setter")
				.html("◩");

		body.appendChild(indexedPage.getArticle());

		addDateAndCopyright(copyRight);
		if (!footer.children().isEmpty()) {
			body.appendChild(footer);
		}
	}

	private void addTagLinkWithPadding(Element parent, PageLink anchor, @NonNull SequencedCollection<IndexedPage> pages, String beforeClass, String anchorClass, String afterClass) {
		Element beforeSpan = parent.appendElement("span");
		if (beforeClass != null) {
			beforeSpan.addClass(beforeClass);
		}
		addTagElement(parent, anchor, pages, anchorClass);
		Element afterSpan = parent.appendElement("span");
		if (afterClass != null) {
			afterSpan.addClass(afterClass);
		}
	}

	private void addTagElement(@NonNull Element parent, @NonNull PageLink tag, @NonNull SequencedCollection<IndexedPage> pages, String optionalClass) {
		pages.stream().filter(p -> p.getPageLink().equals(tag)).findFirst().ifPresent(page -> {
			Element element = parent.appendElement("a");
			element.attr("href", indexedPage.getResolver().toTargetHref(tag));
			if (optionalClass != null) {
				element.attr("class", optionalClass);
			}
			element.html(nonBreakingText(page.getTitle()));
			;
		});
	}

	private static @NonNull String nonBreakingText(@NonNull String text) {
		StringBuilder result = new StringBuilder();
		for (String part : text.split("\\p{Space}")) {
			if (!part.isBlank()) {
				if (!result.isEmpty()) {
					result.append("&nbsp;");
				}
				result.append(part);
			}
		}
		return result.toString();
	}

	private String generateTitleTrail() {
		return indexedPage.getTitle();
	}

	public void appendReferences() {
		if (indexedPage.getNoteById().isEmpty()) {
			return;
		}
		Element referenceList = footer.appendElement("div")
				.attr("class", "reference references")
				.appendElement("table")
				.attr("class", "reference reference-list")
				.attr("id", REFERENCE_LIST);

		SortedSet<FootNoteScanner.Note> notes = new TreeSet<>(Comparator.comparingInt(FootNoteScanner.Note::number));
		notes.addAll(indexedPage.getNoteById().values());
		for (FootNoteScanner.Note note : notes) {
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

	public void replaceLastArticlesReference(@NonNull List<PageRecord> sortedPages, @NonNull SequencedCollection<PageLink> tags, @NonNull SequencedCollection<IndexedPage> pages) {
		Element latestArticlesElement = indexedPage.getLatestArticles();
		if (latestArticlesElement == null) {
			return;
		}
		if (!indexedPage.isIndex()) {
			// No indices for non-index pages; remove the element
			latestArticlesElement.remove();
			return;
		}
		latestArticlesElement.children().remove();
		List<PageLink> subTags = tags.stream()
				.filter(tag -> {
					return tag.stripFile().startsWith(getIndexedPage().getPageLink().stripFile());
				})
				.filter(tag -> !tag.equals(getIndexedPage().getPageLink())).toList();
		if (!subTags.isEmpty()) {
			Element subTagList = latestArticlesElement.prependElement("div").attr("class", "sub-tag-list");
			addTagLinkWithPadding(subTagList, getIndexedPage().getPageLink(), pages, "sub-tag-before", "tag-selected", "sub-tag-after");
			subTagList.appendElement("span").addClass("sub-tag-separator");
			subTags.forEach(tag -> {
				addTagLinkWithPadding(subTagList, tag, pages, "sub-tag-before", null, "sub-tag-after");
			});
		}

		var matchingPages = getMatchingPages(sortedPages);

		if (matchingPages.isEmpty()) {
			return;
		}
		latestArticlesElement.tagName("div");
		latestArticlesElement.attr("class", "latest-articles");
		AtomicInteger counter = new AtomicInteger(0);
		matchingPages.forEach((page) -> addArticle(latestArticlesElement, page, counter.incrementAndGet()));
	}

	private @NonNull ArrayList<IndexedPage> getMatchingPages(@NonNull List<PageRecord> sortedPages) {
		var matchingPages = new ArrayList<IndexedPage>();
		sortedPages.forEach(p -> {
			List<PageLink> mainTagList = p.getIndexedPage().getMainTagList();
			if (mainTagList.isEmpty()) {
				return;
			}
			PageLink pageLink = mainTagList.getLast();
			if (indexedPage.getPageLink().equals(pageLink)) {
				matchingPages.add(p.getIndexedPage());
			}
		});
		return matchingPages;
	}

	private void addArticle(Element articleList, IndexedPage page, int itemNumber) {
		Element item = articleList.appendElement("div");
		if (itemNumber == 1) {
			item.attr("class", "latest-articles-item latest-articles-item-first");
		} else {
			item.attr("class", "latest-articles-item latest-articles-item-subsequent");
		}

		item
				.appendElement("div").attr("class", "latest-article-date")
				.appendElement("span").attr("class", "milliseconds-age")
				.text(Long.toString(page.getTimeModified().getMillis()));

		item
				.appendElement("div")
				.attr("class", "latest-article-title")
				.appendElement("a")
				.attr("class", "latest-article-link")
				.attr("href", page.getResolver().toTargetHref(page.getPageLink()))
				.text(page.getTitle());


		Element summary = item
				.appendElement("div").attr("class", "latest-article-content");

		Element summaryInListing = page.getSummaryInListing();
		summaryInListing.removeAttr("id");
		summary.appendChild(summaryInListing.clone());
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
}
