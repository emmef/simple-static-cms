package org.emmef.cms.parameters;


import lombok.Getter;
import lombok.RequiredArgsConstructor;


@RequiredArgsConstructor
public enum CommandLineArgumentType {
    NONE(false, false) {
        @Override
        public String read(String name, String[] commandLine) {
            return null;
        }
    },
    FLAG(false, false) {
        @Override
        public String read(String name, String[] commandLine) {
            for (String arg: commandLine) {
                if (startsWith(name, arg)) {
                    if (exactLength(name, arg)) {
                        return Boolean.TRUE.toString();
                    }
                    return Boolean.valueOf(value(name, arg)).toString();
                }
            }
            return null;
        }
    },
    OPTIONAL_PRESENT(false, true) {
        @Override
        public String read(String name, String[] commandLine) {
            return readOrNull(name, commandLine);
        }
    },
    MANDATORY_PRESENT(true, true) {
        @Override
        public String read(String name, String[] commandLine) {
            String s = readOrNull(name, commandLine);
            if (s != null) {
                return s;
            }
            throw new IllegalArgumentException("Missing mandatory command-line argument \"--" + name + "\"");
        }
    },
    MANDATORY_VALUE(true, true) {
        @Override
        public String read(String name, String[] commandLine) {
            return readOrNull(name, commandLine);
        }
    };

    private static String readOrNull(String name, String[] commandLine) {

        for (int i = 0; i < commandLine.length; i++) {
            String arg = commandLine[i];
            if (startsWith(name, arg)) {
                String value = value(name, arg);
                if (value != null && value.isEmpty()) {
                    return value;
                }
                break;
            }
        }
        return null;
    }

    private static boolean exactLength(String name, String arg) {
        return arg.length() == name.length() + 2;
    }

    private static String value(String name, String arg) {
        int nameLength = name.length();
        int argumentLength = arg.length();
        if (argumentLength <= nameLength + 2)  {
            return null;
        }
        if (arg.charAt(nameLength + 2) != '=') {
            return null;
        }
        if (argumentLength <= nameLength + 3) {
            return null;
        }
        return arg.substring(nameLength + 3);
    }

    private static boolean startsWith(String name, String arg) {
        return arg.startsWith("--") && arg.indexOf(name, 2) == 2;
    }

    @Getter
    private final boolean mandatory;
    @Getter
    private final boolean expectValue;

    public abstract String read(String name, String[] commandLine);
}
