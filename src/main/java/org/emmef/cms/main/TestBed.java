package org.emmef.cms.main;

import org.emmef.cms.page.PageRecord;
import org.emmef.cms.parameters.*;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public class TestBed {
    public static final Parameter HELP = Parameter.flag("help");
    public static final Parameter SOURCE = Parameter.single("source-root").withDescription("Contains the sources to generate pages from").mandatory().withShorthand("S");
    public static final Parameter TARGET = Parameter.single("target").withDescription("The output directory of pages").mandatory().withShorthand("T");

    public static void main(String arg[]) throws IOException, SAXException, ParserConfigurationException {

        ParameterReader parameterReader = new ParameterReader(ExtraArgumentStrategy.ALLOW_BOTH,
                HELP,
                SOURCE,
                TARGET);

        ParameterResults results = parameterReader.read(
                new String[] {"--source-root", "./src/main/resources", "--target", "/tmp/zaka"});

        System.out.println(results);

        TestBed testBed = new TestBed();

        Path source = FileSystems.getDefault().getPath(results.getValue(SOURCE));
        Path target = FileSystems.getDefault().getPath(results.getValue(TARGET));

        if (!Files.exists(source) || !Files.isDirectory(source)) {
            throw new IllegalArgumentException("Source directory not exist: " + source.toString());
        }
        if (Files.exists(target)) {
            if (!Files.isDirectory(target) || !Files.isWritable(target)) {
                throw new IllegalArgumentException("Target must be a writable directory: " + source.toString());
            }
        }
        else {
            Files.createDirectory(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        }

        Pages pages = Pages.readFrom(source, target);
    }
}
