package org.emmef.cms.page;

import static org.emmef.cms.page.PathResolver.PageLink;

import lombok.Getter;
import lombok.NonNull;

import java.nio.file.Path;
import java.util.List;

public interface PathInfo {
	@NonNull PathResolver getResolver();
	@NonNull PageLink getId();
	@NonNull Path getSourcePath();
	default @NonNull Path getTargetPath() {
		return getResolver().toTargetPath(getId());
	}
	default @NonNull PageLink fromSourceHref(@NonNull String href) {
		return getResolver().fromSourceHref(href);
	}
	default @NonNull PageLink globalize(@NonNull PageLink link) {
		return getId().globalize(link);
	}
	default @NonNull PageLink localize(@NonNull PageLink link) {
		return getId().localize(link);
	}
	default @NonNull String toTargetHref(@NonNull PageLink link) {
		return getResolver().toTargetHref(link);
	}
	default @NonNull String getTargetHref() {
		return getResolver().toTargetHref(getId());
	}

	@Getter
	class DefaultPathInfo implements PathInfo {
		private final PageLink id;
		private final PathResolver resolver;
		private final Path sourcePath;

		public DefaultPathInfo(@NonNull PathResolver resolver, @NonNull Path file) {
			this.id = resolver.fromSourceFile(file);
			this.resolver = resolver;
			this.sourcePath = file;
		}

	}
}
