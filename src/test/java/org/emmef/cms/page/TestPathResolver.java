package org.emmef.cms.page;

import lombok.NonNull;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Assert;

import static org.emmef.cms.page.PathResolver.PageLink;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

// TODO Add test coverage with SITES that are not the document root
// TODO Add test coverage with weird values in path
public class TestPathResolver {
	public static final String SOURCE_DIR_NAME = "source";
	public static final String SOURCE_FILE_NAME_1 = "index.html";
	public static final String SOURCE_FILE_NAME_2 = "other.html";
	public static final String TARGET_DIR_NAME = "target";

	public static final String LOCAL_ID_1 = "significant";
	public static final String LOCAL_ID_2 = "bold";

	private static final Path TMP = createTemporaryDirectory();
	public static final Path SOURCE_PATH = createSubDirectory(TMP, SOURCE_DIR_NAME);
	public static final Path TARGET_PATH = createSubDirectory(TMP, TARGET_DIR_NAME);
	public static final Path SOURCE_FILE_1 = createFile(SOURCE_PATH, SOURCE_FILE_NAME_1);
	public static final Path SOURCE_FILE_2 = createFile(SOURCE_PATH, SOURCE_FILE_NAME_2);

	@BeforeClass
	public static void setup() throws IOException {
		Path tempDirectory = Files.createTempDirectory(TestPathResolver.class.getSimpleName() + UUID.randomUUID());
		tempDirectory.toFile().deleteOnExit();

	}

	@Test
	public void testDefaultSiteEqualToDocumentRoot() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		Assert.assertEquals(resolver.getSourceSiteRootPath(), resolver.getSourceDocumentRootPath());
		Assert.assertEquals(resolver.getTargetSiteRootPath(), resolver.getTargetDocumentRootPath());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testSourceSiteNoSubOfDocumentRoot() {
		new PathResolver(SOURCE_PATH, TARGET_PATH).withSourceSiteRoot(TARGET_PATH);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testTargetSiteNoSubOfDocumentRoot() {
		new PathResolver(SOURCE_PATH, TARGET_PATH).withTargetSiteRoot(SOURCE_PATH);
	}

	@Test
	public void testNoSiteSourceRootHref() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		PageLink link = resolver.fromSourceFile(SOURCE_FILE_1);

		String pageUrl = PathResolver.URL_PATH_SEPARATOR + SOURCE_FILE_NAME_1;
		Assert.assertEquals(pageUrl, link.getPage());
	}

	@Test
	public void testNoSiteTargetHref() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		PageLink link = resolver.fromSourceFile(SOURCE_FILE_1);

