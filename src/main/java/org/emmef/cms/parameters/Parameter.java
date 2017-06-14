package org.emmef.cms.parameters;


import com.google.common.collect.ImmutableList;
import lombok.*;
import org.emmef.cms.util.NameComparator;
import org.emmef.cms.util.Unwrap;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@Getter
public class Parameter {
    private static final Pattern NAME_PATTERN = Pattern.compile("^[0-9A-Z][-_0-9A-Z\\.~]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORTHAND_PATTERN = Pattern.compile("^[0-9A-Z]{1,3}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROPERTY_PATTERN = NAME_PATTERN;
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("^[_0-9A-Z]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPLIT_PATTERN = Pattern.compile("\\p{Space}");

    public static final String FLAG_IS_TRUE = Boolean.TRUE.toString();
    public static final Comparator<Parameter> COMPARATOR = (Parameter o1, Parameter o2) -> Unwrap.compareTo(o1, o2, NameComparator::compareNonNull, (o) -> o.getName() );

    @NonNull
    private final String name;
    private final String description;
    private final int minValues;
    private final int maxValues;
    private final boolean optional;
    private final List<String> defaultValue;
    private final String shortHand;
    private final String nameKey;
    private final String shorthandKey;
    private final String systemProperty;
    private final String environmentVariable;
    private final ExtraArgumentStrategy extraArgumentStrategy;

    public static Parameter flag(@NonNull String name) {
        return new Parameter(name, null, 0, 0, true, null, null, null, null, ExtraArgumentStrategy.ALLOW_BOTH);
    }

    public static Parameter single(@NonNull String name) {
        return new Parameter(name, null, 1, 1, true, null, null, null, null, ExtraArgumentStrategy.ALLOW_BOTH);
    }

    public static Parameter multiple(@NonNull String name, int minValues, int maxValues) {
        if (minValues < 1) {
            throw new IllegalArgumentException("Need at least one value. By default arguments are optional and always accept zero values.");
        }
        if (maxValues < minValues) {
            throw new IllegalArgumentException("Maximum number of values must be bigger than minimum number of values.");
        }
        return new Parameter(name, null, minValues, maxValues, true, null, null, null, null, ExtraArgumentStrategy.ALLOW_BOTH);
    }

    public Parameter withShorthand(String shorthand) {
        return new Parameter(name, description, minValues, maxValues, optional, defaultValue, shorthand, systemProperty, environmentVariable, extraArgumentStrategy);
    }

    public Parameter mandatory() {
        if (maxValues == 0) {
            throw new IllegalStateException("Cannot have mandatory parameter without values: " + name);
        }
        if (!defaultValue.isEmpty()) {
            throw new IllegalStateException("Cannot have mandatory parameter with default value: " + name);
        }
        return new Parameter(name, description, minValues, maxValues, false, defaultValue, shortHand, systemProperty, environmentVariable, extraArgumentStrategy);
    }

    public Parameter withDefault(String value) {
        return withDefaults(value);
    }

    public Parameter withDefaults(String... values) {
        checkOptionalsAndMultiplicity();
        if (values == null) {
            throw new IllegalStateException("Cannot have null default for parameter: " + name);
        }
        checkDefaultsCount(values.length);
        return new Parameter(name, description, minValues, maxValues, optional, ImmutableList.copyOf(values), shortHand, systemProperty, environmentVariable, extraArgumentStrategy);
    }

    public Parameter withDefaults(@NonNull Collection<String> value) {
        checkOptionalsAndMultiplicity();
        if (value == null) {
            throw new IllegalStateException("Cannot have null default for parameter: " + name);
        }
        checkDefaultsCount(value.size());
        return new Parameter(name, description, minValues, maxValues, optional, ImmutableList.copyOf(value), shortHand, systemProperty, environmentVariable, extraArgumentStrategy);
    }

    public Parameter withSystemProperty(String property) {
        return new Parameter(name, description, minValues, maxValues, optional, defaultValue, shortHand, property != null ? property : name, environmentVariable, extraArgumentStrategy);
    }

    public Parameter withEnvironmentVariable(String variable) {
        return new Parameter(name, description, minValues, maxValues, optional, defaultValue, shortHand, systemProperty, variable != null ? variable : transformToVariable(name), extraArgumentStrategy);
    }

    public Parameter withDescription(String description) {
        return new Parameter(name, description, minValues, maxValues, optional, defaultValue, shortHand, systemProperty, environmentVariable, extraArgumentStrategy);
    }

    public Parameter withExtraArgumentStrategy(@NonNull ExtraArgumentStrategy strategy) {
        if (isFlag()) {
            return new Parameter(name, description, minValues, maxValues, optional, defaultValue, shortHand, systemProperty, environmentVariable, strategy);
        }
        throw new IllegalStateException("Cannot of extra argument stratgey for non flag: " + name);
    }

    public boolean isFlag() {
        return maxValues == 0;
    }

    public String displayName() {
        return getNameKey();
    }

    public String getSystemPropertyDisplayName() {
        return "System property " + (systemProperty != null ? systemProperty : "<none>");
    }

    public String getEnvironmentVariableDisplayName() {
        return "Environment variable " + (environmentVariable != null ? environmentVariable : "<none>");
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();

        builder.append(getClass().getSimpleName()).append("{name=");
        builder.append(name);
        builder.append("; type=").append(maxValues == 0 ? "flag" : maxValues == 1 ? "single" : "multiple");

        builder.append("; command-line-argument=").append(nameKey);
        if (shortHand != null) {
            builder.append("|").append(shorthandKey);
        }
        if (systemProperty != null) {
            builder.append("; system-property=").append(systemProperty);
        }
        if (environmentVariable != null) {
            builder.append("; environment-variable=").append(environmentVariable);
        }
        if (maxValues > 0) {
            builder.append("; optional=").append(optional);
            if (maxValues > 1) {
                builder.append("; multiplicity=").append(minValues).append("..").append(maxValues);
            }
        }
        if (defaultValue != null) {
            builder.append("; default=").append(defaultValue);
        }

        builder.append("}");

        return builder.toString();
    }

    private Parameter(String name, String description, int minValues, int maxValues, boolean optional, List<String> defaultValue, String shortHand, String systemProperty, String environmentVariable, ExtraArgumentStrategy extraArgumentStrategy) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid name for argument: must match " + NAME_PATTERN.pattern());
        }
        this.name = name;
        this.description = description;
        this.minValues = minValues;
        this.maxValues = maxValues;
        this.optional = optional;
        this.defaultValue = defaultValue != null ? ImmutableList.copyOf(defaultValue) : ImmutableList.of();
        if (shortHand != null && !SHORTHAND_PATTERN.matcher(shortHand).matches()) {
            throw new IllegalArgumentException("Invalid shorthand for argument: must match " + SHORTHAND_PATTERN.pattern());
        }
        this.shortHand = shortHand;
        this.nameKey = "--" + name;
        this.shorthandKey = shortHand != null ? "-" + shortHand : null;
        if (systemProperty != null && !PROPERTY_PATTERN.matcher(systemProperty).matches()) {
            throw new IllegalArgumentException("Invalid system property name: must match " + PROPERTY_PATTERN.pattern());
        }
        this.systemProperty = systemProperty;
        if (systemProperty != null && !PROPERTY_PATTERN.matcher(systemProperty).matches()) {
            throw new IllegalArgumentException("Invalid system property name: must match " + PROPERTY_PATTERN.pattern());
        }
        this.environmentVariable = environmentVariable;
        this.extraArgumentStrategy = extraArgumentStrategy;
    }

