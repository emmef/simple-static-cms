package org.emmef.cms.page;

import static org.emmef.cms.page.PathResolver.PageLink;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.NonNull;

import java.nio.file.Path;
import java.util.List;

@Getter
public class PathInfo {
	public static final String INDEX_FILE = "index.html";
	public static final String ROOT_INDEX_FILE = PathResolver.URL_PATH_SEPARATOR + INDEX_FILE;

	private final PageLink pageLink;
	private final PathResolver resolver;
	private final Path sourcePath;
	private final boolean index;
	private final boolean root;
	private final List<String> mainTag;

	public PathInfo(@NonNull PathResolver resolver, @NonNull Path file) {
		this.pageLink = resolver.fromSourceFile(file);
		this.resolver = resolver;
		this.sourcePath = file;
		this.index = INDEX_FILE.equals(sourcePath.getFileName().toString());
		String pageId = getPageLink().getPage();
		this.root = ROOT_INDEX_FILE.equals(pageId);

		ImmutableList.Builder<String> tagBuilder = new ImmutableList.Builder<>();
		Path parent = Path.of(pageId).getParent();
		if (parent != null) {
			parent.forEach(path -> tagBuilder.add(path.getFileName().toString()));
		}
		this.mainTag = tagBuilder.build();
	}

	public PathInfo(@NonNull PathInfo info) {
		this.pageLink = info.pageLink;
		this.resolver = info.resolver;
		this.sourcePath = info.sourcePath;
		this.index = info.index;
		this.root = info.root;
		this.mainTag = info.mainTag;
	}
}
