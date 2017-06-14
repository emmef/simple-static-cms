package org.emmef.cms.page;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import lombok.*;
import org.emmef.cms.parameters.NodeExpectation;
import org.emmef.cms.parameters.ValidationException;
import org.emmef.cms.util.ByAttributeValue;
import org.emmef.cms.util.NodeHelper;
import org.w3c.dom.*;

import javax.xml.stream.XMLStreamException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.Writer;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class PageRecord {
    private static final TransformerFactory tFactory = TransformerFactory.newInstance();

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
    private final Node article;
    @NonNull
    private final Multimap<UUID, Node> pageRefNodes;
    @Getter
    private final boolean math;
    private final Element referenceList;
    @Getter
    private boolean index;

    @Getter
    private PageRecord parent = null;
    private final SortedSet<PageRecord> children = createPageSet();
    private SortedSet<PageRecord> siblings = null;
    private String dynamicFilename = null;

    public static TreeSet<PageRecord> createPageSet() {
        return new TreeSet<PageRecord>((p1, p2) -> {
//            if (p1.getId().equals(p2.getId())) {
//                return 0;
//            }
            int i = p1.getTitle().compareToIgnoreCase(p2.getTitle());
            if (i != 0) {
                return i;
            }
            return p1.getId().hashCode() - p2.getId().hashCode();
        });
    }

    private PageRecord(UUID id, @NonNull String title, UUID parentId, @NonNull Path path, @NonNull Node article, @NonNull Multimap<UUID, Node> pageRefNodes, boolean math, boolean isIndex, Element referenceList) {
        this.id = id;
        this.title = title;
        this.parentId = parentId;
        this.path = path;
        this.article = article;
        this.pageRefNodes = pageRefNodes;
        this.math = math;
        this.referenceList = referenceList;
        this.index = isIndex;
    }

    public static PageRecord create(Document document, Path path) {

        Node head = getNodeByTag(document, "head", NodeExpectation.UNIQUE);

        UUID id = getIdentifier(head, META_UUID, "page identifier", null);
        String title = getTitle(head);
        boolean math = Boolean.parseBoolean(getMetaValue(head, META_MATH));
        boolean index = Boolean.parseBoolean(getMetaValue(head, META_INDEX));
        UUID parentId = getIdentifier(head, META_PARENT_UUID, "parent identifier", NULL_PATTERN);

        if (id.equals(parentId)) {
            throw new PageException("Page cannot be its own parent!");
        }

        Node body = getNodeByTag(document, "body", NodeExpectation.UNIQUE);
        if (body == null) {
            throw new PageException("Page has no article!");
        }
        Node article = document.createElement("article");
        NodeHelper.children(body).forEach(n -> {
            article.appendChild(n.cloneNode(true));
        });

        Multimap<UUID, Node> pageRefNodes = ArrayListMultimap.create();

        Element referenceList = processReferences(document, article, pageRefNodes);

        collectPageReferences(article, pageRefNodes);

        return new PageRecord(id, title, parentId, path, article, pageRefNodes, math, index, referenceList);
    }

    private static Element processReferences(Document document, Node article, Multimap<UUID, Node> pageRefNodes) {
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
                    Element footer = document.createElement("footer");
                    footer.setAttribute("class", "references");
                    article.appendChild(footer);
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
                attributes.getNamedItem("href").setNodeValue("./" + page.getDynamicFilename());
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

    public void appendArticle(Writer writer) throws XMLStreamException, TransformerException {
        DOMSource domSource = new DOMSource(article);
        StreamResult out = new StreamResult(writer);
        Transformer transformer = tFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.transform(domSource, out);
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

    public void resetIndex() {
        this.index = false;
    }

    private static final String reservedChars = "|\\?*<:>+[]/";
    private static final int MAX_NAME_LENGTH = 255;
    private static final String HTML_SUFFIX = ".html";
    private static final int MAX_GENERATED_LENGTH = MAX_NAME_LENGTH - HTML_SUFFIX.length();

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
        if (name.charAt(0) == '_') {
            dynamicFilename = name.substring(1);
        }
        else {
            dynamicFilename = name.toString();
        }
        return dynamicFilename;
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
        title = title.trim();
        return title;
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


    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    @EqualsAndHashCode(exclude = {"caption"})
    private static class RefOrPage {
        private final String reference;
        private final String caption;
        private final UUID page;

        public static RefOrPage page(@NonNull UUID page) {
            return new RefOrPage(null, null, page);
        }

        public static RefOrPage reference(@NonNull String reference, String caption) {
            return new RefOrPage(reference, caption != null ? caption: reference, null);
        }
    }
}
