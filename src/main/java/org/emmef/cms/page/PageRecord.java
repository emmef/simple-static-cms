package org.emmef.cms.page;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import lombok.*;
import org.emmef.cms.parameters.NodeExpectation;
import org.emmef.cms.parameters.ValidationException;
import org.emmef.cms.util.ByAttributeValue;
import org.emmef.cms.util.NodeHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.w3c.dom.NodeList;

import javax.swing.text.html.HTML;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.*;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

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
    @Getter
    private final boolean index;

    @Getter
    private PageRecord parent = null;
    private final SortedSet<PageRecord> children = createPageSet();
    private SortedSet<PageRecord> siblings = null;
    private String dynamicFilename = null;
    private boolean duplicate = false;

    public static final Comparator<PageRecord> COMPARATOR = (p1, p2) -> {
        int i = p1.getTitle().compareToIgnoreCase(p2.getTitle());
        if (i != 0) {
            return i;
        }
        return p1.getId().hashCode() - p2.getId().hashCode();
    };

    public static TreeSet<PageRecord> createPageSet() {
        return new TreeSet<PageRecord>(COMPARATOR);
    }

    public PageRecord(Document sourceDocument, Path path) {
        Node head = getNodeByTag(sourceDocument, "head", NodeExpectation.UNIQUE);

        this.id = getIdentifier(head, META_UUID, "page identifier", null);
        this.title = getTitle(head);
        this.math = Boolean.parseBoolean(getMetaValue(head, META_MATH));
        this.index = Boolean.parseBoolean(getMetaValue(head, META_INDEX));
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

        referenceList = processReferences(this.document, pageRefNodes);

        collectPageReferences(article, pageRefNodes);

        this.path = path;
        this.pageRefNodes = pageRefNodes;
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

    public void writePage(Writer writer) throws IOException {
        addHead();
        addBody();

        Document.OutputSettings outputSettings = document.outputSettings();
        outputSettings.charset(StandardCharsets.UTF_8);
        outputSettings.escapeMode(org.jsoup.nodes.Entities.EscapeMode.base);
        writer.append(document.outerHtml());

    }

    private void addHead() {
        Element head = document.head();

        addChild(head, "meta").attr("charset", "UTF-8");
        addSimpleChild(head, "link")
                .attr("rel", "StyleSheet")
                .attr("href", "./style/template-global.css")
                .attr("type", "text/css");
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

    private void addBody() {
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
        body.appendChild(footer);
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
        name.append("./");
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
        if (name.charAt(0) == '_') {
            dynamicFilename = name.substring(1);
        }
        else {
            dynamicFilename = name.toString();
        }
        return dynamicFilename;
    }

    private Element processReferences(Document document, Multimap<UUID, Element> pageRefNodes) {
        List<String> references = new ArrayList<>();
        Element referenceList  = addSimpleChild(footer,"table")
                .attr("class", "reference reference-list")
                .getElement();

        NodeHelper.search(article, ANCHOR_REF, Element.class, refNode -> {
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
                idx = references.size();
                references.add(referenceUrl);
                number = Integer.toString(references.size());
                refId = "scms_reference_" + number;

                Element reference = addSimpleChild(referenceList, "tr")
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
        return referenceList;
    }

    private static void collectPageReferences(Node article, Multimap<UUID, Element> pageRefNodes) {

        NodeHelper.search(article, ANCHOR_PAGE, Element.class, pageRef -> {
            UUID refId = getPageRefId(pageRef, PAGE_SCHEME);
            pageRefNodes.put(refId, pageRef);
        });
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
