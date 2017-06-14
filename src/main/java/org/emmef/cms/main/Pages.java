package org.emmef.cms.main;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringEscapeUtils;
import org.emmef.cms.page.PageException;
import org.emmef.cms.page.PageRecord;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.TransformerException;
import java.io.*;
import java.net.URLEncoder;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
public class Pages {
    private static DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();

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
                writePage(page, output);
                success = true;
                collectedNames.add(dynamicPath);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (TransformerException e) {
                e.printStackTrace();
            } catch (XMLStreamException e) {
                e.printStackTrace();
            }
            if (success && createPermanentFile) {
                try {
                    Files.copy(dynamicPath, permanentPath, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        else {
            log.error("NOT writing page \"{}\" [{}] with already existing title", page.getTitle(), page.getId());
        }
    }

    private static void writePage(PageRecord p, Writer output) throws IOException, XMLStreamException, TransformerException {
        output.append("<!DOCTYPE html>\n");
        output.append("<html>\n\t<head>\n");
        title(output, p);
        output.append("\t<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n");
        output.append("\t<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=2, minimum-scale=0.6\"/>\n");
        output.append("\t<meta name=\"robots\" content=\"noarchive\">\n");
        output.append("\t<link href='http://fonts.googleapis.com/css?family=Open+Sans:400italic,600italic,400,600' rel='stylesheet' type='text/css'>\n");
        output.append("\t<link rel=\"StyleSheet\" href=\"./style/template-global.css\" type=\"text/css\">\n");
        output.append("\t<link rel=\"StyleSheet\" href=\"./style/articles.css\" type=\"text/css\">\n");
        if (p.isMath()) {
            output.append("\t<script type=\"text/javascript\" src=\"https://cdn.mathjax.org/mathjax/latest/MathJax.js?config=TeX-AMS-MML_HTMLorMML\">MathJax.Hub.Config({displayAlign: \"left\", displayIndent: \"2ex\" });</script>\n");
        }
        output.append("\n\t</head>\n\t<body>\n");
        navigation(output, p);
        output.append("\n\n");
        p.appendArticle(output);
        output.append("\n\t</body>\n</html>\n\n");
    }

    private static void navigation(Writer output, @NonNull PageRecord page) throws IOException {

        List<PageRecord> parents = page.getParents(false);
        if (!parents.isEmpty()) {
            output.append("<nav class=\"parentnav\">");
            writeLinks(null, output, parents, "parents", null, "/", null);
            output.append("</nav>");
        }
        output.append("<nav class=\"siblingnav\">");
        List<PageRecord> siblings = new ArrayList<>(page.getSiblings());
        writeLinks(page, output, siblings, "siblings", null, "|", null);
        output.append("</nav>");
        ArrayList<PageRecord> children = new ArrayList<>(page.getChildren());
        if (!children.isEmpty()) {
            output.append("<nav class=\"childnav\">");
            writeLinks(null, output, children, "children", null, "|", null);
            output.append("</nav>");
        }
    }

    private static void title(Writer output, @NonNull PageRecord page) throws IOException {
        output.append("<title>");
        output.append(StringEscapeUtils.escapeHtml(page.getTitle()).replaceAll("\\s", "&nbsp;"));

        PageRecord parent = page.getParent();
        if (parent != null) {
            output.append(" &ndash; [");
            parentTitle(output, parent);
            output.append("]");
        }
        output.append("</title>");
    }



    private static void parentTitle(Writer output, PageRecord page) throws IOException {
        PageRecord parent = page.getParent();
        if (parent != null) {
            parentTitle(output, parent);
            output.append("/");
        }
        output.append(StringEscapeUtils.escapeHtml(page.getTitle()).replaceAll("\\s", "&nbsp;"));
    }


    private static void writeLinks(PageRecord self, Writer output, @NonNull List<PageRecord> pages, String baseClasses, String first, String inner, String last) throws IOException {
        int size = pages.size();
        if (size == 0) {
            return;
        }
        for (int i = 0; i < size; i++) {
            PageRecord page = pages.get(i);
            boolean isFirst = i == 0;
            boolean isLast = i == size - 1;
            boolean isSelf = self != null && page.getId().equals(self.getId());

            if (isFirst) {
                appendSeparator(output, baseClasses, first, isFirst, false, isSelf);
            }

            output.append("<a href=\"./");
            if (isSelf) {
                output.append(page.getId().toString()).append(".html\"");
            }
            else {
                output.append(URLEncoder.encode(page.getDynamicFilename(), "UTF-8")).append("\"");
            }

            appendClasses(output, baseClasses, isFirst, isLast, isSelf);
            output.append(">");
            StringEscapeUtils.escapeHtml(output, page.getTitle());
            output.append("</a>");

            appendSeparator(output, baseClasses, isLast ? last : inner, false, isLast, isSelf);
        }
    }

    private static void appendSeparator(Writer output, String baseClasses, String separator, boolean isFirst, boolean isLast, boolean isSelf) throws IOException {
        if (separator == null) {
            return;
        }
        output.append("<span");
        appendClasses(output, baseClasses, isFirst, isLast, isSelf);
        output.append(">");
        output.append(separator);
        output.append("</span>");
    }

    private static void appendClasses(Writer output, String baseClasses, boolean isFirst, boolean isLast, boolean isSelf) throws IOException {
        output.append(" class=\"").append(baseClasses);
        if (isFirst) {
            output.append(" first");
        }
        if (isLast) {
            output.append(" last");
        }
        if (!isFirst & !isLast) {
            output.append(" inner");
        }
        if (isSelf) {
            output.append(" self");
        }
        output.append("\"");
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
                            if (pageRecord.getTitle().equalsIgnoreCase(duplicated.getTitle())) {
                                log.warn("Duplicate id and title '{}': page \"{}\" ({}) duplicates page \"{}\" ({})",
                                        id, pageRecord.getTitle(), file, duplicated.getTitle(), duplicated.getPath());
                            }
                            else {
                                duplicatePages.put(id, pageRecord);
                                log.error("Duplicate id '{}': page \"{}\" ({}) duplicates page \"{}\" ({})",
                                        id, pageRecord.getTitle(), file, duplicated.getTitle(), duplicated.getPath());
                            }
                        }
                        else {
                            collectedPages.put(pageRecord.getId(), pageRecord);
                        }
                    }
                    catch (PageException e) {
                        log.error("Not a valid source file: " + file, e);
                    }
                    catch (ParserConfigurationException | SAXException e) {
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

    private static PageRecord readFile(Path path) throws ParserConfigurationException, IOException, SAXException {
        try (InputStream fileStream = new FileInputStream(path.toFile())) {
            return getPageRecordFromStream(fileStream, path);
        }

    }

    private static PageRecord getPageRecordFromStream(InputStream fileStream, Path path) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
        Document document = builder.parse(fileStream);

        return PageRecord.create(document, path);
    }
}
