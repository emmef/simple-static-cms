package org.emmef.cms.main;

import lombok.extern.slf4j.Slf4j;
import org.emmef.cms.parameters.ExtraArgumentStrategy;
import org.emmef.cms.parameters.Parameter;
import org.emmef.cms.parameters.ParameterReader;
import org.emmef.cms.parameters.ParameterResults;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

@Slf4j
public class Main {
    public static final Parameter HELP = Parameter.flag("help");
    public static final Parameter SOURCE = Parameter.single("source-root").withDescription("Contains the sources to generate pages from").mandatory().withShorthand("S");
    public static final Parameter TARGET = Parameter.single("target").withDescription("The output directory of pages").mandatory().withShorthand("T");
    public static final Parameter COPYRIGHT = Parameter.single("copyright").withDescription("Copyright holder").withShorthand("C");
    public static final String HOMEPAGE = "/home/michel/net/emmef.org";

    ParameterReader parameterReader = new ParameterReader(ExtraArgumentStrategy.ALLOW_BOTH,
            HELP,
            SOURCE,
            TARGET,
            COPYRIGHT);

    public static void main(String arg[]) throws IOException {
        new Main().generatePages(arg);
    }

    protected void onParameterResults(ParameterResults results) {
        log.debug("{}", results);
    }

    public void generatePages(String[] arg) throws IOException {
        ParameterResults results = parameterReader.read(arg);

        onParameterResults(results);

        Path source = FileSystems.getDefault().getPath(results.getValue(SOURCE));
        Path target = FileSystems.getDefault().getPath(results.getValue(TARGET));
        String copyRight = results.getValue(COPYRIGHT);

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

        Pages.readSourceGenerateOutput(source, target, copyRight);
    }
}
