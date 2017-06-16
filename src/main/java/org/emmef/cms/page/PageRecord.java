package org.emmef.cms.page;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import lombok.*;
import org.emmef.cms.parameters.NodeExpectation;
import org.emmef.cms.parameters.ValidationException;
import org.emmef.cms.util.ByAttributeValue;
import org.emmef.cms.util.NodeHelper;
import org.w3c.dom.*;

import javax.xml.stream.XMLStreamException;
import javax.xml.transform.*;
import java.io.Writer;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class PageRecord {
    private static final String reservedChars = "|\\?*<:>+[]/";
    private static final int MAX_NAME_LENGTH = 255;
    private static final String HTML_SUFFIX = ".html";
    private static final int MAX_GENERATED_LENGTH = MAX_NAME_LENGTH - HTML_SUFFIX.length();

    public static final Predicate<Node> META = NodeHelper.elementByNameCaseInsensitive("meta");
    public static final Predicate<Node> META_UUID = META.and(ByAttributeValue.literal("name", "scms-uuid", true));
    public static final Predicate<Node> META_PARENT_UUID = META.and(ByAttributeValue.literal("name", "scms-parent-uuid", true));
    public static final Predicate<Node> META_MATH = META.and(ByAttributeValue.literal("name", "scms-uses-math", true));
    public static final Predicate<Node> META_INDEX = META.and(ByAttributeValue.literal("name", "scms-is-index", true));

    public static final Predicate<Node> ANCHOR = NodeHelper.elementByNameCaseInsensitive("A");
    public static final String PAGE_SCHEME = "page:";
    public static final String REF_SCHEME = "ref:";
    public static final Predicate<Node> ANCHOR_PAGE = ANCHOR.and(ByAttributeValue.startsWith("href", PAGE_SCHEME));
    public static final Predicate<Node> ANCHOR_REF = ANCHOR.and(ByAttributeValue.startsWith("href", REF_SCHEME));

    public static final Predicate<Node> TITLE = NodeHelper.elementByNameCaseInsensitive("title");
    public static final Pattern NULL_PATTERN = Pattern.compile("^(null|none|root)$", Pattern.CASE_INSENSITIVE);
    public static final String NBSP = "" + Entities.NBSP;


    @NonNull
    @Getter
    private final UUID id;
    @NonNull
    @Getter
    private final String title;
    @Getter
    private UUID parentId;
    @NonNull
    @Getter
    private final Path path;
    @NonNull
    private final Document document;
    @NonNull
    private final Node article;
    @NonNull
    private final Element footer;
    @NonNull
    private final Multimap<UUID, Node> pageRefNodes;
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

    private PageRecord(Document sourceDocument, Path path) {
        Node head = getNodeByTag(sourceDocument, "head", NodeExpectation.UNIQUE);

        this.id = getIdentifier(head, META_UUID, "page identifier", null);
        this.title = getTitle(head);
        this.math = Boolean.parseBoolean(getMetaValue(head, META_MATH));
        this.index = Boolean.parseBoolean(getMetaValue(head, META_INDEX));
        this.parentId = getIdentifier(head, META_PARENT_UUID, "parent identifier", NULL_PATTERN);

        if (id.equals(parentId)) {
            throw new PageException("Page cannot be its own parent!");
        }

        Node body = getNodeByTag(sourceDocument, "body", NodeExpectation.UNIQUE);
        if (body == null) {
            throw new PageException("Page has no article!");
        }

        this.document = Documents.createDocument("html");
        this.article = this.document.createElement("article");
        this.footer = this.document.createElement("footer");
        NodeHelper.children(body).forEach(n -> {
            Node imported = this.document.importNode(n, true);
            article.appendChild(imported);
        });

        Multimap<UUID, Node> pageRefNodes = ArrayListMultimap.create();

        referenceList = processReferences(this.document, article, pageRefNodes);

        collectPageReferences(article, pageRefNodes);

        this.path = path;
        this.pageRefNodes = pageRefNodes;
    }

    public static PageRecord create(Document document, Path path) {
        return new PageRecord(document, path);
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
                NamedNodeMap attributes = n.getAttributes();
                System.out.println("HREF" + attributes.getNamedItem("href").getNodeValue());
                System.out.println(n.getParentNode().getNodeName());
                attributes.getNamedItem("href").setNodeValue(page.getDynamicFilename());
                String content = n.getTextContent();
                if (content == null || content.isEmpty()) {
                    n.setTextContent(refPageTitle);
                }
                else {
                    switch (content.trim()) {
                        case ":title" :
                            n.setTextContent(refPageTitle);
                            break;
                        case ":title-lower":
                            n.setTextContent(refPageTitle.toLowerCase());
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
                NamedNodeMap attributes = n.getAttributes();
                attributes.getNamedItem("href").setNodeValue("./" + id + ".html");
                n.setTextContent("[NOT FOUND]");
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

    public void writePage(Writer writer) throws TransformerException {
        Element html = document.getDocumentElement();
        addHead(html);
        addBody(html);

        Documents.writeDocument(writer, document);
    }

    private void addHead(Element html) {
        Element head = addChild(html, "head");

        addChild(head, "meta").setAttribute("charset", "UTF-8");
        addSimpleChild(head, "link")
                .attr("rel", "StyleSheet")
                .attr("href", "./style/template-global.css")
                .attr("type", "text/css");
        if (math) {
            addSimpleChild(head, "script")
                    .attr("type", "text/javascript")
                    .attr("src", "https://cdnjs.cloudflare.com/ajax/libs/mathjax/2.7.1/MathJax.js?config=TeX-AMS-MML_HTMLorMML")
                    .content("MathJax.Hub.Config({displayAlign: \"left\", displayIndent: \"2ex\" });");
        }

        StringBuilder output = new StringBuilder();
        output.append(getTitle());

        String title = generateTitleTrail(output);
        addSimpleChild(head, "title").content(title);
    }

    private void addBody(Element html) {
        Element body = addChild(html, "body");
        Element nav = addChild(body, "nav");


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
                addChild(nav, "span").setAttribute(
                        "class", createClasses(
                                baseClass, "separator", true, false, false));
            }
            addSimpleChild(nav, "a")
                    .attr("href", page.getDynamicFilename())
                    .attr("class", createClasses(baseClass, "element", isFirst, isLast, isSelf))
                    .content(page.getTitle());

            addChild(nav, "span").setAttribute(
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

    public void appendArticle(Writer writer) throws XMLStreamException, TransformerException {
        Documents.writeDocument(writer, document);
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

    private Element processReferences(Document document, Node article, Multimap<UUID, Node> pageRefNodes) {
        List<String> references = new ArrayList<>();
        Element referenceList = null;
        for (Element refNode : NodeHelper.search(article, ANCHOR_REF, Element.class)) {
            String reference = getReferenceValue(refNode, REF_SCHEME);
            int idx = references.indexOf(reference);
            if (idx == -1) {
                idx = references.size();
                references.add(reference);

                Element anchor = document.createElement("a");
                anchor.setAttribute("href", reference);
                String textContent = refNode.getTextContent();
                anchor.setTextContent(textContent != null && !textContent.isEmpty() ? textContent : reference);
                UUID pageRef = reference.startsWith(PAGE_SCHEME) ? getUuidorNull(reference.substring(PAGE_SCHEME.length())) : null;
                if (pageRef != null) {
                    pageRefNodes.put(pageRef, anchor);
                }

                Element item = document.createElement("li");
                item.setAttribute("class", "reference");
                item.setAttribute("id", "reference_" + idx);
                item.appendChild(anchor);

                if (referenceList == null) {
                    footer.setAttribute("class", "references");
                    referenceList = document.createElement("ol");
                    referenceList.setAttribute("class", "references");
                    footer.appendChild(referenceList);
                }
                referenceList.appendChild(item);
            }
            refNode.setAttribute("href", "#reference_" + idx);
            refNode.setTextContent("[" + (idx + 1) + "]");
            refNode.setAttribute("class", "reference-ptr");
        }
        return referenceList;
    }

    private static void collectPageReferences(Node article, Multimap<UUID, Node> pageRefNodes) {
        for (Node pageRef : NodeHelper.search(article, ANCHOR_PAGE)) {
            UUID refId = getPageRefId(pageRef, PAGE_SCHEME);
            pageRefNodes.put(refId, pageRef);
        }
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
        String href = pageRef.getAttributes().getNamedItem("href").getNodeValue();
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

    private static UUID getIdentifier(@NonNull Node head, @NonNull Predicate<Node> predicate, @NonNull String description, Pattern nullPattern) {
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

    private static String getMetaValue(Node head, Predicate<Node> predicate) {
        Node meta = NodeHelper.searchFirst(head, predicate);
        if (meta == null || !meta.hasAttributes()) {
            return null;
        }
        Node value = meta.getAttributes().getNamedItem("value");
        return value != null ? value.getNodeValue() : null;
    }

    private static String getContent(Node head, Predicate<Node> predicate) {
        Node meta = NodeHelper.searchFirst(head, predicate);
        return meta != null ? meta.getTextContent() : null;
    }

    private static Node getNodeByTag(Document document, String tagName, NodeExpectation expectation) {
        NodeList elementsByTagName = document.getElementsByTagName(tagName);
        Node item = null;
        if (elementsByTagName.getLength() == 0) {
            if (expectation == NodeExpectation.OPTIONAL) {
                item = null;
            }
            else {
                throw new ValidationException("Expected element with tag \"" + tagName + "\"");
            }
        }
        else {
            if (elementsByTagName.getLength() > 1 && expectation == NodeExpectation.UNIQUE) {
                throw new ValidationException("Expected exactly one element with tag \"" + tagName + "\"");
            }
            item = elementsByTagName.item(0);
        }
        return item;
    }

    @RequiredArgsConstructor
    private class SimpleElement {
        @NonNull private final Element element;

        public SimpleElement appendChild(Node node) {
            element.appendChild(node);
            return this;
        }
        public SimpleElement attr(String name, String value) {
            element.setAttribute(name, value);
            return this;
        }
        public SimpleElement content(String content) {
            element.setTextContent(content);
            return this;
        }
    }
}
