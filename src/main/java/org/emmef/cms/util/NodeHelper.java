package org.emmef.cms.util;

import lombok.NonNull;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

public class NodeHelper {

    public static Iterable<Node> children(@NonNull Node node) {
        return () -> getNodeIterator(node);
    }

    public static Iterable<Node> search(@NonNull Node node, @NonNull Predicate<Node> predicate) {
        return () -> new IteratorWithPredicate<Node>(getNodeIterator(node), predicate);
    }

    public static <T extends Node> Iterable<T> search(@NonNull Node node, @NonNull Predicate<Node> predicate, Class<T> c) {
        return () -> IteratorWithTypePredicate.of(new IteratorWithPredicate<Node>(getNodeIterator(node), predicate), c);
    }

    public static Node searchFirst(@NonNull Node node, @NonNull Predicate<Node> predicate) {
        NodeList nodes = node.getChildNodes();
        int count = nodes.getLength();
        for (int i = 0; i < count; i++) {
            Node n = nodes.item(i);
            if (predicate.test(n)) {
                return n;
            }
        }
        return null;
    }

    public static Predicate<Node> elementByName(@NonNull String name) {
        return new ByElementName(name, true);
    }

    public static Predicate<Node> elementByNameCaseInsensitive(@NonNull String name) {
        return new ByElementName(name, false);
    }

    static Iterator<Node> getNodeIterator(Node node) {
        return new Iterator<Node>() {
            private final NodeList nodes = node.getChildNodes();
            private int i = 0;

            @Override
            public boolean hasNext() {
                return i < nodes.getLength();
            }

            @Override
            public Node next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return nodes.item(i++);
            }
        };
    }
}
