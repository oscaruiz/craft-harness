# First completing real run — solo-pack on myCQRS (2026-07-19)

The demonstration run D24 said was environment-bound: `run-solo` completed
end-to-end (`specify → R6 approval → code → verify → done`) against the real
myCQRS Maven project, on the approved QueryInterceptor task. This is the run
that never happened during D23. No sixth environment issue surfaced.

## Environment fix that unblocked it

WSL Ubuntu had no Linux JDK/Maven (the D23/D24 toolchain split). Installed
without sudo via sdkman:

- Java **21.0.5-tem** (Temurin) — `~/.sdkman/candidates/java/current/bin/java`
- Maven **3.9.16** — `~/.sdkman/candidates/maven/current/bin/mvn`
- sdkman's `unzip`/`zip` prerequisites were absent (apt needs sudo); shimmed in
  `~/.local/bin` with a minimal python3 `unzip` (`-t/-o/-q/-d`, restores exec
  bits from zip external attrs) and python3 `zip`. sdkman init appended to
  `~/.bashrc`.

Pre-harness sanity check: `mvn -q test -pl src/core` in `/mnt/d/Dev/myCQRS`
exited 0 directly (before any harness involvement), proving the project builds
in this environment.

## Run facts

- Project: `/mnt/d/Dev/myCQRS`, enforced branch `craft/query-interceptor`.
- Pack install: `solo-pack` at `c97ad0086af5be1a40c28f0672ae6e71264e3a30`.
- Flags: `--adapter claude-code --phase-timeout 1200` (other flags default).
- Prior state: staged QueryInterceptor reference work stashed; old
  `.craft-harness` (status `failed`, the D23 run) wiped; fresh start.
- R6 gate: runner stopped at `awaiting_approval`, printed the one-time token to
  the runner console; owner-approved. (Caveat for this specific run: the token
  transited the assistant conversation and the assistant wrote the APPROVED
  file at the owner's explicit instruction — so this run demonstrates the
  pipeline, not the "agent never handles the token" property. That property
  holds by construction and was demonstrated by the toy runs.)
- Result: `run-solo: SUCCESS — all phases complete`, `status=done`.
- Candidate commit: `6c00164cc2f72026c3920f019f2de46331613b17`, author
  `craft-harness <noreply@craft-harness.local>` — the m4.8 `ensure_commit_identity`
  seeding worked on the real project (D23 fix (a) confirmed in vivo).
- Toolchain reachability: `mvn` resolved through the inherited PATH (D23 fix
  (b) confirmed in vivo). TEST_CMD came from myCQRS `project.prompt`
  (`test: mvn -q test -pl src/core`) via the D22 literal-injection mechanism.

## Verify evidence (primary, not summary)

- Verify ran the declared TEST_CMD **exactly as written**: `mvn -q test -pl
  src/core`, exit 0 — **43 tests, 0 failures, 0 errors, 0 skipped** across 8
  classes. It did **not** fall back to `./mvnw` (the spec's `./mvnw` mention was
  specifier prose; the enforced command is the declared one). Verify counted
  from `src/core/target/surefire-reports/*.txt`, not the quiet console.
- Independent re-execution by the session operator at the checked-out candidate
  commit reproduced it exactly: exit 0, 43/0/0/0. Per class: ArchitectureTest 5,
  AggregateRootTest 4, SimpleCommandBusTest 6, SimpleEventBusTest 5,
  SimpleQueryBusTest 9, IdempotencyCommandInterceptorTest 3,
  CorrelationIdCommandInterceptorTest 5, CorrelationIdQueryInterceptorTest 6.
- All 13 tagged scenarios traced (@SUT-1..13). Accepted deviation: @SUT-7's
  test reuses `FakeQuery` instead of the feature file's literal "OrphanQuery"
  name; all observable assertions present.
- `ArchitectureTest` confirmed absent from the commit's file list and green.

## Test coverage vs task invariants

The code phase wrote **12 new test methods** (the D23 run wrote none):
`SimpleQueryBusTest` 3 → 9, `CorrelationIdQueryInterceptorTest` new with 6.

| Invariant | Test | Provenance |
|---|---|---|
| Registration order, outermost first | `shouldRunInterceptorsInRegistrationOrder_outermostFirst` (journal `A:before, B:before, handler, B:after, A:after`) | new |
| Result returned intact | `shouldReturnSameObject_whenChainIsPassThrough` (`assertSame`) | new |
| Duplicate registration unchanged | `shouldThrowWithQueryTypeName_whenHandlerAlreadyRegistered` | pre-existing, kept green (spec @SUT-1 baseline) |
| Pre-existing correlation id propagated, not cleared | `propagates_existing_mdc_value`, `does_not_clear_when_value_was_pre_existing`, `pre_existing_value_survives_a_throwing_chain` | new |
| Absent id generated, removed on every exit path incl. throw | `generates_uuid_when_mdc_empty_and_returns_result_unchanged`, `clears_mdc_when_it_set_it`, `clears_mdc_even_when_chain_throws` | new |
| ArchitectureTest still green, unmodified | 5 tests green | pre-existing |

