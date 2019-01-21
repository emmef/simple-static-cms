package org.emmef.cms.util;

import com.google.common.collect.ImmutableList;
import lombok.NonNull;
import org.jsoup.nodes.Element;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public class ByElementNames implements Predicate<Element> {
    private final ImmutableList<String> names;
    private final Comparator<String> comp;

    public ByElementNames(@NonNull List<String> names, boolean caseSensitive) {
        for (String name : names) {
            if (name == null) {
                throw new IllegalArgumentException("Tag names cannot have null eleemnts");
            }
        }
        this.names = ImmutableList.copyOf(names);
        this.comp = caseSensitive ? Comparator.naturalOrder() : String.CASE_INSENSITIVE_ORDER;
    }

    @Override
    public boolean test(Element node) {
        return node != null && compare(node.nodeName());
    }

    private boolean compare(String elementName) {
        if (elementName == null) {
            return false;
        }
        for (String name : names) {
            if (comp.compare(name, elementName) == 0) {
                return true;
            }
        }
        return false;
    }
}
