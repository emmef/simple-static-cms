package org.emmef.cms.parameters;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import lombok.NonNull;
import org.emmef.cms.util.Environment;

import java.util.*;

public class ParameterReader {
    private final Map<String, Parameter> lookupArgumentsMap;
    private final Set<Parameter> parameters;
    private final ExtraArgumentStrategy extraArgumentStrategy;

    public ParameterReader(@NonNull ExtraArgumentStrategy extraArgumentStrategy, @NonNull Collection<Parameter> parameters) {
        if (parameters.isEmpty()) {
            throw new IllegalArgumentException("Need at least one parameter");
        }

        ImmutableSortedSet.Builder<Parameter> parameterBuilder = ImmutableSortedSet.orderedBy(Parameter.COMPARATOR);
        for (Parameter parameter : parameters) {
            if (parameter == null) {
                throw new IllegalArgumentException("Parameter cannot be null");
            }
            parameterBuilder.add(parameter);
        }

        this.parameters = parameterBuilder.build();

        ImmutableMap.Builder<String,Parameter> mapBuilder = ImmutableMap.builder();

        for (Parameter parameter : parameters) {
            mapBuilder.put(parameter.getNameKey(), parameter);
            if (parameter.getShortHand() != null) {
                mapBuilder.put(parameter.getShorthandKey(), parameter);
            }
        }

        this.lookupArgumentsMap = mapBuilder.build();
        this.extraArgumentStrategy = extraArgumentStrategy;
    }

    public ParameterReader(ExtraArgumentStrategy extraArgumentStrategy, @NonNull Parameter... parameters) {
        this(extraArgumentStrategy, Arrays.asList(parameters));
    }

    public ParameterResults read(@NonNull String[] commandLine) {
        return read(commandLine, Environment.SYSTEM_PROPERTIES, Environment.SYSTEM_ENVIRONMENT);
    }

    public ParameterResults read(@NonNull String[] commandLine, @NonNull Environment systemProperties, @NonNull Environment environmentVariables) {
        ParameterResults results = createResults();

        readFromCommandLine(results, commandLine);
        readFromSystemProperties(results, systemProperties);
        readFromEnvironment(results, environmentVariables);
        substituteDefaults(results);

        return verifyMandatory(results);
    }

    public ParameterResults createResults() {
        return new ParameterResults(extraArgumentStrategy);
    }

    public ParameterResults readFromCommandLine(@NonNull ParameterResults result, @NonNull String[] commandLine) {
        ExtraArgumentStrategy strategy = result.getExtraArgumentStrategy();

        for (int i = 0; i < commandLine.length; i++) {
            String arg = commandLine[i];
            int assignment = arg.indexOf('=');
            String key = assignment == -1 ? arg : arg.substring(0, assignment);
            Parameter param = lookupArgumentsMap.get(key);
            if (param == null || strategy == ExtraArgumentStrategy.ALL_ARE_EXTRA) {
                result.setExtraValue(arg);
                continue;
            }
            if (param.isFlag()) {
                if (assignment == -1 || Boolean.TRUE.toString().equalsIgnoreCase(arg.substring(assignment + 1))) {
                    strategy = result.setFlag(param, true);
                }
                else if (Boolean.FALSE.toString().equalsIgnoreCase(arg.substring(assignment + 1))) {
                    strategy = result.setFlag(param, false);
                }
                else {
                    throw new IllegalArgumentException(param.getNameKey() + ": unexpected value: expects no value, true or false");
                }
                continue;
            }
            // As the parameter is not a flag, parameter values are required.
            // As lookupArgumentsMap have fixed multiplicity, each parameter is only allowed to be specified once.
            if (result.isSet(param)) {
                throw new IllegalArgumentException(param.getNameKey() + ": can only be specified once");
            }

            // Single value parameter
            if (param.getMaxValues() == 1) {
                if (assignment == -1) {
                    if (commandLine.length < i + 1) {
                        throw new IllegalArgumentException(param.getNameKey() + ": value expected, but none given");
                    }
                    else {
                        result.setParameterValue(param, commandLine[i + 1]);
                        i++;
                    }
                }
                else {
                    // we allow empty value here (assignment is last character)
                    result.setParameterValue(param, arg.substring(assignment + 1));
                }

                continue;
            }

            // Mulivalues
            if (assignment != -1) {
                // Expect single assigned value to contain whitespace separated values
                String value = arg.substring(assignment + 1);
                addMultipleFromSingle(result, param, value, NameAndDisplay.COMMANDLINE_ARGUMENT);

                continue;
            }

            int position = i + 1;
            int maxValues = param.getMaxValues();
            int valueCount = getValueCount(commandLine, position, maxValues);
            if (valueCount >= param.getMinValues()) {
                for (int j = 0, pos = i + 1; j < valueCount; pos++, j++) {
                    result.setParameterValue(param, commandLine[pos]);
                }
                i += valueCount;
            }
            else {
                throw new IllegalArgumentException(param.getNameKey() + ": too few values: " + param);
            }
        }

        return result;
    }

