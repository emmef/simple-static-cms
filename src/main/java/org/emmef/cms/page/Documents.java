package org.emmef.cms.page;

import lombok.NonNull;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.Writer;

public class Documents {

    private static final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
    private static final TransformerFactory transformerFactory = TransformerFactory.newInstance();

    public static Document createDocument(String simpleDocType) {
        try {
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            DOMImplementation domImplementation = documentBuilder.getDOMImplementation();
            DocumentType docType = domImplementation.createDocumentType(simpleDocType, null, null);
            return domImplementation.createDocument(null, simpleDocType, docType);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("While creating document", e);
        }
    }

    public static void writeNode(Writer writer, Node node) throws TransformerException {
        DOMSource domSource = new DOMSource(node);
        StreamResult out = new StreamResult(writer);
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.transform(domSource, out);
    }

    public static void writeDocument(@NonNull Writer writer, @NonNull Document document) throws TransformerException {
        Element documentElement = document.getDocumentElement();
        DOMSource domSource = new DOMSource(document);
        StreamResult out = new StreamResult(writer);
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, documentElement.getTagName());
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.transform(domSource, out);
    }

}
