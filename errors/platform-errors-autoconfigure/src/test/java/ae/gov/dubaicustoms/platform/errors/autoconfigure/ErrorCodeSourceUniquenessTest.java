package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Build-time gate on platform error codes across EVERY platform module (phase-01 scope R3.7, decision D92; carried by
 * Spring-native Phase 2, rows C24 and R2). A platform error code is a whole {@code DC-<CAP>-<NNNN>} string, set as the
 * {@code code} property of a {@code ProblemDetail} (D138). The gate parses the production sources of the whole
 * repository with the JDK's own parser and fails closed:
 * <ol>
 *   <li><b>Literals:</b> every string literal that is {@code "DC"} or starts with {@code "DC-"} or {@code "DC_"} must be
 *       a whole code; each code value is declared once. {@code "DC".concat(…)} and {@code "DC-MSG-" + n} fail here.</li>
 *   <li><b>Every code-bearing entry point</b> must receive a value that resolves to whole codes: {@code setProperty}
 *       with a key that resolves to {@code "code"}; {@code factory.create} and {@code factory.decorate} (argument 1);
 *       the handler's {@code respond} (argument 1); {@code DataAccessFailure}'s constants (argument 1). A value
 *       resolves through string literals, conditional expressions, {@code static final String} constants with a literal
 *       initialiser (same file first, then by unique name), and local variables initialised from those. Anything else
 *       is reported, unless it is one of the audited forwarding paths in {@link #FORWARDING}, whose upstream is checked
 *       here.</li>
 *   <li>A minimum count stops an empty scan from passing.</li>
 * </ol>
 * Consumer examples and the archetype are excluded (separate services, with codes of their own).
 */
class ErrorCodeSourceUniquenessTest {

    private static final Pattern FORMAT = Pattern.compile("^DC-[A-Z]{2,8}-\\d{4}$");
    private static final Pattern CODE_LIKE = Pattern.compile("^DC($|[-_].*)", Pattern.DOTALL);

    /** The platform's codes at Phase 2: errors (7), messaging (2), the parked REST client (1). */
    private static final int MINIMUM_CODES = 10;

    /**
     * Audited forwarding paths, by file and source text of the argument. Each passes on a code that was checked where
     * it entered the platform.
     */
    private static final Set<String> FORWARDING = Set.of(
            // decorate() writes the code it selected: the thrower's String code, or the defaultCode its callers pass
            // (checked at each factory.decorate / factory.create call site below).
            "ProblemDetailFactory.java|code",
            // create() forwards its code parameter to decorate().
            "ProblemDetailFactory.java|code#decorate",
            // respond() forwards its code parameter to factory.create(); every respond(...) call site is checked.
            "PlatformExceptionHandler.java|code#create",
            // A DataAccessFailure's code is one of its constants, each checked at the enum constant.
            "PlatformExceptionHandler.java|failure.code()",
            // copyOf() copies a body's existing properties; a code among them was checked where it was set.
            "ProblemDetailFactory.java|deepCopy(value)");

    record Declaration(String code, String file) {
    }

    record Scan(List<Declaration> declarations, List<String> unsupported) {
    }

    @Test
    void everyPlatformErrorCodeIsWholeDeclaredOnceAndReachesEveryEntryPointAsAConstant() throws IOException {
        Scan scan = scan(platformSources(repositoryRoot()));

        assertThat(scan.unsupported()).as("an error code must be one whole DC-<CAP>-<NNNN> constant").isEmpty();
        // A near-empty result means the gate stopped looking, not that the platform has no codes.
        assertThat(scan.declarations()).hasSizeGreaterThanOrEqualTo(MINIMUM_CODES);
        assertThat(scan.declarations()).extracting(Declaration::code)
                .contains("DC-CORE-0500", "DC-CORE-0400", "DC-CORE-0410", "DC-CORE-0499",
                        "DC-DATA-0409", "DC-DATA-0410", "DC-DATA-0503", "DC-MSG-0001", "DC-RCLIENT-0500");
        assertThat(duplicates(scan)).as("error codes are contracts and must be unique platform-wide").isEmpty();
    }

    @Test
    void theScannerSeesEveryFormattingAndRejectsEveryUncheckedCode() {
        Scan scan = scan(List.of(
                source("A", """
                        class A {
                            static final String SPLIT_OVER_LINES =
                                    "DC-TST-0001";
                            static final String KEY = "code";
                            // "DC-TST-9999" in a comment is not a declaration
                            String text = "the code DC-TST-8888 inside a sentence is not one either";
                            void ok(org.springframework.http.ProblemDetail problem, boolean flag) {
                                problem.setProperty("code", SPLIT_OVER_LINES);
                                String chosen = flag ? SPLIT_OVER_LINES : "DC-TST-0003";
                                factory.create(500, chosen, null, null, null, null);
                            }
                            void reassigned(org.springframework.http.ProblemDetail problem) {
                                String later = SPLIT_OVER_LINES;
                                later = lookupCode();
                                problem.setProperty("code", later);
                            }
                            ProblemDetailFactory held;
                            void qualified() {
                                this.held.create(500, lookupLater(), null, null, null, null);
                                (this.held).create(500, lookupParenthesised(), null, null, null, null);
                            }
                            static final String shadowedLater = "DC-TST-0005";
                            void shadowed(org.springframework.http.ProblemDetail problem) {
                                String shadowedLater = "NOT-A-CODE";
                                shadowedLater = lookupShadowed();
                                problem.setProperty("code", shadowedLater);
                            }
                            void reassignedKey(org.springframework.http.ProblemDetail problem) {
                                String key = "code";
                                key = "code";
                                problem.setProperty(key, lookupKeyed());
                            }
                            void otherObject(java.util.Properties properties) {
                                properties.setProperty("code", lookupIgnored());
                            }
                            void chained() {
                                org.springframework.http.ProblemDetail.forStatus(500).setProperty("code", "CHAINED");
                            }
                            void broken(org.springframework.http.ProblemDetail problem) {
                                problem.setProperty(KEY, "BROKEN");
                                factory.create(500, lookupCode(), null, null, null, null);
                                factory.decorate(problem, "DC".concat("-TST-0004"), null, null);
                            }
                        }
                        """),
                source("B", """
                        class B {
                            static final String ESCAPED = "DC-TST-\\u0030001";
                            static final String FROM_CONSTANT = PREFIX + "0002";
                            static final String PREFIX = "DC-TST-";
                        }
                        """),
                source("C", """
                        class C {
                            static final String SHORT = "DC-TST-12";
                        }
                        """)));

        assertThat(scan.declarations()).extracting(Declaration::code)
                .as("multi-line, a conditional's literal, and a unicode escape resolving to the same runtime value")
                .containsExactlyInAnyOrder("DC-TST-0001", "DC-TST-0003", "DC-TST-0005", "DC-TST-0001");
        assertThat(duplicates(scan)).containsOnlyKeys("DC-TST-0001");
        assertThat(scan.unsupported())
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("BROKEN"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("lookupCode()"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("code later "))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("lookupLater()"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("lookupParenthesised()"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("code shadowedLater "))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("lookupKeyed()"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("CHAINED"))
                .noneSatisfy(entry -> assertThat(entry).contains("lookupIgnored()"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("\"DC\""))
                .anySatisfy(entry -> assertThat(entry).contains("B.java").contains("\"DC-TST-\""))
                .anySatisfy(entry -> assertThat(entry).contains("C.java").contains("DC-TST-12"))
                .noneSatisfy(entry -> assertThat(entry).contains("SPLIT_OVER_LINES"))
                .noneSatisfy(entry -> assertThat(entry).contains("chosen"));
    }

    private static Map<String, List<String>> duplicates(Scan scan) {
        return scan.declarations().stream()
                .collect(Collectors.groupingBy(Declaration::code, Collectors.mapping(Declaration::file, Collectors.toList())))
                .entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Scan scan(List<JavaFileObject> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> {
        }, List.of("-proc:none"), null, sources);
        List<CompilationUnitTree> units = new ArrayList<>();
        try {
            task.parse().forEach(units::add);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Constants constants = Constants.of(units);
        Set<String> factoryNames = namesDeclaredAs(units, "ProblemDetailFactory");
        factoryNames.add("factory");
        Set<String> problemNames = namesDeclaredAs(units, "ProblemDetail");
        Set<String> otherNames = namesDeclaredOtherThan(units, "ProblemDetail");
        List<Declaration> declarations = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (CompilationUnitTree unit : units) {
            String file = fileName(unit);
            new TreeScanner<Void, Void>() {

                // Local names of the current method (parameters included). A null value means "not known
                // statically" (reassigned, no initialiser, a parameter): such a name never falls back to a constant.
                private Map<String, ExpressionTree> locals = new HashMap<>();
                private Set<String> reassigned = new java.util.HashSet<>();

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    Map<String, ExpressionTree> outer = locals;
                    Set<String> outerReassigned = reassigned;
                    locals = new HashMap<>();
                    reassigned = assignedNames(node);
                    try {
                        return super.visitMethod(node, unused);
                    } finally {
                        locals = outer;
                        reassigned = outerReassigned;
                    }
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    String name = node.getName().toString();
                    boolean known = node.getInitializer() != null && !reassigned.contains(name);
                    locals.put(name, known ? node.getInitializer() : null);
                    return super.visitVariable(node, unused);
                }

                @Override
                public Void visitLiteral(LiteralTree node, Void unused) {
                    if (node.getValue() instanceof String text && CODE_LIKE.matcher(text).matches()) {
                        if (FORMAT.matcher(text).matches()) {
                            declarations.add(new Declaration(text, file));
                        } else {
                            unsupported.add(file + ": \"" + text + "\" is not a whole code");
                        }
                    }
                    return super.visitLiteral(node, unused);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    String name = methodName(node);
                    List<? extends ExpressionTree> args = node.getArguments();
                    if (name.equals("setProperty") && args.size() == 2 && onProblemDetail(node, problemNames, otherNames)
                            && mayBeCodeKey(resolve(args.get(0), file, constants, locals))) {
                        check(args.get(1), file, "", constants, locals, unsupported);
                    } else if ((name.equals("create") || name.equals("decorate")) && args.size() >= 2
                            && isFactoryCall(node, factoryNames)) {
                        check(args.get(1), file, "#" + name, constants, locals, unsupported);
                    } else if (name.equals("respond") && args.size() >= 2) {
                        check(args.get(1), file, "#respond", constants, locals, unsupported);
                    }
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    if (node.getIdentifier().toString().equals("DataAccessFailure") && node.getArguments().size() >= 2) {
                        check(node.getArguments().get(1), file, "#DataAccessFailure", constants, locals, unsupported);
                    }
                    return super.visitNewClass(node, unused);
                }
            }.scan(unit, null);
        }
        return new Scan(declarations, unsupported);
    }

    // A call on anything declared as a ProblemDetailFactory (field, parameter, local), however qualified or
    // parenthesised (factory.create, this.factory.create, (this.held).create), or decorate() called by the factory itself.
    private static boolean isFactoryCall(MethodInvocationTree node, Set<String> factoryNames) {
        ExpressionTree select = node.getMethodSelect();
        if (select instanceof MemberSelectTree member) {
            String last = lastName(member.getExpression());
            return factoryNames.contains(last) || last.equals("ProblemDetailFactory");
        }
        return select instanceof IdentifierTree;
    }

    // Conservative: every setProperty counts unless its receiver is a name declared only with some other type
    // (a java.util.Properties, say). Method-call receivers (ProblemDetail.forStatus(500), getBody()) always count.
    private static boolean onProblemDetail(MethodInvocationTree node, Set<String> problemNames, Set<String> otherNames) {
        if (!(node.getMethodSelect() instanceof MemberSelectTree member)) {
            return true;
        }
        ExpressionTree receiver = unwrap(member.getExpression());
        if (receiver instanceof MethodInvocationTree) {
            return true;
        }
        String name = lastName(receiver);
        return problemNames.contains(name) || !otherNames.contains(name);
    }

    // A key whose values are all known and none is "code" is not a code key; anything else may be.
    private static boolean mayBeCodeKey(Set<String> keys) {
        return keys.isEmpty() || keys.contains("code");
    }

    private static ExpressionTree unwrap(ExpressionTree expression) {
        ExpressionTree current = expression;
        while (current instanceof ParenthesizedTree parenthesized) {
            current = parenthesized.getExpression();
        }
        return current;
    }

    private static String lastName(ExpressionTree expression) {
        ExpressionTree receiver = unwrap(expression);
        return receiver instanceof MemberSelectTree qualified ? qualified.getIdentifier().toString()
                : receiver instanceof IdentifierTree identifier ? identifier.getName().toString() : "";
    }

    // Every name declared with the given simple type (or a qualified name ending in it) in the scanned sources.
    private static Set<String> namesDeclaredAs(List<CompilationUnitTree> units, String simpleType) {
        Set<String> names = new java.util.HashSet<>();
        for (CompilationUnitTree unit : units) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    String type = node.getType() == null ? "" : node.getType().toString();
                    if (type.equals(simpleType) || type.endsWith("." + simpleType)) {
                        names.add(node.getName().toString());
                    }
                    return super.visitVariable(node, unused);
                }
            }.scan(unit, null);
        }
        return names;
    }

    // Every name declared with a type other than the given simple type.
    private static Set<String> namesDeclaredOtherThan(List<CompilationUnitTree> units, String simpleType) {
        Set<String> names = new java.util.HashSet<>();
        for (CompilationUnitTree unit : units) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    String type = node.getType() == null ? "" : node.getType().toString();
                    if (!type.isEmpty() && !type.equals(simpleType) && !type.endsWith("." + simpleType)) {
                        names.add(node.getName().toString());
                    }
                    return super.visitVariable(node, unused);
                }
            }.scan(unit, null);
        }
        return names;
    }

    // Names assigned anywhere in a method after their declaration (x = ..., x += ...).
    private static Set<String> assignedNames(MethodTree method) {
        Set<String> names = new java.util.HashSet<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitAssignment(com.sun.source.tree.AssignmentTree node, Void unused) {
                if (node.getVariable() instanceof IdentifierTree identifier) {
                    names.add(identifier.getName().toString());
                }
                return super.visitAssignment(node, unused);
            }

            @Override
            public Void visitCompoundAssignment(com.sun.source.tree.CompoundAssignmentTree node, Void unused) {
                if (node.getVariable() instanceof IdentifierTree identifier) {
                    names.add(identifier.getName().toString());
                }
                return super.visitCompoundAssignment(node, unused);
            }
        }.scan(method, null);
        return names;
    }

    private static String methodName(MethodInvocationTree node) {
        ExpressionTree select = node.getMethodSelect();
        if (select instanceof MemberSelectTree member) {
            return member.getIdentifier().toString();
        }
        return select instanceof IdentifierTree identifier ? identifier.getName().toString() : "";
    }

    private static void check(ExpressionTree value, String file, String site, Constants constants,
            Map<String, ExpressionTree> locals, List<String> unsupported) {
        String text = value.toString();
        if (FORWARDING.contains(file + "|" + text + site) || FORWARDING.contains(file + "|" + text)) {
            return;
        }
        Set<String> resolved = resolve(value, file, constants, locals);
        if (resolved.isEmpty()) {
            unsupported.add(file + ": code " + text + " is not a literal or a literal constant");
            return;
        }
        resolved.stream().filter(code -> !FORMAT.matcher(code).matches())
                .forEach(code -> unsupported.add(file + ": code " + text + " resolves to \"" + code + "\""));
    }

    // The string values an expression can have, or empty when it cannot be resolved statically.
    private static Set<String> resolve(ExpressionTree expression, String file, Constants constants,
            Map<String, ExpressionTree> locals) {
        if (expression instanceof ParenthesizedTree parenthesized) {
            return resolve(parenthesized.getExpression(), file, constants, locals);
        }
        if (expression instanceof LiteralTree literal) {
            return literal.getValue() instanceof String text ? Set.of(text) : Set.of();
        }
        if (expression instanceof ConditionalExpressionTree conditional) {
            Set<String> whenTrue = resolve(conditional.getTrueExpression(), file, constants, locals);
            Set<String> whenFalse = resolve(conditional.getFalseExpression(), file, constants, locals);
            if (whenTrue.isEmpty() || whenFalse.isEmpty()) {
                return Set.of();
            }
            Set<String> both = new LinkedHashSet<>(whenTrue);
            both.addAll(whenFalse);
            return both;
        }
        if (expression instanceof IdentifierTree identifier) {
            String name = identifier.getName().toString();
            if (locals.containsKey(name)) {
                // A local never falls back to a same-named constant: it is its initialiser, or unknown.
                ExpressionTree local = locals.get(name);
                if (local == null || local == expression) {
                    return Set.of();
                }
                Map<String, ExpressionTree> withoutSelf = new HashMap<>(locals);
                withoutSelf.put(name, null);
                return resolve(local, file, constants, withoutSelf);
            }
            return constants.lookup(file, name);
        }
        if (expression instanceof MemberSelectTree member) {
            String owner = member.getExpression().toString();
            String ownerFile = owner.substring(owner.lastIndexOf('.') + 1) + ".java";
            Set<String> inOwner = constants.lookup(ownerFile, member.getIdentifier().toString());
            return inOwner.isEmpty() ? constants.lookup(file, member.getIdentifier().toString()) : inOwner;
        }
        return Set.of();
    }

    /** {@code static final String NAME = "literal"} constants, by file and by name. */
    private record Constants(Map<String, Map<String, String>> byFile, Map<String, Set<String>> byName) {

        static Constants of(List<CompilationUnitTree> units) {
            Map<String, Map<String, String>> byFile = new HashMap<>();
            Map<String, Set<String>> byName = new HashMap<>();
            for (CompilationUnitTree unit : units) {
                String file = fileName(unit);
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree node, Void unused) {
                        Set<Modifier> modifiers = node.getModifiers().getFlags();
                        if (modifiers.contains(Modifier.STATIC) && modifiers.contains(Modifier.FINAL)
                                && node.getType().toString().equals("String")
                                && node.getInitializer() instanceof LiteralTree literal
                                && literal.getValue() instanceof String value) {
                            String name = node.getName().toString();
                            byFile.computeIfAbsent(file, f -> new HashMap<>()).put(name, value);
                            byName.computeIfAbsent(name, n -> new LinkedHashSet<>()).add(value);
                        }
                        return super.visitVariable(node, unused);
                    }
                }.scan(unit, null);
            }
            return new Constants(byFile, byName);
        }

        // Same file first; otherwise only a name that means one value everywhere.
        Set<String> lookup(String file, String name) {
            String local = byFile.getOrDefault(file, Map.of()).get(name);
            if (local != null) {
                return Set.of(local);
            }
            Set<String> values = byName.getOrDefault(name, Set.of());
            return values.size() == 1 ? values : Set.of();
        }
    }

    private static String fileName(CompilationUnitTree unit) {
        String path = unit.getSourceFile().getName().replace('\\', '/');
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static JavaFileObject source(String name, String code) {
        return new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }

    private static List<JavaFileObject> platformSources(Path root) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (Stream<Path> files = Files.walk(root);
                var fileManager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            List<Path> paths = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(ErrorCodeSourceUniquenessTest::isPlatformProductionSource)
                    .toList();
            List<JavaFileObject> sources = new ArrayList<>();
            fileManager.getJavaFileObjectsFromPaths(paths).forEach(sources::add);
            return sources;
        }
    }

    private static boolean isPlatformProductionSource(Path file) {
        String path = file.toString().replace('\\', '/');
        return path.contains("/src/main/java/")
                && !path.contains("/target/")
                && !path.contains("/examples/")
                && !path.contains("/tooling/")
                && !path.contains("/scratch/")
                && !path.contains("/tmpviol/");
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("build/platform-parent/pom.xml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above %s", Path.of("").toAbsolutePath()).isNotNull();
        return dir;
    }
}