    private static List<String> createDefault(String name, String defaultValue, int minValues, int maxValues) {
        if (maxValues == 0 || defaultValue == null) {
            return null;
        }
        if (maxValues == 1) {
            return ImmutableList.of(defaultValue);
        }
        String[] split = defaultValue.split(" ", maxValues);
        if (split.length >= minValues) {
            return ImmutableList.copyOf(split);
        }
        throw new IllegalArgumentException(name + ": invalid default (too few values, should be at least " + minValues + "): " + defaultValue);
    }

    private String transformToVariable(String name) {
        return name.replaceAll("[^0-9A-Za-z_]", "_");
    }

    private void checkOptionalsAndMultiplicity() {
        if (!optional) {
            throw new IllegalStateException("Cannot have mandatory parameter with default value: " + name);
        }
        if (maxValues == 0) {
            throw new IllegalStateException("Cannot have default for parameter without values: " + name);
        }
    }

    private void checkDefaultsCount(int length) {
        if (length < minValues) {
            throw new IllegalStateException("Not enough defaults for parameter (at least " + minValues + " expected): " + name);
        }
        if (length > maxValues) {
            throw new IllegalStateException("Too many defaults for parameter (at most " + maxValues + " expected): " + name);
        }
    }

    public ExtraArgumentStrategy getExtraArgumentStrategy() {
        return extraArgumentStrategy;
    }
}
