package org.emmef.cms.page;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.parameters.NodeExpectation;
import org.emmef.cms.parameters.ValidationException;
import org.emmef.cms.util.ByAttributeValue;
import org.emmef.cms.util.GetFirst;
import org.emmef.cms.util.NodeHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeVisitor;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Slf4j
public class PageRecord {
    private static final String reservedChars = "|\\?*<:>+[]/";
    private static final int MAX_NAME_LENGTH = 255;
    private static final String HTML_SUFFIX = ".html";
    private static final int MAX_GENERATED_LENGTH = MAX_NAME_LENGTH - HTML_SUFFIX.length();

    public static final String PAGE_SCHEME = "page:";
    public static final String ELEMENT_SCHEME = "elem:";
    public static final String REF_SCHEME = "ref:";
    public static final String NOTE_SCHEME = "note:";
    public static final String NOTE_ELEMENT = "aside";
    public static final String SUMMARY_ELEMENT = "p";
    public static final String SUMMARY_ID = "article-summary";
    public static final String SUMMARY_TITLE_ELEMENT = "h1";
    public static final String SUMMARY_TITLE_ID = "article-summary-title";
    public static final String LATEST_ARTICLE_ELEMENT = "section";
    public static final String LATEST_ARTICLE_ID = "latest-articles";

    public static final Predicate<Element> META = NodeHelper.elementByNameCaseInsensitive("meta");
    public static final Predicate<Element> META_UUID = META.and(ByAttributeValue.literal("name", "scms-uuid", true));
    public static final Predicate<Element> META_PARENT_UUID = META.and(ByAttributeValue.literal("name", "scms-parent-uuid", true));
    public static final Predicate<Element> META_MATH = META.and(ByAttributeValue.literal("name", "scms-uses-math", true));
    public static final Predicate<Element> META_INDEX = META.and(ByAttributeValue.literal("name", "scms-is-index", true));

    public static final Predicate<Element> ANCHOR = NodeHelper.elementByNameCaseInsensitive("a");
    public static final Predicate<Element> ANCHOR_PAGE = ANCHOR.and(ByAttributeValue.startsWith("href", PAGE_SCHEME));
    public static final Predicate<Element> ANCHOR_REF = ANCHOR.and(ByAttributeValue.startsWith("href", REF_SCHEME));
    public static final Predicate<Element> ELEMENT_WITH_ID = NodeHelper.elementByNamesCaseInsensitive("h1", "h2", "h3", "dt").and(ByAttributeValue.isUuid("id"));

    public static final Predicate<Element> TITLE = NodeHelper.elementByNameCaseInsensitive("title");
    public static final Pattern NULL_PATTERN = Pattern.compile("^(null|none|root)$", Pattern.CASE_INSENSITIVE);
    public static final String NBSP = "" + Entities.NBSP;
    public static final String STYLE_CSS = "./style/simple-static-cms.css";
    public static final String CSS_TARGET_TYPE = "simple-static-cms-style-type";
    public static final String PAGE_COPYRIGHT = "copyright";
    public static final String REFERENCE_LIST = "reference-list";
    public static final String NOTE_NUMBER = "note-number";

    @NonNull
    @Getter
    private final UUID id;
    @NonNull
    @Getter
    private final String title;
    private final Element header;
    private final Map<String, Element> notes = new HashMap<>();
    private final Element latestArticlesElement;
    @Getter
    private final String summaryTitle;
    @NonNull
    @Getter
    private final Map<UUID, String> idContentMap;
    @Getter
    private UUID parentId;
    @NonNull
    @Getter
    private final Path path;
    @NonNull
    @Getter
    private final Path rootPath;
    @NonNull
    private final Document document;
    @NonNull
    private final Element article;
    private List<Node> summary;
    @NonNull
    private final Element footer;
    @NonNull
    private final Multimap<UUID, Element> pageRefNodes;
    @Getter
    private final boolean math;
    private final Element referenceList;
//    @Getter
    private boolean index;

