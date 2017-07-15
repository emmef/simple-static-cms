package org.emmef.cms.main;

import org.emmef.cms.parameters.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

public class TestBed {
    public static final String HOMEPAGE = "/home/michel/net/emmef.org";

    public static void main(String arg[]) throws IOException, SAXException, ParserConfigurationException {
        new Main().generatePages(
                new String[] {"--source-root", HOMEPAGE + "/Site-source", "--target", HOMEPAGE + "/public_html", "--copyright", "Michel Fleur"});
    }
}
