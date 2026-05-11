package org.emmef.cms.page;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.document.Attributes;
import org.emmef.cms.document.Elements;
import org.emmef.cms.document.Styles;
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
 * TODO Move the sub-tag index page(s) out of the list of articles
 */
@Slf4j
public class PageRecord {
	public static final String STYLE_CSS = "/style/simple-static-cms.css";

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
		this.header = this.document.createElement(Elements.HEADER);
		this.document.appendChild(indexedPage.getArticle());
		this.footer = this.document.createElement(Elements.FOOTER);
	}

	@Override
	public String toString() {
		return "Page \"" + indexedPage.getTitle() + "\" (" + indexedPage.getPageLink() + ")";
	}

	public void writePage(@NonNull Writer writer, @NonNull String copyRight, String siteName, @NonNull SequencedCollection<IndexedPage> pages, @NonNull SequencedCollection<PageLink> tags) throws IOException {
		addHead();
		addBody(copyRight, siteName, pages, tags);

		Document.OutputSettings outputSettings = document.outputSettings();
		outputSettings.charset(StandardCharsets.UTF_8);
		outputSettings.escapeMode(org.jsoup.nodes.Entities.EscapeMode.base);
		writer.append(document.outerHtml());
	}

	private void addHead() {
		Element head = document.head();

		head.appendElement(Elements.META).attr(Attributes.META_CHARSET, "UTF-8");

		head.appendElement(Elements.META)
				.attr(Attributes.NAME, "viewport")
				.attr(Attributes.META_CONTENT, "width=device-width, initial-scale=1.0, maximum-scale=2, minimum-scale=0.5");

		long stamp = System.currentTimeMillis();

		head.appendElement(Elements.META_LINK)
				.attr(Attributes.META_RELATION, "stylesheet")
				.attr(Attributes.HREF, "https://fonts.googleapis.com/css?family=Open+Sans:400italic,600italic,400,600")
				.attr(Attributes.META_TYPE, "text/css");
		head.appendElement(Elements.META_LINK)
				.attr(Attributes.META_RELATION, "stylesheet")
				.attr(Attributes.HREF, STYLE_CSS + "?stamp=" + stamp)
				.attr(Attributes.META_TYPE, "text/css");
		if (indexedPage.isMath()) {
			head.appendElement(Elements.META_SCRIPT)
					.attr(Attributes.META_TYPE, "text/javascript")
					.attr(Attributes.META_SOURCE, "https://cdnjs.cloudflare.com/ajax/libs/mathjax/2.7.1/MathJax.js?config=TeX-AMS-MML_HTMLorMML")
					.appendChild(new DataNode("MathJax.Hub.Config({displayAlign: \"left\", displayIndent: \"2ex\" });", ""));
		}
		head.appendElement(Elements.META_SCRIPT)
				.attr(Attributes.META_TYPE, "text/javascript")
				.attr(Attributes.META_SOURCE, "/emmef-util.js?stamp=" + stamp);

		head.appendElement(Elements.TITLE).text(generateTitleTrail());
	}

	private void addBody(String copyRight, String siteName, @NonNull SequencedCollection<IndexedPage> pages, @NonNull SequencedCollection<PageLink> tags) {
		Element body = document.body();
		body.attr(Attributes.ON_LOAD, "EmmefUtil.init();");

		addHeader(body, pages, tags);

		body.appendChild(indexedPage.getArticle());
		appendFootnotes(body);

		addDateAndCopyright(copyRight);
		if (!footer.children().isEmpty()) {
			body.appendChild(footer);
		}
	}

	private void addHeader(@NonNull Element body, @NonNull SequencedCollection<IndexedPage> pages, @NonNull SequencedCollection<PageLink> tags) {
		body.appendChild(header);
		Element nav = header.appendElement(Elements.NAVIGATION);

		// Add title
		nav.appendElement(Elements.DIV)
				.addClass(Styles.ARTICLE_TITLE)
				.text(generateTitleTrail());


		// Add tag and parent tag links
		Element tagList = nav.appendElement(Elements.DIV).addClass(Styles.TAG_LIST).addClass(Styles.TAG_LIST_MAIN);
		List<PageLink> mainTagList = getIndexedPage().getMainTagList();
		mainTagList.forEach(anchor -> {
			addTagLinkWithPadding(tagList, anchor, pages);
		});

		// Add sub tags
		if (getIndexedPage().isIndex()) {
			List<PageLink> subTags = tags.stream()
					.filter(tag -> {
						return tag.stripFile().startsWith(getIndexedPage().getPageLink().stripFile());
					})
					.filter(tag -> !tag.equals(getIndexedPage().getPageLink())).toList();

			Element subTagList = nav.appendElement(Elements.DIV).addClass(Styles.TAG_LIST).addClass(Styles.TAG_LIST_CHILDREN);
//			addTagLinkWithPadding(subTagList, getIndexedPage().getPageLink(), pages);
			if (!subTags.isEmpty()) {
				subTags.forEach(tag -> {
					addTagLinkWithPadding(subTagList, tag, pages);
				});
			}
		}

		// Add contrast change button
		header
				.appendElement(Elements.DIV).addClass(Styles.PAGE_SETTINGS)
				.appendElement(Elements.DIV).addClass(Styles.ACTION_SET_CONTRAST).attr(Attributes.ON_CLICK, "EmmefUtil.contrast()")
				.html("◩");

	}

	private void addTagLinkWithPadding(Element parent, PageLink anchor, @NonNull SequencedCollection<IndexedPage> pages) {
		parent.appendElement(Elements.SPAN).addClass(Styles.TAG_BEFORE);
		addTagElement(parent, anchor, pages, Styles.TAG_LINK);
		parent.appendElement(Elements.SPAN).addClass(Styles.TAG_AFTER);
	}

	private void addTagElement(@NonNull Element parent, @NonNull PageLink tag, @NonNull SequencedCollection<IndexedPage> pages, String optionalClass) {
		pages.stream().filter(p -> p.getPageLink().equals(tag)).findFirst().ifPresent(page -> {
			Element element = parent.appendElement(Elements.ANCHOR);
			element.attr(Attributes.HREF, indexedPage.getResolver().toTargetHref(tag));
			if (optionalClass != null) {
				element.addClass(optionalClass);
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

	public void appendFootnotes(@NonNull Element body) {
		if (indexedPage.getNoteById().isEmpty()) {
			return;
		}

		Element footnoteList = body.appendElement(Elements.DIV)
				.addClass(Styles.FOOTNOTE_LIST);

		SortedSet<FootNoteScanner.Note> notes = new TreeSet<>(Comparator.comparingInt(FootNoteScanner.Note::number));
		notes.addAll(indexedPage.getNoteById().values());
		AtomicInteger noteNumber = new AtomicInteger();
		for (FootNoteScanner.Note note : notes) {
			int number = noteNumber.incrementAndGet();
			Element node = note.node();
			Element reference = footnoteList.appendElement(Elements.DIV)
					.addClass(Styles.LIST_ENTRY);
			if (number == 1) {
				reference.addClass(Styles.LIST_FIRST);
			}
			if (number == notes.size()) {
				reference.addClass(Styles.LIST_LAST);
			}
			reference.appendElement(Elements.DIV)
					.addClass(Styles.FOOTNOTE_NUMBER)
					.text(Integer.toString(note.number()));

			node.tagName(Elements.DIV)
					.addClass(Styles.FOOTNOTE_CONTENT);
			reference.appendChild(node);
		}
	}

	private void addDateAndCopyright(String copyRight) {
		Element fileData = footer.appendElement(Elements.DIV).addClass(Styles.ARTICLE_DATA);
		StringBuilder fileDating = new StringBuilder();
		DateTime timePublished = indexedPage.getTimePublished();
		DateTime timeModified = indexedPage.getTimeModified();
		fileDating.append(formatFileDateInGMT(timeModified));
		if (timeModified.getMillis() - timePublished.getMillis() > 60000) {
			fileDating.append("\u00a0~(").append(formatFileDateInGMT(timePublished)).append(")");
		}
		fileDating.append("\u00a0GMT");
		fileData.appendElement(Elements.SPAN).addClass(Styles.ARTICLE_DATA_MODIFIED).addClass(Styles.EPOCH_MILLIS)
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
			fileData.appendElement(Elements.SPAN).addClass(Styles.ARTICLE_DATA_SEPARATOR);
			fileData.appendElement(Elements.SPAN).addClass(Styles.ARTICLE_DATA_COPYRIGHT)
					.text(String.format("\u00a9\u00a0%s\u00a0%s.", years, copyRight.replaceAll("\\s", "\u00a0")));
		}
	}

	private String formatFileDateInGMT(DateTime timeModified1) {
		return DATE_TIME_FORMATTER.format(getCalendarInGMT(timeModified1.getMillis()).toZonedDateTime());
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
