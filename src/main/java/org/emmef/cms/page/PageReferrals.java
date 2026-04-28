package org.emmef.cms.page;

import lombok.NonNull;
import org.emmef.cms.util.ByAttributeValue;
import org.emmef.cms.util.NodeHelper;
import org.jsoup.nodes.Element;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public class PageReferrals {
	public static final Predicate<Element> ANCHOR = NodeHelper.elementByNameCaseInsensitive("a");

	private final Predicate<Element> hrefs;
	private final String startsWith;

	public PageReferrals(@NonNull String startsWith) {
		this.startsWith = startsWith;
		this.hrefs = ANCHOR.and(ByAttributeValue.startsWith("href", this.startsWith));
	}

	public String getStartsWith() {
		return startsWith;
	}

	public static String normalize(@NonNull UUID uuid, String localId) {
		return localId != null ? uuid + "#" + localId : uuid.toString();
	}

	public String getReferral(@NonNull UUID uuid, String localId) {
		return startsWith + normalize(uuid, localId);
	}

	public String getReferral(@NonNull PageLink link) {
		return startsWith + link.getNormalized();
	}

	public PageLink of(@NonNull String href) {
		if (!href.startsWith(startsWith)) {
			return null;
		}
		int startIndex = startsWith.length() + 36;
		UUID id;
		try {
			id = UUID.fromString(href.substring(startsWith.length(), startIndex));
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (href.length() <= startIndex + 1 || href.charAt(startIndex) != DocumentUtils.LOCAL_LINK) {
			return PageLink.of(id);
		}
		return PageLink.of(id, href.substring(startIndex + 1));
	}
}