Plus short-circuit, decorate, exception-propagation, and
interceptors-never-run-on-missing-handler tests (@SUT-4..7).

## Inspector report (verbatim, exit 0)

```
inspect-run — solo-pack session /mnt/d/Dev/myCQRS/.craft-harness/solo/current against project /mnt/d/Dev/myCQRS
  enforced branch: craft/query-interceptor

  [x] (a) no mutation invocation in the session logs
  [x] (b) executed CRAP threshold was 6
  [x] (c) commits only on the enforced branch, no blacklisted path
  [x] (d) structured handoffs schema-valid and consumed
  [x] (f) every spec scenario ID is traced to a test or verify check

RESULT: PASS
```

## Wrappers log (verbatim) — note the DRY failures

```
crap: score=6
crap: threshold=6
crap: offenders=src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBus.java:3 src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/observability/CorrelationIdQueryInterceptor.java:3
crap: result=pass
dry: score=44
dry: threshold=0
dry: offenders=src/core/src/main/java/com/oscaruiz/mycqrs/core/contracts/query/QueryInterceptor.java src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBus.java src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/observability/CorrelationIdQueryInterceptor.java src/core/src/test/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBusTest.java src/core/src/test/java/com/oscaruiz/mycqrs/core/infrastructure/observability/CorrelationIdQueryInterceptorTest.java
dry: result=fail
dry: score=3
dry: threshold=0
dry: offenders=src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/observability/CorrelationIdCommandInterceptor.java
dry: result=fail
dry: score=5
dry: threshold=0
dry: offenders=src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/bus/command/SimpleCommandBus.java
dry: result=fail
```

**Open observation for the owner's verdict:** the DRY wrapper failed three
times and nothing blocked on it — the code phase recorded the toy threshold
(0) as structurally unattainable for Java and proceeded; verify carried it as
an open item; `inspect-run` has no DRY check at all. DRY is currently
*advisory* in solo-pack, in tension with the design's "quality gates" framing.
Design-vs-reality clash per CLAUDE.md; needs a decisions.md verdict (enforce a
realistic per-language threshold, or record it as advisory by design).

Other carried-forward open item: wiring the query interceptor into
Spring/Micronaut/demo modules is a follow-up task (explicitly out of scope).

## Candidate diff (verbatim, `.craft-harness/solo/current/diff.patch`)

Commit `6c00164` — `feat(core): add interceptor chain support to SimpleQueryBus`
— 5 files, +301/−1:

