package com.codeforsuccess.trace;

import com.sun.jdi.*;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import static com.codeforsuccess.trace.TraceModel.*;

/** Compiles full source and records actual VM state at bytecode boundaries. */
public final class JavaTracer {
    private static final int MAX_STEPS = 1800, MAX_INSTRUCTIONS = 60_000, MAX_ARRAY = 256, MAX_COLLECTION = 128;
    private static final long TIMEOUT_MS = 12_000;

    public Result trace(Request request) {
        long start = System.nanoTime();
        List<Step> steps = new ArrayList<>();
        List<TraceModel.Diagnostic> diagnostics = new ArrayList<>();
        VirtualMachine vm = null;
        Process process = null;
        Path directory = null;
        Capture stdout = new Capture(), stderr = new Capture();
        Thread outThread = null, errThread = null, inputThread = null;
        AtomicReference<IOException> inputFailure = new AtomicReference<>();
        StatementSource statementSource = null;
        String status = "completed", message = "Execution complete. Every value was captured from the JVM.";
        try {
            directory = Files.createTempDirectory("cfs-java-");
            String simpleName = request.mainClass().substring(request.mainClass().lastIndexOf('.') + 1);
            Path source = directory.resolve(simpleName + ".java");
            statementSource = new StatementSource(request.code());
            Files.writeString(source, statementSource.source);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IllegalStateException("A full JDK 21 is required, not a JRE.");
            DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
            boolean compiled;
            try (StandardJavaFileManager files = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
                compiled = compiler.getTask(null, files, collector,
                        List.of("-g", "-proc:none", "--release", "21", "-encoding", "UTF-8", "-d", directory.toString(), "-classpath", directory.toString()),
                        null, files.getJavaFileObjects(source)).call();
            }
            for (var diagnostic : collector.getDiagnostics()) diagnostics.add(statementSource.diagnostic(diagnostic));
            if (!compiled) return new Result("compile_error", steps, diagnostics, "", "", "Java could not compile. Fix the highlighted errors and run again.", elapsed(start));
            Set<String> userClasses = new HashSet<>();
            try (var paths = Files.walk(directory)) {
                Path root = directory;
                paths.filter(p -> p.toString().endsWith(".class")).forEach(p -> userClasses.add(root.relativize(p).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "")));
            }
            SourceTransfers transfers = new SourceTransfers(statementSource.source);
            var connector = Bootstrap.virtualMachineManager().defaultConnector();
            Map<String, Connector.Argument> arguments = connector.defaultArguments();
            arguments.get("main").setValue(request.mainClass());
            arguments.get("options").setValue("-Xmx64m -Xss512k -Dfile.encoding=UTF-8 -cp \"" + directory + "\"");
            arguments.get("suspend").setValue("true");
            vm = connector.launch(arguments);
            process = vm.process();
            Process child = process;
            byte[] input = Objects.requireNonNullElse(request.input(), "").getBytes(StandardCharsets.UTF_8);
            int stepDepth = input.length == 0 ? StepRequest.STEP_MIN : StepRequest.STEP_LINE;
            if (input.length == 0) child.getOutputStream().close();
            else inputThread = Thread.startVirtualThread(() -> {
                try (OutputStream stream = child.getOutputStream()) {
                    stream.write(input);
                } catch (IOException e) {
                    inputFailure.set(e);
                }
            });
            outThread = Thread.startVirtualThread(() -> stdout.read(child.getInputStream()));
            errThread = Thread.startVirtualThread(() -> stderr.read(child.getErrorStream()));
            EventRequestManager manager = vm.eventRequestManager();
            ClassPrepareRequest prepare = manager.createClassPrepareRequest();
            prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            prepare.enable();
            ExceptionRequest exceptions = manager.createExceptionRequest(null, false, true);
            exceptions.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            exceptions.enable();
            long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000;
            long tracedThread = -1;
            int instructions = 0;
            StatementSource.Boundary pending = null;
            String pendingFrame = "";
            long previousInstruction = -1;
            boolean previousWasStep = false;
            boolean done = false;
            while (!done) {
                if (System.nanoTime() > deadline) {
                    status = "limit_reached"; message = "Stopped after 12 seconds. The partial trace is available."; break;
                }
                EventSet events = vm.eventQueue().remove(150);
                if (events == null) continue;
                for (Event event : events) {
                    if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) { done = true; continue; }
                    if (event instanceof ClassPrepareEvent prepared && userClasses.contains(prepared.referenceType().name())) {
                        MethodEntryRequest entry = manager.createMethodEntryRequest();
                        entry.addClassFilter(prepared.referenceType());
                        entry.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        entry.enable();
                        MethodExitRequest exit = manager.createMethodExitRequest();
                        exit.addClassFilter(prepared.referenceType());
                        exit.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        exit.enable();
                    }
                    if (event instanceof ExceptionEvent error) {
                        status = "runtime_error";
                        message = "Uncaught " + error.exception().referenceType().name() + " at line " + statementSource.originalLine(error.location().lineNumber()) + ".";
                    }
                    if (!(event instanceof StepEvent) && !(event instanceof MethodEntryEvent) && !(event instanceof MethodExitEvent)) continue;
                    LocatableEvent location = (LocatableEvent) event;
                    boolean userLocation = userClasses.contains(location.location().declaringType().name());
                    if (!userLocation) continue;
                    if (tracedThread == -1) {
                        tracedThread = location.thread().uniqueID();
                        StepRequest step = manager.createStepRequest(location.thread(), stepDepth, StepRequest.STEP_INTO);
                        for (String exclude : List.of("java.*", "javax.*", "sun.*", "com.sun.*", "jdk.*")) step.addClassExclusionFilter(exclude);
                        step.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        step.enable();
                    } else if (tracedThread != location.thread().uniqueID()) {
                        status = "unsupported"; message = "This milestone traces single-threaded programs only."; done = true; break;
                    }
                    if (++instructions > MAX_INSTRUCTIONS || steps.size() >= MAX_STEPS) {
                        status = "limit_reached"; message = "Trace limit reached. Try a smaller input or fewer loop iterations."; done = true; break;
                    }
                    var boundary = statementSource.boundary(location.location().lineNumber());
                    Step snapshot = snapshot(location.thread(), steps.size(), pending == null ? 0 : pending.line(), userClasses);
                    Step last = steps.isEmpty() ? null : steps.getLast();
                    String frame = snapshot.method() + ":" + snapshot.stack().size();
                    boolean exiting = event instanceof MethodExitEvent;
                    boolean repeated = event instanceof StepEvent && previousWasStep && pendingFrame.equals(frame)
                            && location.location().codeIndex() <= previousInstruction;
                    // Only completed source boundaries become playback steps. No partial compound assignments.
                    if ((last == null && boundary != null) || (pending != null && (exiting || repeated || !pending.equals(boundary) || !pendingFrame.equals(frame)))) {
                        snapshot = new Step(snapshot.index(), snapshot.executedLine(), snapshot.nextLine(), snapshot.method(), snapshot.variables(), snapshot.arrays(), snapshot.collections(), snapshot.stack(), transfers.between(last, snapshot), transfers.collectionFlows(last, snapshot));
                        steps.add(snapshot);
                    }
                    pending = exiting ? null : boundary;
                    pendingFrame = frame;
                    previousInstruction = location.location().codeIndex();
                    previousWasStep = event instanceof StepEvent;
                }
                if (!done) events.resume();
            }
            if (status.equals("completed") && steps.isEmpty()) {
                status = "runtime_error"; message = "No main method was executed. Check the entry class and public static void main(String[] args).";
            }
        } catch (VMDisconnectedException ignored) {
            // A normal System.exit also disconnects JDI; retain the captured trace.
        } catch (Exception e) {
            status = "engine_error"; message = "Tracing failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (vm != null) try { vm.dispose(); } catch (Exception ignored) {}
            if (process != null) {
                try { if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            }
            try {
                if (outThread != null) outThread.join(1000);
                if (errThread != null) errThread.join(1000);
                if (inputThread != null) inputThread.join(1000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (directory != null) try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
        if (inputFailure.get() != null && status.equals("completed")) {
            status = "engine_error";
            message = "Could not provide the saved program input: " + inputFailure.get().getMessage();
        }
        StatementSource mapping = statementSource;
        List<Step> mappedSteps = mapping == null ? steps : steps.stream().map(mapping::originalStep).toList();
        String errorOutput = stderr.text();
        if (mapping != null) {
            var matcher = java.util.regex.Pattern.compile("(\\.java:)(\\d+)(\\))").matcher(errorOutput);
            errorOutput = matcher.replaceAll(m -> m.group(1) + mapping.originalLine(Integer.parseInt(m.group(2))) + m.group(3));
        }
        return new Result(status, mappedSteps, diagnostics, stdout.text(), errorOutput, message, elapsed(start));
    }

    private Step snapshot(ThreadReference thread, int index, int executedLine, Set<String> userClasses) throws IncompatibleThreadStateException {
        Map<Long, ArrayValue> arrays = new LinkedHashMap<>();
        Map<Long, CollectionValue> collections = new LinkedHashMap<>();
        List<TraceModel.Frame> stack = new ArrayList<>();
        for (StackFrame frame : thread.frames()) {
            if (!userClasses.contains(frame.location().declaringType().name())) continue;
            Map<String, Variable> variables = new LinkedHashMap<>();
            try {
                for (LocalVariable local : frame.visibleVariables()) {
                    if (local.name().equals("args") && local.typeName().equals("java.lang.String[]")) continue;
                    variables.put(local.name(), variable(frame.getValue(local), local.typeName(), arrays, collections));
                }
            } catch (AbsentInformationException ignored) {}
            for (Field field : frame.location().declaringType().visibleFields()) {
                if (field.isStatic() && !field.isSynthetic()) variables.putIfAbsent(field.name(), variable(frame.location().declaringType().getValue(field), field.typeName(), arrays, collections));
            }
            if (frame.thisObject() != null) for (Field field : frame.thisObject().referenceType().allFields()) {
                if (!field.isStatic() && !field.isSynthetic()) variables.put("this." + field.name(), variable(frame.thisObject().getValue(field), field.typeName(), arrays, collections));
            }
            stack.add(new TraceModel.Frame(frame.location().declaringType().name() + "." + frame.location().method().name(), frame.location().lineNumber(), variables));
        }
        TraceModel.Frame active = stack.getFirst();
        return new Step(index, executedLine, active.line(), active.method(), active.variables(), arrays, collections, stack, List.of(), List.of());
    }

    private Variable variable(Value value, String type, Map<Long, ArrayValue> arrays, Map<Long, CollectionValue> collections) {
        if (value instanceof ArrayReference a) {
            if (!arrays.containsKey(a.uniqueID())) {
                arrays.put(a.uniqueID(), new ArrayValue(a.uniqueID(), type, List.of(), a.length()));
                List<String> values = new ArrayList<>();
                for (Value item : a.getValues(0, Math.min(a.length(), MAX_ARRAY))) {
                    if (item instanceof ArrayReference nested) variable(nested, nested.referenceType().name(), arrays, collections);
                    else if (item instanceof ObjectReference object) variable(object, object.referenceType().name(), arrays, collections);
                    values.add(display(item));
                }
                arrays.put(a.uniqueID(), new ArrayValue(a.uniqueID(), type, values, a.length()));
            }
            return new Variable(type, "Array #" + a.uniqueID(), a.uniqueID(), null);
        }
        if (value instanceof ObjectReference object && collectionKind(object.referenceType()) != null) {
            CollectionValue collection = collection(object, arrays, collections);
            String simpleName = object.referenceType().name().substring(object.referenceType().name().lastIndexOf('.') + 1);
            return new Variable(type, simpleName + " #" + object.uniqueID(), null, object.uniqueID());
        }
        return new Variable(type, display(value), null);
    }

    private CollectionValue collection(ObjectReference object, Map<Long, ArrayValue> arrays,
                                       Map<Long, CollectionValue> collections) {
        long id = object.uniqueID();
        CollectionValue existing = collections.get(id);
        if (existing != null) return existing;
        String kind = collectionKind(object.referenceType());
        CollectionValue placeholder = new CollectionValue(id, object.referenceType().name(), kind, List.of(), 0, false, false,
                "This collection implementation is not yet inspectable.");
        collections.put(id, placeholder);
        List<CollectionEntry> entries = new ArrayList<>();
        int size = -1;
        boolean supported = false;
        try {
            if (kind.equals("map")) {
                MapContents contents = mapContents(object, arrays, collections);
                entries.addAll(contents.entries());
                size = contents.size();
                supported = contents.supported();
            } else if (kind.equals("set")) {
                Value backing = fieldValue(object, "map");
                if (backing instanceof ObjectReference map && collectionKind(map.referenceType()).equals("map")) {
                    MapContents contents = mapContents(map, arrays, collections);
                    for (CollectionEntry entry : contents.entries()) entries.add(new CollectionEntry(null, entry.key()));
                    size = contents.size();
                    supported = contents.supported();
                } else if (object.referenceType().name().contains("ImmutableCollections$SetN")) {
                    Value elements = fieldValue(object, "elements");
                    if (elements instanceof ArrayReference array) {
                        for (Value item : array.getValues()) {
                            if (item == null) continue;
                            observe(item, arrays, collections);
                            entries.add(new CollectionEntry(null, display(item)));
                        }
                        size = intField(object, "size", entries.size());
                        supported = true;
                    }
                }
            } else {
                ListContents contents = listContents(object, arrays, collections);
                entries.addAll(contents.entries());
                size = contents.size();
                supported = contents.supported();
            }
        } catch (VMDisconnectedException e) {
            throw e;
        } catch (RuntimeException e) {
            placeholder = new CollectionValue(id, object.referenceType().name(), kind, List.of(), 0, false, false,
                    "Could not inspect this collection implementation.");
            collections.put(id, placeholder);
            return placeholder;
        }
        boolean truncated = supported && entries.size() < size;
        CollectionValue result = new CollectionValue(id, object.referenceType().name(), kind,
                List.copyOf(entries.subList(0, Math.min(entries.size(), MAX_COLLECTION))),
                Math.max(size, entries.size()), supported, truncated,
                supported ? "" : "This collection implementation is not yet inspectable.");
        collections.put(id, result);
        return result;
    }

    private ListContents listContents(ObjectReference object, Map<Long, ArrayValue> arrays,
                                      Map<Long, CollectionValue> collections) {
        String name = object.referenceType().name();
        if (name.contains("LinkedList")) {
            Value first = fieldValue(object, "first");
            int size = intField(object, "size", -1);
            List<CollectionEntry> entries = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            while (first instanceof ObjectReference node && seen.add(node.uniqueID()) && entries.size() <= MAX_COLLECTION) {
                Value item = fieldValue(node, "item");
                observe(item, arrays, collections);
                entries.add(new CollectionEntry(null, display(item)));
                first = fieldValue(node, "next");
            }
            return new ListContents(entries, Math.max(size, entries.size()), size >= 0);
        }
        for (String field : List.of("elementData", "elements", "array", "a")) {
            Value backing = fieldValue(object, field);
            if (!(backing instanceof ArrayReference array)) continue;
            int size = intField(object, "size", intField(object, "elementCount", array.length()));
            int count = Math.min(Math.min(Math.max(size, 0), array.length()), MAX_COLLECTION + 1);
            List<CollectionEntry> entries = new ArrayList<>(count);
            for (Value item : array.getValues(0, count)) {
                observe(item, arrays, collections);
                entries.add(new CollectionEntry(null, display(item)));
            }
            return new ListContents(entries, Math.max(size, 0), true);
        }
        if (name.contains("SingletonList")) {
            Value item = fieldValue(object, "element");
            observe(item, arrays, collections);
            return new ListContents(List.of(new CollectionEntry(null, display(item))), 1, true);
        }
        if (name.contains("EmptyList")) return new ListContents(List.of(), 0, true);
        if (name.contains("List12")) {
            List<CollectionEntry> entries = new ArrayList<>(2);
            for (String field : List.of("e0", "e1")) {
                Value item = fieldValue(object, field);
                if (item != null) {
                    observe(item, arrays, collections);
                    entries.add(new CollectionEntry(null, display(item)));
                }
            }
            return new ListContents(entries, entries.size(), true);
        }
        Value wrapped = fieldValue(object, "list");
        if (wrapped instanceof ObjectReference delegate) {
            CollectionValue nested = collection(delegate, arrays, collections);
            if (nested.supported()) return new ListContents(nested.entries(), nested.size(), true);
        }
        return new ListContents(List.of(), 0, false);
    }

    private MapContents mapContents(ObjectReference object, Map<Long, ArrayValue> arrays,
                                    Map<Long, CollectionValue> collections) {
        String name = object.referenceType().name();
        List<CollectionEntry> entries = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        if (name.contains("ImmutableCollections$Map1")) {
            Value key = fieldValue(object, "k0"), value = fieldValue(object, "v0");
            observe(key, arrays, collections);
            observe(value, arrays, collections);
            return new MapContents(List.of(new CollectionEntry(display(key), display(value))), 1, true);
        }
        Value tableValue = fieldValue(object, "table");
        if (tableValue instanceof ArrayReference table) {
            if (name.contains("ImmutableCollections$MapN")) {
                List<Value> slots = table.getValues();
                for (int i = 0; i + 1 < slots.size(); i += 2) {
                    Value key = slots.get(i), value = slots.get(i + 1);
                    if (key == null) continue;
                    observe(key, arrays, collections);
                    observe(value, arrays, collections);
                    entries.add(new CollectionEntry(display(key), display(value)));
                }
                int size = intField(object, "size", entries.size());
                return new MapContents(entries, Math.max(size, entries.size()), true);
            }
            for (Value bucket : table.getValues()) {
                Value node = bucket;
                while (node instanceof ObjectReference entry && seen.add(entry.uniqueID()) && entries.size() <= MAX_COLLECTION) {
                    Value key = fieldValue(entry, "key");
                    Value value = fieldValue(entry, "value");
                    observe(key, arrays, collections);
                    observe(value, arrays, collections);
                    entries.add(new CollectionEntry(display(key), display(value)));
                    node = fieldValue(entry, "next");
                }
            }
            return new MapContents(entries, Math.max(intField(object, "size", entries.size()), entries.size()), true);
        }
        if (name.contains("TreeMap")) {
            Value root = fieldValue(object, "root");
            Deque<ObjectReference> pending = new ArrayDeque<>();
            if (root instanceof ObjectReference node) pending.add(node);
            while (!pending.isEmpty() && entries.size() <= MAX_COLLECTION) {
                ObjectReference node = pending.removeFirst();
                if (!seen.add(node.uniqueID())) continue;
                Value key = fieldValue(node, "key"), value = fieldValue(node, "value");
                observe(key, arrays, collections);
                observe(value, arrays, collections);
                entries.add(new CollectionEntry(display(key), display(value)));
                Value left = fieldValue(node, "left"), right = fieldValue(node, "right");
                if (left instanceof ObjectReference leftNode) pending.addLast(leftNode);
                if (right instanceof ObjectReference rightNode) pending.addLast(rightNode);
            }
            int size = intField(object, "size", entries.size());
            return new MapContents(entries, Math.max(size, entries.size()), true);
        }
        Value wrapped = fieldValue(object, "m");
        if (wrapped instanceof ObjectReference delegate) return mapContents(delegate, arrays, collections);
        return new MapContents(List.of(), 0, false);
    }

    private void observe(Value value, Map<Long, ArrayValue> arrays, Map<Long, CollectionValue> collections) {
        if (value instanceof ArrayReference array) variable(array, array.referenceType().name(), arrays, collections);
        else if (value instanceof ObjectReference object && collectionKind(object.referenceType()) != null)
            variable(object, object.referenceType().name(), arrays, collections);
    }

    private Value fieldValue(ObjectReference object, String name) {
        Field field = object.referenceType().allFields().stream()
                .filter(candidate -> candidate.name().equals(name) && !candidate.isStatic())
                .findFirst().orElse(null);
        return field == null ? null : object.getValue(field);
    }

    private int intField(ObjectReference object, String name, int fallback) {
        Value value = fieldValue(object, name);
        return value instanceof IntegerValue integer ? integer.value() : fallback;
    }

    private String collectionKind(ReferenceType type) {
        if (implementsType(type, "java.util.Map")) return "map";
        if (implementsType(type, "java.util.Set")) return "set";
        if (implementsType(type, "java.util.Collection")) return "list";
        return null;
    }

    private boolean implementsType(ReferenceType type, String target) {
        if (type.name().equals(target)) return true;
        if (type instanceof ClassType classType) {
            for (InterfaceType iface : classType.interfaces()) if (implementsType(iface, target)) return true;
            return classType.superclass() != null && implementsType(classType.superclass(), target);
        }
        if (type instanceof InterfaceType interfaceType)
            for (InterfaceType parent : interfaceType.superinterfaces()) if (implementsType(parent, target)) return true;
        return false;
    }

    private record ListContents(List<CollectionEntry> entries, int size, boolean supported) {}
    private record MapContents(List<CollectionEntry> entries, int size, boolean supported) {}
    private String display(Value value) {
        if (value == null) return "null";
        if (value instanceof StringReference s) return "\"" + s.value() + "\"";
        if (value instanceof CharValue c) return "'" + c.value() + "'";
        if (value instanceof ArrayReference a) return "Array #" + a.uniqueID();
        if (value instanceof ObjectReference o) {
            String name = o.referenceType().name();
            if (List.of("java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                    "java.lang.Long", "java.lang.Float", "java.lang.Double").contains(name)) {
                Value primitive = fieldValue(o, "value");
                if (primitive != null) return primitive.toString();
            }
            if (name.equals("java.lang.Character")) {
                Value primitive = fieldValue(o, "value");
                if (primitive instanceof CharValue character) return "'" + character.value() + "'";
            }
            return name + " #" + o.uniqueID();
        }
        return value.toString();
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static final class Capture {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated;
        void read(InputStream input) {
            try (input) {
                byte[] bytes = new byte[2048];
                for (int n; (n = input.read(bytes)) != -1;) synchronized (this) {
                    int room = Math.max(0, 32_768 - buffer.size());
                    buffer.write(bytes, 0, Math.min(room, n));
                    if (n > room) truncated = true;
                }
            } catch (IOException ignored) {}
        }
        synchronized String text() { return buffer.toString(StandardCharsets.UTF_8) + (truncated ? "\n[Output truncated at 32 KB]" : ""); }
    }
}
