package org.emmef.cms.util;

import lombok.NonNull;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class NodeHelper {

    public static Iterable<Node> children(@NonNull Node node) {
        return () -> node.childNodes().iterator();
    }

    public static <T extends Node> Iterable<T> children(@NonNull Node node, Class<T> type) {
        return () -> IteratorWithTypePredicate.of(node.childNodes().iterator(), type);
    }

    public static void search(@NonNull Node node, @NonNull Predicate<Node> predicate, Consumer<Node> consumer) {

        node.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (predicate.test(node)) {
                    consumer.accept(node);
                }
            }

            @Override
            public void tail(Node node, int depth) {

            }
        });
    }

    public static <T extends Node> void search(@NonNull Node node, @NonNull Predicate<T> predicate, Class<T> c, Consumer<T> consumer) {

        node.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (c.isInstance(node)) {
                    T cast = c.cast(node);
                    if (predicate.test(c.cast(node))) {
                        consumer.accept(cast);
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {

            }
        });
    }

    public static Node searchFirst(@NonNull Node node, @NonNull Predicate<Element> predicate) {
        for (Node n : node.childNodes()) {
            if (n instanceof Element && predicate.test((Element)n)) {
                return n;
            }
        }
        return null;
    }

    public static Predicate<Element> elementByNameCaseInsensitive(@NonNull String name) {
        return new ByElementName(name, false);
    }

}
