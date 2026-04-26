package org.emmef.cms.page;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

import java.util.UUID;

@Getter
@EqualsAndHashCode(callSuper = false, of = {"normalized"})
public class PageLink {
	private final @NonNull UUID uuid;
	private final String localId;
	private final String normalized;

	public static PageLink of(@NonNull UUID uuid) {
		return new PageLink(uuid, null);
	}

	public static PageLink of(@NonNull UUID uuid, @NonNull String localId) {
		return new PageLink(uuid, localId.isBlank() ? null : localId);
	}

	private PageLink(@NonNull UUID uuid, String localId) {
		this.uuid = uuid;
		this.localId = localId;
		this.normalized = PageReferrals.normalize(this.uuid, this.localId);
	}
}
