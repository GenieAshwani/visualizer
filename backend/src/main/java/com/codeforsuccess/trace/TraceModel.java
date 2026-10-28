package com.codeforsuccess.trace;

import java.util.List;
import java.util.Map;

public final class TraceModel {
    private TraceModel() {}
    public record Request(String code, String mainClass, String input) {
        public Request(String code, String mainClass) { this(code, mainClass, ""); }
    }
    public record Variable(String type, String value, Long arrayId, Long collectionId) {
        public Variable(String type, String value, Long arrayId) { this(type, value, arrayId, null); }
    }
    public record ArrayValue(long id, String type, List<String> values, int length) {}
    public record CollectionEntry(String key, String value) {}
    public record CollectionValue(long id, String type, String kind, List<CollectionEntry> entries,
                                 int size, boolean supported, boolean truncated, String note) {}
    public record CollectionFlow(long collectionId, int index, String source, String target, String value, String direction) {}
    public record Frame(String method, int line, Map<String, Variable> variables) {}
    public record Transfer(String array, int index, String variable, String value, String direction, String targetArray, Integer targetIndex) {
        public Transfer(String array, int index, String variable, String value, String direction) {
            this(array, index, variable, value, direction, null, null);
        }
    }
    // State is observed AFTER executedLine and BEFORE nextLine. A line may have multiple changes.
    public record Step(int index, int executedLine, int nextLine, String method,
                       Map<String, Variable> variables, Map<Long, ArrayValue> arrays,
                       Map<Long, CollectionValue> collections,
                       List<Frame> stack, List<Transfer> transfers, List<CollectionFlow> collectionFlows) {}
    public record Diagnostic(String kind, long line, long column, String message) {}
    public record Result(String status, List<Step> steps, List<Diagnostic> diagnostics,
                         String stdout, String stderr, String message, long durationMs) {}
}
