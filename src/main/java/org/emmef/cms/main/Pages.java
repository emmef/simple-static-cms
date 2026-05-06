package org.emmef.cms.main;

import com.google.common.collect.ImmutableList;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.file.Files.isDirectory;

@Slf4j
public class Pages {
	private static final Pattern HTML_PATTERN = Pattern.compile("\\.html?$", Pattern.CASE_INSENSITIVE);
	public static final Set<PosixFilePermission> ATTRIBUTES = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")).value();
	public static final String TAG_SEPARATOR = "/";

	public static Pages readSourceGenerateOutput(@NonNull Path source, @NonNull Path target, String copyRight, @NonNull PageReferrals uuidRelativeLinks) throws IOException {
		List<IndexedPage> collectedPages = new ArrayList<>();
		List<Path> toCopy = new ArrayList<>();
		collectPages(source, collectedPages, toCopy, 3, uuidRelativeLinks);
		Optional<IndexedPage> rootPage = collectedPages.stream().filter(p -> p.isRoot()).findFirst();
		String siteName = rootPage.isPresent() ? rootPage.get().getTitle() : "Home";

		List<PageRecord> pageRecords = collectedPages.stream().map(ip -> {
			return new PageRecord(ip);
		}).collect(Collectors.toUnmodifiableList());

		replaceLastArticlesReferences(pageRecords, pageRecords);
		appendReferences(pageRecords);
		replacePageReferences(collectedPages);

		Set<Path> collectedNames = new TreeSet<Path>();
		Map<String, Object> cache = new HashMap<>();

		cache.put(PageRecord.PAGE_COPYRIGHT, copyRight);

		pageRecords.forEach((page) -> {
			generatePageOutput(target, page, collectedNames, cache, siteName);
		});

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
				e.printStackTrace();
			}
		});

		return null;
	}

	private static void generatePageOutput(@NonNull Path target, @NonNull PageRecord page, Set<Path> collectedNames, Map<String, Object> cache, String siteName) {
		Path dynamicPath = target.resolve(page.getIndexedPage().getRelativePath());
		if (!collectedNames.contains(dynamicPath)) {
			ensureDirectory(dynamicPath);
			try (FileWriter output = new FileWriter(dynamicPath.toFile())) {
				log.info("Wrote " + page + " to file " + dynamicPath);

				page.writePage(output, cache, siteName);
				collectedNames.add(dynamicPath);
			} catch (IOException e) {
				e.printStackTrace();
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

	private static List<IndexedPage> createOrderedPages(Collection<IndexedPage> pages) {
		long mostRecentCreated = 0;
		long mostRecentModified = 0;
		List<IndexedPage> result = new ArrayList<>();
		for (IndexedPage page : pages) {
			mostRecentCreated = Math.max(page.getTimePublished().getMillis(), mostRecentCreated);
			mostRecentModified = Math.max(page.getTimeModified().getMillis(), mostRecentModified);
			result.add(page);
		}
		Comparator<IndexedPage> comparator = IndexedPage.createDateComparator(mostRecentCreated, mostRecentModified);
		Collections.sort(result, comparator);
		return ImmutableList.copyOf(result);
	}

	private static @NonNull Set<PathInfo> collectPathInfos(@NonNull Path source, @NonNull PageReferrals uuidRelativeLinks, List<Path> toCopy, int levels) {
		List<Directory> subDirectories = new ArrayList<>();
		Path realSource = PathUtil.realAndNormalized(source);
		subDirectories.add(new Directory(realSource, 1));
		Set<PathInfo> result = new HashSet<>();
		String tagsPath = source.resolve("tags").toString();
		Set<Path> collected = new HashSet<>();

		try {
			while (!subDirectories.isEmpty()) {
				Directory directory = subDirectories.get(0);
				log.info("Scanning directory {}.", directory);
				Files.list(directory.directory).forEach((listedFile) -> {
					PathUtil.withRealAndNormalized(listedFile, file -> {
						boolean ignore;
						boolean isDirectory = file.toFile().isDirectory();
						if ("_".equals(file.getFileName().toString().substring(0, 1))) {
							ignore = true;
						} else if (isDirectory) {
							String name = file.toString();
							ignore = name.equals(tagsPath);
						}
						else {
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
									result.add(new DefaultPathInfo(realSource, file.normalize()));
									collected.add(file);
								}
								else {
									log.warn("Ignoring file \"{}\" as it is a duplicate.", file.toString());
								}
							} else {
								toCopy.add(file);
							}
						}

					}, (t, p) -> {
						log.error("Was not able to resolve \"{}\": {}", t, p);
					});
				});
				subDirectories.remove(0);
			}
		} catch (
				IOException e) {
			throw new

					RuntimeException(e);
		}
		return Collections.unmodifiableSet(result);
	}

	private static void collectPages(@NonNull Path rootPath, List<IndexedPage> collectedPages, List<Path> toCopy, int levels, @NonNull PageReferrals uuidRelativeLinks) throws IOException {
		Set<PathInfo> infos = collectPathInfos(rootPath, uuidRelativeLinks, toCopy, levels);
		for (PathInfo info : infos) {
			try {
				collectedPages.add(readFile(info, uuidRelativeLinks));
			} catch (PageException e) {
				log.error("Not a valid source file: " + info.getPath(), e);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private static void replacePageReferences(List<IndexedPage> collectedPages) {
		collectedPages.forEach((page) -> page.replacePageReferences(collectedPages));
	}

	private static void appendReferences(List<PageRecord> collectedPages) {
		collectedPages.forEach((page) -> page.appendReferences());
	}

	private static void replaceLastArticlesReferences(Collection<PageRecord> pages, List<PageRecord> sortedPages) {
		pages.forEach((page) -> page.replaceLastArticlesReference(sortedPages));
	}

	private static IndexedPage readFile(PathInfo pageInfo, @NonNull PageReferrals uuidRelativeLinks) throws IOException {
		try (InputStream fileStream = new FileInputStream(pageInfo.getPath().toFile())) {
			Document document = Jsoup.parse(fileStream, "UTF-8", "");

			return new IndexedPage(document, uuidRelativeLinks, pageInfo);
		}
	}

	private record Directory(@NonNull Path directory, int level) {}
}