    @Getter
    private PageRecord parent = null;
    private final SortedSet<PageRecord> children = createPageSet();
    private SortedSet<PageRecord> siblings = null;
    private String dynamicFilename = null;
    private boolean duplicate = false;
    @Getter
    private final FileTime timeModified;
    @Getter
    private final FileTime timeCreated;

    public static final Comparator<PageRecord> COMPARE_BY_NAME = (p1, p2) -> {
        int i = p1.title.compareToIgnoreCase(p2.title);
        if (i != 0) {
            return i;
        }
        return p1.id.hashCode() - p2.id.hashCode();
    };

    public static final Comparator<PageRecord> createDateComparator(long mostRecentCreated, long mostRecentModified) {
        return new Comparator<PageRecord>() {
            @Override
            public int compare(PageRecord p1, PageRecord p2) {
                double createP1 = Math.log(Math.max(1, mostRecentCreated - p1.timeCreated.toMillis()));
                double createP2 = Math.log(Math.max(1, mostRecentCreated - p2.timeCreated.toMillis()));
                double createValue = createP1 - createP2;
                double modP1 = Math.log(Math.max(1, mostRecentModified - p1.timeModified.toMillis()));
                double modP2 = Math.log(Math.max(1, mostRecentModified - p2.timeModified.toMillis()));
                double modValue = modP1 - modP2;

                double value = createValue * 2 + modValue;
                return value < 0 ? -1 : value > 0 ? 1 : p1.id.hashCode() - p2.id.hashCode();
            }
        };
    }

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm'GMT'");

    public static TreeSet<PageRecord> createPageSet() {
        return new TreeSet<PageRecord>(COMPARE_BY_NAME);
    }

    public PageRecord(Document sourceDocument, Path path, Path rootPath) {
        Node head = getNodeByTag(sourceDocument, "head", NodeExpectation.UNIQUE);
        FileTime modifiedTime;
        this.id = getIdentifier(head, META_UUID, "page identifier", null);
        this.title = getTitle(head);
        this.math = Boolean.parseBoolean(getMetaValue(head, META_MATH));
        this.index = Boolean.parseBoolean(getMetaValue(head, META_INDEX));
        if (index) {
            System.out.println("INDEX " + title);
        }
        this.parentId = getIdentifier(head, META_PARENT_UUID, "parent identifier", NULL_PATTERN);

        if (id.equals(parentId)) {
            throw new PageException("Page cannot be its own parent!");
        }

        Element sourceBody = (Element)getNodeByTag(sourceDocument, "body", NodeExpectation.UNIQUE);
        if (sourceBody == null) {
            throw new PageException("Page has no article!");
        }

        this.document = Jsoup.parse("<!DOCTYPE html><html></html>");
        this.header = this.document.createElement("header");
        this.article = this.document.createElement("article");
        this.footer = this.document.createElement("footer");
        GetFirst<Element> latestArticleRef = new GetFirst<>();
        GetFirst<String> summaryTitleRef = new GetFirst<>();
        NodeHelper.children(sourceBody).forEach(sourceNode -> {
            Node clone = sourceNode.clone();
            Element note = getAcceptedTagElementOrNull(clone, NOTE_ELEMENT, (e) -> {
                String id = e.attr("id");
                return id != null && !id.isEmpty();
            });

            if (note != null) {
                this.notes.put(note.attr("id"), note);
                return;
            }
            Element st = getAcceptedTagAndIdElementOrNull(clone, SUMMARY_TITLE_ELEMENT, SUMMARY_TITLE_ID);
            if (st != null) {
                summaryTitleRef.accept(st.text());
            }
            Element lae = getAcceptedTagAndIdElementOrNull(clone, LATEST_ARTICLE_ELEMENT, LATEST_ARTICLE_ID);
            latestArticleRef.accept(lae);
            article.appendChild(clone);
        });
        this.latestArticlesElement = latestArticleRef.getValue();
        this.summaryTitle = summaryTitleRef.getValue() != null ? summaryTitleRef.getValue() : title;

        Multimap<UUID, Element> pageRefNodes = ArrayListMultimap.create();

        referenceList = collectReferences(pageRefNodes);

        collectPageReferences(article, pageRefNodes);

        this.idContentMap = collectIdElementMap(article);
        this.path = path;
        this.pageRefNodes = pageRefNodes;

        try {
            rootPath.relativize(path);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(this + ": path not relative to root-path " + rootPath);
        }
        this.rootPath = rootPath;
        this.timeModified = getFileLastModified(path);
        this.timeCreated = getCreationTime(path);
    }

