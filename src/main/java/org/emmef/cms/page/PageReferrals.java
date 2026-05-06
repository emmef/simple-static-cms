package org.emmef.cms.page;

import lombok.NonNull;
import org.emmef.cms.util.NodeHelper;
import org.jsoup.nodes.Element;

import java.util.function.Predicate;

public class PageReferrals {
	public static final Predicate<Element> ANCHOR = NodeHelper.elementByNameCaseInsensitive("a");

	public PageReferrals() {
	}

	public String getReferral(@NonNull PageLink link) {
		return link.getNormalized();
	}

	public PageLink of(@NonNull String href) {
		if (!href.startsWith("/")) {
			return null;
		}
		int localRefIdx = href.lastIndexOf(DocumentUtils.LOCAL_LINK);
		if (localRefIdx == -1) {
			return PageLink.of(href);
		}
		String pageId = href.substring(0, localRefIdx);
		if (href.length() <= localRefIdx + 1) {
			return PageLink.of(pageId);
		}
		return PageLink.of(pageId, href.substring(localRefIdx + 1));
	}
}
