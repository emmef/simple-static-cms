package org.emmef.cms.main;

import com.google.common.collect.ImmutableList;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.page.IndexedPage;
import org.emmef.cms.page.PageException;
import org.emmef.cms.page.PageRecord;
import org.emmef.cms.page.PageReferrals;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static java.nio.file.Files.isDirectory;

@Slf4j
public class Pages {
	private static final Pattern HTML_PATTERN = Pattern.compile("\\.html?$", Pattern.CASE_INSENSITIVE);
	public static final Set<PosixFilePermission> ATTRIBUTES = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")).value();

	public static Pages readSourceGenerateOutput(@NonNull Path source, @NonNull Path target, String copyRight, @NonNull PageReferrals uuidRelativeLinks) throws IOException {
		Map<UUID, PageRecord> collectedPages = new HashMap<>();
		List<Path> toCopy = new ArrayList<>();
		collectPages(source, source, collectedPages, toCopy, 3, uuidRelativeLinks);
		Optional<PageRecord> rootPage = collectedPages.values().stream().filter(p -> p.isRoot()).findFirst();
		String siteName = rootPage.isPresent() ? rootPage.get().getTitle() : "Home";
		List<PageRecord> orderedPages = createOrderedPages(collectedPages.values());
		replacePageReferences(collectedPages, collectedPages);

		replaceLastArticlesReferences(collectedPages.values(), orderedPages);

		Set<Path> collectedNames = new TreeSet<Path>();
		Map<String, Object> cache = new HashMap<>();

		cache.put(PageRecord.PAGE_COPYRIGHT, copyRight);

		collectedPages.values().forEach((page) -> {
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
		Path dynamicPath = target.resolve(page.getRelativeOutputPath());
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

	private static List<PageRecord> createOrderedPages(Collection<PageRecord> pages) {
		long mostRecentCreated = 0;
		long mostRecentModified = 0;
		List<PageRecord> result = new ArrayList<>();
		for (PageRecord page : pages) {
			mostRecentCreated = Math.max(page.getTimePublished().getMillis(), mostRecentCreated);
			mostRecentModified = Math.max(page.getTimeModified().getMillis(), mostRecentModified);
			result.add(page);
		}
		Comparator<PageRecord> comparator = PageRecord.createDateComparator(mostRecentCreated, mostRecentModified);
		Collections.sort(result, comparator);
		return ImmutableList.copyOf(result);
	}

	private static @NonNull SortedSet<Path> collectPageFiles(@NonNull Path source, @NonNull PageReferrals uuidRelativeLinks, List<Path> toCopy, int levels) {
		List<Directory> subDirectories = new ArrayList<>();
		subDirectories.add(new Directory(source, 1));
		Set<Path> result = new HashSet<>();
		String uuidCopyPath = source.resolve(uuidRelativeLinks.getStartsWith().substring(1)).toString();
		String tagsPath = source.resolve("tags").toString();

		try {
			while (!subDirectories.isEmpty()) {
				Directory directory = subDirectories.get(0);
				log.info("Scanning directory {}", directory);
				Files.list(directory.directory).forEach((file) -> {

					boolean ignore;
					boolean isDirectory = file.toFile().isDirectory();
					if ("_.".contains(file.getFileName().toString().substring(0, 1))) {
						ignore = true;
					} else if (isDirectory) {
						String name = file.toString();
						ignore = name.equals(uuidCopyPath) || name.equals(tagsPath);
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
							result.add(file);
						} else {
							toCopy.add(file);
						}
					}
				});
				subDirectories.remove(0);
			}
		} catch (
				IOException e) {
			throw new

					RuntimeException(e);
		}
		SortedSet<Path> sortedResult = new TreeSet<>();
		result.forEach(p -> sortedResult.add(p.normalize()));
		return sortedResult;
	}

	private static void collectPages(Path rootPath, @NonNull Path source, Map<UUID, PageRecord> collectedPages, List<Path> toCopy, int levels, @NonNull PageReferrals uuidRelativeLinks) throws IOException {
		SortedSet<Path> files = collectPageFiles(source, uuidRelativeLinks, toCopy, levels);
		for (Path file : files) {
			try {
				IndexedPage page = readFile(file, uuidRelativeLinks);
				UUID id = page.getId();
				if (collectedPages.containsKey(id)) {
					PageRecord duplicated = collectedPages.get(id);
					log.warn("Duplicate id {} for \n  1. \"{}\" ({})\n  2.\"{}\"' ({}) duplicates INDEX page \"{}\" ({})",
							id, duplicated.getTitle(), duplicated.getRelativeOutputPath(), page.getTitle(), file);
					page.replaceId(createUniqueNameBasedId(collectedPages, page));
				}
				collectedPages.put(page.getId(), new PageRecord(page, file, rootPath));
			} catch (PageException e) {
				log.error("Not a valid source file: " + file, e);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private static @NonNull UUID createUniqueNameBasedId(Map<UUID, PageRecord> collectedPages, IndexedPage pageRecord) {
		UUID newId;
		AtomicInteger index = new AtomicInteger();
		do {
			int idx = index.getAndIncrement();
			String title = idx == 0 ? pageRecord.getTitle() : pageRecord.getTitle() + idx;
			newId = UUID.fromString(title);
		}
		while (collectedPages.containsKey(newId));
		return newId;
	}

	private static void replacePageReferences(Map<UUID, PageRecord> collectedPages, Map<UUID, PageRecord> index) {
		collectedPages.values().forEach((page) -> page.replacePageReferences(index));
	}

	private static void replaceLastArticlesReferences(Collection<PageRecord> pages, List<PageRecord> sortedPages) {
		pages.forEach((page) -> page.replaceLastArticlesReference(sortedPages));
	}

	private static IndexedPage readFile(Path path, @NonNull PageReferrals uuidRelativeLinks) throws IOException {
		boolean isIndex = "index.html".equals(path.getFileName().toString());


		try (InputStream fileStream = new FileInputStream(path.toFile())) {
			Document document = Jsoup.parse(fileStream, "UTF-8", "");

			return new IndexedPage(document, uuidRelativeLinks, isIndex);
		}
	}

	private record Directory(@NonNull Path directory, int level) {}
}
