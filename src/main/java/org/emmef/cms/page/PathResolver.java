package org.emmef.cms.page;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.SequencedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Getter
public class PathResolver {
	public static final Pattern REPLACE_UPPER_CASE = Pattern.compile("\\p{Upper}");
	public static final String URL_PATH_SEPARATOR = "/";
	public static final Character LOCAL_LINK = '#';

	/**
	 * Path in the file system from which the
	 */
	private final @NonNull Path sourceDocumentRootPath;
	private final @NonNull Path sourceSiteRootPath;
	private final @NonNull Path targetDocumentRootPath;
	private final @NonNull Path targetSiteRootPath;

	private final @NonNull String sourceRefStartsWith;
	private final @NonNull String targetRefStartsWith;

	public PathResolver(@NonNull Path sourceDocumentRootPath, @NonNull Path targetDocumentRootPath) {
		this(sourceDocumentRootPath, null, targetDocumentRootPath, null);
	}

	public PathResolver withSourceSiteRoot(@NonNull Path sourceSiteRootPath) {
		return new PathResolver(sourceDocumentRootPath, sourceSiteRootPath, targetDocumentRootPath, targetSiteRootPath);
	}

	public PathResolver withTargetSiteRoot(@NonNull Path targetSiteRootPath) {
		return new PathResolver(sourceDocumentRootPath, sourceSiteRootPath, targetDocumentRootPath, targetSiteRootPath);
	}

	public boolean isPartOfSource(String sourceHref) {
		return sourceHref != null && !sourceHref.isBlank() && sourceHref.startsWith(sourceRefStartsWith);
	}

	public PageLink fromSourceFile(@NonNull Path sourceFile) {
		Path normalized = toNormalizedReal(sourceFile, "source file");
		String sourceId = normalized.toString();
		String siteId = sourceSiteRootPath.toString();
		if (!sourceId.startsWith(siteId)) {
			throw new IllegalArgumentException(String.format("Source file \"%s\" is not a child of source site root \"%s\".", sourceId, siteId));
		}
		Path relative = sourceSiteRootPath.relativize(sourceFile).normalize();
		StringBuilder pageBuilder = new StringBuilder();
		for (Path snippet : relative) {
			pageBuilder.append(URL_PATH_SEPARATOR).append(snippet.toString());
		}
		String page = pageBuilder.toString();
		return new PageLink(page, null);
	}

	public PageLink fromSourceHref(@NonNull String href) {
		if (href.isBlank()) {
			return null;
		}
		String trimmedHref = URLDecoder.decode(href.trim(), StandardCharsets.UTF_8);
		if (trimmedHref.startsWith(LOCAL_LINK.toString())) {
			String trimmedLocalId = trimmedHref.substring(1).trim();
			if (trimmedLocalId.isBlank()) {
				return null;
			}
			return PageLink.of(null, trimmedLocalId);
		}

		if (!isPartOfSource(trimmedHref)) {
			return null;
		}
		String relative = trimmedHref.substring(sourceRefStartsWith.length() - 1).trim();
		int localRefIdx = relative.lastIndexOf(LOCAL_LINK);
		if (localRefIdx == -1) {
			if (relative.isBlank()) {
				return null;
			}
			return PageLink.of(relative, null);
		}
		String pageId = relative.substring(0, localRefIdx);
		String localId = relative.substring(localRefIdx + 1);
		if (pageId.isBlank()) {
			if (localId.isBlank()) {
				return null;
			}
			return PageLink.of(null, localId);
		} else if (localId.isBlank()) {
			return PageLink.of(pageId, null);
		} else {
			return PageLink.of(pageId, localId);
		}
	}

	public @NonNull String toTargetHref(@NonNull PageLink link) {
		if (link.isLocal()) {
			return LOCAL_LINK + link.getLocalId();
		}
		String page = link.getPage();
		String lowerCase = targetRefStartsWith + replaceUpperCase(page);
		StringBuilder result = new StringBuilder();
		String[] split = lowerCase.split(URL_PATH_SEPARATOR);

		for (String part : split) {
			if (!part.isBlank()) {
				result.append(URL_PATH_SEPARATOR);
				result.append(URLEncoder.encode(part, StandardCharsets.UTF_8));
			}
		}
		String pagePath = result.toString();

		if (link.isPage()) {
			return pagePath;
		}
		return pagePath + LOCAL_LINK + link.getLocalId();
	}

