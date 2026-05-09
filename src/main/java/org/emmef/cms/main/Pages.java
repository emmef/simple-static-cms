package org.emmef.cms.main;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.page.*;
import org.emmef.cms.util.PathUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.file.Files.isDirectory;

@Slf4j
public class Pages {
	private static final Pattern HTML_PATTERN = Pattern.compile("\\.html?$", Pattern.CASE_INSENSITIVE);
	public static final Set<PosixFilePermission> ATTRIBUTES = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")).value();

	public static void readSourceGenerateOutput(@NonNull Path source, @NonNull Path target, String copyRight, @NonNull PathResolver uuidRelativeLinks) throws IOException {
		List<IndexedPage> collectedPages = new ArrayList<>();
		List<Path> toCopy = new ArrayList<>();
		collectPages(source, collectedPages, toCopy, 3, uuidRelativeLinks);
		Optional<IndexedPage> rootPage = collectedPages.stream().filter(PathInfo::isRoot).findFirst();
		String siteName = rootPage.map(IndexedPage::getTitle).orElse("Home");

		List<PageRecord> pageRecords = collectedPages.stream().map(PageRecord::new).toList();

		replaceLastArticlesReferences(pageRecords, pageRecords);
		appendReferences(pageRecords);
		replacePageReferences(collectedPages);

		Set<Path> collectedNames = new TreeSet<>();
		Map<String, Object> cache = new HashMap<>();

		cache.put(PageRecord.PAGE_COPYRIGHT, copyRight);

		pageRecords.forEach(page ->
				generatePageOutput(page, collectedNames, cache, siteName));

		toCopy.forEach(file -> {
			Path relativeSource = source.relativize(file);
			Path destination = target.resolve(relativeSource);
			try {
				Path dir = destination.getParent();
				if (!Files.exists(dir)) {
					Files.createDirectories(dir);
				}
				Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
				Files.setPosixFilePermissions(destination, ATTRIBUTES);
			} catch (IOException e) {
				log.error("Error copying page {} to {}", file, destination, e);
			}
		});
	}

	private static void generatePageOutput(@NonNull PageRecord page, Set<Path> collectedNames, Map<String, Object> cache, String siteName) {
		Path dynamicPath = page.getIndexedPage().getResolver().toTargetPath(page.getIndexedPage().getPageLink());
		if (!collectedNames.contains(dynamicPath)) {
			ensureDirectory(dynamicPath);
			try (FileWriter output = new FileWriter(dynamicPath.toFile())) {
				log.info("Wrote " + page + " to file " + dynamicPath);

				page.writePage(output, cache, siteName);
				collectedNames.add(dynamicPath);
			} catch (IOException e) {
				log.error("Error writing page {} to file {}", page, page.getIndexedPage(), e);
			}
		} else {
			log.error("NOT writing page \"{}\" [{}] with already existing title", page.getTitle(), page.getId());
		}
	}

	private static void ensureDirectory(Path dynamicPath) {
		File directory = dynamicPath.getParent().toFile();
		if (directory.exists()) {
			if (!directory.isDirectory()) {
				throw new IllegalStateException("Directory " + dynamicPath + " exists but is not a directory");
			}
			return;
		}
		if (!directory.mkdirs()) {
			throw new IllegalStateException("Unable to create directory " + dynamicPath);
		}
	}

	private static @NonNull Collection<PathInfo> collectPathInfos(@NonNull Path source, @NonNull PathResolver pathResolver, List<Path> toCopy, int levels) {
		List<Directory> subDirectories = new ArrayList<>();
		Path realSource = PathUtil.realAndNormalized(source);
		subDirectories.add(new Directory(realSource, 1));
		Set<PathInfo> result = new HashSet<>();
		String tagsPath = source.resolve("tags").toString();
		Set<Path> collected = new LinkedHashSet<>();

		try {
			while (!subDirectories.isEmpty()) {
				Directory directory = subDirectories.getFirst();
				log.info("Scanning directory {}.", directory);
				try (Stream<Path> list = Files.list(directory.directory)) {
					list.forEach(listedFile -> PathUtil.withRealAndNormalized(listedFile, file -> {
						boolean ignore;
						boolean isDirectory = file.toFile().isDirectory();
						if ("_".equals(file.getFileName().toString().substring(0, 1))) {
							ignore = true;
						} else if (isDirectory) {
							String name = file.toString();
							ignore = name.equals(tagsPath);
						} else {
							ignore = false;
						}
						if (ignore) {
							log.info("Ignoring {}: {}", isDirectory(file) ? "directory" : "file", file.getFileName());
						} else if (isDirectory) {
							if (directory.level < levels) {
								subDirectories.add(new Directory(file, directory.level + 1));
							}
						} else {
							String name = file.getFileName().toString();

							if (HTML_PATTERN.matcher(name).find()) {
								if (!collected.contains(file)) {
									result.add(new PathInfo(pathResolver, file.normalize()));
									collected.add(file);
								} else {
									log.warn("Ignoring file \"{}\" as it is a duplicate.", file);
								}
							} else {
								toCopy.add(file);
							}
						}

					},
							(t, p) -> log.error("Was not able to resolve \"{}\": {}", t, p)));
					subDirectories.removeFirst();
				}
			}
		} catch (
				IOException e) {
			throw new

					RuntimeException(e);
		}
		return result;
	}

	private static void collectPages(@NonNull Path rootPath, List<IndexedPage> collectedPages, List<Path> toCopy, int levels, @NonNull PathResolver pathResolver) throws IOException {
		for (PathInfo info : collectPathInfos(rootPath, pathResolver, toCopy, levels)) {
			try {
				collectedPages.add(readFile(info));
			} catch (PageException e) {
				log.error("Not a valid source file: {}", info.getPageLink(), e);
			}
		}
	}

	private static void replacePageReferences(List<IndexedPage> collectedPages) {
		collectedPages.forEach((page) -> page.replacePageReferences(collectedPages));
	}

	private static void appendReferences(List<PageRecord> collectedPages) {
		collectedPages.forEach(PageRecord::appendReferences);
	}

	private static void replaceLastArticlesReferences(Collection<PageRecord> pages, List<PageRecord> sortedPages) {
		pages.forEach((page) -> page.replaceLastArticlesReference(sortedPages));
	}

	private static IndexedPage readFile(PathInfo pageInfo) throws IOException {
		try (InputStream fileStream = new FileInputStream(pageInfo.getSourcePath().toFile())) {
			Document document = Jsoup.parse(fileStream, "UTF-8", "");

			return new IndexedPage(document, pageInfo);
		}
	}

	private record Directory(@NonNull Path directory, int level) {
	}
}
