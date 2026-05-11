package org.emmef.cms.page.resolving;

import lombok.Getter;
import lombok.NonNull;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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
		Path normalized = toNormalizedReal(sourceFile, false, "source file");
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
		return fromHref(href, sourceRefStartsWith); // fromTargetHref
	}

	public PageLink fromTargetHref(@NonNull String href) {
		return fromHref(href, targetRefStartsWith); // fromTargetHref
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
		this.sourceDocumentRootPath = toNormalizedReal(sourceDocumentRootPath, false, "source document root");
		this.targetDocumentRootPath = toNormalizedReal(targetDocumentRootPath, false, "target document root");
		this.sourceSiteRootPath = resolveSiteRoot(this.sourceDocumentRootPath, sourceSiteRootPath, false, "source");
		this.targetSiteRootPath = resolveSiteRoot(this.targetDocumentRootPath, targetSiteRootPath, true, "target");
		this.sourceRefStartsWith = resolveSourceStartsWith(this.sourceDocumentRootPath, this.sourceSiteRootPath);
		this.targetRefStartsWith = resolveSourceStartsWith(this.targetDocumentRootPath, this.targetSiteRootPath);
	}

	private PageLink fromHref(@NonNull String href, @NonNull String startsWith) {
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
		String relative = trimmedHref.substring(startsWith.length() - 1).trim();
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

	private static @NonNull Path resolveSiteRoot(@NonNull Path documentRootPath, Path siteRootPath, boolean create, @NonNull String sourceOrTarget) {
		if (siteRootPath == null) {
			return documentRootPath;
		}
		String what = sourceOrTarget + " site root";
		if (siteRootPath.isAbsolute()) {
			Path real = toNormalizedReal(siteRootPath, create, "absolute " + what);
			if (real.toString().startsWith(documentRootPath.toString())) {
				return real;
			}
			throw new IllegalArgumentException("The absolute " + what + " path is no child of the " + sourceOrTarget + " document root  \"" + documentRootPath + "\".");
		} else {
			return toNormalizedReal(documentRootPath.resolve(siteRootPath), create,"relative " + what);
		}
	}

	private static @NonNull Path toNormalizedReal(@NonNull Path path, boolean create, @NonNull String whatPath) {
		try {
			return path.toRealPath().toAbsolutePath().normalize();
		}
		catch (NoSuchFileException nsf) {
			if (create && path.toFile().mkdirs()) {
				return toNormalizedReal(path, false, whatPath);
			}
			throw new RuntimeException("Could not normalize " + whatPath + " path:", nsf);
		}
		catch (IOException e) {
			throw new RuntimeException("Could not normalize " + whatPath + " path:", e);
		}
	}

}
