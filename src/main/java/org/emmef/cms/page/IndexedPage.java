package org.emmef.cms.page;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.document.Attributes;
import org.emmef.cms.document.Elements;
import org.emmef.cms.document.Identifiers;
import org.emmef.cms.document.Styles;
import org.emmef.cms.page.resolving.PageLink;
import org.emmef.cms.page.resolving.PathInfo;
import org.emmef.cms.util.FileTimeStamps;
import org.joda.time.DateTime;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.emmef.cms.page.DocumentUtils.*;

@Slf4j
public class IndexedPage extends PathInfo {
	public static final String META_MATH = "scms-uses-math";
	public static final String META_PUBLISH_DATE = "scms-published-date";
	public static final String META_REPUBLISH_DATE = "scms-republish-date";
	public static final Pattern ID_TO_TITLE = Pattern.compile("[^\\p{Alnum}]+");

	@Getter
	private final @NonNull String title;
	@Getter
	private final boolean math;
	@Getter
	private final @NonNull Element article;
	@Getter
	private final Element latestArticles;
	@Getter
	private final @NonNull Element summary;
	@Getter
	private final @NonNull Element summaryInListing;
	@Getter
	private final @NonNull Map<String, FootNoteScanner.Note> noteById;
	@Getter
	private final @NonNull Map<String, Element> captionById;
	@Getter
	private final DateTime timeModified;
	@Getter
	private final DateTime timePublished;
	@Getter
	private final Document document;
	private List<PageLink> mainTagList;

	public IndexedPage(Document document, @NonNull PathInfo info) {
		super(info);
		@NonNull Element sourceHtml = getHtmlElement(document);
		Element head = getNodeByTag(sourceHtml, "head");
		this.math = Boolean.parseBoolean(DocumentUtils.getMetaValueOrNull(head, META_MATH, "tex support", Function.identity()));
		Path file = getSourcePath();
		this.timePublished = PageUtils.getTime(head, META_PUBLISH_DATE, Collections.singletonList(FileTimeStamps.createdSupplier(file)));
		this.timeModified = PageUtils.getTime(head, META_REPUBLISH_DATE, Arrays.asList(() -> this.timePublished, FileTimeStamps.modifiedSupplier(file)));
		this.article = PageUtils.getArticle(sourceHtml);
		this.title = DocumentUtils.getTitle(head);
		this.summary = PageUtils.searchForSummary(sourceHtml);
		this.summaryInListing = summary.clone();
		this.document = htmlDeclarationFromElement(sourceHtml);
		this.captionById = PageUtils.createCaptionById(article);
		FootNoteScanner footNoteScanner = new FootNoteScanner(getResolver(), getPageLink());
		this.noteById = footNoteScanner.scanForNotes(sourceHtml, article);
		footNoteScanner.removeManagedNotes(article);
		this.latestArticles = searchForLatestArticles(article);
	}

	@Override
	public String toString() {
		return String.format("%s %s \"%s\"", IndexedPage.class.getSimpleName(), getPageLink().getLink(), title);
	}

	public void resolveInContext(@NonNull List<IndexedPage> pages, @NonNull SortedSet<PageLink> tags) {
		replacePageReferences(pages);
		generateMainTagList(tags);
		replaceLastArticlesReference(pages);
	}

	public List<PageLink> getMainTagList() {
		return mainTagList != null ? mainTagList : List.of();
	}

	private void replacePageReferences(@NonNull List<IndexedPage> pages) {
		replacePageReferences(article, pages, false); //  includes summary
		noteById.values().forEach(note -> {
			replacePageReferences(note.node(), pages, false);
		});
		replacePageReferences(summaryInListing, pages, true);
	}

	private void replacePageReferences(@NonNull Element element, @NonNull List<IndexedPage> pages, boolean globalize) {
		PageUtils.scanForManagedAnchors(getResolver(), element, (anchor, link) -> {
			findPage(pages, link, globalize).ifPresent(result -> result.elementContentModifier.accept(anchor));
		});
	}

	private void generateMainTagList(@NonNull SortedSet<PageLink> existingTagLinks) {
		if (this.mainTagList == null) {
			var links = new TreeSet<PageLink>();
			if (isIndex() && !existingTagLinks.contains(getPageLink())) {
				log.warn("Page \"{}\" ({}) is index, but not marked as tag.", getTitle(), getPageLink().getLink());
			}
			getPageLink().createTagHierarchy().stream()
					.filter(existingTagLinks::contains)
					.forEach(links::add);
			if (isIndex()) {
				links.remove(getPageLink());
			}

			this.mainTagList = List.copyOf(links);
		}
	}

	private Optional<PageResult> findPage(@NonNull List<IndexedPage> pages, PageLink pageLink, boolean globalize) {
		if (getPageLink().isSamePage(pageLink)) {
			return findInPage(pageLink, globalize);
		}
		for (IndexedPage page : pages) {
			if (page.getPageLink().isSamePage(pageLink)) {
				return page.findInPage(pageLink, true);
			}
		}
		return Optional.empty();
	}

