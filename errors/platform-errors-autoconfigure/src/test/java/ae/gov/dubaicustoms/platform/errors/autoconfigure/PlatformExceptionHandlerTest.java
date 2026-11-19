package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Behavior of every mapping in the platform advice, including the one full-JSON-shape assert. */
class PlatformExceptionHandlerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION = "0123456789abcdef0123456789abcdef";

    // The request's trace, as the tracer would report it; MockMvc runs on the calling thread.
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    private MockMvc mockMvc(ErrorsProperties properties) {
        ProblemDetailFactory factory = new ProblemDetailFactory(properties, request -> TRACE.get(), FIXED_CLOCK);
        return MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new PlatformExceptionHandler(properties, factory))
                .build();
    }

    // Opens a trace for the current thread: the error body reads it, and the log line carries it in the MDC.
    private static AutoCloseable trace(String traceId) {
        TRACE.set(traceId);
        org.slf4j.MDC.MDCCloseable mdc = org.slf4j.MDC.putCloseable("traceId", traceId);
        return () -> {
            mdc.close();
            TRACE.remove();
        };
    }

    // A service's own error: Spring's ErrorResponseException with its code property (D138).
    static ErrorResponseException serviceError(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return new ErrorResponseException(status, body, null);
    }

    private static ErrorsProperties defaults() {
        return new ErrorsProperties(true, false, "urn:dc:problem:", true, false);
    }

    private static ErrorsProperties withRejectedValues() {
        return new ErrorsProperties(true, false, "urn:dc:problem:", true, true);
    }

    @Test
    void notFoundMapsToFullRfc9457Shape() throws Exception {
        MockMvc mvc = mockMvc(defaults());
        try (AutoCloseable scope = trace(CORRELATION)) {
            MvcResult result = mvc.perform(get("/missing"))
                    .andExpect(status().isNotFound())
                    .andReturn();

            // The one strict full-shape assertion (spec: assert full JSON shape once with JSONAssert).
            JSONAssert.assertEquals("""
                    {
                      "type": "urn:dc:problem:DC-TEST-0404",
                      "title": "DC-TEST-0404",
                      "status": 404,
                      "detail": "order 42 not found",
                      "instance": "/missing",
                      "code": "DC-TEST-0404",
                      "traceId": "%s",
                      "timestamp": "2026-01-01T00:00:00Z",
                      "retryable": false
                    }""".formatted(CORRELATION),
                    result.getResponse().getContentAsString(), JSONCompareMode.STRICT);
            assertThat(result.getResponse().getContentType())
                    .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        }
    }

    @Test
    void conflictMapsTo409() throws Exception {
        mockMvc(defaults()).perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DC-TEST-0409"));
    }

    @Test
    void aServiceServerErrorKeepsItsCodeButNotItsDetail() throws Exception {
        MvcResult result = mockMvc(defaults()).perform(get("/infra"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DC-TEST-0500"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("transport down");
    }

    @Test
    void aServiceErrorsStatusDecidesRetryableWithoutLeakingItsDetail() throws Exception {
        MvcResult result = mockMvc(defaults()).perform(get("/remote"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DC-TEST-0503"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("orders.internal");
    }

    @Test
    void malformedJsonMapsTo400WithoutParserInternals() throws Exception {
        MvcResult result = mockMvc(defaults())
                .perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"name\": [oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DC-CORE-0410"))
                .andExpect(jsonPath("$.detail").value("The request body is malformed or unreadable."))
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContainIgnoringCase("jackson").doesNotContain("NewOrder", "line:", "column:");
    }

    @Test
    void frameworkRejectionsGetThePlatformExtensions() throws Exception {
        try (AutoCloseable scope = trace(CORRELATION)) {
            mockMvc(defaults()).perform(post("/missing"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("DC-CORE-0499"))
                    .andExpect(jsonPath("$.traceId").value(CORRELATION))
                    .andExpect(jsonPath("$.retryable").value(false))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeakingTheMessage() throws Exception {
        MvcResult result = mockMvc(defaults()).perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DC-CORE-0500"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("jdbc://secret-host");
    }

    @Test
    void constraintViolationMapsTo409WithAFixedDetailAndNoSql() throws Exception {
        try (AutoCloseable scope = trace(CORRELATION)) {
            MvcResult result = mockMvc(defaults()).perform(get("/duplicate"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DC-DATA-0409"))
                    .andExpect(jsonPath("$.type").value("urn:dc:problem:DC-DATA-0409"))
                    .andExpect(jsonPath("$.detail").value("The request conflicts with existing data."))
                    .andExpect(jsonPath("$.traceId").value(CORRELATION))
                    .andExpect(jsonPath("$.retryable").value(false))
                    .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("ORA-").doesNotContain("ORDERS_REF_UK").doesNotContainIgnoringCase("insert into");
        }
    }

    @Test
    void concurrentUpdateMapsTo409WithItsOwnCode() throws Exception {
        MvcResult result = mockMvc(defaults()).perform(get("/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DC-DATA-0410"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ORDERS#42");
    }

    @Test
    void unavailableDatabaseMapsTo503RetryableWithoutConnectionDetails() throws Exception {
        MvcResult result = mockMvc(defaults()).perform(get("/db-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DC-DATA-0503"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.retryable").value(true))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("db-secret-host").doesNotContain("HikariPool").doesNotContain("jdbc:");
    }

    @Test
    void aSqlBugIsStillAGeneric500() throws Exception {
        MvcResult result = mockMvc(defaults()).perform(get("/bad-sql"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DC-CORE-0500"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret_column");
    }

    @Test
    void requestBodyValidationOmitsRejectedValuesByDefault() throws Exception {
        MvcResult result = mockMvc(defaults())
                .perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"password\":\"hunter2!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DC-CORE-0400"))
                .andExpect(jsonPath("$.errors[?(@.field=='name')].message").exists())
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("hunter2!");
    }

    @Test
    void requestBodyValidationMapsTo400WithRedactedErrorsWhenRejectedValuesAreOn() throws Exception {
        mockMvc(withRejectedValues())
                .perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"password\":\"hunter2!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DC-CORE-0400"))
                .andExpect(jsonPath("$.errors[?(@.field=='name')].rejectedValue").value(""))
                // credential-shaped fields never echo their raw value back
                .andExpect(jsonPath("$.errors[?(@.field=='password')].rejectedValue").value("REDACTED"));
    }

    @Test
    void mapValidationOffFallsBackToDefaultSpringBody() throws Exception {
        ErrorsProperties off = new ErrorsProperties(true, false, "urn:dc:problem:", false, false);
        mockMvc(off)
                .perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"password\":\"hunter2!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void includeStacktraceAddsTheExtension() throws Exception {
        ErrorsProperties withTrace = new ErrorsProperties(true, true, "urn:dc:problem:", true, false);
        mockMvc(withTrace).perform(get("/boom"))
                .andExpect(jsonPath("$.stacktrace").exists());
    }

    @Test
    void traceIdExtensionIsOmittedOutsideAnOpenContextAndCorrelationIdIsGone() throws Exception {
        mockMvc(defaults()).perform(get("/missing"))
                .andExpect(jsonPath("$.traceId").doesNotExist())
                .andExpect(jsonPath("$.correlationId").doesNotExist());
    }

    @RestController
    static class FailingController {

        record NewOrder(@NotBlank String name, @Size(min = 12) String password) {
        }

        @GetMapping("/missing")
        String missing() {
            throw serviceError(HttpStatus.NOT_FOUND, "DC-TEST-0404", "order 42 not found");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw serviceError(HttpStatus.CONFLICT, "DC-TEST-0409", "name already taken");
        }

        @GetMapping("/remote")
        String remote() {
            throw serviceError(HttpStatus.SERVICE_UNAVAILABLE, "DC-TEST-0503", "orders.internal refused the call");
        }

        @GetMapping("/infra")
        String infra() {
            throw serviceError(HttpStatus.INTERNAL_SERVER_ERROR, "DC-TEST-0500", "transport down");
        }

        // Constraints on handler parameters: Spring MVC's native method validation applies to the
        // whole method, so even the @Valid body failure surfaces as HandlerMethodValidationException.
        @GetMapping("/quantity")
        String quantity(@org.springframework.web.bind.annotation.RequestParam @jakarta.validation.constraints.Min(1)
                int quantity) {
            return "ok";
        }

        @PostMapping("/copies")
        String copies(@jakarta.validation.Valid @RequestBody NewOrder order,
                @org.springframework.web.bind.annotation.RequestParam @jakarta.validation.constraints.Min(1) int copies) {
            return "ok";
        }

        @GetMapping("/unwritable")
        Unwritable unwritable() {
            return new Unwritable(); // the JSON converter fails while writing → framework 500
        }

        @GetMapping("/denied")
        String denied() {
            throw new org.springframework.security.access.AccessDeniedException("method security says no");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("cannot reach jdbc://secret-host:5432");
        }

        // The chains observed on Oracle (OracleExceptionPathsIT), with Oracle's message text.
        @GetMapping("/duplicate")
        String duplicate() {
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "could not execute batch [ORA-00001: unique constraint (APP.ORDERS_REF_UK) violated] "
                            + "[insert into orders (reference_code,id) values (?,?)]",
                    new org.hibernate.exception.ConstraintViolationException("could not execute batch",
                            new java.sql.BatchUpdateException("ORA-00001: unique constraint (APP.ORDERS_REF_UK) violated",
                                    "23000", 1, new int[0]), "APP.ORDERS_REF_UK"));
        }

        @GetMapping("/stale")
        String stale() {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "Row was updated or deleted by another transaction: [ORDERS#42]");
        }

        @GetMapping("/db-down")
        String dbDown() {
            throw new org.springframework.transaction.CannotCreateTransactionException(
                    "Could not open JPA EntityManager for transaction",
                    new org.hibernate.exception.JDBCConnectionException("Unable to acquire JDBC Connection",
                            new java.sql.SQLTransientConnectionException(
                                    "HikariPool-1 - Connection is not available, request timed out after 5000ms "
                                            + "(jdbc:oracle:thin:@db-secret-host:1521/APP)")));
        }

        @GetMapping("/bad-sql")
        String badSql() {
            throw new org.springframework.jdbc.BadSqlGrammarException("select",
                    "select secret_column from orders", new java.sql.SQLException("ORA-00942", "42000"));
        }

        // Status and body disagree: the exception's status wins.
        @GetMapping("/mismatch-5xx")
        String mismatchServerError() {
            ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "db-7.internal refused");
            throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, body, null);
        }

        @GetMapping("/mismatch-4xx")
        String mismatchClientError() {
            ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "field x is missing");
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST, body, null);
        }

        // A body whose extensions are named like standard members or platform-owned fields.
        @GetMapping("/reserved")
        String reserved() {
            ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "boom");
            body.setProperty("detail", "connection to db-7.internal refused");
            body.setProperty("stacktrace", "at db.Driver.connect(Driver.java:42)");
            body.setProperty("traceId", "ffffffffffffffffffffffffffffffff");
            body.setProperty("status", 200);
            body.setProperty("code", "DC-TEST-0599");
            throw new ErrorResponseException(HttpStatus.INTERNAL_SERVER_ERROR, body, null);
        }

        @GetMapping("/code")
        String code(@org.springframework.web.bind.annotation.RequestParam String value) {
            throw serviceError(HttpStatus.CONFLICT, value, "conflict");
        }

        @GetMapping("/client-gone")
        String clientGone() throws java.io.IOException {
            throw new org.springframework.web.context.request.async.AsyncRequestNotUsableException("client went away");
        }

        @GetMapping("/committed")
        void committed(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
            response.getWriter().write("partial");
            response.flushBuffer();
            throw new IllegalStateException("failed after the response was committed");
        }

        // One instance thrown by every request: a service may prebuild its exceptions.
        @GetMapping("/shared")
        String shared() {
            throw SHARED;
        }

        @GetMapping("/annotated")
        String annotated() {
            throw new AlreadyShipped();
        }

        @GetMapping("/annotated-5xx")
        String annotatedServerError() {
            throw new LedgerDown();
        }

        @GetMapping("/status")
        String statusException() {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "quantity exceeds stock");
        }

        @PostMapping("/orders")
        String create(@jakarta.validation.Valid @RequestBody NewOrder order) {
            return "created";
        }
    }

    static final ErrorResponseException SHARED = serviceError(HttpStatus.CONFLICT, "DC-TEST-0410", "slot taken");

    @ResponseStatus(code = HttpStatus.CONFLICT, reason = "order already shipped")
    static final class AlreadyShipped extends RuntimeException {
    }

    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    static final class LedgerDown extends RuntimeException {
        LedgerDown() {
            super("ledger.internal:8443 refused");
        }
    }

    public static final class Unwritable {
        public String getValue() {
            throw new IllegalStateException("serializer reached db-secret-host");
        }
    }

    @Test
    void securityDenialsPassThroughToSpringSecurityInsteadOfBecoming500() {
        // A method-security denial inside a controller must reach ExceptionTranslationFilter (which
        // renders the platform 403); the catch-all would otherwise turn it into a 500.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mockMvc(defaults()).perform(get("/denied")))
                .rootCause().isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void twoHundredConcurrentErrorsEachCarryTheirOwnTraceId() throws Exception {
        MockMvc mvc = mockMvc(defaults());
        // Plan §3.11: three consecutive rounds in-test. MockMvc runs on the calling thread, so each
        // caller's trace is that request's trace.
        for (int round = 0; round < 3; round++) {
            try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(50)) {
                List<java.util.concurrent.Future<String[]>> results = new java.util.ArrayList<>();
                for (int i = 0; i < 200; i++) {
                    results.add(pool.submit(() -> {
                        String traceId = java.util.UUID.randomUUID().toString().replace("-", "");
                        try (AutoCloseable scope = trace(traceId)) {
                            String body = mvc.perform(get("/boom")).andReturn().getResponse().getContentAsString();
                            return new String[] {traceId, com.jayway.jsonpath.JsonPath.read(body, "$.traceId")};
                        }
                    }));
                }
                for (java.util.concurrent.Future<String[]> result : results) {
                    String[] sentAndSeen = result.get();
                    assertThat(sentAndSeen[1]).as("round %d", round).isEqualTo(sentAndSeen[0]);
                }
            }
        }
    }

    @Test
    void nativeMethodValidationOfAQueryParameterMapsToTheErrorsShape() throws Exception {
        mockMvc(defaults()).perform(get("/quantity").param("quantity", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DC-CORE-0400"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andExpect(jsonPath("$.errors[0].message").exists())
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    }

    @Test
    void nativeMethodValidationOfANestedArgumentListsItsFieldPaths() throws Exception {
        mockMvc(defaults())
                .perform(post("/copies").param("copies", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"password\":\"0123456789abcdef\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DC-CORE-0400"))
                .andExpect(jsonPath("$.errors[?(@.field=='name')].message").exists());
    }

    @Test
    void frameworkServerErrorsAreLoggedOnceWithTheThrowableAndTheTrace() throws Exception {
        ch.qos.logback.classic.Logger handlerLog =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PlatformExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        handlerLog.addAppender(appender);
        try (AutoCloseable scope = trace(CORRELATION)) {
            mockMvc(defaults()).perform(get("/unwritable"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("DC-CORE-0500"))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                    .andExpect(jsonPath("$.traceId").value(CORRELATION));
        } finally {
            handlerLog.detachAppender(appender);
        }
        assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .singleElement().satisfies(event -> {
                    assertThat(event.getThrowableProxy()).isNotNull();
                    assertThat(event.getMDCPropertyMap()).containsEntry("traceId", CORRELATION);
                });
    }

    @Test
    void aResponseStatusAnnotatedExceptionKeepsItsStatusAndReason() throws Exception {
        mockMvc(defaults()).perform(get("/annotated"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DC-CORE-0499"))
                .andExpect(jsonPath("$.detail").value("order already shipped"))
                .andExpect(jsonPath("$.retryable").value(false));
        MvcResult result = mockMvc(defaults()).perform(get("/annotated-5xx"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DC-CORE-0500"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.retryable").value(true))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ledger.internal");
    }

    @Test
    void aResponseStatusExceptionKeepsItsStatusAndReasonWithThePlatformExtensions() throws Exception {
        try (AutoCloseable scope = trace(CORRELATION)) {
            mockMvc(defaults()).perform(get("/status"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("DC-CORE-0499"))
                    .andExpect(jsonPath("$.detail").value("quantity exceeds stock"))
                    .andExpect(jsonPath("$.traceId").value(CORRELATION))
                    .andExpect(jsonPath("$.type").value("urn:dc:problem:DC-CORE-0499"));
        }
    }

    @Test
    void aServiceClientErrorIsLoggedAtDebugAndAServerErrorAtErrorWithTheThrowable() throws Exception {
        ch.qos.logback.classic.Logger handlerLog =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PlatformExceptionHandler.class);
        ch.qos.logback.classic.Level previous = handlerLog.getLevel();
        handlerLog.setLevel(ch.qos.logback.classic.Level.DEBUG);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        handlerLog.addAppender(appender);
        try {
            mockMvc(defaults()).perform(get("/conflict")).andExpect(status().isConflict());
            mockMvc(defaults()).perform(get("/infra")).andExpect(status().isInternalServerError());
        } finally {
            handlerLog.detachAppender(appender);
            handlerLog.setLevel(previous);
        }
        assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.DEBUG)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("DC-TEST-0409"));
        assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .singleElement().satisfies(event -> {
                    assertThat(event.getFormattedMessage()).contains("DC-TEST-0500");
                    assertThat(event.getThrowableProxy()).isNotNull();
                });
    }

    @Test
    void aSharedExceptionInstanceNeverCarriesOneRequestsTraceIntoAnother() throws Exception {
        MockMvc mvc = mockMvc(defaults());
        for (int round = 0; round < 3; round++) {
            try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(50)) {
                List<java.util.concurrent.Future<String[]>> results = new java.util.ArrayList<>();
                for (int i = 0; i < 200; i++) {
                    results.add(pool.submit(() -> {
                        String traceId = java.util.UUID.randomUUID().toString().replace("-", "");
                        try (AutoCloseable scope = trace(traceId)) {
                            String body = mvc.perform(get("/shared")).andReturn().getResponse().getContentAsString();
                            return new String[] {traceId, com.jayway.jsonpath.JsonPath.read(body, "$.traceId"),
                                    com.jayway.jsonpath.JsonPath.read(body, "$.code")};
                        }
                    }));
                }
                for (java.util.concurrent.Future<String[]> result : results) {
                    String[] seen = result.get();
                    assertThat(seen[1]).as("round %d", round).isEqualTo(seen[0]);
                    assertThat(seen[2]).isEqualTo("DC-TEST-0410");
                }
            }
        }
        assertThat(SHARED.getBody().getProperties()).as("the exception's own body is never decorated")
                .containsOnlyKeys("code");
        assertThat(SHARED.getBody().getInstance()).isNull();
    }

    @Test
    void theExceptionsStatusWinsOverItsBodysInBothDirections() throws Exception {
        MvcResult server = mockMvc(defaults()).perform(get("/mismatch-5xx"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("DC-CORE-0500"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.retryable").value(true))
                .andReturn();
        assertThat(server.getResponse().getContentAsString()).doesNotContain("db-7");
        mockMvc(defaults()).perform(get("/mismatch-4xx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("DC-CORE-0499"))
                .andExpect(jsonPath("$.detail").value("field x is missing"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void extensionsNamedLikeStandardOrPlatformFieldsNeverReachTheBody() throws Exception {
        String withoutTrace = mockMvc(defaults()).perform(get("/reserved"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
        assertThat(withoutTrace).doesNotContain("db-7", "Driver.java", "ffffffffffffffffffffffffffffffff");
        assertThat(occurrences(withoutTrace, "\"detail\"")).isEqualTo(1);
        assertThat(occurrences(withoutTrace, "\"status\"")).isEqualTo(1);
        assertThat(occurrences(withoutTrace, "\"traceId\"")).as("no trace: none in the body").isZero();
        assertThat(occurrences(withoutTrace, "\"stacktrace\"")).isZero();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(withoutTrace, "$.code")).isEqualTo("DC-TEST-0599");
        try (AutoCloseable scope = trace(CORRELATION)) {
            String traced = mockMvc(defaults()).perform(get("/reserved")).andReturn().getResponse().getContentAsString();
            assertThat(occurrences(traced, "\"traceId\"")).isEqualTo(1);
            assertThat(com.jayway.jsonpath.JsonPath.<String>read(traced, "$.traceId")).isEqualTo(CORRELATION);
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void aCodeWithURIUnsafeCharactersIsEncodedInTheTypeAndKeptRawElsewhere() throws Exception {
        for (String code : List.of("ORDER ALREADY PAID", "ORDER%FAILED", "ORD#1", "قيد-1")) {
            MvcResult result = mockMvc(defaults()).perform(get("/code").param("value", code))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.title").value(code))
                    .andReturn();
            String type = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.type");
            assertThat(java.net.URI.create(type)).as(code).isNotNull();
            assertThat(type).startsWith("urn:dc:problem:")
                    .isEqualTo("urn:dc:problem:" + org.springframework.web.util.UriUtils.encode(code,
                            java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void aGoneClientOrACommittedResponseGetsNothingMoreAndNoUnexpectedErrorLog() throws Exception {
        ch.qos.logback.classic.Logger handlerLog =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PlatformExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        handlerLog.addAppender(appender);
        try {
            MvcResult gone = mockMvc(defaults()).perform(get("/client-gone")).andReturn();
            assertThat(gone.getResponse().getContentAsString()).isEmpty();
            MvcResult committed = mockMvc(defaults()).perform(get("/committed")).andReturn();
            assertThat(committed.getResponse().getContentAsString()).isEqualTo("partial");
        } finally {
            handlerLog.detachAppender(appender);
        }
        assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("unhandled exception"));
        assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("committed"));
    }

    @RestController
    static final class SharedThrower {

        private final RuntimeException shared;
        private final RuntimeException reason;

        SharedThrower(RuntimeException shared, RuntimeException reason) {
            this.shared = shared;
            this.reason = reason;
        }

        @GetMapping("/localised")
        String localised() {
            throw shared;
        }

        @GetMapping("/reason")
        String reason() {
            throw reason;
        }
    }

    // Codex round 1, finding 1: a shared exception localised per request, in two locales at once. Each response
    // gets its own locale's detail; the exception's own body is never written.
    @Test
    void aSharedExceptionIsLocalisedPerRequestWithoutWritingItsOwnBody() throws Exception {
        org.springframework.context.support.StaticMessageSource messages =
                new org.springframework.context.support.StaticMessageSource();
        messages.addMessage("problemDetail.slot", java.util.Locale.ENGLISH, "Slot {0} is taken");
        messages.addMessage("problemDetail.slot", java.util.Locale.FRENCH, "Le créneau {0} est pris");
        messages.addMessage("slot.reason", java.util.Locale.ENGLISH, "No free slot");
        messages.addMessage("slot.reason", java.util.Locale.FRENCH, "Aucun créneau libre");
        ErrorsProperties properties = defaults();
        ProblemDetailFactory factory = new ProblemDetailFactory(properties, request -> TRACE.get(), FIXED_CLOCK);
        FrameworkErrors framework = new FrameworkErrors(properties, factory);
        framework.setMessageSource(messages);
        ErrorResponseException shared = new ErrorResponseException(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "slot taken"), null, "problemDetail.slot",
                new Object[] {7});
        org.springframework.web.server.ResponseStatusException reason =
                new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "slot.reason");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SharedThrower(shared, reason))
                .setControllerAdvice(new PlatformExceptionHandler(factory, framework))
                .build();
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(20)) {
            List<java.util.concurrent.Future<String[]>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String language = i % 2 == 0 ? "en" : "fr";
                String path = i % 4 < 2 ? "/localised" : "/reason";
                results.add(pool.submit(() -> new String[] {language, path, com.jayway.jsonpath.JsonPath.read(
                        mvc.perform(get(path).header("Accept-Language", language)).andReturn().getResponse()
                                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.detail")}));
            }
            for (java.util.concurrent.Future<String[]> result : results) {
                String[] seen = result.get();
                String expected = seen[1].equals("/localised")
                        ? (seen[0].equals("en") ? "Slot 7 is taken" : "Le créneau 7 est pris")
                        : (seen[0].equals("en") ? "No free slot" : "Aucun créneau libre");
                assertThat(seen[2]).as("%s %s", seen[0], seen[1]).isEqualTo(expected);
            }
        }
        assertThat(shared.getBody().getDetail()).isEqualTo("slot taken");
        assertThat(shared.getBody().getProperties()).isNull();
        assertThat(reason.getBody().getDetail()).isEqualTo("slot.reason");
    }
}
