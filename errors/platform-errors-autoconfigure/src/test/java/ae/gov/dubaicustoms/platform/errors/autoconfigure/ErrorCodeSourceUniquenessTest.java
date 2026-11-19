package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
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
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Build-time gate on platform error codes across EVERY platform module (phase-01 scope R3.7, decision D92; carried by
 * Spring-native Phase 2, rows C24 and R2). A platform error code is a whole {@code DC-<CAP>-<NNNN>} string, set as the
 * {@code code} property of a {@code ProblemDetail} (D138). The gate compiles the production sources of the whole
 * repository with the JDK's own compiler (attribution only, nothing written) and fails closed:
 * <ol>
 *   <li><b>Literals:</b> every string literal that is {@code "DC"} or starts with {@code "DC-"} or {@code "DC_"} must be
 *       a whole code; each code value is declared once. {@code "DC".concat(…)} and {@code "DC-MSG-" + n} fail here.</li>
 *   <li><b>Every code-bearing entry point</b> must receive a value that resolves to whole codes: every
 *       {@code setProperty}, on any receiver, unless its key is a string literal other than {@code "code"} (or the
 *       call is one of the audited calls in {@link #NOT_A_PROBLEM_DETAIL}); {@code factory.create} and
 *       {@code factory.decorate} (argument 1); the handler's {@code respond} (argument 1); {@code DataAccessFailure}'s
 *       constants (argument 1). A value resolves through string literals, conditional expressions, compile-time
 *       constants as javac resolves them, and local variables never assigned after their declaration, through their
 *       initialisers. Inside a class whose supertypes javac cannot resolve, an inherited name could shadow any other,
 *       so nothing resolves there. Anything else is unknown and reported, unless it is one of the audited forwarding
 *       paths in {@link #FORWARDING}, whose upstream is checked here.</li>
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

    /**
     * Audited setProperty calls on something other than a ProblemDetail, by file and exact source text. Each has a
     * non-literal key; none can carry an error code.
     */
    private static final Set<String> NOT_A_PROBLEM_DETAIL = Set.of(
            // Oracle JDBC driver properties (java.util.Properties), copied from Spring's configuration.
            "OracleFlywayConnectionDefaults.java|properties.setProperty(key, value)",
            // Oracle JDBC driver timeouts on the outbox pool's data-source properties (java.util.Properties).
            "OutboxConnections.java|properties.setProperty(READ_TIMEOUT, Long.toString(readTimeout.toMillis()))",
            "OutboxConnections.java|properties.setProperty(CONNECT_TIMEOUT, \"10000\")");

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
                                properties.setProperty("code", lookupNotExempt());
                            }
                            org.springframework.http.ProblemDetail body;
                            void blockShadow() {
                                { java.util.Properties body = new java.util.Properties(); }
                                body.setProperty("code", "SHADOWED");
                            }
                            <T extends org.springframework.http.ProblemDetail> void bounded(T customBody) {
                                customBody.setProperty("code", "BOUNDED");
                            }
                            void chained() {
                                org.springframework.http.ProblemDetail.forStatus(500).setProperty("code", "CHAINED");
                            }
                            void scopedKey(org.springframework.http.ProblemDetail problem) {
                                { String KEY = "detail"; }
                                problem.setProperty(KEY, "SCOPED");
                            }
                            void loopKey(org.springframework.http.ProblemDetail problem) {
                                for (String KEY = "detail"; false; ) { }
                                problem.setProperty(KEY, "LOOPED");
                            }
                            void earlyKey(org.springframework.http.ProblemDetail problem) {
                                problem.setProperty(KEY, "EARLY");
                                String KEY = "detail";
                            }
                            void capturedKey(org.springframework.http.ProblemDetail problem) {
                                String outerKey = "code";
                                new Runnable() {
                                    public void run() {
                                        problem.setProperty(outerKey, "CAPTURED");
                                    }
                                };
                            }
                            void capturedAlias(org.springframework.http.ProblemDetail problem) {
                                String aliasKey = "code";
                                String captured = aliasKey;
                                new Runnable() {
                                    String aliasKey = "detail";
                                    public void run() {
                                        problem.setProperty(captured, "ALIASED");
                                    }
                                };
                            }
                            static final String DETAIL_KEY = "detail";
                            static final String INHERIT_GUARD = "DC-TST-0007";
                            void inheritedValue() {
                                new Base() {
                                    void run() {
                                        factory.create(500, INHERIT_GUARD, null, null, null, null);
                                    }
                                };
                            }
                            void inheritedKey(org.springframework.http.ProblemDetail problem) {
                                new Base() {
                                    void run() {
                                        problem.setProperty(DETAIL_KEY, "INHERITED");
                                    }
                                };
                            }
                            String instanceKey = "detail";
                            void fieldKey(org.springframework.http.ProblemDetail problem) {
                                problem.setProperty(this.instanceKey, "INSTANCE_FIELD");
                                problem.setProperty(instanceKey, "BARE_FIELD");
                            }
                            void otherFilesKey(org.springframework.http.ProblemDetail problem) {
                                problem.setProperty(ONLY_IN_D, "CROSS_FILE");
                                problem.setProperty(D.ONLY_IN_D, "QUALIFIED_NOT_CODE");
                                problem.setProperty("code", D.D_CODE);
                            }
                            Keys F;
                            void variableNamedLikeAType(org.springframework.http.ProblemDetail problem, Keys E) {
                                problem.setProperty(E.ONLY_IN_E, "VARIABLE_RECEIVER");
                                problem.setProperty(this.F.ONLY_IN_F, "FIELD_RECEIVER");
                            }
                            void localClassField(org.springframework.http.ProblemDetail problem) {
                                new Runnable() {
                                    String fieldKey = "detail";
                                    public void run() {
                                        this.fieldKey = "code";
                                        problem.setProperty(fieldKey, "LOCAL_CLASS_FIELD");
                                    }
                                };
                            }
                            static final String BLOCK_KEY = "detail";
                            {
                                String BLOCK_KEY = "code";
                                org.springframework.http.ProblemDetail.forStatus(500).setProperty(BLOCK_KEY, "INSTANCE_BLOCK");
                            }
                            static {
                                String BLOCK_KEY = "code";
                                org.springframework.http.ProblemDetail.forStatus(500).setProperty(BLOCK_KEY, "STATIC_BLOCK");
                            }
                            {
                                String assignedInBlock = "detail";
                                assignedInBlock = "code";
                                org.springframework.http.ProblemDetail.forStatus(500)
                                        .setProperty(assignedInBlock, "ASSIGNED_IN_BLOCK");
                            }
                            Runnable lambdaField = () -> {
                                String BLOCK_KEY = "code";
                                org.springframework.http.ProblemDetail.forStatus(500).setProperty(BLOCK_KEY, "FIELD_LAMBDA");
                            };
                            void innerKey(org.springframework.http.ProblemDetail problem) {
                                { String inner = "detail"; problem.setProperty(inner, "NAMED_KEY"); }
                                problem.setProperty("detail", "LITERAL_OTHER_KEY");
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
                        """),
                source("D", """
                        class D {
                            static final String ONLY_IN_D = "detail";
                            static final String D_CODE = "DC-TST-0006";
                        }
                        """),
                source("E", """
                        class E {
                            static final String ONLY_IN_E = "detail";
                        }
                        """),
                source("F", """
                        class F {
                            static final String ONLY_IN_F = "detail";
                        }
                        """)));

        assertThat(scan.declarations()).extracting(Declaration::code)
                .as("multi-line, a conditional's literal, and a unicode escape resolving to the same runtime value")
                .containsExactlyInAnyOrder("DC-TST-0001", "DC-TST-0003", "DC-TST-0005", "DC-TST-0006", "DC-TST-0007",
                        "DC-TST-0001");
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
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("BOUNDED"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("lookupNotExempt()"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("SHADOWED"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("SCOPED"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("LOOPED"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("EARLY"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("CAPTURED"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("ALIASED"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("INHERITED"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("INSTANCE_FIELD"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("BARE_FIELD"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("CROSS_FILE"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("VARIABLE_RECEIVER"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("FIELD_RECEIVER"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("INSTANCE_BLOCK"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("STATIC_BLOCK"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("ASSIGNED_IN_BLOCK"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("FIELD_LAMBDA"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("LOCAL_CLASS_FIELD"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("QUALIFIED_NOT_CODE"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("NAMED_KEY"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("INHERIT_GUARD"))
                .noneSatisfy(entry -> assertThat(entry).contains("D_CODE"))
                .noneSatisfy(entry -> assertThat(entry).contains("LITERAL_OTHER_KEY"))
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
        // Attributed, not just parsed, so that javac resolves every name. Types outside the classpath stay unresolved
        // (error types) and javac attributes past them (should-stop at FLOW); the resolver treats them as unknown.
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> {
        }, List.of("-proc:none", "-XDshould-stop.ifError=FLOW", "-classpath", System.getProperty("java.class.path")),
                null, sources);
        List<CompilationUnitTree> units = new ArrayList<>();
        try {
            task.parse().forEach(units::add);
            task.analyze();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Values values = Values.of(Trees.instance(task), units);
        Set<String> factoryNames = namesDeclaredAs(units, "ProblemDetailFactory");
        factoryNames.add("factory");
        List<Declaration> declarations = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (CompilationUnitTree unit : units) {
            String file = fileName(unit);
            new TreePathScanner<Void, Void>() {

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
                    if (name.equals("setProperty") && args.size() == 2 && onProblemDetail(node, file)
                            && !isOtherLiteralKey(args.get(0))) {
                        check(argument(1), file, "", values, unsupported);
                    } else if ((name.equals("create") || name.equals("decorate")) && args.size() >= 2
                            && isFactoryCall(node, factoryNames)) {
                        check(argument(1), file, "#" + name, values, unsupported);
                    } else if (name.equals("respond") && args.size() >= 2) {
                        check(argument(1), file, "#respond", values, unsupported);
                    }
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    if (node.getIdentifier().toString().equals("DataAccessFailure") && node.getArguments().size() >= 2) {
                        check(argument(1), file, "#DataAccessFailure", values, unsupported);
                    }
                    return super.visitNewClass(node, unused);
                }

                private TreePath argument(int index) {
                    Tree call = getCurrentPath().getLeaf();
                    List<? extends ExpressionTree> args = call instanceof NewClassTree created ? created.getArguments()
                            : ((MethodInvocationTree) call).getArguments();
                    return new TreePath(getCurrentPath(), args.get(index));
                }
            }.scan(unit, null);
        }
        return new Scan(declarations, unsupported);
    }

    // The only setProperty calls skipped: a key that is a string literal other than "code". The gate never resolves a
    // key's name, so no shadowing, scope or receiver trick can make a code key look like another key.
    private static boolean isOtherLiteralKey(ExpressionTree key) {
        return unwrap(key) instanceof LiteralTree literal && literal.getValue() instanceof String text
                && !text.equals("code");
    }

    private static void check(TreePath value, String file, String site, Values values, List<String> unsupported) {
        String text = value.getLeaf().toString();
        if (FORWARDING.contains(file + "|" + text + site) || FORWARDING.contains(file + "|" + text)) {
            return;
        }
        Set<String> resolved = values.of(value);
        if (resolved.isEmpty()) {
            unsupported.add(file + ": code " + text + " is not a literal or a literal constant");
            return;
        }
        resolved.stream().filter(code -> !FORMAT.matcher(code).matches())
                .forEach(code -> unsupported.add(file + ": code " + text + " resolves to \"" + code + "\""));
    }

    /**
     * The string values an expression can have, with javac's own name resolution. A name is a compile-time constant
     * (javac's constant value), or a local variable never assigned after its declaration (its initialiser's values);
     * anything else is unknown (empty).
     */
    private record Values(Trees trees, Map<Element, TreePath> locals, Set<Element> assigned) {

        static Values of(Trees trees, List<CompilationUnitTree> units) {
            Map<Element, TreePath> locals = new HashMap<>();
            Set<Element> assigned = new java.util.HashSet<>();
            for (CompilationUnitTree unit : units) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree node, Void unused) {
                        Element element = trees.getElement(getCurrentPath());
                        if (element != null && (element.getKind() == ElementKind.LOCAL_VARIABLE
                                || element.getKind() == ElementKind.RESOURCE_VARIABLE)) {
                            locals.put(element, getCurrentPath());
                        }
                        return super.visitVariable(node, unused);
                    }

                    @Override
                    public Void visitAssignment(AssignmentTree node, Void unused) {
                        assign(node.getVariable());
                        return super.visitAssignment(node, unused);
                    }

                    @Override
                    public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                        assign(node.getVariable());
                        return super.visitCompoundAssignment(node, unused);
                    }

                    private void assign(ExpressionTree target) {
                        Element element = trees.getElement(new TreePath(getCurrentPath(), target));
                        if (element != null) {
                            assigned.add(element);
                        }
                    }
                }.scan(unit, null);
            }
            return new Values(trees, locals, assigned);
        }

        Set<String> of(TreePath path) {
            return of(path, 0);
        }

        private Set<String> of(TreePath path, int depth) {
            Tree expression = path.getLeaf();
            if (depth > 16 || insideUnresolvedHierarchy(path)) {
                return Set.of();
            }
            if (expression instanceof ParenthesizedTree parenthesized) {
                return of(new TreePath(path, parenthesized.getExpression()), depth + 1);
            }
            if (expression instanceof LiteralTree literal) {
                return literal.getValue() instanceof String text ? Set.of(text) : Set.of();
            }
            if (expression instanceof ConditionalExpressionTree conditional) {
                Set<String> whenTrue = of(new TreePath(path, conditional.getTrueExpression()), depth + 1);
                Set<String> whenFalse = of(new TreePath(path, conditional.getFalseExpression()), depth + 1);
                if (whenTrue.isEmpty() || whenFalse.isEmpty()) {
                    return Set.of();
                }
                Set<String> both = new LinkedHashSet<>(whenTrue);
                both.addAll(whenFalse);
                return both;
            }
            if (expression instanceof IdentifierTree || expression instanceof MemberSelectTree) {
                Element element = trees.getElement(path);
                if (element instanceof VariableElement variable) {
                    if (variable.getConstantValue() instanceof String constant) {
                        return Set.of(constant);
                    }
                    TreePath declaration = locals.get(variable);
                    if (declaration != null && !assigned.contains(variable)
                            && ((VariableTree) declaration.getLeaf()).getInitializer() != null) {
                        return of(new TreePath(declaration, ((VariableTree) declaration.getLeaf()).getInitializer()),
                                depth + 1);
                    }
                }
            }
            return Set.of();
        }

        // In a class whose superclass or interfaces javac could not resolve, an inherited member could shadow any name.
        private boolean insideUnresolvedHierarchy(TreePath path) {
            for (TreePath current = path; current != null; current = current.getParentPath()) {
                if (current.getLeaf() instanceof ClassTree && trees.getElement(current) instanceof TypeElement type
                        && (type.getSuperclass().getKind() == TypeKind.ERROR
                        || type.getInterfaces().stream().anyMatch(i -> i.getKind() == TypeKind.ERROR))) {
                    return true;
                }
            }
            return false;
        }
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

    // Fails closed: every setProperty counts, wherever it is called, except the audited calls in NOT_A_PROBLEM_DETAIL.
    private static boolean onProblemDetail(MethodInvocationTree node, String file) {
        return !NOT_A_PROBLEM_DETAIL.contains(file + "|" + node);
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

    private static String methodName(MethodInvocationTree node) {
        ExpressionTree select = node.getMethodSelect();
        if (select instanceof MemberSelectTree member) {
            return member.getIdentifier().toString();
        }
        return select instanceof IdentifierTree identifier ? identifier.getName().toString() : "";
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