	private Optional<PageResult> findInPage(PageLink pageLink, boolean globalize) {
		PageLink globalized = getPageLink().globalize(pageLink);
		PageLink transformed = globalize ? globalized : getPageLink().localize(pageLink);
		if (pageLink.isPage()) {
			return Optional.of(new PageResult(globalized, (anchor) -> {
				anchor.attr(Attributes.HREF,
						transformed.isLocal() ? transformed.getLink() : getResolver().toTargetHref(transformed));
				if (anchor.text().isBlank()) {
					anchor.children().remove();
					anchor.text(title);
				}
			}));
		}
		if (captionById.containsKey(pageLink.getLocalId())) {
			return Optional.of(new PageResult(globalized, (anchor) -> {
				anchor.attr(Attributes.HREF,
						transformed.isLocal() ? transformed.getLink() : getResolver().toTargetHref(transformed));
				Element element = captionById.get(pageLink.getLocalId());
				if (anchor.text().isBlank()) {
					anchor.children().remove();
					anchor.text(element.text());
				}
			}));
		}
		if (noteById.containsKey(pageLink.getLocalId())) {
			return Optional.of(new PageResult(globalized, (anchor) -> {
				anchor.attr(Attributes.HREF, transformed.isLocal() ? transformed.getLink() : getResolver().toTargetHref(transformed));
				if (anchor.text().isBlank()) {
					FootNoteScanner.Note note = noteById.get(pageLink.getLocalId());
					anchor.children().remove();
					anchor.addClass(Styles.FOOTNOTE_REFERENCE);
					if (globalize) {
						anchor.addClass(Styles.FOOTNOTE_EXTERNAL);
					}
					anchor.text(note.number().toString());
				}
				anchor.attr(Attributes.ANCHOR_TITLE, generateAnchorTitle(transformed.getLocalId()));
			}));
		}
		return Optional.empty();
	}

	private String generateAnchorTitle(@NonNull String localId) {
		return ID_TO_TITLE.matcher(localId).replaceAll(" ").trim();
	}

	private Element searchForLatestArticles(Element sourceBody) {
		List<Element> elements = sourceBody.getAllElements().stream()
				.filter(e -> Elements.LATEST_ARTICLE.equalsIgnoreCase(e.tagName()))
				.filter(e -> Identifiers.ARTICLE_ENTRY_ID.equalsIgnoreCase(e.id()))
				.toList();
		if (elements.isEmpty()) {
			return null;
		}
		Element latestArticle = elements.getFirst();
		for (Element remove : elements.subList(1, elements.size())) {
			remove.remove();
		}
		return latestArticle;
	}

	private void replaceLastArticlesReference(@NonNull List<IndexedPage> sortedPages) {
		Element latestArticlesElement = getLatestArticles();
		if (latestArticlesElement == null) {
			return;
		}
		if (!isIndex()) {
			// No indices for non-index pages; remove the element
			latestArticlesElement.remove();
			return;
		}
		latestArticlesElement.children().remove();

		var matchingPages = getMatchingPages(sortedPages);

		if (matchingPages.isEmpty()) {
			return;
		}
		latestArticlesElement.tagName("div");
		latestArticlesElement.addClass(Styles.ARTICLE_ENTRY_LIST);
		int entryCount = matchingPages.size();
		AtomicInteger entryNumber = new AtomicInteger(0);
		matchingPages.forEach((page) -> addArticle(latestArticlesElement, page, entryNumber.incrementAndGet(), entryCount));
	}

	private @NonNull ArrayList<IndexedPage> getMatchingPages(@NonNull List<IndexedPage> pages) {
		var matchingPages = new ArrayList<IndexedPage>();
		pages.forEach(p -> {
			if (p.isIndex()) {
				return;
			}
			List<PageLink> mainTagList = p.getMainTagList();
			if (mainTagList.isEmpty()) {
				return;
			}
			PageLink pageLink = mainTagList.getLast();
			if (getPageLink().equals(pageLink)) {
				matchingPages.add(p);
			}
		});
		return matchingPages;
	}

	private static void addArticle(Element articleList, IndexedPage page, int entryNumber, int entryCount) {
		Element item = articleList.appendElement(Elements.DIV);
		item.addClass(Styles.LIST_ENTRY);
		if (entryNumber == 1) {
			item.addClass(Styles.LIST_FIRST);
		}
		if (entryNumber == entryCount) {
			item.addClass(Styles.LIST_LAST);
		}

		item
				.appendElement(Elements.DIV)
				.addClass(Styles.ARTICLE_ENTRY_TITLE)
				.appendElement(Elements.ANCHOR)
				.addClass(Styles.ARTICLE_ENTRY_LINK)
				.attr(Attributes.HREF, page.getResolver().toTargetHref(page.getPageLink()))
				.text(page.getTitle());

		item
				.appendElement(Elements.DIV).addClass(Styles.ARTICLE_ENTRY_DATE)
				.appendElement(Elements.SPAN).addClass(Styles.EPOCH_MILLIS)
				.text(Long.toString(page.getTimeModified().getMillis()));

		Element summary = item
				.appendElement(Elements.DIV).addClass(Styles.ARTICLE_ENTRY_CONTENT);

		Element summaryInListing = page.getSummaryInListing();
		summaryInListing.removeAttr("id");
		summary.appendChild(summaryInListing.clone());
	}

	private record PageResult(PageLink link, Consumer<Element> elementContentModifier) {
	}
}
