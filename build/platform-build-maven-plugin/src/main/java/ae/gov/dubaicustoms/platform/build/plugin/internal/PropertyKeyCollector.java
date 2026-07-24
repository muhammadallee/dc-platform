package ae.gov.dubaicustoms.platform.build.plugin.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

// Collects the flat, dotted configuration keys a project actually sets, by scanning its
// application*.{yml,yaml,properties} resources. Deliberately dependency-free: a small
// indentation-based YAML key reader (no SnakeYAML on the plugin classpath) plus a .properties reader.
// It extracts KEYS only (not values), which is all the deprecation scan needs.
public final class PropertyKeyCollector {

    private PropertyKeyCollector() {
    }

    /** Keys set by every {@code application*.{yml,yaml,properties}} under {@code resourcesRoot}. */
    public static Set<String> collect(Path resourcesRoot) throws IOException {
        Set<String> keys = new TreeSet<>();
        if (resourcesRoot == null || !Files.isDirectory(resourcesRoot)) {
            return keys;
        }
        try (Stream<Path> tree = Files.walk(resourcesRoot)) {
            tree.filter(Files::isRegularFile)
                    .filter(PropertyKeyCollector::isConfigFile)
                    .forEach(file -> keys.addAll(keysOf(file)));
        }
        return keys;
    }

    private static boolean isConfigFile(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("application")
                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
    }

    private static Set<String> keysOf(Path file) {
        String name = file.getFileName().toString();
        try {
            List<String> lines = Files.readAllLines(file);
            return name.endsWith(".properties") ? propertiesKeys(lines) : yamlKeys(lines);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static Set<String> propertiesKeys(List<String> lines) {
        Set<String> keys = new TreeSet<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int split = indexOfKeyTerminator(line);
            String key = (split < 0 ? line : line.substring(0, split)).strip();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static int indexOfKeyTerminator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    // Indentation-driven: a "key:" (map node) is pushed with its indent; a "key: value" (leaf) is
    // emitted as the dotted path of the enclosing map stack + key. Sequence items and multi-doc
    // markers are ignored — good enough for the flat dc.platform.* keys upgrade-check looks for.
    private static Set<String> yamlKeys(List<String> lines) {
        Set<String> keys = new TreeSet<>();
        Deque<Indented> stack = new ArrayDeque<>();
        for (String raw : lines) {
            String noComment = stripComment(raw);
            if (noComment.strip().isEmpty() || noComment.strip().equals("---")
                    || noComment.strip().startsWith("-")) {
                continue;
            }
            int indent = indentOf(noComment);
            String content = noComment.strip();
            int colon = content.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = content.substring(0, colon).strip();
            if (key.isEmpty()) {
                continue;
            }
            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            String prefix = stack.isEmpty() ? "" : stack.peek().path + ".";
            String path = prefix + key;
            String value = content.substring(colon + 1).strip();
            if (value.isEmpty()) {
                stack.push(new Indented(indent, path));
            } else {
                keys.add(path);
            }
        }
        return keys;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private record Indented(int indent, String path) {
    }
}
