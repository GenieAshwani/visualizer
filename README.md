# CodeForSuccess · Array Lab

A working React + TypeScript + Tailwind + Monaco frontend, GSAP animation layer, and Java 21 / Spring Boot backend. Student programs are compiled and executed in a separate JVM. The Java Debug Interface (JDI) supplies all visualized values; examples are editable source code, not prerecorded traces.

## Run

Prerequisites: a full JDK 21 (`java` and `javac` on PATH), Maven 3.6.3+, Node.js 22+, and npm.

From this folder in PowerShell:

```powershell
.\start.ps1
```

This builds the frontend, runs backend tests, packages the UI into the Spring Boot JAR, and serves the app at **http://127.0.0.1:8085**. Stop an existing service on that port first. Press Ctrl+C to stop. Dependency downloads need network access; Monaco, its Java language support, and its worker are bundled locally, with no editor CDN dependency.

For development, use two terminals:

```powershell
cd backend
mvn spring-boot:run
```

```powershell
cd frontend
npm ci
npm run dev
```

Open **http://127.0.0.1:5173**. Vite proxies `/api` to the backend on port 8085.

## Use

1. Write a complete Java source file, including `public static void main(String[] args)`.
2. Set **Entry class** to its fully qualified class name (`Main`, or e.g. `example.Demo`). The filename follows that class. Imports, packages, helper methods, and additional package-private classes in that file work.
3. While typing, **Live preview** displays parsed declarations, array initializers, variables, and method signatures, including incomplete syntax. This does not compile or execute the program.
4. Click **Debug & Visualize** to compile and record actual execution. Debugging opens paused at the initial state, before the first statement; nothing automatically plays.
5. **Next** advances one source-statement boundary and animates its complete effect. **Previous** restores one recorded state immediately. Both work without Play. Play/Pause is optional; Restart returns to the initial paused state, and the timeline jumps to any step. Editing source or changing the entry class clears the old trace.
6. Enable **Predict before reveal** to pause before an upcoming array-cell change. Students enter and commit a prediction before Next unlocks; the result is checked against the recorded JVM snapshot. **Replay current step** replays its animation without moving the cursor.
7. **Projection view** enlarges the code and visualization and can hide the code or console. **Prepare lesson** saves starter code, entry class, sample stdin, teaching notes, and line-numbered prediction questions in browser storage; choose a saved lesson from the toolbar to restore it.

The highlighted line produced the displayed state. Separate statements on the same line have separate steps; internal bytecode changes within one compound statement are grouped. Loop conditions and update expressions are stepping boundaries. Calls step into user methods; entry/return boundaries show the corresponding stack frame. The initial step is the state before the first instruction. Previous/Restart restore recorded snapshots; they do not rerun side effects.

Violet marks changes. Mint marks a verified value transfer. The moving value travels from an array cell into a scalar, from a scalar into an array cell, or between array cells. Names pointing to the same JVM array share one memory object. Numeric markers beneath cells indicate integer locals whose value equals that index; they are not a claim that every such variable is an index. Variables leave the panel when they leave scope. The console explicitly displays **full-run output**, not output synchronized to the playback cursor.

## How real execution is traced

```text
Monaco source → POST /api/trace → javac -g -proc:none --release 21
                                 ↓
                         separate suspended JVM
                                 ↓
                JDI class preparation + method events + source-statement steps
                                 ↓
              stack frames / local variables / static fields / array objects
                                 ↓
                    immutable JSON runtime snapshots
                                 ↓
                   React playback cursor → GSAP renderer
```

The compiler Tree API identifies source-statement boundaries. `StatementSource` inserts only whitespace around these boundaries in the temporary compilation copy, allowing JDI to distinguish multiple statements on one original line. Traces without supplied stdin use `STEP_MIN` with `STEP_INTO` and collect completed source boundaries; traces with supplied input use `STEP_LINE` to avoid bytecode-level stepping through input parsing internals. Both modes emit source-statement snapshots, group compound statements, and map debugger locations, diagnostics, and stack frames back to the original editor source. Actual values still come exclusively from JDI. Standard-library internals are excluded from stepping. Mutations made by library methods are still visible when control returns to user code. JDI array identity preserves aliases and arrays retained by caller frames.

The Java compiler Tree API identifies simple array-load/store/copy expressions for optional transfer arrows. Index expressions support integer literals, locals, parentheses, and basic integer arithmetic. A transfer is emitted only when observed before/after values confirm it. Complex expressions still execute and visualize their real state changes; they may get a change pulse rather than a transfer particle. No expression evaluator supplies execution results.

## Live preview semantics

`POST /api/preview` accepts `{ "code": "..." }`. It uses javac's recovery-capable syntax parser only (`parse`, without attribution or compilation). An aborted/debounced frontend request cannot overwrite a newer preview. Source edits invalidate debug snapshots and line highlights.

Preview shows declarations across methods with scope/line labels, even if those methods are never called. It is **not a runtime memory snapshot**. Array literals, scalar literals, supported integer constant expressions, and one-dimensional fixed-size array defaults can be displayed. Final literal constants can supply lengths; mutable variables, calls, array reads, unsupported expressions, and missing initializers are `?`. Assignments, loops, branches, and methods are never executed by preview. Syntax recovery retains declarations from unfinished array literals and missing braces; parser-recovered declarations can be incomplete and preview does not validate Java types.

