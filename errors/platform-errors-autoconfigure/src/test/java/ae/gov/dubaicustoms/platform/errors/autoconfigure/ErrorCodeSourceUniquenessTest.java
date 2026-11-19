package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Build-time gate on platform error codes across EVERY platform module (phase-01 scope R3.7, decision D92; carried by
 * Spring-native Phase 2, rows C24 and R2). A platform error code is a whole {@code DC-<CAP>-<NNNN>} string, set as the
 * {@code code} property of a {@code ProblemDetail} (D138). The gate compiles the production sources of the whole
 * repository with the JDK's own compiler (attribution only, nothing written), lets javac resolve every name, and fails
 * closed:
 * <ol>
 *   <li><b>Literals:</b> every string literal that is {@code "DC"} or starts with {@code "DC-"} or {@code "DC_"} must be
 *       a whole code; each code value is declared once. {@code "DC".concat(…)} and {@code "DC-MSG-" + n} fail here.</li>
 *   <li><b>Entry points.</b> These must receive a value that resolves to whole codes: every {@code setProperty}, on any
 *       receiver, unless its key is a string literal other than {@code "code"} (or the call is audited in
 *       {@link #NOT_A_PROBLEM_DETAIL}); {@code create} and {@code decorate} (argument 1) unless javac resolves them to a
 *       type other than {@code ProblemDetailFactory}; {@code respond} (argument 1); every invocation of a constructor
 *       of a type named {@code DataAccessFailure} ({@code new}, enum constant, {@code this(…)}, {@code super(…)}),
 *       at its parameter named {@code code}, and every assignment to such a type's {@code code} field. A method or
 *       constructor reference to an entry point, and an anonymous subclass of {@code DataAccessFailure}, are
 *       reported.</li>
 *   <li><b>Values.</b> A value is a string literal, a conditional of values, or a name that javac resolves to a local
 *       variable declared in the scanned sources (its initialiser and every assignment to it; a for-each variable is
 *       unknown) or to a constant variable declared there (its initialiser). Only literals end a chain, and javac's
 *       string folding is off, so every accepted code is a source literal counted by rule 1. A parameter or a call is a
 *       value only where {@link #FORWARDING} audits that exact method; its upstream is checked here. Inside a class with any
 *       supertype javac cannot resolve, an inherited name could shadow any other, so nothing resolves there. Anything
 *       else is unknown and reported.</li>
 *   <li>A minimum count stops an empty scan from passing.</li>
 * </ol>
 * Consumer examples, the archetype and tooling ({@code examples/}, {@code tooling/}) and the build's violation fixtures
 * ({@code scratch/}, {@code tmpviol/}) are excluded, each at the repository root.
 */
class ErrorCodeSourceUniquenessTest {

    private static final Pattern FORMAT = Pattern.compile("^DC-[A-Z]{2,8}-\\d{4}$");
    private static final Pattern CODE_LIKE = Pattern.compile("^DC($|[-_].*)", Pattern.DOTALL);

    /** The platform's codes at Phase 2: errors (7), messaging (2), the parked REST client (1). */
    private static final int MINIMUM_CODES = 10;

    private static final String FACTORY = "ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory";
    private static final String HANDLER = "ae.gov.dubaicustoms.platform.errors.autoconfigure.PlatformExceptionHandler";
    private static final String DATA_ACCESS_FAILURE = "ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.DataAccessFailure";

    /** A value passed on from an audited forwarding path: an object, so no literal can equal it. */
    private static final Object FORWARDED = new Object();

    private static final String REQUEST = "jakarta.servlet.http.HttpServletRequest";
    private static final String STRING = "java.lang.String";

    /**
     * Audited forwarding paths, each tied to one exact method (fully qualified type, name and parameter types).
     * {@code method#parameter}: that method's parameter at position 1, the argument its every call site checks, never
     * assigned. {@code method calls callee: text}: a call of that exact callee, by source text, inside that method.
     */
    private static final Set<String> FORWARDING = Set.of(
            // create() forwards its code to decorate(); every create(...) call site is checked.
            FACTORY + "#create(int," + STRING + "," + STRING + "," + STRING + ",java.lang.Throwable," + REQUEST + ")#code",
            // decorate() falls back to its defaultCode; every decorate(...) call site is checked.
            FACTORY + "#decorate(org.springframework.http.ProblemDetail," + STRING + ",java.lang.Throwable," + REQUEST
                    + ")#defaultCode",
            // respond() forwards its code to create(); every respond(...) call site is checked.
            HANDLER + "#respond(int," + STRING + "," + STRING + ",java.lang.Exception," + REQUEST + ")#code",
            // decorate() keeps the thrower's String code, checked where the thrower set it.
            FACTORY + "#decorate(org.springframework.http.ProblemDetail," + STRING + ",java.lang.Throwable," + REQUEST
                    + ") calls " + FACTORY + "#serviceCode(org.springframework.http.ProblemDetail): serviceCode(problem)",
            // copyOf() copies a body's existing properties; a code among them was checked where it was set.
            FACTORY + "#copyOf(org.springframework.http.ProblemDetail) calls " + FACTORY
                    + "#deepCopy(java.lang.Object): deepCopy(value)",
            // The constructor's code goes to the code field; every invocation of this constructor is checked.
            DATA_ACCESS_FAILURE + "#<init>(int," + STRING + "," + STRING + ")#code",
            // A DataAccessFailure's code is its code field, checked at every assignment.
            HANDLER + "#handle(java.lang.Exception," + REQUEST + ",jakarta.servlet.http.HttpServletResponse) calls "
                    + DATA_ACCESS_FAILURE + "#code(): failure.code()");

    /**
     * Audited setProperty calls on something other than a ProblemDetail, by enclosing type and exact source text. Each
     * has a non-literal key; none can carry an error code.
     */
    private static final Set<String> NOT_A_PROBLEM_DETAIL = Set.of(
            // Oracle JDBC driver properties (java.util.Properties), copied from Spring's configuration.
            "ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OracleFlywayConnectionDefaults|properties.setProperty(key, value)",
            // Oracle JDBC driver timeouts on the outbox pool's data-source properties (java.util.Properties).
            "ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.OutboxConnections"
                    + "|properties.setProperty(READ_TIMEOUT, Long.toString(readTimeout.toMillis()))",
            "ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.OutboxConnections"
                    + "|properties.setProperty(CONNECT_TIMEOUT, \"10000\")");

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
                            void factoryReceivers(Object object, ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory[] factories) {
                                ((ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory) object).create(500, "CAST_RECEIVER", null, null, null, null);
                                factories[0].create(500, "ARRAY_RECEIVER", null, null, null, null);
                                factories[0].decorate(null, "ARRAY_DECORATE", null, null);
                            }
                            static final String DPART = "D";
                            static final String BUILT = DPART + "C-TST-0001";
                            void built(org.springframework.http.ProblemDetail problem) {
                                problem.setProperty("code", BUILT);
                            }
                            static final String INHERIT_DEEP = "DC-TST-0008";
                            class Child extends Parent {
                                void deep(org.springframework.http.ProblemDetail problem) {
                                    problem.setProperty("code", INHERIT_DEEP);
                                }
                            }
                            void reference(org.springframework.http.ProblemDetail problem) {
                                java.util.function.BiConsumer<String, Object> setter = problem::setProperty;
                            }
                            void marker(org.springframework.http.ProblemDetail problem) {
                                problem.setProperty("code", "\\0forwarded");
                            }
                            void forEach(org.springframework.http.ProblemDetail problem, java.util.List<String> supplied) {
                                for (String loopCode : supplied) {
                                    problem.setProperty("code", loopCode);
                                    loopCode = SPLIT_OVER_LINES;
                                }
                            }
                            class Early {
                                final String earlyCode;
                                Early(org.springframework.http.ProblemDetail problem) {
                                    publish(problem);
                                    earlyCode = SPLIT_OVER_LINES;
                                }
                                void publish(org.springframework.http.ProblemDetail problem) {
                                    problem.setProperty("code", earlyCode);
                                }
                            }
                            static final String FOLDED = "D" + "C-TST-0009";
                            String prefixedDuplicate = "prefix" + "DC-TST-0003";
                            String prefixedBroken = "prefix" + "DC-BROKEN";
                            void folded(org.springframework.http.ProblemDetail problem) {
                                problem.setProperty("code", FOLDED);
                            }
                            void failures() {
                                java.util.function.BiFunction<Integer, String, Object> maker = other.DataAccessFailure::new;
                                new other.DataAccessFailure<String>(500, "GENERIC_FAILURE");
                                new @A other.DataAccessFailure(500, "ANNOTATED_FAILURE");
                                new other.DataAccessFailure(500, "ANONYMOUS_FAILURE") { };
                            }
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
                        """),
                source("DataAccessFailure", """
                        package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;

                        enum DataAccessFailure {
                            DELEGATING(500),
                            DIRECT(409, "BROKEN_CONSTANT", "detail"),
                            SWAPPED("BROKEN_SWAPPED", 1);

                            private final String code;

                            DataAccessFailure(int status) {
                                this(status, System.getProperty("unchecked.code"), "detail");
                            }

                            DataAccessFailure(int status, String code, String detail) {
                                this.code = code;
                            }

                            DataAccessFailure(String swapped, int status) {
                                this.code = swapped;
                            }
                        }
                        """),
                source("G", """
                        class Parent extends Missing {
                        }
                        """),
                source("ProblemDetailFactory", """
                        package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;

                        import org.springframework.http.ProblemDetail;

                        public final class ProblemDetailFactory {
                            public <T extends String> ProblemDetail create(int status, T code, String detail, String path,
                                    Throwable source, jakarta.servlet.http.HttpServletRequest request) {
                                return decorate(ProblemDetail.forStatus(status), code, source, request);
                            }
                            public ProblemDetail decorate(ProblemDetail problem, String defaultCode, Throwable source,
                                    jakarta.servlet.http.HttpServletRequest request) {
                                problem.setProperty("code", defaultCode);
                                return problem;
                            }
                            ProblemDetail decorate(ProblemDetail problem, String checked, String defaultCode) {
                                problem.setProperty("code", defaultCode);
                                return problem;
                            }
                            void notForwarding(ProblemDetail problem) {
                                String code = "UNAUDITED_IN_FACTORY";
                                problem.setProperty("code", code);
                            }
                        }
                        """)));

        assertThat(scan.declarations()).extracting(Declaration::code)
                .as("multi-line, a conditional's literal, and a unicode escape resolving to the same runtime value")
                .containsExactlyInAnyOrder("DC-TST-0001", "DC-TST-0003", "DC-TST-0005", "DC-TST-0006", "DC-TST-0007",
                        "DC-TST-0008", "DC-TST-0003", "DC-TST-0001");
        assertThat(duplicates(scan)).containsOnlyKeys("DC-TST-0001", "DC-TST-0003");
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
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("CAST_RECEIVER"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("ARRAY_RECEIVER"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("ARRAY_DECORATE"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("code BUILT "))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("INHERIT_DEEP"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("problem::setProperty"))
                .anySatisfy(entry -> assertThat(entry).contains("ProblemDetailFactory.java")
                        .contains("UNAUDITED_IN_FACTORY"))
                .noneSatisfy(entry -> assertThat(entry).contains("code code is not"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("forwarded"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("code loopCode "))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("code earlyCode "))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("code FOLDED "))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("\"DC-BROKEN\" is not a whole code"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("\"DC\" is not a whole code"));
        assertThat(scan.unsupported())
                .anySatisfy(entry -> assertThat(entry).contains("DataAccessFailure.java")
                        .contains("System.getProperty(\"unchecked.code\")"))
                .anySatisfy(entry -> assertThat(entry).contains("DataAccessFailure.java").contains("BROKEN_CONSTANT"))
                .anySatisfy(entry -> assertThat(entry).contains("DataAccessFailure.java").contains("code swapped "))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("DataAccessFailure::new"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("GENERIC_FAILURE"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("ANNOTATED_FAILURE"))
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("anonymous DataAccessFailure"))
                .noneSatisfy(entry -> assertThat(entry).contains("DataAccessFailure.java").contains("code code "));
        // The audited decorate(..., defaultCode, ...) forwards; its unaudited overload does not.
        assertThat(scan.unsupported()).filteredOn(entry -> entry.contains("code defaultCode ")).hasSize(1);
        assertThat(scan.unsupported())
                .anySatisfy(entry -> assertThat(entry).contains("A.java").contains("\"DC\""))
                .anySatisfy(entry -> assertThat(entry).contains("B.java").contains("\"DC-TST-\""))
                .anySatisfy(entry -> assertThat(entry).contains("C.java").contains("DC-TST-12"))
                .noneSatisfy(entry -> assertThat(entry).contains("SPLIT_OVER_LINES"))
                .noneSatisfy(entry -> assertThat(entry).contains("chosen"));
    }

    @Test
    void onlyTopLevelDirectoriesAreExcluded() {
        Path root = Path.of("repo").toAbsolutePath();
        assertThat(isPlatformProductionSource(root, root.resolve("errors/m/src/main/java/a/scratch/U.java"))).isTrue();
        assertThat(isPlatformProductionSource(root, root.resolve("errors/m/src/main/java/a/tooling/target/U.java")))
                .isTrue();
        assertThat(isPlatformProductionSource(root, root.resolve("scratch/m/src/main/java/a/U.java"))).isFalse();
        assertThat(isPlatformProductionSource(root, root.resolve("examples/m/src/main/java/a/U.java"))).isFalse();
        assertThat(isPlatformProductionSource(root, root.resolve("tooling/m/src/main/java/a/U.java"))).isFalse();
        assertThat(isPlatformProductionSource(root, root.resolve("errors/m/target/x/src/main/java/a/U.java"))).isFalse();
        assertThat(isPlatformProductionSource(root, root.resolve("errors/m/src/test/java/a/U.java"))).isFalse();
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
        // String folding off: "a" + "b" stays two source literals, each seen by the literal rule.
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> {
        }, List.of("-proc:none", "-XDshould-stop.ifError=FLOW", "-XDallowStringFolding=false",
                "-classpath", System.getProperty("java.class.path")),
                null, sources);
        List<CompilationUnitTree> units = new ArrayList<>();
        try {
            task.parse().forEach(units::add);
            task.analyze();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Trees trees = Trees.instance(task);
        Values values = Values.of(trees, task.getTypes(), units);
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
                    if (name.equals("setProperty") && args.size() == 2 && !isOtherLiteralKey(args.get(0))
                            && !NOT_A_PROBLEM_DETAIL.contains(values.enclosingType(getCurrentPath()) + "|" + node)) {
                        check(argument(1), file, values, unsupported);
                    } else if ((name.equals("create") || name.equals("decorate")) && args.size() >= 2
                            && mayBeFactory(trees.getElement(getCurrentPath()))) {
                        check(argument(1), file, values, unsupported);
                    } else if (name.equals("respond") && args.size() >= 2) {
                        check(argument(1), file, values, unsupported);
                    } else if (name.equals("this") || name.equals("super")) {
                        checkFailureConstructor(trees.getElement(getCurrentPath()), "");
                    }
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitAssignment(AssignmentTree node, Void unused) {
                    if (isFailureCodeField(trees.getElement(new TreePath(getCurrentPath(), node.getVariable())))) {
                        check(new TreePath(getCurrentPath(), node.getExpression()), file, values, unsupported);
                    }
                    return super.visitAssignment(node, unused);
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    if (node.getInitializer() != null && isFailureCodeField(trees.getElement(getCurrentPath()))) {
                        check(new TreePath(getCurrentPath(), node.getInitializer()), file, values, unsupported);
                    }
                    return super.visitVariable(node, unused);
                }

                // A constructor of a type named DataAccessFailure: its argument at the parameter named code. Unresolved
                // (outside the classpath), a construction named DataAccessFailure is checked at argument 1.
                private void checkFailureConstructor(Element constructor, String unresolvedName) {
                    if (constructor instanceof ExecutableElement executable
                            && executable.getKind() == ElementKind.CONSTRUCTOR
                            && isNamedFailure(executable.getEnclosingElement())) {
                        List<? extends VariableElement> parameters = executable.getParameters();
                        for (int index = 0; index < parameters.size(); index++) {
                            if (parameters.get(index).getSimpleName().contentEquals("code")) {
                                check(argument(index), file, values, unsupported);
                            }
                        }
                    } else if (!(constructor instanceof ExecutableElement) && unresolvedName.equals("DataAccessFailure")
                            && argumentCount() >= 2) {
                        check(argument(1), file, values, unsupported);
                    }
                }

                private int argumentCount() {
                    Tree call = getCurrentPath().getLeaf();
                    return call instanceof NewClassTree created ? created.getArguments().size()
                            : ((MethodInvocationTree) call).getArguments().size();
                }

                @Override
                public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                    String name = node.getName().toString();
                    boolean failureConstructor = name.equals("<init>")
                            && (lastName(node.getQualifierExpression()).equals("DataAccessFailure")
                            || isNamedFailure(trees.getElement(new TreePath(getCurrentPath(),
                                    node.getQualifierExpression()))));
                    if (name.equals("setProperty") || name.equals("respond") || failureConstructor
                            || (name.equals("create") || name.equals("decorate"))
                            && mayBeFactory(trees.getElement(getCurrentPath()))) {
                        unsupported.add(file + ": " + node + " hands an entry point on unchecked");
                    }
                    return super.visitMemberReference(node, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    String typeName = lastName(node.getIdentifier());
                    if (node.getClassBody() != null && (typeName.equals("DataAccessFailure")
                            || isNamedFailure(trees.getElement(new TreePath(getCurrentPath(), node.getIdentifier()))))) {
                        unsupported.add(file + ": an anonymous DataAccessFailure hides its constructor's code");
                    } else {
                        checkFailureConstructor(trees.getElement(getCurrentPath()), typeName);
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
    // key's name, so no shadowing, scope or receiver case can make a code key look like another key.
    private static boolean isOtherLiteralKey(ExpressionTree key) {
        return unwrap(key) instanceof LiteralTree literal && literal.getValue() instanceof String text
                && !text.equals("code");
    }

    private static boolean isNamedFailure(Element type) {
        return type instanceof TypeElement named && named.getSimpleName().contentEquals("DataAccessFailure");
    }

    private static boolean isFailureCodeField(Element field) {
        return field instanceof VariableElement variable && variable.getKind() == ElementKind.FIELD
                && variable.getSimpleName().contentEquals("code") && isNamedFailure(variable.getEnclosingElement());
    }

    // create/decorate is a factory call unless javac resolved it to a method of another type. ProblemDetailFactory is
    // final, so no subclass can override them.
    private static boolean mayBeFactory(Element method) {
        if (method instanceof ExecutableElement && method.getEnclosingElement() instanceof TypeElement owner
                && method.asType().getKind() != TypeKind.ERROR && !owner.getQualifiedName().isEmpty()) {
            return owner.getQualifiedName().contentEquals(FACTORY);
        }
        return true;
    }

    private static void check(TreePath value, String file, Values values, List<String> unsupported) {
        String text = value.getLeaf().toString();
        Set<Object> resolved = values.of(value);
        if (resolved.isEmpty()) {
            unsupported.add(file + ": code " + text + " is not a literal or a literal constant");
            return;
        }
        resolved.stream().filter(code -> code != FORWARDED && !FORMAT.matcher((String) code).matches())
                .forEach(code -> unsupported.add(file + ": code " + text + " resolves to \"" + code + "\""));
    }

    /**
     * The string values an expression can have, with javac's name resolution. Every name is followed to its declaration
     * in the scanned sources; only literals (and audited forwarding paths) end a chain. Empty means unknown.
     */
    private record Values(Trees trees, Types types, Map<Element, VariableTree> declarations,
            Map<Element, List<TreePath>> assignments, Set<Element> opaque) {

        static Values of(Trees trees, Types types, List<CompilationUnitTree> units) {
            Map<Element, VariableTree> declarations = new HashMap<>();
            Map<Element, List<TreePath>> assignments = new HashMap<>();
            // Variables with a value the gate cannot see: compound-assigned, or a for-each variable.
            Set<Element> opaque = new java.util.HashSet<>();
            for (CompilationUnitTree unit : units) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree node, Void unused) {
                        Element element = trees.getElement(getCurrentPath());
                        if (element != null) {
                            declarations.put(element, node);
                        }
                        return super.visitVariable(node, unused);
                    }

                    @Override
                    public Void visitAssignment(AssignmentTree node, Void unused) {
                        Element element = trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
                        if (element != null) {
                            assignments.computeIfAbsent(element, e -> new ArrayList<>())
                                    .add(new TreePath(getCurrentPath(), node.getExpression()));
                        }
                        return super.visitAssignment(node, unused);
                    }

                    @Override
                    public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                        Element element = trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
                        if (element != null) {
                            opaque.add(element);
                        }
                        return super.visitCompoundAssignment(node, unused);
                    }

                    @Override
                    public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
                        Element element = trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
                        if (element != null) {
                            opaque.add(element);
                        }
                        return super.visitEnhancedForLoop(node, unused);
                    }
                }.scan(unit, null);
            }
            return new Values(trees, types, declarations, assignments, opaque);
        }

        Set<Object> of(TreePath path) {
            return of(path, 0);
        }

        private Set<Object> of(TreePath path, int depth) {
            Tree expression = path.getLeaf();
            if (depth > 16 || insideUnresolvedHierarchy(path)) {
                return Set.of();
            }
            if (expression instanceof ParenthesizedTree parenthesized) {
                return of(new TreePath(path, parenthesized.getExpression()), depth + 1);
            }
            if (expression instanceof LiteralTree literal) {
                return literal.getValue() instanceof String text ? Set.of((Object) text) : Set.of();
            }
            if (expression instanceof ConditionalExpressionTree conditional) {
                return union(List.of(new TreePath(path, conditional.getTrueExpression()),
                        new TreePath(path, conditional.getFalseExpression())), depth);
            }
            if (expression instanceof IdentifierTree || expression instanceof MemberSelectTree) {
                return ofVariable(trees.getElement(path), depth);
            }
            ExecutableElement method = enclosingMethod(path);
            if (expression instanceof MethodInvocationTree && method != null
                    && trees.getElement(path) instanceof ExecutableElement callee
                    && FORWARDING.contains(signature(method) + " calls " + signature(callee) + ": " + expression)) {
                return Set.of(FORWARDED);
            }
            return Set.of();
        }

        private Set<Object> ofVariable(Element element, int depth) {
            if (!(element instanceof VariableElement variable) || opaque.contains(variable)) {
                return Set.of();
            }
            List<TreePath> assigned = assignments.getOrDefault(variable, List.of());
            if (variable.getKind() == ElementKind.PARAMETER) {
                return assigned.isEmpty() && isForwardedParameter(variable) ? Set.of(FORWARDED) : Set.of();
            }
            VariableTree declaration = declarations.get(variable);
            if (declaration == null) {
                return Set.of();
            }
            List<TreePath> sources = new ArrayList<>();
            if (variable.getKind() == ElementKind.LOCAL_VARIABLE || variable.getKind() == ElementKind.RESOURCE_VARIABLE) {
                // Java's definite assignment guarantees a local is assigned before it is read: its initialiser and
                // every assignment are all its possible values.
                sources.addAll(assigned);
            } else if (!(variable.getKind() == ElementKind.FIELD && variable.getConstantValue() instanceof String)) {
                // A field counts only as a constant variable: javac inlines it, so it is never read before its
                // initialiser runs, and it is never assigned.
                return Set.of();
            }
            if (declaration.getInitializer() != null) {
                sources.add(new TreePath(trees.getPath(variable), declaration.getInitializer()));
            }
            return union(sources, depth);
        }

        // Every source must resolve; the result is all their values.
        private Set<Object> union(List<TreePath> sources, int depth) {
            if (sources.isEmpty()) {
                return Set.of();
            }
            Set<Object> all = new LinkedHashSet<>();
            for (TreePath source : sources) {
                Set<Object> one = of(source, depth + 1);
                if (one.isEmpty()) {
                    return Set.of();
                }
                all.addAll(one);
            }
            return all;
        }

        private boolean isForwardedParameter(VariableElement parameter) {
            return parameter.getEnclosingElement() instanceof ExecutableElement method
                    && method.getParameters().indexOf(parameter) == 1
                    && FORWARDING.contains(signature(method) + "#" + parameter.getSimpleName());
        }

        // pkg.Type#name(paramType,...), parameter types erased by javac (type variables to their bounds, annotations
        // and type arguments dropped).
        private String signature(ExecutableElement method) {
            String owner = method.getEnclosingElement() instanceof TypeElement type ? type.getQualifiedName().toString()
                    : "";
            return owner + "#" + method.getSimpleName() + method.getParameters().stream()
                    .map(parameter -> erasure(parameter.asType()))
                    .collect(Collectors.joining(",", "(", ")"));
        }

        private String erasure(TypeMirror type) {
            TypeMirror erased = types.erasure(type);
            return erased instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
                    ? element.getQualifiedName().toString() : erased.toString();
        }

        String enclosingType(TreePath path) {
            for (TreePath current = path; current != null; current = current.getParentPath()) {
                if (current.getLeaf() instanceof ClassTree && trees.getElement(current) instanceof TypeElement type) {
                    return type.getQualifiedName().toString();
                }
            }
            return "";
        }

        private ExecutableElement enclosingMethod(TreePath path) {
            for (TreePath current = path; current != null; current = current.getParentPath()) {
                if (current.getLeaf() instanceof MethodTree) {
                    return trees.getElement(current) instanceof ExecutableElement method ? method : null;
                }
                if (current.getLeaf() instanceof ClassTree) {
                    return null;
                }
            }
            return null;
        }

        // In a class with any supertype javac cannot resolve, directly or further up, an inherited member could shadow
        // any name.
        private boolean insideUnresolvedHierarchy(TreePath path) {
            for (TreePath current = path; current != null; current = current.getParentPath()) {
                if (current.getLeaf() instanceof ClassTree) {
                    Element element = trees.getElement(current);
                    if (!(element instanceof TypeElement type) || unresolved(type.asType(), new java.util.HashSet<>())) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean unresolved(TypeMirror type, Set<Element> seen) {
            try {
                if (type.getKind() == TypeKind.ERROR) {
                    return true;
                }
                if (!(type instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)
                        || !seen.add(element)) {
                    return false;
                }
                if (unresolved(element.getSuperclass(), seen)) {
                    return true;
                }
                return element.getInterfaces().stream().anyMatch(supertype -> unresolved(supertype, seen));
            } catch (RuntimeException incomplete) {
                // javac's CompletionFailure: a supertype's class file references a type that is not on the classpath.
                return true;
            }
        }
    }

    private static ExpressionTree unwrap(ExpressionTree expression) {
        ExpressionTree current = expression;
        while (current instanceof ParenthesizedTree parenthesized) {
            current = parenthesized.getExpression();
        }
        return current;
    }

    private static String lastName(Tree expression) {
        Tree receiver = expression instanceof ExpressionTree tree ? unwrap(tree) : expression;
        while (receiver instanceof ParameterizedTypeTree || receiver instanceof AnnotatedTypeTree) {
            receiver = receiver instanceof ParameterizedTypeTree parameterized ? parameterized.getType()
                    : ((AnnotatedTypeTree) receiver).getUnderlyingType();
        }
        return receiver instanceof MemberSelectTree qualified ? qualified.getIdentifier().toString()
                : receiver instanceof IdentifierTree identifier ? identifier.getName().toString() : "";
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
                    .filter(file -> isPlatformProductionSource(root, file))
                    .toList();
            List<JavaFileObject> sources = new ArrayList<>();
            fileManager.getJavaFileObjectsFromPaths(paths).forEach(sources::add);
            return sources;
        }
    }

    // A module's src/main/java, anywhere but under the excluded top-level directories or a build output directory.
    static boolean isPlatformProductionSource(Path root, Path file) {
        String path = "/" + root.relativize(file).toString().replace('\\', '/');
        int sources = path.indexOf("/src/main/java/");
        if (sources < 0) {
            return false;
        }
        String module = path.substring(0, sources) + "/";
        return !module.contains("/target/") && Stream.of("/examples/", "/tooling/", "/scratch/", "/tmpviol/")
                .noneMatch(module::startsWith);
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
