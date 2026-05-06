package org.emmef.cms.page;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

@Getter
@EqualsAndHashCode(callSuper = false, of = {"normalized"})
public class PageLink {
	private final @NonNull String page;
	private final String localId;
	private final String normalized;

	public static String normalize(@NonNull String uuid, String localId) {
		return localId != null ? uuid + "#" + localId : uuid.toString();
	}

	public static PageLink of(@NonNull String uuid) {
		return new PageLink(uuid, null);
	}

	public static PageLink of(@NonNull String uuid, @NonNull String localId) {
		return new PageLink(uuid, localId.isBlank() ? null : localId);
	}

	private PageLink(@NonNull String page, String localId) {
		this.page = page;
		this.localId = localId;
		this.normalized = normalize(this.page, this.localId);
	}

	@Override
	public String toString() {
		return PageLink.class.getSimpleName() + "{" + normalized + "}";
	}
}