    public ParameterResults readFromEnvironment(@NonNull ParameterResults result, @NonNull Environment environment) {
        return readFromGeneric(result, environment, NameAndDisplay.ENVIRONMENT_VARIABLE);
    }

    public ParameterResults readFromSystemProperties(@NonNull ParameterResults result, @NonNull Environment systemProperties) {
        return readFromGeneric(result, systemProperties, NameAndDisplay.SYSTEM_PROPERTY);
    }

    public ParameterResults substituteDefaults(@NonNull ParameterResults result) {
        for (Parameter parameter : parameters) {
            if (parameter.isFlag()) {
                continue;
            }
            if (result.isSet(parameter)) {
                continue;
            }
            for (String value : parameter.getDefaultValue()) {
                result.setParameterValue(parameter, value);
            }
        }

        return result;
    }

    public ParameterResults verifyMandatory(@NonNull ParameterResults results) {
        Set<Parameter> parameters = getMissingParameters(results);
        if (parameters.isEmpty()) {
            return results;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Missing parameters:");
        for (Parameter parameter : parameters) {
            msg.append("\n - ").append(parameter);
        }
        throw new IllegalArgumentException(msg.toString());
    }

    public Set<Parameter> getMissingParameters(@NonNull ParameterResults results) {
        SortedSet<Parameter> parameters = new TreeSet<>((o1,o2)-> o1.getName().compareTo(o2.getName()));
        for (Parameter parameter : parameters) {
            if (!(parameter.isOptional() || results.isSet(parameter))) {
                parameters.add(parameter);
            }
        }
        return parameters;
    }

    private ParameterResults readFromGeneric(@NonNull ParameterResults result, @NonNull Environment properties, @NonNull NameAndDisplay nameAndDisplay) {
        for (Parameter parameter : parameters) {
            String name = nameAndDisplay.getName(parameter);
            if (name == null) {
                continue;
            }

            String value = properties.get(name);
            if (value == null) {
                continue;
            }

            if (parameter.isFlag()) {
                if (value.isEmpty() || Boolean.TRUE.toString().equalsIgnoreCase(value)) {
                    result.setFlag(parameter, true);
                }
                else {
                    result.setFlag(parameter, false);
                }
                continue;
            }

            if (result.isSet(parameter)) {
                // don't overwrite already set values!
                continue;
            }

            if (parameter.getMaxValues() == 1) {
                result.setParameterValue(parameter, value);

                continue;
            }

            addMultipleFromSingle(result, parameter, value, nameAndDisplay);
        }

        return result;
    }

    private void addMultipleFromSingle(@NonNull ParameterResults result, Parameter param, String value, NameAndDisplay nameAndDisplay) {
        String[] split = value.split(" ", param.getMaxValues());
        if (split.length < param.getMinValues()) {
            throw new IllegalArgumentException(nameAndDisplay.getDisplay(param) + ": expected at least " + param.getMinValues() + " argments but got " + split.length);
        }
        for (String v : split) {
            result.setParameterValue(param, v);
        }
    }

    private int getValueCount(@NonNull String[] commandLine, int position, int maxValues) {
        int valueCount = 0;
        while (position < commandLine.length && valueCount < maxValues) {
            String value = commandLine[position];
            if (isParameter(value)) {
                break;
            }
            position++;
            valueCount++;
        }
        return valueCount;
    }

    private boolean isParameter(String argument) {
        if (argument.charAt(0) != '-') {
            return false;
        }
        int assignment = argument.indexOf('=');
        return assignment == -1 ?
                lookupArgumentsMap.containsKey(argument) :
                lookupArgumentsMap.containsKey(argument.substring(0,assignment));
    }

    interface NameAndDisplay {
        String getName(Parameter parameter);
        String getDisplay(Parameter parameter);

        NameAndDisplay SYSTEM_PROPERTY = new NameAndDisplay() {
            @Override
            public String getName(Parameter parameter) {
                return parameter.getSystemProperty();
            }

            @Override
            public String getDisplay(Parameter parameter) {
                String name = getName(parameter);
                return name != null ? "System property " + name : "";
            }
        };

        NameAndDisplay ENVIRONMENT_VARIABLE = new NameAndDisplay() {
            @Override
            public String getName(Parameter parameter) {
                return parameter.getEnvironmentVariable();
            }

            @Override
            public String getDisplay(Parameter parameter) {
                String name = getName(parameter);
                return name != null ? "Environment variable " + name : "";
            }
        };

        NameAndDisplay COMMANDLINE_ARGUMENT = new NameAndDisplay() {
            @Override
            public String getName(Parameter parameter) {
                return parameter.getName();
            }

            @Override
            public String getDisplay(Parameter parameter) {
                return "Commandline argument " + parameter.getNameKey();
            }
        };
    }
}