    private ImmutableMap<UUID, String> collectIdElementMap(Node article) {
        Map<UUID, String> contentIdMap = new HashMap<>();
        NodeHelper.deepSearch(article, Element.class, ELEMENT_WITH_ID, (node) -> {
            UUID id = getUuidorNull(node.attr("id"));
            if (id != null) {
                if (contentIdMap.containsKey(id)) {
                    log.warn("Duplicate ID found in {} element of page {}: .", node.tagName(), this.id, this.title);
                }
                else {
                    contentIdMap.put(id, node.text());
                }
            }

        });

        return ImmutableMap.copyOf(contentIdMap);
    }

    private Element collectReferences(Multimap<UUID, Element> pageRefNodes) {
        List<String> referencesUrls = new ArrayList<>();
        Element referencesElement = processReferences(article, pageRefNodes, referencesUrls);

        if (referencesElement != null) {
            Set<String> hadNotes = new HashSet<>();
            int previousResolved = 0;
            while (referencesUrls.size() != previousResolved) {
                previousResolved = referencesUrls.size();

                notes.forEach((url, n) -> {
                    if (!hadNotes.contains(url) && referencesUrls.contains(NOTE_SCHEME + n.id())) {
                        processReferences(n, pageRefNodes, referencesUrls);
                        hadNotes.add(url);
                    }
                });
            }
        }
        return referencesElement;
    }

    private FileTime getFileLastModified(Path path) {
        FileTime time;
        try {
            time = Files.readAttributes(path, BasicFileAttributes.class).lastModifiedTime();
        } catch (IOException e) {
            time = FileTime.fromMillis(System.currentTimeMillis());
            log.warn("Cannot determine modified time {}", this);
        }
        return time;
    }

    private FileTime getCreationTime(Path path) {
        FileTime time;
        try {
            time = Files.readAttributes(path, BasicFileAttributes.class).creationTime();
        } catch (IOException | UnsupportedOperationException | ClassCastException e) {
            time = FileTime.fromMillis(System.currentTimeMillis());
            log.warn("Cannot determine creation time for {}", this);
        }
        return time;
    }

    @Override
    public String toString() {
        return "Page \"" + title + "\" [" + id + "] (" + path.toString() + ")";
    }

    public void replacePageReferences(@NonNull Map<UUID, PageRecord> pages) {
        Set<UUID> toReplace = new HashSet<>(pageRefNodes.keySet());
        
        pages.forEach((id,page) -> {
            String refPageTitle = page.getTitle();
            pageRefNodes.get(id).forEach((n -> {
                n.attr("href", page.getDynamicFilename());
                elementTextReplacement(refPageTitle, n);
            }));
            toReplace.remove(id);
        });

        Iterator<UUID> iterator = toReplace.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            boolean replaced = false;
            for (PageRecord page : pages.values()) {
                if (page.getIdContentMap().containsKey(id)) {
                    String refElementText = page.getIdContentMap().get(id);
                    pageRefNodes.get(id).forEach(n -> {
                        n.attr("href", page.getDynamicFilename() + "#" + id);
                        elementTextReplacement(refElementText, n);
                    });
                    replaced = true;
                }
            }
            if (replaced) {
                iterator.remove();
            }
        }

