package org.emmef.cms.main;

import com.google.common.collect.ImmutableList;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static java.nio.file.Files.isDirectory;

@Slf4j
public class Pages {
	private static final Pattern HTML_PATTERN = Pattern.compile("\\.html?$", Pattern.CASE_INSENSITIVE);
	public static final Set<PosixFilePermission> ATTRIBUTES = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")).value();

	public static Pages readSourceGenerateOutput(@NonNull Path source, @NonNull Path target, String copyRight, @NonNull PageReferrals uuidRelativeLinks) throws IOException {
		Map<UUID, PageRecord> collectedPages = new HashMap<>();
		Map<UUID, PageRecord> duplicatePages = new HashMap<>();
		List<Path> toCopy = new ArrayList<>();
		collectPages(source, source, collectedPages, duplicatePages, toCopy, 3, uuidRelativeLinks);

		createHierarchy(collectedPages);
		createRootSiblings(collectedPages.values(), duplicatePages.values());
		List<PageRecord> orderedPages = createOrderedPages(collectedPages.values());
		replacePageReferences(collectedPages, collectedPages);
		replacePageReferences(duplicatePages, collectedPages);

		replaceLastArticlesReferences(collectedPages.values(), orderedPages);

		Set<Path> collectedNames = new TreeSet<Path>();
		Map<String, Object> cache = new HashMap<>();

		cache.put(PageRecord.PAGE_COPYRIGHT, copyRight);

		collectedPages.values().forEach((page) -> {
			generatePageOutput(target, true, page, collectedNames, cache);
		});
		duplicatePages.values().forEach((page) -> {
			generatePageOutput(target, false, page, collectedNames, cache);
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

	private static void generatePageOutput(@NonNull Path target, boolean createPermanentFile, @NonNull PageRecord page, Set<Path> collectedNames, Map<String, Object> cache) {
		Path dynamicPath = target.resolve(page.getDynamicFilename());
		Path permanentPath = target.resolve(page.getId().toString() + ".html");
		boolean success = false;
		if (!collectedNames.contains(dynamicPath)) {
			try (FileWriter output = new FileWriter(dynamicPath.toFile())) {
				log.info("Wrote " + page + " to file " + dynamicPath);

				page.writePage(output, cache);
				success = true;
				collectedNames.add(dynamicPath);
			} catch (IOException e) {
				e.printStackTrace();
			}
			if (success) {
				try {
					Files.setPosixFilePermissions(dynamicPath, ATTRIBUTES);
					if (createPermanentFile) {
						try {
							Files.copy(dynamicPath, permanentPath, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					String fileName = dynamicPath.getFileName().toString();
					boolean index = page.isIndex();
					if (index && !"index.html".equalsIgnoreCase(fileName)) {
						try {
							Path resolve = target.resolve("index.html");
							Files.copy(dynamicPath, resolve, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
							Files.setPosixFilePermissions(resolve, ATTRIBUTES);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		} else {
			log.error("NOT writing page \"{}\" [{}] with already existing title", page.getTitle(), page.getId());
		}
	}


	private static void createHierarchy(Map<UUID, PageRecord> index) {
		index.values().forEach((page) -> {
			UUID parentId = page.getParentId();
			if (parentId != null) {
				PageRecord parent = index.get(parentId);
				if (parent != null) {
					parent.addChild(page);
					page.setParent(parent);
					log.info("Relation PARENT \"{}\" [{}] (\"{}\") CHILD \"{}\" [{}] ({})",
							parent.getTitle(), parent.getId(), parent.getPath(),
							page.getTitle(), page.getId(), page.getPath());
				} else {
					log.warn("Page \"{}\" [{}] ([]) has non-existent parent [{}]: attched to root",
							page.getTitle(), page.getId(), page.getPath(), parentId);
					page.setParent(null);
				}
			}
		});
	}

	private static void createRootSiblings(@NonNull Collection<PageRecord>... index) {
		TreeSet<PageRecord> rootPages = PageRecord.createPageSet();
		for (Collection<PageRecord> coll : index) {
			coll.forEach((page) -> {
				if (page.getParentId() == null) {
					if (rootPages.add(page)) {
						log.info("Added root " + page);
					} else {
						log.warn("NOT added duplicate root " + page);
					}
				}
			});
		}
		rootPages.forEach((root) -> root.setSiblings(rootPages));
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

	private static @NonNull Set<Path> collectPageFiles(@NonNull Path source, @NonNull PageReferrals uuidRelativeLinks, List<Path> toCopy, int levels) {
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
		return result;
	}

	private static void collectPages(Path rootPath, @NonNull Path source, Map<UUID, PageRecord> collectedPages, Map<UUID, PageRecord> duplicatePages, List<Path> toCopy, int levels, @NonNull PageReferrals uuidRelativeLinks) throws IOException {
		Set<Path> files = collectPageFiles(source, uuidRelativeLinks, toCopy, levels);
		AtomicBoolean hadIndex = new AtomicBoolean(false);
		for (Path file : files) {
			try {
				PageRecord pageRecord = readFile(rootPath, file, uuidRelativeLinks);
				UUID id = pageRecord.getId();
				if (!collectedPages.containsKey(id)) {
					collectedPages.put(pageRecord.getId(), pageRecord);
					continue;
				}
				PageRecord duplicated = collectedPages.get(id);
				log.warn("Duplicate id {} for \n  1. \"{}\" ({})\n  2.\"{}\"' ({}) duplicates INDEX page \"{}\" ({})",
						id, duplicated.getTitle(), duplicated.getPath(), pageRecord.getTitle(), file);
				duplicatePages.put(id, pageRecord);
			} catch (PageException e) {
				log.error("Not a valid source file: " + file, e);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private static void replacePageReferences(Map<UUID, PageRecord> collectedPages, Map<UUID, PageRecord> index) {
		collectedPages.values().forEach((page) -> page.replacePageReferences(index));
	}

	private static void replaceLastArticlesReferences(Collection<PageRecord> pages, List<PageRecord> sortedPages) {
		pages.forEach((page) -> page.replaceLastArticlesReference(sortedPages));
	}

	private static PageRecord readFile(Path rootPath, Path path, @NonNull PageReferrals uuidRelativeLinks) throws IOException {
		try (InputStream fileStream = new FileInputStream(path.toFile())) {
			return getPageRecordFromStream(rootPath, fileStream, path, uuidRelativeLinks);
		}
	}

	private static PageRecord getPageRecordFromStream(Path rootPath, InputStream fileStream, Path path, @NonNull PageReferrals uuidRelativeLinks) throws IOException {
		Document document = Jsoup.parse(fileStream, "UTF-8", "");

		return new PageRecord(document, path, rootPath, uuidRelativeLinks);
	}

	private record Directory(@NonNull Path directory, int level) {}
}
