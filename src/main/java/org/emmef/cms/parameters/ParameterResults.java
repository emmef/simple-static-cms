package org.emmef.cms.parameters;

import com.google.common.collect.ImmutableList;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.*;

@RequiredArgsConstructor
public class ParameterResults {
    private static final String FLAG_IS_TRUE = "true";

    private final Map<Parameter, List<String>> values = new TreeMap<>(Parameter.COMPARATOR);
    private final List<String> extraArguments = new ArrayList<>();

    @NonNull
    private ExtraArgumentStrategy extraArgumentStrategy;
    private Parameter lastFlag;

    public boolean isSet(Parameter key) {
        return values.containsKey(key);
    }

    public String getValue(@NonNull Parameter parameter) {
        if (parameter.getMaxValues() != 1) {
            throw new IllegalArgumentException(parameter + ": cannot get single value");
        }
        List<String> result = values.get(parameter);
        if (result != null) {
            return result.get(0);
        }
        if (!parameter.isOptional()) {
            throw new IllegalStateException(parameter + ": value was not set");
        }
        List<String> value = parameter.getDefaultValue();

        return value.isEmpty() ? null : value.get(0);
    }

    public List<String> getValues(@NonNull Parameter parameter) {
        if (parameter.getMaxValues() <= 1) {
            throw new IllegalArgumentException(parameter + ": cannot get multiple values");
        }
        List<String> result = values.get(parameter);
        if (result != null) {
            return result;
        }
        if (!parameter.isOptional()) {
            throw new IllegalStateException(parameter + ": value was not set");
        }
        List<String> value = parameter.getDefaultValue();

        return value.isEmpty() ? null : value;
    }

    public int getStrength(@NonNull Parameter parameter) {
        if (!parameter.isFlag()) {
            throw new IllegalArgumentException(parameter + ": cannot get strength for non-flag");
        }
        List<String> result = values.get(parameter);
        if (result == null) {
            return 0;
        }
        int strength = 0;
        for (String value : result) {
            if (value == FLAG_IS_TRUE) {
                strength++;
            }
        }
        return strength;
    }

    public List<String> getUnknownParameters() {
        return ImmutableList.copyOf(extraArguments);
    }

    ExtraArgumentStrategy setFlag(@NonNull Parameter flag, boolean value) {
        switch (extraArgumentStrategy) {
            case ALLOW_NONE:
            case ALLOW_EXTRA:
            case ALL_ARE_EXTRA:
                throw new IllegalArgumentException("setFlag : not allowed: " + flag);
            default:
                break;
        }
        if (!flag.isFlag()) {
            throw new IllegalStateException("setFlag: not a flag: " + flag);
        }
        addStrength(flag, value);
        lastFlag = flag;
        extraArgumentStrategy = flag.getExtraArgumentStrategy();
        return extraArgumentStrategy;
    }

    void setExtraValue(@NonNull String value) {
        switch (extraArgumentStrategy) {
            case ALLOW_PARAMS:
            case ALLOW_NONE:
                throw new IllegalArgumentException("setExtraValue: not allowed: " + value);
            default:
                extraArguments.add(value);
        }
    }

    void setParameterValue(@NonNull Parameter parameter, String value) {
        switch (extraArgumentStrategy) {
            case ALLOW_NONE:
            case ALLOW_EXTRA:
            case ALL_ARE_EXTRA:
                throw new IllegalArgumentException("setParameterValue: not allowed: " + parameter);
            default:
                break;
        }
        if (parameter.isFlag()) {
            throw new IllegalStateException("setParameterValue: flag is not allowed here: " + parameter);
        }
        addToList(parameter, value);
    }

    private void addStrength(@NonNull Parameter flag, boolean plus) {
        List<String> valueList = values.get(flag);
        if (valueList == null) {
            valueList = new ArrayList<>();
            valueList.add("1");
            values.put(flag, valueList);
            return;
        }
        int i = Integer.parseInt(valueList.get(0));
        valueList.set(0, Integer.toString(plus ? i + 1 : i - 1));
    }

    private void addToList(Parameter key, String value) {
        List<String> valueList = values.get(key);
        if (valueList == null) {
            valueList = new ArrayList<>();
            values.put(key, valueList);
        }
        valueList.add(value);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{Parameters=" + values.toString() + "; extra arguments=" + extraArguments + "}";
    }

    public ExtraArgumentStrategy getExtraArgumentStrategy() {
        return extraArgumentStrategy;
    }
}