        toReplace.forEach((id) -> {
            pageRefNodes.get(id).forEach((n -> {
                n.attr("href", "./" + id + ".html");
                n.text("[NOT FOUND]");
            }));
        });
    }

    private void elementTextReplacement(String refPageTitle, Element n) {
        String content = n.text();
        if (content == null || content.isEmpty()) {
            n.text(refPageTitle);
        }
        else {
            switch (content.trim()) {
                case ":title" :
                    n.text(refPageTitle);
                    break;
                case ":title-lower":
                    n.text(refPageTitle.toLowerCase());
                    break;
                default:
                    // No replacement
            }
        }
    }

    public void setParent(PageRecord parent) {
        this.parent = parent;
        this.parentId = this.parent != null ? parent.getId() : null;
    }

    public boolean addChild(@NonNull PageRecord rec) {
        return children.add(rec);
    }

    public SortedSet<PageRecord> getChildren() {
        return Collections.unmodifiableSortedSet(children);
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public void markDuplicate() {
        duplicate = true;
    }

    public void resetIndex() {
        index = false;
    }

    public List<PageRecord> getParents(boolean reverse) {
        List<PageRecord> list = new ArrayList<>();
        PageRecord parent = this.parent;
        while (parent != null) {
            if (reverse) {
                list.add(parent);
            }
            else {
                list.add(0, parent);
            }
            parent = parent.parent;
        }

        return list;
    }

    public void setSiblings(@NonNull SortedSet<PageRecord> siblings) {
        this.siblings = Collections.unmodifiableSortedSet(siblings);
    }

    public boolean isIndex() {
        return parent == null && index;
    }

    public String getDynamicFilename() {
        if (dynamicFilename != null) {
            return dynamicFilename;
        }
        List<PageRecord> parents = getParents(true);
        StringBuilder name = new StringBuilder();
        appendNormalized(name, title);
        if (!parents.isEmpty()) {
            parents.forEach(p -> {
                if (Character.isUpperCase(p.title.charAt(0))) {
                    name.append("_-");
                }
                else {
                    name.append("_-_");
                }
                appendNormalized(name, p.title);
            });
        }
        if (name.length() > MAX_GENERATED_LENGTH) {
            name.setLength(MAX_GENERATED_LENGTH);
        };
        name.append(".html");
        int i = 0;
        while (i < name.length() && name.charAt(i) == '_') {
            i++;
        }
        name.delete(0,i);
        name.insert(0, '/');
        name.insert(0, '.');
        dynamicFilename = name.toString();

        return dynamicFilename;
    }

    public void writePage(@NonNull Writer writer, @NonNull Map<String, Object> cache) throws IOException {
        addHead(cache);
        addBody((String)cache.get(PAGE_COPYRIGHT));

        Document.OutputSettings outputSettings = document.outputSettings();
        outputSettings.charset(StandardCharsets.UTF_8);
        outputSettings.escapeMode(org.jsoup.nodes.Entities.EscapeMode.base);
        writer.append(document.outerHtml());
    }

    private void addHead(@NonNull Map<String, Object> cache) {
        Element head = document.head();

        head.appendElement("meta").attr("charset", "UTF-8");

        head.appendElement("meta")
                .attr("name", "viewport")
                .attr("content", "width=device-width, initial-scale=1.0, maximum-scale=2, minimum-scale=0.5");

        long stamp = System.currentTimeMillis();

        head.appendElement("link")
                .attr("rel", "stylesheet")
                .attr("href", "https://fonts.googleapis.com/css?family=Open+Sans:400italic,600italic,400,600")
                .attr("type", "text/css");
        head.appendElement("link")
                .attr("rel", "stylesheet")
                .attr("href", STYLE_CSS + "?stamp=" + stamp)
                .attr("type", "text/css");
        if (math) {
            head.appendElement("script")
                    .attr("type", "text/javascript")
                    .attr("src", "https://cdnjs.cloudflare.com/ajax/libs/mathjax/2.7.1/MathJax.js?config=TeX-AMS-MML_HTMLorMML")
                    .appendChild(new DataNode("MathJax.Hub.Config({displayAlign: \"left\", displayIndent: \"2ex\" });", ""));
        }
        head.appendElement("script")
                .attr("type", "text/javascript")
                .attr("src", "./emmef-util.js?stamp=" + stamp);

        head.appendElement("title").text(generateTitleTrail());
    }

    private void addBody(String copyRight) {
        Element body = document.body();
        body.attr("onload", "EmmefUtil.init();");
        body.appendChild(header);
        Element nav = header.appendElement("nav");

        List<PageRecord> parents = getParents(false);
        List<PageRecord> self = Collections.singletonList(this);
        List<PageRecord> siblings = new ArrayList<>();
        getSiblings().forEach(p -> {
            if (p != this) {
                siblings.add(p);
            }
        });

        ArrayList<PageRecord> children = new ArrayList<>(getChildren());

        if (!parents.isEmpty()) {
            writeLinks(null, nav, parents, "parents");
        }
        addPermaLink(nav);
        writeLinks(this, nav, self, "current");
        if (!children.isEmpty()) {
            writeLinks(null, nav, children, "children");
        }
        writeLinks(null, nav, siblings, "siblings");

        header.appendElement("div")
                .attr("id", "article-title")
                .text(generateTitleTrail());

        body.appendChild(article);

        addDateAndCopyright(copyRight);
        if (footer.children().size() != 0) {
            body.appendChild(footer);
        }
    }

    private void addPermaLink(Element nav) {
        String imageStyles = duplicate ? "permalink-disabled" : "permalink-enabled";

        if (!duplicate) {
            nav.appendElement("a")
                    .attr("href", id.toString() + ".html")
                    .attr("class", imageStyles)
                    .attr("title", "Permanent link")
                    .text("" + Entities.ODOT);
        }
        else {
            nav.appendElement("span")
                    .attr("class", imageStyles)
                    .attr("title", "Permanent link")
                    .text("" + Entities.ODOT);
        }
    }

    private String generateTitleTrail() {
        return generateTitleTrail(true);
    }

    private String generateTitleTrail(boolean showTopmost) {
        StringBuilder output = new StringBuilder();
        output.append(getTitle());

        PageRecord parent = getParent();
        if (parent != null) {
            output.append(Entities.NBSP).append(Entities.MDASH).append(" ");
            parentTitle(output, parent, showTopmost);
        }

        return output.toString();
    }

    public String parentTitle(boolean showTopmost) {
        if (parent != null) {
            StringBuilder output = new StringBuilder();
            parentTitle(output, parent, showTopmost);
            return output.toString();
        }
        return "";
    }

    private static void parentTitle(StringBuilder output, PageRecord page, boolean showTopmost) {
        PageRecord parent = page.getParent();
        if (parent != null) {
            if (parent.parent != null || showTopmost) {
                parentTitle(output, parent, showTopmost);
                output.append(Entities.NBSP).append("/ ");
            }
        }
        output.append(page.getTitle());
    }

    private void writeLinks(PageRecord self, Element nav, List<PageRecord> pages, String baseClass) {
        int size = pages.size();
        if (size == 0) {
            return;
        }
        for (int i = 0; i < size; i++) {
            PageRecord page = pages.get(i);
            boolean isFirst = i == 0;
            boolean isLast = i == size - 1;
            boolean isSelf = self != null && PageRecord.COMPARE_BY_NAME.compare(page, self) == 0;

            if (isFirst) {
                nav.appendElement("span").attr(
                        "class", createClasses(
                                baseClass, "separator", true, false, false));
            }
            nav.appendElement("a")
                    .attr("href", page.getDynamicFilename())
                    .attr("class", createClasses(baseClass, "element", isFirst, isLast, isSelf))
                    .text(page.getTitle());

            nav.appendElement("span").attr(
                    "class", createClasses(
                            baseClass, "separator", false, isLast, false));
        }
    }

    private String createClasses(String baseClass, String subClass, boolean isFirst, boolean isLast, boolean isSelf) {
        StringBuilder classes = new StringBuilder();

        classes.append(baseClass).append(" ").append(subClass).append(" ").append(baseClass).append("-").append(subClass);
        if (isFirst) {
            addPositionClasses(classes, baseClass, subClass, "first");
        }
        if (isLast) {
            addPositionClasses(classes, baseClass, subClass, "last");
        }
        if (!isFirst && !isLast) {
            addPositionClasses(classes, baseClass, subClass, "inner");
        }
        if (isSelf) {
            addPositionClasses(classes, baseClass, subClass, "self");
        }
        return classes.toString();
    }

    private StringBuilder addPositionClasses(StringBuilder classes, String baseClass, String subClass, String position) {
        return classes
                .append(" ").append(baseClass).append("-").append(position)
                .append(" ").append(subClass).append("-").append(position)
                .append(" ").append(baseClass).append("-").append(subClass).append("-").append(position);
    }

    private SortedSet<PageRecord> getSiblings() {
        if (siblings != null) {
            return siblings;
        }
        if (parent != null) {
            return parent.getChildren();
        }
        return Collections.emptySortedSet();
    }

    private Element processReferences(Node rootNode, Multimap<UUID, Element> pageRefNodes, List<String> references) {
        AtomicReference<Element> referenceList = new AtomicReference<>(footer.getElementById(REFERENCE_LIST));

        NodeHelper.deepSearch(rootNode, Element.class, ANCHOR_REF, refNode -> {
            String referenceUrl = getReferenceValue(refNode, REF_SCHEME);
            int idx = references.indexOf(referenceUrl);
            String number;
            String refId;
            if (idx == -1) {
                Element note = null;
                if (referenceUrl.startsWith(NOTE_SCHEME)) {
                    String noteId = referenceUrl.substring(NOTE_SCHEME.length());
                    note = notes.get(noteId);
                    if (note == null) {
                        return;
                    }
                }
                references.add(referenceUrl);
                number = Integer.toString(references.size());
                refId = "scms_reference_" + number;
                if (referenceList.get() == null) {
                    referenceList.set(
                            footer.appendElement("div")
                                    .attr("class", "reference references")
                                    .appendElement("table")
                                        .attr("class", "reference reference-list")
                                        .attr("id", REFERENCE_LIST));
                }
                Element reference = referenceList.get().appendElement("tr")
                        .attr("class", "reference reference-item")
                        .attr("id", refId);

                reference.appendElement("td")
                        .attr("class", "reference reference-item-number")
                        .text(number);

                Element content = reference.appendElement("td")
                        .attr("class", "reference reference-item-content");

                if (note == null) {
                    String textContent = refNode.text();
                    Element anchor = content.appendElement("a")
                            .attr("href", referenceUrl)
                            .attr("class", "reference reference-item-content-link")
                            .text(textContent != null && !textContent.isEmpty() ? textContent : referenceUrl);
                    UUID pageRef = referenceUrl.startsWith(PAGE_SCHEME) ? getUuidorNull(referenceUrl.substring(PAGE_SCHEME.length())) : null;
                    if (pageRef != null) {
                        pageRefNodes.put(pageRef, anchor);
                    }
                } else {
                    note.attr("class", "reference reference-item-content-link");
                    content.appendChild(note);
                }
            } else {
                number = Integer.toString(idx + 1);
                refId = "scms_reference_" + number;

            }
            refNode.attr("href", "#" + refId);
            refNode.text(number);
            refNode.attr("class", "reference-ptr");
        });
        return referenceList.get();
    }

    private static void collectPageReferences(Node article, Multimap<UUID, Element> pageRefNodes) {

        NodeHelper.deepSearch(article, Element.class, ANCHOR_PAGE, pageRef -> {
            UUID refId = getPageRefId(pageRef, PAGE_SCHEME);
            pageRefNodes.put(refId, pageRef);
        });
    }

    private void addDateAndCopyright(String copyRight) {
        Element fileData = footer.appendElement("div").attr("class", "file-data");

        fileData.appendElement("div").attr("class", "source-modification")
                .appendElement("span").attr("class", "milliseconds-date").text(formatFileDateInGMT(this.timeModified));
        if (copyRight != null) {
            String years;
            int yearCreated = getGMTYear(timeCreated.toMillis());
            int yearModified = getGMTYear(this.timeModified.toMillis());
            if (yearCreated >= yearModified) {
                years = String.format("%04d", yearModified);
            }
            else {
                years = String.format("%04d\u2013%04d", yearCreated, yearModified);
            }
            fileData.appendElement("span").attr("class", "source-copyright")
                        .text(String.format("\u00a9\u00a0%s\u00a0%s.", years, copyRight.replaceAll("\\s", "\u00a0")));
        }
    }

    private String formatFileDateInGMT(FileTime timeModified1) {
        return DATE_TIME_FORMATTER.format(getCalendarInGMT(timeModified1.toMillis()).toZonedDateTime());
    }

    public void replaceLastArticlesReference(List<PageRecord> sortedPages) {
        if (latestArticlesElement == null) {
            return;
        }
        List<PageRecord> orderedChildren = new ArrayList<>();
        PageRecord self = this;
        sortedPages.forEach((p) -> {
            List<Node> s = p.ensureSummary();
            if (p.isChildOf(self) && orderedChildren.size() < 10 && s != null && !s.isEmpty()) {
                orderedChildren.add(p);
            }
        });
        if (orderedChildren.isEmpty()) {
            return;
        }
        latestArticlesElement.tagName("div");
        this.latestArticlesElement.attr("class", "latest-articles");
        orderedChildren.forEach((page) -> addArticle(this.latestArticlesElement, page));
    }

    private void addArticle(Element articleList, PageRecord page) {
        List<Node> s = page.ensureSummary();
        if (s == null) {
            return;
        }
        Element item = articleList.appendElement("div");
        if (articleList.children().size() == 1) {
            item.attr("class", "latest-articles-item latest-articles-item-first");
        }
        else {
            item.attr("class", "latest-articles-item latest-articles-item-subsequent");
        }
        String categoryLink = page.parent != null ? page.parent.getDynamicFilename() : null;
        Element categoryDiv = item
                .appendElement("div")
                .attr("class", "latest-article-category");
        if (categoryLink != null) {
            categoryDiv.appendElement("a")
                    .attr("href", categoryLink)
                    .attr("class", "latest-article-category")
                    .text(page.parentTitle(false));
        }
        else {
            categoryDiv.text(page.parentTitle(false));
        }


        item
                .appendElement("div").attr("class", "latest-article-date")
                .appendElement("span").attr("class", "milliseconds-age")
                .text(Long.toString(page.getTimeModified().toMillis()));

        item
                .appendElement("div")
                        .attr("class", "latest-article-title")
                        .appendElement("a")
                                .attr("class", "latest-article-link")
                                .attr("href", page.getDynamicFilename())
                                .text(page.getSummaryTitle());

//        Element summaryAndDate = item.appendElement("div").attr("class", "latest-article-content");

        Element summary = item
                .appendElement("div").attr("class", "latest-article-summary");
        for (Node n : s) {
            summary.appendChild(n);
        }

    }

    private List<Node> ensureSummary() {
        if (summary != null) {
            return summary;
        }
        summary = summarizeText();
        return summary;
    }

    private List<Node> summarizeText() {
        Element p = NodeHelper.deepGetFirst(article,
                Element.class, e -> {
                    if (e == null) {
                        return false;
                    }
                    if (!SUMMARY_ELEMENT.equalsIgnoreCase(e.tagName())) {
                        return false;
                    }
                    String id = e.attr("id");
                    return SUMMARY_ID.equalsIgnoreCase(id);
                });
        if (p == null) {
            return null;
        }
        LocalToRelativeLinkVisitor visitor = new LocalToRelativeLinkVisitor();
        ArrayList<Node> summary = new ArrayList<>();
        for (Node child : p.childNodes()) {
            summary.add(child.clone().traverse(visitor));
        }
        return summary.isEmpty() ? Collections.emptyList() : summary;
    }

    private boolean isChildOf(PageRecord supposedParent) {
        if (supposedParent == null) {
            return false;
        }
        PageRecord p = this.parent;
        while (p != null) {
            if (p.id.equals(supposedParent.id)) {
                return true;
            }
            p = p.parent;
        }
        return false;
    }


    private int getGMTYear(long millis) {
        GregorianCalendar calendar = getCalendarInGMT(millis);
        return calendar.get(Calendar.YEAR);
    }

    private GregorianCalendar getCalendarInGMT(long millis) {
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
        calendar.setTimeInMillis(millis);
        return calendar;
    }


    private void appendNormalized(@NonNull StringBuilder output, @NonNull String name) {
        name.chars().forEach(c -> {
            char chr = (char)c;
            if (chr <= ' '|| chr >= '\u007f' || reservedChars.indexOf(c) != -1) {
                output.append('_');
            }
            else if (chr >= 'A' && chr <='Z') {
                output.append('_').append((char)Character.toLowerCase(c));
            }
            else {
                output.append(chr);
            }
        });
    }

    private static UUID getPageRefId(Node pageRef, String scheme) {
        String refIdText = getReferenceValue(pageRef, scheme);
        UUID refId;
        try {
            refId = UUID.fromString(refIdText);
        }
        catch (IllegalArgumentException e) {
            throw new PageException("A href with scheme " + scheme + " requires a uuid");
        }
        return refId;
    }

    private static String getReferenceValue(Node pageRef, String scheme) {
        String href = pageRef.attr("href");
        return href.substring(scheme.length());
    }

    private static UUID getUuidorNull(String reference) {
        try {
            return UUID.fromString(reference);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static String getTitle(Node head) {
        String title = getContent(head, TITLE);
        if (title == null || title.isEmpty()) {
            throw new PageException("Title must not be empty");
        }
        return title.trim().replaceAll("\\s+", " ").replaceAll("\\s", NBSP);
    }

    private static UUID getIdentifier(@NonNull Node head, @NonNull Predicate<Element> predicate, @NonNull String description, Pattern nullPattern) {
        String uuidText = getMetaValue(head, predicate);
        if (uuidText == null) {
            throw new PageException("Missing " + description);
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        }
        catch (IllegalArgumentException e) {
            if (nullPattern != null && nullPattern.matcher(uuidText).matches()) {
                return null;
            }
            throw new PageException("While parsing " + description + ": " + e.getMessage());
        }
        return uuid;
    }

    private static String getMetaValue(Node head, Predicate<Element> predicate) {
        Node meta = NodeHelper.searchFirst(head, predicate);
        if (meta == null) {
            return null;
        }
        String value = meta.attr("value");
        return value.isEmpty() ? null : value;
    }

    private static String getContent(Node head, Predicate<Element> predicate) {
        Element meta = NodeHelper.searchFirst(head, predicate);
        return meta != null ? meta.text() : null;
    }

    private static Element getNodeByTag(Document document, String tagName, NodeExpectation expectation) {

        Elements elementsByTagName = document.getElementsByTag(tagName);
        Element item;
        if (elementsByTagName.size() == 0) {
            if (expectation == NodeExpectation.OPTIONAL) {
                item = null;
            }
            else {
                throw new ValidationException("Expected element with tag \"" + tagName + "\"");
            }
        }
        else {
            if (elementsByTagName.size() > 1 && expectation == NodeExpectation.UNIQUE) {
                throw new ValidationException("Expected exactly one element with tag \"" + tagName + "\"");
            }
            item = elementsByTagName.get(0);
        }
        return item;
    }

    private Element getAcceptedTagAndIdElementOrNull(@NonNull Node node, @NonNull String tagName, @NonNull String idValue) {
        return getAcceptedElementOrNull(node, NodeHelper.elementByNameCaseInsensitive(tagName).and((e) -> idValue.equalsIgnoreCase(e.attr("id"))));
    }

    private Element getAcceptedTagElementOrNull(@NonNull Node node, @NonNull String tagName, @NonNull Predicate<Element> predicate) {
        return getAcceptedElementOrNull(node, NodeHelper.elementByNameCaseInsensitive(tagName).and(predicate));
    }

    private Element getAcceptedElementOrNull(@NonNull Node node, @NonNull Predicate<Element> predicate) {
        if (!(node instanceof Element)) {
            return null;
        }
        Element element = (Element)node;
        return predicate.test(element) ? element : null;
    }

    private class LocalToRelativeLinkVisitor implements NodeVisitor {
        @Override
        public void head(Node node, int depth) {

        }

        @Override
        public void tail(Node node, int depth) {
            if (!(node instanceof Element)) {
                return;
            }
            Element e = (Element)node;
            if (!"a".equalsIgnoreCase(e.tagName())) {
                return;
            }
            String href = e.attr("href");
            if (href == null || !href.startsWith("#")) {
                return;
            }
            String newHref = getDynamicFilename() + href;
            e.attr("href", newHref);
        }
    }
}
