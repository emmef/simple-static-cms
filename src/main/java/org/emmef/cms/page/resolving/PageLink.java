package org.emmef.cms.page.resolving;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

import java.util.Collections;
import java.util.SequencedSet;
import java.util.TreeSet;

@EqualsAndHashCode(callSuper = false)
public class PageLink implements Comparable<PageLink> {
	public static final PageLink NONE = new PageLink(null, null);
	public static final String INDEX_FILE = "index.html";
	public static final String ROOT_INDEX_FILE = PathResolver.URL_PATH_SEPARATOR + INDEX_FILE;
	@Getter
	private final String page;
	@Getter
	private final String localId;
	@Getter
	private final String link;

	public boolean isRoot() {
		return !isLocal() && ROOT_INDEX_FILE.equals(getPage());
	}

	public boolean isIndex() {
		if (isLocal()) {
			return false;
		}
		int separator = link.lastIndexOf(PathResolver.URL_PATH_SEPARATOR);
		if (separator >= 0 || link.length() > separator + INDEX_FILE.length() + 1) {
			return INDEX_FILE.equals(link.substring(separator + 1));
		}
		return false;
	}

	public SequencedSet<PageLink> createTagHierarchy() {
		var result = new TreeSet<PageLink>();

		if (isIndex()) {
			result.add(this);
		}
		var link = getLink();
		StringBuilder path = new StringBuilder();
		for (String part : link.split(PathResolver.URL_PATH_SEPARATOR)) {
			if (!part.isBlank()) {
				path.append(PathResolver.URL_PATH_SEPARATOR).append(part);
				result.add(PageLink.of(path.toString() + ROOT_INDEX_FILE, null));
			}
		}

		return Collections.unmodifiableSequencedSet(result);
	}

	@Override
	public String toString() {
		return PageLink.class.getSimpleName() + "{" + link + "}";
	}

	public boolean isLocal() {
		return page == null;
	}

	public boolean isPage() {
		return localId == null;
	}

	public @NonNull PageLink globalize(@NonNull PageLink sourceLink) {
		if (isLocal()) {
			throw new IllegalArgumentException("Cannot bind to a local page link: " + this);
		}
		String sourcePage = sourceLink.getPage();
		String localId = sourceLink.getLocalId();

		if (sourcePage == null) {
			return PageLink.of(page, localId);
		}
		return sourceLink;
	}

	public @NonNull PageLink localize(@NonNull PageLink sourceLink) {
		if (isLocal()) {
			throw new IllegalArgumentException("Cannot localize to a page link that is itself local: " + this);
		}
		String sourcePage = sourceLink.getPage();
		String localId = sourceLink.getLocalId();

		if (sourcePage != null && sourcePage.equals(page)) {
			return PageLink.of(null, localId);
		}
		return sourceLink;
	}

	public boolean isSamePage(@NonNull PageLink other) {
		if (other.isLocal()) {
			return true;
		}
		if (isLocal()) {
			return false;
		}
		return page.equals(other.getPage());
	}

	@Override
	public int compareTo(@NonNull PageLink o) {
		return link.compareTo(o.link);
	}

	static PageLink of(String pageId, String localId) {
		if (localId == null || localId.isBlank()) {
			if (pageId == null) {
				return NONE;
			}
			return new PageLink(pageId, null);
		}
		return new PageLink(pageId, localId);
	}

	PageLink(String page, String localId) {
		this.page = page != null ? page.trim() : null;
		this.localId = localId != null ? localId.trim() : null;
		if (this.page == null) {
			if (this.localId == null) {
				this.link = "NONE";
			} else {
				this.link = PathResolver.LOCAL_LINK + localId;
			}
		} else if (this.localId == null) {
			this.link = page;
		} else {
			this.link = page + PathResolver.LOCAL_LINK + localId;
		}
	}
}
