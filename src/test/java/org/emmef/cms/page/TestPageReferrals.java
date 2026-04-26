package org.emmef.cms.page;

import lombok.NonNull;
import org.junit.Test;
import org.junit.Assert;

import java.util.Optional;
import java.util.UUID;

public class TestPageReferrals {
	public static final String PREFIX = "/uuid/";
	private static final PageReferrals PAGE_REF = new PageReferrals(PREFIX);

	public static final UUID UUID_1 = UUID.randomUUID();
	public static final String LOCAL_ID = "significant";
	public static final String QUERY_1 = "value=3";

	public static String ofUuid(@NonNull UUID uuid) {
		return PREFIX + uuid.toString();
	}

	public static String ofUuidAndLocal(@NonNull UUID uuid, @NonNull String localId) {
		return PREFIX + uuid.toString() + '#' + localId;
	}

	public static final String HREF_UUID_ONLY = ofUuid(UUID_1);
	public static final String HREF_UUID_AND_LOCAL = ofUuidAndLocal(UUID_1, LOCAL_ID);

	@Test
	public void testIdOnly() {
		PageLink result = PAGE_REF.of(HREF_UUID_ONLY);
		Assert.assertNotNull(result);
		Assert.assertEquals(UUID_1, result.getUuid());
		Assert.assertNull(result.getLocalId());
		Assert.assertEquals(PAGE_REF.getReferral(result), HREF_UUID_ONLY);
	}

	@Test
	public void testIdOnlyEmptyLocal() {
		PageLink result = PAGE_REF.of(HREF_UUID_ONLY + '#');
		Assert.assertNotNull(result);
		Assert.assertEquals(UUID_1, result.getUuid());
		Assert.assertNull(result.getLocalId());
		Assert.assertEquals(PAGE_REF.getReferral(result), HREF_UUID_ONLY);
	}

	@Test
	public void testIdAndLocalId() {
		PageLink result = PAGE_REF.of(HREF_UUID_AND_LOCAL);
		Assert.assertNotNull(result);
		Assert.assertEquals(UUID_1, result.getUuid());
		Assert.assertEquals(LOCAL_ID, result.getLocalId());
		Assert.assertNotNull(result.getLocalId());
		Assert.assertEquals(PAGE_REF.getReferral(result), HREF_UUID_AND_LOCAL);
	}

}
