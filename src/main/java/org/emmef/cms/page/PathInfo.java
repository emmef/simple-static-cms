package org.emmef.cms.page;

import lombok.Getter;
import lombok.NonNull;

import java.nio.file.Path;

import static org.emmef.cms.page.PathResolver.PageLink;

@Getter
public class PathInfo {

	private final PageLink pageLink;
	private final PathResolver resolver;
	private final Path sourcePath;
	private final boolean index;
	private final boolean root;

	public PathInfo(@NonNull PathResolver resolver, @NonNull Path file) {
		this.pageLink = resolver.fromSourceFile(file);
		this.resolver = resolver;
		this.sourcePath = file;
		this.index = pageLink.isIndex();
		this.root = pageLink.isRoot();
	}

	public PathInfo(@NonNull PathInfo info) {
		this.pageLink = info.pageLink;
		this.resolver = info.resolver;
		this.sourcePath = info.sourcePath;
		this.index = info.index;
		this.root = info.root;
	}
}
