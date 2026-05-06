package org.emmef.cms.page;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.NonNull;

import java.nio.file.Path;
import java.util.List;

@Getter
public class DefaultPathInfo implements PathInfo {
	public static final String INDEX_FILE = "index.html";

	private final Path path;
	private final Path relativePath;
	private final String absoluteUrl;
	private final boolean index;
	private final boolean root;
	private final List<String> mainTag;
	private final Path rootPath;

	public DefaultPathInfo(@NonNull Path root, @NonNull Path fileInRoot) {
		this.path = fileInRoot.normalize();
		this.rootPath = root.normalize();
		try {
			this.relativePath = rootPath.relativize(this.path);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(this + ": path not relative to root-path " + rootPath);
		}
		String relativeUri = rootPath.resolve(relativePath).toUri().toString();
		String rootUriUri = rootPath.toUri().toString();
		if (!relativeUri.startsWith(rootUriUri)) {
			throw new IllegalStateException(this + ": could not generate proper relative URL, as '" + relativeUri + "' does not start with '" + rootUriUri + "'");
		}
		this.absoluteUrl = "/" + relativeUri.substring(rootUriUri.length());
		this.index = INDEX_FILE.equals(this.relativePath.getFileName().toString());
		this.root = INDEX_FILE.equals(this.relativePath.toString());

		ImmutableList.Builder<String> tagBuilder = new ImmutableList.Builder<>();
		Path parent = relativePath.getParent();
		if (parent != null) {
			parent.forEach(path -> {
				tagBuilder.add(path.getFileName().toString());
			});
		}
		this.mainTag = tagBuilder.build();
	}

	public final int getLevel() {
		return mainTag.size();
	}

	@Override
	public boolean isSame(String sourceHref) {
		if (sourceHref == null) {
			return false;
		}
		return rootPath.resolve(sourceHref).equals(path);
	}

	public String pathIfSame(String sourceHref) {
		if (sourceHref == null) {
			return null;
		}
		if (isSame(sourceHref)) {
			return path.toString();
		}
		return null;
	}
}
