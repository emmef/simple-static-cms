package org.emmef.cms.page;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.NonNull;

import java.nio.file.Path;
import java.util.List;

@Getter
public class DefaultPathInfo extends PathInfo.DefaultPathInfo {
	public static final String INDEX_FILE = "index.html";
	public static final String ROOT_INDEX_FILE = PathResolver.URL_PATH_SEPARATOR + INDEX_FILE;

	private final boolean index;
	private final boolean root;
	private final List<String> mainTag;

	public DefaultPathInfo(@NonNull PathResolver resolver, @NonNull Path fileInRoot) {
		super(resolver, fileInRoot);
		this.index = INDEX_FILE.equals(fileInRoot.getFileName().toString());
		String pageId = getId().getPage();
		this.root = ROOT_INDEX_FILE.equals(pageId);

		ImmutableList.Builder<String> tagBuilder = new ImmutableList.Builder<>();
		Path parent = Path.of(pageId).getParent();
		if (parent != null) {
			parent.forEach(path -> {
				tagBuilder.add(path.getFileName().toString());
			});
		}
		this.mainTag = tagBuilder.build();
	}
}