		String pageHref = link.getPage();
		String resolvedRef = resolver.toTargetHref(link);
		Assert.assertEquals(pageHref, resolvedRef);
	}

	@Test
	public void testNoSiteGlobalLinkFromHref() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		PageLink link = resolver.fromSourceFile(SOURCE_FILE_1);

		String localLinkUrl = link.getPage() + PathResolver.LOCAL_LINK + LOCAL_ID_1;
		PageLink localLink = resolver.fromSourceHref(localLinkUrl);
		Assert.assertEquals(link.getPage(), localLink.getPage());
		Assert.assertEquals(LOCAL_ID_1, localLink.getLocalId());
	}

	@Test
	public void testNoSiteTargetPath() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		PageLink link = resolver.fromSourceFile(SOURCE_FILE_1);

		Path targetPath = resolver.toTargetPath(link);
		Path expected = TARGET_PATH.resolve(SOURCE_FILE_NAME_1);
		Assert.assertEquals(expected, targetPath);
	}

	@Test
	public void testNoSiteNoPageLocalLink() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);

		String sourceHref = PathResolver.LOCAL_LINK + LOCAL_ID_1;
		PageLink link = resolver.fromSourceHref(sourceHref);
		Assert.assertNull(link.getPage());
		Assert.assertEquals(LOCAL_ID_1, link.getLocalId());
	}

	@Test
	public void testNoSiteGlobalizeLocalLink() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		PageLink pageLink = resolver.fromSourceFile(SOURCE_FILE_1);

		PageLink localLink = resolver.fromSourceHref(PathResolver.LOCAL_LINK + LOCAL_ID_1);
		PageLink boundLink = pageLink.globalize(localLink);

		Assert.assertEquals(pageLink.getPage(), boundLink.getPage());
		Assert.assertEquals(LOCAL_ID_1, boundLink.getLocalId());
	}

	@Test
	public void testNoSiteGlobalizeGlobalLinkDifferentPage() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		PageLink pageLink = resolver.fromSourceFile(SOURCE_FILE_1);
		PageLink otherPageLink = resolver.fromSourceFile(SOURCE_FILE_2);

		String sourceHref = PathResolver.LOCAL_LINK + LOCAL_ID_2;
		PageLink localLink = resolver.fromSourceHref(sourceHref);
		PageLink globalLink = otherPageLink.globalize(localLink);

		PageLink boundLink = pageLink.globalize(globalLink);

		Assert.assertEquals(otherPageLink.getPage(), boundLink.getPage());
		Assert.assertEquals(LOCAL_ID_2, boundLink.getLocalId());
	}

	@Test
	public void testNoSiteGlobalizeGlobalLinkDifferentPageNoLocal() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		PageLink pageLink = resolver.fromSourceFile(SOURCE_FILE_1);
		PageLink otherPageLink = resolver.fromSourceFile(SOURCE_FILE_2);

		PageLink boundLink = pageLink.globalize(otherPageLink);

		Assert.assertEquals(otherPageLink.getPage(), boundLink.getPage());
		Assert.assertNull(otherPageLink.getLocalId());
		Assert.assertNull(boundLink.getLocalId());
	}

	@Test
	public void testNoSiteLocalizeLocalLink() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		PageLink pageLink = resolver.fromSourceFile(SOURCE_FILE_1);
		PageLink localLink = resolver.fromSourceHref(PathResolver.LOCAL_LINK + LOCAL_ID_1);
		PageLink localizedLink = pageLink.localize(localLink);
		Assert.assertEquals(LOCAL_ID_1, localizedLink.getLocalId());
		Assert.assertNull(localizedLink.getPage());
	}

	@Test
	public void testNoSiteLocalizeGlobalizedLinkSamePage() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		PageLink pageLink = resolver.fromSourceFile(SOURCE_FILE_1);
		PageLink localLink = resolver.fromSourceHref(PathResolver.LOCAL_LINK + LOCAL_ID_1);
		PageLink globalizedLink = pageLink.globalize(localLink);
		PageLink localizedLink = pageLink.localize(globalizedLink);
		Assert.assertEquals(LOCAL_ID_1, localizedLink.getLocalId());
		Assert.assertNull(localizedLink.getPage());
	}

	@Test
	public void testNoSiteLocalizeGlobalizedLinkDifferentPage() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);
		PageLink pageLink = resolver.fromSourceFile(SOURCE_FILE_1);
		PageLink otherPageLink = resolver.fromSourceFile(SOURCE_FILE_2);
		PageLink localLink = resolver.fromSourceHref(PathResolver.LOCAL_LINK + LOCAL_ID_1);
		PageLink globalizedLink = otherPageLink.globalize(localLink);
		PageLink localizedLink = pageLink.localize(globalizedLink);
		Assert.assertEquals(LOCAL_ID_1, localizedLink.getLocalId());
		Assert.assertNull(otherPageLink.getPage(), localLink.getPage());
	}

	@Test
	public void testNoSiteNoPageWithinSource() {
		PathResolver resolver = new PathResolver(SOURCE_PATH, TARGET_PATH);

		String sourceHref = TARGET_DIR_NAME + PathResolver.URL_PATH_SEPARATOR + SOURCE_FILE_NAME_1;
		PageLink link = resolver.fromSourceHref(sourceHref);

		Assert.assertNull(link);
	}

	private static Path createTemporaryDirectory() {
		Path tempDirectory;
		try {
			tempDirectory = Files.createTempDirectory(TestPathResolver.class.getSimpleName() + UUID.randomUUID());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		tempDirectory.toFile().deleteOnExit();
		return tempDirectory;
	}

	private static @NonNull Path createSubDirectory(@NonNull Path parent, @NonNull String childName) {
		Path resolved = parent.resolve(childName);
		File file = resolved.toFile();
		file.mkdirs();
		file.deleteOnExit();
		return resolved;
	}

	private static @NonNull Path createFile(@NonNull Path source, @NonNull String fileName) {
		Path path = source.resolve(fileName);
		try {
			File file = path.toFile();
			file.createNewFile();
			file.deleteOnExit();
			return path;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