## Project layout and extension points

- `backend/.../JavaTracer.java`: compilation, debuggee lifecycle, JDI events, snapshots, cleanup, resource limits.
- `backend/.../JavaSyntax.java` and `StaticPreview.java`: recovery-capable syntax-only preview and conservative constant evaluation.
- `backend/.../StatementSource.java`: whitespace-only source normalization and original-source coordinates for statement stepping.
- `backend/.../TraceModel.java`: transport model; independent of rendering.
- `backend/.../SourceTransfers.java`: optional source semantics; failure never changes or blocks runtime tracing.
- `backend/.../TraceController.java`: REST API, input validation, single-run concurrency gate.
- `frontend/src/main.tsx`: editor, API client, playback, diagnostics, console, call stack.
- `frontend/src/ArrayStage.tsx`: array renderer, state-change descriptions, GSAP animation lifecycle.
- `frontend/src/types.ts`: shared frontend trace contracts.
- `frontend/src/examples.ts`: editable Java programs only.

Add new data structures by extending the snapshot value model and adding a renderer. Keep the compiler/JDI execution layer and playback cursor independent of any particular structure. This milestone has no authentication, database, or external service dependency.

## API

`GET /api/health` returns engine readiness metadata.

`POST /api/trace` accepts:

```json
{"mainClass":"Main","code":"public class Main { public static void main(String[] args) { int[] a = {1,2}; a[0] = 9; } }","input":"4\n7\n"}
```

The optional `input` string (up to 32,000 characters) is written to the debuggee's stdin, then EOF is sent; interactive prompts are not supported. The response contains `status`, `steps`, `diagnostics`, `stdout`, `stderr`, `message`, and `durationMs`. Each step has `executedLine` (effect just observed), `nextLine`, `method`, `variables`, `arrays` and `collections` keyed by JVM object ID, `stack`, plus verified array transfers and collection read/write flows. Collection values and recursive frame locals are captured from suspended JVM state; collection transfer animations require a matching source expression and observed before/after values. Statuses include `completed`, `compile_error`, `runtime_error`, `limit_reached`, `unsupported`, and `engine_error`. Partial traces remain inspectable after runtime failures and limits. HTTP 400 rejects invalid requests and HTTP 429 rejects concurrent runs.

## Milestone boundaries

- Single-threaded Java programs in one source file. Additional user threads are detected and reported as unsupported.
- Full JDK required. No third-party student dependencies or interactive stdin prompts; a saved lesson can pass up to 32,000 characters through stdin, followed by EOF. `System.out` and `System.err` are captured.
- Primitive arrays and arrays of strings are displayed by value. Nested arrays appear as separate array objects with references. Common JDK `List` implementations (including `ArrayList`, `LinkedList`, and `List.of`), `HashMap`/`LinkedHashMap`/`TreeMap`, and `HashSet`/`LinkedHashSet` contents are displayed when their runtime layout is recognized. Other collection implementations are explicitly marked as unsupported rather than shown as empty. General objects are shown as type/identity, not deep object visualizations. Recursive calls show separate stack frames and their local variables.
- Up to 40,000 source characters, 12 seconds of debuggee runtime, 60,000 traced instructions, 1,800 visible steps, 64 MB debuggee heap, 256 displayed elements per array, 128 displayed entries per collection, and 32 KB per output stream. Large arrays and collections are explicitly labeled as truncated.
- Playback follows a completed recording; this is not a remotely attached interactive debugger. No breakpoints, live variable edits, or stdin prompts in this milestone.

**Execution security:** this is a local development milestone. The server binds to `127.0.0.1`. Submitted Java runs with the OS permissions of the backend process. A child JVM, heap cap, and timeout are not a security sandbox. Run trusted code only. Before opening this to students over a network, replace the execution worker with isolated, unprivileged containers/VMs with read-only filesystems, no host mounts/secrets or network, process limits, and enforced termination. Do not expose this backend directly as a public code runner.

## Verification

```powershell
cd backend
mvn test
```

Backend tests first verify incomplete-code static preview without execution. Integration tests launch real JVMs and check swaps with transfers, collection contents and verified movement flows, recursive frames, nested-loop sorting, enhanced-for traversal, same-line operations, atomic compound and multi-line statements, source-coordinate preservation, helper calls/aliases, while-loop reversal, empty arrays, compiler/runtime errors, packages/static fields/primitive arrays, and limits.

With both development servers running:

```powershell
cd frontend
npx playwright install chromium
npm run test:e2e
npm run build
```

The browser tests call the actual backend without mocks. They verify live typing without any trace request, paused Debug startup, statement-level Next/Previous without Play, pointers, values, all examples, pasted custom code, source invalidation, diagnostics, line highlighting, moving particles, Pause/Play/Next/Previous/Restart/speed, and the mobile viewport. Screenshots and recordings are written to `frontend/test-results/`.

Technical references: [JDI stepping](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jdi/com/sun/jdi/request/StepRequest.html), [Java compiler Tree API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.compiler/com/sun/source/util/JavacTask.html).
