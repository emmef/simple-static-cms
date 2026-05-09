package org.emmef.cms.page;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.page.resolving.PageLink;
import org.emmef.cms.page.resolving.PathInfo;
import org.emmef.cms.parameters.NodeExpectation;
import org.emmef.cms.util.FileTimeStamps;
import org.joda.time.DateTime;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.emmef.cms.page.DocumentUtils.*;

/**
 * TODO Page summary
 * The page summary is cloned and will replace local links with absolute links that will be visited.
 * TODO Links last
 * The replacement of links will happen at the last possible moment on all pages.
 */
@Slf4j
public class IndexedPage extends PathInfo {
	public static final String META_MATH = "scms-uses-math";
	public static final String META_PUBLISH_DATE = "scms-published-date";
	public static final String META_REPUBLISH_DATE = "scms-republish-date";
	public static final String LATEST_ARTICLE_ELEMENT = "section";
	public static final String LATEST_ARTICLE_ID = "latest-articles";

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

	public IndexedPage(Document document, @NonNull PathInfo info) {
		super(info);
		@NonNull Element sourceHtml = getHtmlElement(document);
		Node head = getNodeByTag(sourceHtml, "head", NodeExpectation.UNIQUE);
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

	private Element searchForLatestArticles(Element sourceBody) {
		List<Element> elements = sourceBody.getAllElements().stream()
				.filter(e -> LATEST_ARTICLE_ELEMENT.equalsIgnoreCase(e.tagName()))
				.filter(e -> LATEST_ARTICLE_ID.equalsIgnoreCase(e.attr("id")))
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

	public void replacePageReferences(@NonNull List<IndexedPage> pages) {
		replacePageReferences(article, pages, false); //  includes summary
		noteById.values().forEach(note -> replacePageReferences(article, pages, false));
		replacePageReferences(summaryInListing, pages, true);
	}

	public void replacePageReferences(@NonNull Element element, @NonNull List<IndexedPage> pages, boolean globalize) {
		PageUtils.scanForManagedAnchors(getResolver(), element, (anchor, link) -> findPage(pages, link, globalize).ifPresent(result -> result.elementContentModifier.accept(anchor)));
	}

	public SequencedSet<PageLink> generateTagList(@NonNull SortedSet<PageLink> existingTagLinks) {
		var result = new TreeSet<PageLink>();
		if (isIndex() && !existingTagLinks.contains(getPageLink())) {
			log.warn("Page \"{}\" ({}) is index, but not marked as tag.", getTitle(), getPageLink().getLink());
		}
		getPageLink().createTagHierarchy().stream()
				.filter(existingTagLinks::contains)
				.forEach(result::add);
		if (isIndex()) {
			result.remove(getPageLink());
		}
		return Collections.unmodifiableSortedSet(result);
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
				anchor.attr("href", transformed.isLocal() ? transformed.getLink() : getResolver().toTargetHref(transformed));
				if (anchor.text().isBlank()) {
					anchor.children().remove();
					anchor.text(title);
				}
			}));
		}
		if (captionById.containsKey(pageLink.getLocalId())) {
			return Optional.of(new PageResult(globalized, (anchor) -> {
				anchor.attr("href", transformed.isLocal() ? transformed.getLink() : getResolver().toTargetHref(transformed));
				Element element = captionById.get(pageLink.getLocalId());
				if (anchor.text().isBlank()) {
					anchor.children().remove();
					anchor.text(element.text());
//					element.children().forEach(child -> anchor.children().add(child.clone()));
				}
			}));
		}
		if (noteById.containsKey(pageLink.getLocalId())) {
			return Optional.of(new PageResult(globalized, (anchor) -> {
				anchor.attr("href", transformed.isLocal() ? transformed.getLink() : getResolver().toTargetHref(transformed));
				if (anchor.text().isBlank()) {
					FootNoteScanner.Note note = noteById.get(pageLink.getLocalId());
					anchor.children().remove();
					anchor.addClass("reference-ptr");
					if (globalize) {
						anchor.text("*" + note.number());
					} else {
						anchor.text(note.number().toString());
					}
				}
			}));
		}
		return Optional.empty();
	}

	private record PageResult(PageLink link, Consumer<Element> elementContentModifier) {
	}
}