```diff
diff --git a/src/core/src/main/java/com/oscaruiz/mycqrs/core/contracts/query/QueryInterceptor.java b/src/core/src/main/java/com/oscaruiz/mycqrs/core/contracts/query/QueryInterceptor.java
new file mode 100644
index 0000000..2963ac5
--- /dev/null
+++ b/src/core/src/main/java/com/oscaruiz/mycqrs/core/contracts/query/QueryInterceptor.java
@@ -0,0 +1,24 @@
+package com.oscaruiz.mycqrs.core.contracts.query;
+
+/**
+ * Allows pre-processing or wrapping query execution.
+ *
+ * <p>An interceptor must return either the value produced by {@code next.invoke(query)}
+ * or a deliberate replacement/short-circuit value assignable to the query's declared
+ * result type {@code R}.
+ */
+@FunctionalInterface
+public interface QueryInterceptor {
+    /**
+     * Called before or around query execution.
+     *
+     * @param query the query being handled
+     * @param next function to invoke the actual handler
+     * @return the query result, either from {@code next} or a replacement
+     */
+    Object intercept(Query<?> query, QueryHandlerInvoker next);
+
+    interface QueryHandlerInvoker {
+        Object invoke(Query<?> query);
+    }
+}
diff --git a/src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBus.java b/src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBus.java
index 41ed375..92b5d1e 100644
--- a/src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBus.java
+++ b/src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBus.java
@@ -5,13 +5,17 @@ import com.oscaruiz.mycqrs.core.contracts.query.Query;
 import com.oscaruiz.mycqrs.core.contracts.query.QueryBus;
 import com.oscaruiz.mycqrs.core.contracts.query.QueryHandler;
 import com.oscaruiz.mycqrs.core.contracts.query.QueryHandlerNotFoundException;
+import com.oscaruiz.mycqrs.core.contracts.query.QueryInterceptor;
 
+import java.util.List;
 import java.util.Map;
 import java.util.concurrent.ConcurrentHashMap;
+import java.util.concurrent.CopyOnWriteArrayList;
 
 public class SimpleQueryBus implements QueryBus {
 
     private final Map<Class<? extends Query<?>>, QueryHandler<?, ?>> handlers = new ConcurrentHashMap<>();
+    private final List<QueryInterceptor> interceptors = new CopyOnWriteArrayList<>();
 
     public <Q extends Query<R>, R> void registerHandler(Class<Q> queryType, QueryHandler<Q, R> handler) {
         if (handlers.putIfAbsent(queryType, handler) != null) {
@@ -19,6 +23,15 @@ public class SimpleQueryBus implements QueryBus {
         }
     }
 
+    /**
+     * Registers a query interceptor to be applied around handler execution.
+     *
+     * @param interceptor the interceptor to add
+     */
+    public void addInterceptor(QueryInterceptor interceptor) {
+        interceptors.add(interceptor);
+    }
+
     @SuppressWarnings("unchecked")
     @Override
     public <R, Q extends Query<R>> R handle(Q query) {
@@ -27,6 +40,23 @@ public class SimpleQueryBus implements QueryBus {
             Class<? extends Query<?>> queryType = (Class<? extends Query<?>>) query.getClass();
             throw new QueryHandlerNotFoundException(queryType);
         }
-        return handler.handle(query);
+
+        // Define the core handler invocation
+        QueryInterceptor.QueryHandlerInvoker invoker = q -> handler.handle((Q) q);
+
+        // Wrap with interceptors
+        return (R) applyInterceptors(query, invoker);
+    }
+
+    private Object applyInterceptors(Query<?> query, QueryInterceptor.QueryHandlerInvoker target) {
+        QueryInterceptor.QueryHandlerInvoker chain = target;
+
+        for (int i = interceptors.size() - 1; i >= 0; i--) {
+            QueryInterceptor interceptor = interceptors.get(i);
+            QueryInterceptor.QueryHandlerInvoker next = chain;
+            chain = q -> interceptor.intercept(q, next);
+        }
+
+        return chain.invoke(query);
     }
 }
diff --git a/src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/observability/CorrelationIdQueryInterceptor.java b/src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/observability/CorrelationIdQueryInterceptor.java
new file mode 100644
index 0000000..85662c1
--- /dev/null
+++ b/src/core/src/main/java/com/oscaruiz/mycqrs/core/infrastructure/observability/CorrelationIdQueryInterceptor.java
@@ -0,0 +1,42 @@
+package com.oscaruiz.mycqrs.core.infrastructure.observability;
+
+import com.oscaruiz.mycqrs.core.contracts.query.Query;
+import com.oscaruiz.mycqrs.core.contracts.query.QueryInterceptor;
+import org.slf4j.MDC;
+
+import java.util.UUID;
+
+/**
+ * Populates the SLF4J {@link MDC} correlation id for the duration of query handling so
+ * every log line emitted downstream (interceptors, handler, projections) carries a
+ * single trace key.
+ *
+ * <p>Ownership rules:
+ * <ul>
+ *   <li>If the key is already set (HTTP filter populated it, or the outbox poller did before
+ *       re-dispatching an event), the interceptor propagates that value and does NOT clear
+ *       it on exit — clearing is the outer scope's responsibility.</li>
+ *   <li>If the key is absent, the interceptor generates a {@link UUID}, puts it in MDC, and
+ *       removes it on exit (including when the chain throws).</li>
+ * </ul>
+ *
+ * <p>Registered as the outermost interceptor in the chain so that every log line emitted
+ * during query handling is tagged with the correlation id too.
+ */
+public final class CorrelationIdQueryInterceptor implements QueryInterceptor {
+
+    @Override
+    public Object intercept(Query<?> query, QueryHandlerInvoker next) {
+        boolean preExisting = MDC.get(CorrelationIdMdc.KEY) != null;
+        if (!preExisting) {
+            MDC.put(CorrelationIdMdc.KEY, UUID.randomUUID().toString());
+        }
+        try {
+            return next.invoke(query);
+        } finally {
+            if (!preExisting) {
+                MDC.remove(CorrelationIdMdc.KEY);
+            }
+        }
+    }
+}
diff --git a/src/core/src/test/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBusTest.java b/src/core/src/test/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBusTest.java
index 3e91be2..243e1f5 100644
--- a/src/core/src/test/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBusTest.java
+++ b/src/core/src/test/java/com/oscaruiz/mycqrs/core/infrastructure/bus/query/SimpleQueryBusTest.java
@@ -4,10 +4,17 @@ import com.oscaruiz.mycqrs.core.contracts.query.DuplicateQueryHandlerException;
 import com.oscaruiz.mycqrs.core.contracts.query.Query;
 import com.oscaruiz.mycqrs.core.contracts.query.QueryHandler;
 import com.oscaruiz.mycqrs.core.contracts.query.QueryHandlerNotFoundException;
+import com.oscaruiz.mycqrs.core.contracts.query.QueryInterceptor;
 import org.junit.jupiter.api.BeforeEach;
 import org.junit.jupiter.api.Test;
 
+import java.util.ArrayList;
+import java.util.List;
+import java.util.concurrent.atomic.AtomicBoolean;
+
 import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertFalse;
+import static org.junit.jupiter.api.Assertions.assertSame;
 import static org.junit.jupiter.api.Assertions.assertThrows;
 import static org.junit.jupiter.api.Assertions.assertTrue;
 
@@ -53,9 +60,102 @@ class SimpleQueryBusTest {
         assertTrue(exception.getMessage().contains("FakeQuery"));
     }
 
+    @Test
+    void shouldRunInterceptorsInRegistrationOrder_outermostFirst() {
+        List<String> journal = new ArrayList<>();
+        bus.registerHandler(FakeQuery.class, query -> {
+            journal.add("handler");
+            return "result";
+        });
+        bus.addInterceptor(journaling("A", journal));
+        bus.addInterceptor(journaling("B", journal));
+
+        String result = bus.handle(new FakeQuery());
+
+        assertEquals("result", result);
+        assertEquals(List.of("A:before", "B:before", "handler", "B:after", "A:after"), journal);
+    }
+
+    @Test
+    void shouldReturnSameObject_whenChainIsPassThrough() {
+        Object handlerResult = new Object();
+        bus.registerHandler(ObjectQuery.class, query -> handlerResult);
+        bus.addInterceptor((query, next) -> next.invoke(query));
+        bus.addInterceptor((query, next) -> next.invoke(query));
+
+        Object result = bus.handle(new ObjectQuery());
+
+        assertSame(handlerResult, result);
+    }
+
+    @Test
+    void shouldShortCircuit_whenInterceptorDoesNotInvokeNext() {
+        AtomicBoolean handlerInvoked = new AtomicBoolean(false);
+        bus.registerHandler(FakeQuery.class, query -> {
+            handlerInvoked.set(true);
+            return "result";
+        });
+        bus.addInterceptor((query, next) -> "cached");
+
+        String result = bus.handle(new FakeQuery());
+
+        assertEquals("cached", result);
+        assertFalse(handlerInvoked.get());
+    }
+
+    @Test
+    void shouldDecorateHandlerResult_whenInterceptorTransformsIt() {
+        bus.registerHandler(FakeQuery.class, new FakeQueryHandler());
+        bus.addInterceptor((query, next) -> next.invoke(query) + "-decorated");
+
+        String result = bus.handle(new FakeQuery());
+
+        assertEquals("result-decorated", result);
+    }
+
+    @Test
+    void shouldPropagateHandlerException_throughTheChain() {
+        RuntimeException boom = new RuntimeException("boom");
+        bus.registerHandler(FakeQuery.class, query -> {
+            throw boom;
+        });
+        bus.addInterceptor((query, next) -> next.invoke(query));
+
+        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bus.handle(new FakeQuery()));
+
+        assertSame(boom, thrown);
+    }
+
+    @Test
+    void shouldFailBeforeAnyInterceptorRuns_whenNoHandlerRegistered() {
+        List<String> journal = new ArrayList<>();
+        bus.addInterceptor(journaling("A", journal));
+        FakeQuery query = new FakeQuery();
+
+        QueryHandlerNotFoundException exception = assertThrows(
+            QueryHandlerNotFoundException.class,
+            () -> bus.handle(query)
+        );
+
+        assertTrue(exception.getMessage().contains("FakeQuery"));
+        assertTrue(journal.isEmpty());
+    }
+
+    private static QueryInterceptor journaling(String name, List<String> journal) {
+        return (query, next) -> {
+            journal.add(name + ":before");
+            Object result = next.invoke(query);
+            journal.add(name + ":after");
+            return result;
+        };
+    }
+
     static class FakeQuery implements Query<String> {
     }
 
+    static class ObjectQuery implements Query<Object> {
+    }
+
     static class FakeQueryHandler implements QueryHandler<FakeQuery, String> {
         @Override
         public String handle(FakeQuery query) {
diff --git a/src/core/src/test/java/com/oscaruiz/mycqrs/core/infrastructure/observability/CorrelationIdQueryInterceptorTest.java b/src/core/src/test/java/com/oscaruiz/mycqrs/core/infrastructure/observability/CorrelationIdQueryInterceptorTest.java
new file mode 100644
index 0000000..4b23a1e
--- /dev/null
+++ b/src/core/src/test/java/com/oscaruiz/mycqrs/core/infrastructure/observability/CorrelationIdQueryInterceptorTest.java
@@ -0,0 +1,104 @@
+package com.oscaruiz.mycqrs.core.infrastructure.observability;
+
+import com.oscaruiz.mycqrs.core.contracts.query.Query;
+import com.oscaruiz.mycqrs.core.contracts.query.QueryInterceptor.QueryHandlerInvoker;
+import org.junit.jupiter.api.AfterEach;
+import org.junit.jupiter.api.Test;
+import org.slf4j.MDC;
+
+import java.util.concurrent.atomic.AtomicReference;
+import java.util.regex.Pattern;
+
+import static org.assertj.core.api.Assertions.assertThat;
+import static org.assertj.core.api.Assertions.assertThatThrownBy;
+
+class CorrelationIdQueryInterceptorTest {
+
+    private static final Pattern UUID_REGEX = Pattern.compile(
+        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
+    );
+
+    private final CorrelationIdQueryInterceptor interceptor = new CorrelationIdQueryInterceptor();
+
+    @AfterEach
+    void clearMdc() {
+        MDC.clear();
+    }
+
+    @Test
+    void generates_uuid_when_mdc_empty_and_returns_result_unchanged() {
+        AtomicReference<String> captured = new AtomicReference<>();
+        QueryHandlerInvoker next = query -> {
+            captured.set(MDC.get(CorrelationIdMdc.KEY));
+            return "result";
+        };
+
+        Object result = interceptor.intercept(new FakeQuery(), next);
+
+        assertThat(result).isEqualTo("result");
+        assertThat(captured.get()).isNotNull();
+        assertThat(UUID_REGEX.matcher(captured.get()).matches()).isTrue();
+        assertThat(MDC.get(CorrelationIdMdc.KEY)).isNull();
+    }
+
+    @Test
+    void propagates_existing_mdc_value() {
+        String existing = "outer-scope-id-123";
+        MDC.put(CorrelationIdMdc.KEY, existing);
+        AtomicReference<String> captured = new AtomicReference<>();
+        QueryHandlerInvoker next = query -> {
+            captured.set(MDC.get(CorrelationIdMdc.KEY));
+            return null;
+        };
+
+        interceptor.intercept(new FakeQuery(), next);
+
+        assertThat(captured.get()).isEqualTo(existing);
+        assertThat(MDC.get(CorrelationIdMdc.KEY)).isEqualTo(existing);
+    }
+
+    @Test
+    void clears_mdc_when_it_set_it() {
+        QueryHandlerInvoker next = query -> null;
+
+        interceptor.intercept(new FakeQuery(), next);
+
+        assertThat(MDC.get(CorrelationIdMdc.KEY)).isNull();
+    }
+
+    @Test
+    void does_not_clear_when_value_was_pre_existing() {
+        String existing = "already-here";
+        MDC.put(CorrelationIdMdc.KEY, existing);
+        QueryHandlerInvoker next = query -> null;
+
+        interceptor.intercept(new FakeQuery(), next);
+
+        assertThat(MDC.get(CorrelationIdMdc.KEY)).isEqualTo(existing);
+    }
+
+    @Test
+    void clears_mdc_even_when_chain_throws() {
+        QueryHandlerInvoker next = query -> { throw new RuntimeException("boom"); };
+
+        assertThatThrownBy(() -> interceptor.intercept(new FakeQuery(), next))
+            .isInstanceOf(RuntimeException.class)
+            .hasMessage("boom");
+
+        assertThat(MDC.get(CorrelationIdMdc.KEY)).isNull();
+    }
+
+    @Test
+    void pre_existing_value_survives_a_throwing_chain() {
+        String existing = "already-here";
+        MDC.put(CorrelationIdMdc.KEY, existing);
+        QueryHandlerInvoker next = query -> { throw new RuntimeException("boom"); };
+
+        assertThatThrownBy(() -> interceptor.intercept(new FakeQuery(), next))
+            .isInstanceOf(RuntimeException.class);
+
+        assertThat(MDC.get(CorrelationIdMdc.KEY)).isEqualTo(existing);
+    }
+
+    static class FakeQuery implements Query<String> {}
+}
```
