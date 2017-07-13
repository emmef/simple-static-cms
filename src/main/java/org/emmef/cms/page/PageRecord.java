package org.emmef.cms.page;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.parameters.NodeExpectation;
import org.emmef.cms.parameters.ValidationException;
import org.emmef.cms.util.ByAttributeValue;
import org.emmef.cms.util.NodeHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public static final String REF_SCHEME = "ref:";
    public static final String NOTE_SCHEME = "note:";
    public static final String NOTE_ELEMENT = "aside";

    public static final Predicate<Element> META = NodeHelper.elementByNameCaseInsensitive("meta");
    public static final Predicate<Element> META_UUID = META.and(ByAttributeValue.literal("name", "scms-uuid", true));
    public static final Predicate<Element> META_PARENT_UUID = META.and(ByAttributeValue.literal("name", "scms-parent-uuid", true));
    public static final Predicate<Element> META_MATH = META.and(ByAttributeValue.literal("name", "scms-uses-math", true));
    public static final Predicate<Element> META_INDEX = META.and(ByAttributeValue.literal("name", "scms-is-index", true));

    public static final Predicate<Element> ANCHOR = NodeHelper.elementByNameCaseInsensitive("a");
    public static final Predicate<Element> ANCHOR_PAGE = ANCHOR.and(ByAttributeValue.startsWith("href", PAGE_SCHEME));
    public static final Predicate<Element> ANCHOR_REF = ANCHOR.and(ByAttributeValue.startsWith("href", REF_SCHEME));

    public static final Predicate<Element> TITLE = NodeHelper.elementByNameCaseInsensitive("title");
    public static final Pattern NULL_PATTERN = Pattern.compile("^(null|none|root)$", Pattern.CASE_INSENSITIVE);
    public static final String NBSP = "" + Entities.NBSP;
    public static final String STYLE_CSS = "./style/simple-static-cms.css";
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
    private final FileTime timeModified;
    private final FileTime timeCreated;

    public static final Comparator<PageRecord> COMPARATOR = (p1, p2) -> {
        int i = p1.getTitle().compareToIgnoreCase(p2.getTitle());
        if (i != 0) {
            return i;
        }
        return p1.getId().hashCode() - p2.getId().hashCode();
    };

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm'GMT'");

    public static TreeSet<PageRecord> createPageSet() {
        return new TreeSet<PageRecord>(COMPARATOR);
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
        NodeHelper.children(sourceBody).forEach(sourceNode -> {
            Node clone = sourceNode.clone();
            Element note = getNoteElementOrNull(clone);
            if (note != null) {
                this.notes.put(note.attr("id"), note);
            }
            else {
                article.appendChild(clone);
            }
        });

        Multimap<UUID, Element> pageRefNodes = ArrayListMultimap.create();
        List<String> references = new ArrayList<>();
        referenceList = processReferences(article, pageRefNodes, references);
        if (referenceList != null) {
            notes.values().forEach(n -> { processReferences(n, pageRefNodes, references); });
        }

        collectPageReferences(article, pageRefNodes);

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

    private FileTime getFileLastModified(Path path) {
        FileTime time;
        try {
            time = Files.getLastModifiedTime(path);
            Files.getAttribute(path, "creationTime");
        } catch (IOException e) {
            time = FileTime.fromMillis(System.currentTimeMillis());
            log.warn("Cannot determine modified time {}", this);
        }
        return time;
    }

    private FileTime getCreationTime(Path path) {
        FileTime time;
        try {
            time = (FileTime) Files.getAttribute(path, "creationTime");

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
            }));
            toReplace.remove(id);
        });

        toReplace.forEach((id) -> {
            pageRefNodes.get(id).forEach((n -> {
                n.attr("href", "./" + id + ".html");
                n.text("[NOT FOUND]");
            }));
        });
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

        addChild(head, "meta").attr("charset", "UTF-8");
        // 	<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=2, minimum-scale=0.6"/>

        addChild(head, "meta")
                .attr("name", "viewport")
                .attr("content", "width=device-width, initial-scale=1.0, maximum-scale=2, minimum-scale=0.5");

        Object style = cache.computeIfAbsent(STYLE_CSS, (s) -> {
            Path styleSheetFile = rootPath.resolve(STYLE_CSS);
            if (Files.exists(styleSheetFile) && Files.isReadable(styleSheetFile)) {
                try {
                    System.out.println("Reading stylesheet for inlining...");
                    StringBuilder builder = new StringBuilder();
                    builder.append("\n");
                    for (String line : Files.readAllLines(styleSheetFile, StandardCharsets.UTF_8)) {
                        if (!line.trim().isEmpty()) {
                            builder.append(line).append("\n");
                        }
                    }
                    return builder.toString();
                } catch (IOException e) {
                    // FALLTHROUGH
                }
            }
            return Boolean.FALSE;
        });
        addSimpleChild(head, "link")
                .attr("rel", "stylesheet")
                .attr("href", "http://fonts.googleapis.com/css?family=Open+Sans:400italic,600italic,400,600")
                .attr("type", "text/css");
        if (style instanceof String) {
            addChild(head, "style").appendChild(new DataNode((String)style, ""));
        }
        else {
            addSimpleChild(head, "link")
                    .attr("rel", "stylesheet")
                    .attr("href", STYLE_CSS)
                    .attr("type", "text/css");
        }
        if (math) {
            addSimpleChild(head, "script")
                    .attr("type", "text/javascript")
                    .attr("src", "https://cdnjs.cloudflare.com/ajax/libs/mathjax/2.7.1/MathJax.js?config=TeX-AMS-MML_HTMLorMML")
                    .getElement().appendChild(new DataNode("MathJax.Hub.Config({displayAlign: \"left\", displayIndent: \"2ex\" });", ""));
        }

        StringBuilder output = new StringBuilder();
        output.append(getTitle());

        String title = generateTitleTrail(output);
        addSimpleChild(head, "title").content(title);
    }

    private void addBody(String copyRight) {
        Element body = document.body();
        body.appendChild(header);
        Element nav = addChild(header, "nav");

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

        body.appendChild(article);

        addDateAndCopyright(copyRight);
        if (footer.children().size() != 0) {
            body.appendChild(footer);
        }
    }

    private void addPermaLink(Element nav) {
        String imageStyles = duplicate ? "permalink-disabled" : "permalink-enabled";

        if (!duplicate) {
            addSimpleChild(nav, "a")
                    .attr("href", id.toString() + ".html")
                    .attr("class", imageStyles)
                    .attr("title", "Permanent link")
                    .content("" + Entities.ODOT);
        }
        else {
            addSimpleChild(nav, "span")
                    .attr("class", imageStyles)
                    .attr("title", "Permanent link")
                    .content("" + Entities.ODOT);
        }
    }

    private String generateTitleTrail(StringBuilder output) {
        PageRecord parent = getParent();
        if (parent != null) {
            output.append(Entities.NBSP).append(Entities.MDASH).append(" ");
            parentTitle(output, parent);
        }

        return output.toString();
    }

    private static void parentTitle(StringBuilder output, PageRecord page) {
        PageRecord parent = page.getParent();
        if (parent != null) {
            parentTitle(output, parent);
            output.append(Entities.NBSP).append("/ ");
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
            boolean isSelf = self != null && PageRecord.COMPARATOR.compare(page, self) == 0;

            if (isFirst) {
                addChild(nav, "span").attr(
                        "class", createClasses(
                                baseClass, "separator", true, false, false));
            }
            addSimpleChild(nav, "a")
                    .attr("href", page.getDynamicFilename())
                    .attr("class", createClasses(baseClass, "element", isFirst, isLast, isSelf))
                    .content(page.getTitle());

            addChild(nav, "span").attr(
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


    private Element addChild(Element parent, String tagName) {
        Element element = document.createElement(tagName);
        parent.appendChild(element);
        return element;
    }

    private SimpleElement addSimpleChild(Element parent, String tagName) {
        return new SimpleElement(addChild(parent, tagName));
    }

    public SortedSet<PageRecord> getSiblings() {
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

        NodeHelper.search(rootNode, ANCHOR_REF, Element.class, refNode -> {
            String referenceUrl = getReferenceValue(refNode, REF_SCHEME);
            int idx = references.indexOf(referenceUrl);
            String number = null;
            String refId = null;
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
                            addSimpleChild(footer, "table")
                                    .attr("class", "reference reference-list")
                                    .attr("id", REFERENCE_LIST)
                                    .getElement());
                }
                Element reference = addSimpleChild(referenceList.get(), "tr")
                        .attr("class", "reference reference-item")
                        .attr("id", refId).getElement();

                addSimpleChild(reference, "td")
                        .attr("class", "reference reference-item-number")
                        .content(number);

                Element content = addSimpleChild(reference, "td")
                        .attr("class", "reference reference-item-content").getElement();

                if (note == null) {
                    String textContent = refNode.text();
                    Element anchor = addSimpleChild(content, "a")
                            .attr("href", referenceUrl)
                            .attr("class", "reference reference-item-content-link")
                            .content(textContent != null && !textContent.isEmpty() ? textContent : referenceUrl).getElement();
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

        NodeHelper.search(article, ANCHOR_PAGE, Element.class, pageRef -> {
            UUID refId = getPageRefId(pageRef, PAGE_SCHEME);
            pageRefNodes.put(refId, pageRef);
        });
    }

    private void addDateAndCopyright(String copyRight) {
        Element fileData = addChild(footer, "div").attr("class", "file-data");

        fileData.appendElement("span").attr("class", "source-modification").text(DATE_TIME_FORMATTER.format(getCalendarInGMT(timeModified.toMillis()).toZonedDateTime()));
        if (copyRight != null) {
            String years;
            int yearCreated = getGMTYear(timeCreated.toMillis());
            int yearModified = getGMTYear(timeModified.toMillis());
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

    private int getGMTYear(long millis) {
        GregorianCalendar calendar = getCalendarInGMT(millis);
        return calendar.get(Calendar.YEAR);
    }

    private GregorianCalendar getCalendarInGMT(long millis) {
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
        calendar.setTimeInMillis(millis);
        return calendar;
    }


    protected void appendNormalized(@NonNull StringBuilder output, @NonNull String name) {
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
        Node meta = NodeHelper.searchFirst(head, predicate);
        return meta instanceof Element ? ((Element)meta).text() : null;
    }

    private static Element getNodeByTag(Document document, String tagName, NodeExpectation expectation) {

        Elements elementsByTagName = document.getElementsByTag(tagName);
        Element item = null;
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

    private Element getNoteElementOrNull(Node node) {
        if (!(node instanceof Element)) {
            return null;
        }
        Element element = (Element)node;
        if (!NOTE_ELEMENT.equalsIgnoreCase(element.tagName())) {
            return null;
        }
        String elementId = element.attr("id");
        if (elementId == null || elementId.isEmpty()) {
            return null;
        }
        return element;
    }

    private void printElements(String title, Element article) {
        StringBuilder output = new StringBuilder();
        output.append("PAGE ").append(title).append(":\n");
        printNode(output, article, 1);
        System.out.println(output);
    }

    private void printNode(StringBuilder output, Element element, int indent) {
        String indents = "\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t";
        output.append(indents.substring(0, indent)).append("<").append(element.tagName());

        for (Attribute attribute : element.attributes().asList()) {
            output.append(" ").append(attribute.getKey()).append("=\"").append(attribute.getValue()).append("\"");
        }
        output.append(">\n");
        NodeHelper.children(element, Element.class).forEach(child -> printNode(output, child, indent + 1));
    }

    @RequiredArgsConstructor
    private class SimpleElement {
        @Getter
        @NonNull
        private final Element element;

        public SimpleElement appendChild(Node node) {
            element.appendChild(node);
            return this;
        }
        public SimpleElement attr(String name, String value) {
            element.attr(name, value);
            return this;
        }
        public SimpleElement content(String content) {
            element.text(content);
            return this;
        }
    }
}