	public @NonNull Path toTargetPath(@NonNull PageLink link) {
		if (!link.isPage()) {
			throw new IllegalArgumentException("Can only give target path for page link (no local reference)");
		}
		String page = link.getPage();
		String lowerCase = replaceUpperCase(page);
		Path result = targetSiteRootPath;
		for (String part : lowerCase.split(URL_PATH_SEPARATOR)) {
			if (!part.isBlank()) {
				result = result.resolve(part);
			}
		}
		return result;
	}

	private PathResolver(@NonNull Path sourceDocumentRootPath, Path sourceSiteRootPath, @NonNull Path targetDocumentRootPath, Path targetSiteRootPath) {
		this.sourceDocumentRootPath = toNormalizedReal(sourceDocumentRootPath, "source document root");
		this.targetDocumentRootPath = toNormalizedReal(targetDocumentRootPath, "target document root");
		this.sourceSiteRootPath = resolveSiteRoot(this.sourceDocumentRootPath, sourceSiteRootPath, "source");
		this.targetSiteRootPath = resolveSiteRoot(this.targetDocumentRootPath, targetSiteRootPath, "target");
		this.sourceRefStartsWith = resolveSourceStartsWith(sourceDocumentRootPath, sourceSiteRootPath);
		this.targetRefStartsWith = resolveSourceStartsWith(targetDocumentRootPath, targetSiteRootPath);
	}

	private static String replaceUpperCase(String page) {
		return REPLACE_UPPER_CASE.matcher(page).replaceAll(g -> "_" + g.group(0).toLowerCase());
	}

	private @NonNull String resolveSourceStartsWith(@NonNull Path documentRootPath, Path siteRootPath) {
		if (siteRootPath == null) {
			return URL_PATH_SEPARATOR;
		}
		String relative = siteRootPath.toString().substring(documentRootPath.toString().length());
		if (relative.isEmpty()) {
			return URL_PATH_SEPARATOR;
		}
		String separator = documentRootPath.getFileSystem().getSeparator();
		String normalized = URL_PATH_SEPARATOR.equals(separator) ? relative : relative.replaceAll(Pattern.quote(separator), URL_PATH_SEPARATOR);
		String prefixed = normalized.startsWith(URL_PATH_SEPARATOR) ? normalized : URL_PATH_SEPARATOR + normalized;
		return prefixed.endsWith(URL_PATH_SEPARATOR) ? prefixed : prefixed + URL_PATH_SEPARATOR;
	}

	private static @NonNull Path resolveSiteRoot(@NonNull Path targetDocumentRootPath, Path targetSiteRootPath, @NonNull String sourceOrTarget) {
		if (targetSiteRootPath == null) {
			return targetDocumentRootPath;
		}
		String what = sourceOrTarget + " site root";
		if (targetSiteRootPath.isAbsolute()) {
			Path real = toNormalizedReal(targetSiteRootPath, "absolute " + what);
			if (real.toString().startsWith(targetDocumentRootPath.toString())) {
				return real;
			}
			throw new IllegalArgumentException("The absolute " + what + " path is no child of the " + sourceOrTarget + " document root  \"" + targetDocumentRootPath + "\".");
		} else {
			return toNormalizedReal(targetDocumentRootPath.relativize(targetSiteRootPath), "relative " + what);
		}
	}

	private static @NonNull Path toNormalizedReal(@NonNull Path path, @NonNull String whatPath) {
		try {
			return path.toRealPath().toAbsolutePath().normalize();
		} catch (IOException e) {
			throw new RuntimeException("Could not normalize " + whatPath + " path:", e);
		}
	}

	@EqualsAndHashCode(callSuper = false)
	public static class PageLink implements Comparable<PageLink> {
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
			int separator = link.lastIndexOf(URL_PATH_SEPARATOR);
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
			for (String part : link.split(URL_PATH_SEPARATOR)) {
				if (!part.isBlank()) {
					path.append(URL_PATH_SEPARATOR).append(part);
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

		private static PageLink of(String pageId, String localId) {
			if (localId == null || localId.isBlank()) {
				if (pageId == null) {
					return NONE;
				}
				return new PageLink(pageId, null);
			}
			return new PageLink(pageId, localId);
		}

		private PageLink(String page, String localId) {
			this.page = page != null ? page.trim() : null;
			this.localId = localId != null ? localId.trim() : null;
			if (this.page == null) {
				if (this.localId == null) {
					this.link = "NONE";
				} else {
					this.link = LOCAL_LINK + localId;
				}
			} else if (this.localId == null) {
				this.link = page;
			} else {
				this.link = page + LOCAL_LINK + localId;
			}
		}
	}
}
