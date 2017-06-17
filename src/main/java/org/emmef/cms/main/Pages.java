package org.emmef.cms.main;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.page.PageException;
import org.emmef.cms.page.PageRecord;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Slf4j
public class Pages {
    private static final Pattern HTML_PATTERN = Pattern.compile("\\.html?$", Pattern.CASE_INSENSITIVE);

    public static Pages readFrom(@NonNull Path source, @NonNull Path target) throws IOException {
        Map<UUID, PageRecord> collectedPages = new HashMap<>();
        Map<UUID, PageRecord> duplicatePages = new HashMap<>();
        List<Path> toCopy = new ArrayList<>();

        collectPages(source, collectedPages, duplicatePages, toCopy, true);

        createHierarchy(collectedPages);
        createRootSiblings(collectedPages.values(), duplicatePages.values());
        replacePageReferences(collectedPages, collectedPages);
        replacePageReferences(duplicatePages, collectedPages);

        Set<Path> collectedNames = new TreeSet<Path>();

        collectedPages.values().forEach((page) -> {
            generatePageOutput(target, true, page, collectedNames);
        });
        duplicatePages.values().forEach((page) -> {
            generatePageOutput(target, false, page, collectedNames);
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
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        return null;
    }

    private static void generatePageOutput(@NonNull Path target, boolean createPermanentFile, @NonNull PageRecord page, Set<Path> collectedNames) {
        Path dynamicPath = target.resolve(page.getDynamicFilename());
        Path permanentPath = target.resolve(page.getId().toString() + ".html");
        boolean success = false;
        if (!collectedNames.contains(dynamicPath)) {
            try (FileWriter output = new FileWriter(dynamicPath.toFile())){
                log.info("Wrote " + page);
                page.writePage(output);
                success = true;
                collectedNames.add(dynamicPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (success) {
                if (createPermanentFile) {
                    try {
                        Files.copy(dynamicPath, permanentPath, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                String fileName = page.getPath().getFileName().toString();
                boolean index = page.isIndex();
                if (index && !"index.html".equalsIgnoreCase(fileName)) {
                    try {
                        Path resolve = target.resolve("index.html");
                        System.out.println(resolve);
                        Files.copy(dynamicPath, resolve, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        else {
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
                    log.debug("Relation PARENT \"{}\" [{}] (\"{}\") CHILD \"{}\" [{}] ({})",
                            parent.getTitle(), parent.getId(), parent.getPath(),
                            page.getTitle(), page.getId(), page.getPath());
                }
                else {
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
                    }
                    else {
                        log.warn("NOT added duplicate root " + page);
                    }
                }
            });
        }
        rootPages.forEach((root) -> root.setSiblings(rootPages));
    }

    private static void collectPages(@NonNull Path source, Map<UUID, PageRecord> collectedPages, Map<UUID, PageRecord> duplicatePages, List<Path> toCopy, boolean processPages) throws IOException {
        List<Path> subDirectories = new ArrayList<>();
        AtomicReference<Boolean> hadIndex = new AtomicReference<>(Boolean.FALSE);
        Files.list(source).forEach((file) -> {
            if (Files.isDirectory(file)) {
                subDirectories.add(file);
            }
            else {
                String name = file.getFileName().toString();

                if (processPages && HTML_PATTERN.matcher(name).find()) {
                    try {
                        PageRecord pageRecord = readFile(file);
                        UUID id = pageRecord.getId();
                        if (collectedPages.containsKey(id)) {
                            PageRecord duplicated = collectedPages.get(id);
                            if (pageRecord.isIndex() && !duplicated.isIndex()) {
                                log.warn("Duplicate id and title '{}': page \"{}\" ({}) duplicates INDEX page \"{}\" ({})",
                                        id, duplicated.getTitle(), duplicated.getPath(), pageRecord.getTitle(), file);
                                duplicated.markDuplicate();
                                duplicatePages.put(id, duplicated);
                            }
                            else if (pageRecord.getTitle().equalsIgnoreCase(duplicated.getTitle())) {
                                pageRecord.resetIndex();
                                log.warn("Duplicate id and title '{}': page \"{}\" ({}) duplicates page \"{}\" ({})",
                                        id, pageRecord.getTitle(), file, duplicated.getTitle(), duplicated.getPath());
                                return;
                            }
                            else {
                                pageRecord.resetIndex();
                                duplicatePages.put(id, pageRecord);
                                pageRecord.markDuplicate();
                                log.error("Duplicate id '{}': page \"{}\" ({}) duplicates page \"{}\" ({})",
                                        id, pageRecord.getTitle(), file, duplicated.getTitle(), duplicated.getPath());
                                return;
                            }
                        }
                        collectedPages.put(pageRecord.getId(), pageRecord);
                        if (pageRecord.isIndex()) {
                            if (!hadIndex.compareAndSet(Boolean.FALSE, Boolean.TRUE)) {
                                pageRecord.resetIndex();
                            }
                        }
                    }
                    catch (PageException e) {
                        log.error("Not a valid source file: " + file, e);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                else {
                    toCopy.add(file);
                }
            }
        });

        for (Path subDir : subDirectories) {
            collectPages(subDir, collectedPages, duplicatePages, toCopy, false);
        }
    }

    private static void replacePageReferences(Map<UUID, PageRecord> collectedPages, Map<UUID, PageRecord> index) {
        collectedPages.values().forEach((page) -> page.replacePageReferences(index));
    }

    private static PageRecord readFile(Path path) throws IOException {
        try (InputStream fileStream = new FileInputStream(path.toFile())) {
            return getPageRecordFromStream(fileStream, path);
        }

    }

    private static PageRecord getPageRecordFromStream(InputStream fileStream, Path path) throws IOException {
        Document document = Jsoup.parse(fileStream, "UTF-8", "");

        return new PageRecord(document, path);
    }
}
