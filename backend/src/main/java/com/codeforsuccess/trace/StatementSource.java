package com.codeforsuccess.trace;

import com.sun.source.tree.*;
import com.sun.source.util.TreePathScanner;
import java.util.*;
import static com.codeforsuccess.trace.TraceModel.*;

/** Whitespace-only source mapping gives separate statements on one line distinct JDI line locations. */
final class StatementSource {
    record Span(int start, int end) {}
    record Boundary(int id, int line) {}
    final String source;
    private final List<Integer> originalLines = new ArrayList<>();
    private final List<Boundary> boundaries = new ArrayList<>();
    private final List<Integer> originalOffsets = new ArrayList<>();
    private final String original;

    StatementSource(String original) throws Exception {
        this.original = original;
        var parsed = JavaSyntax.parse(original);
        List<Span> rawSpans = new ArrayList<>();
        if (!parsed.incomplete()) new TreePathScanner<Void, Void>() {
            int start(Tree t) { return (int) parsed.positions().getStartPosition(parsed.unit(), t); }
            int end(Tree t) { return (int) parsed.positions().getEndPosition(parsed.unit(), t); }
            void mark(Tree t) { if (t != null) mark(start(t), end(t)); }
            void mark(int from, int to) { if (from >= 0 && to > from && to <= original.length()) rawSpans.add(new Span(from, to)); }
            @Override public Void scan(Tree tree, Void p) {
                if (tree != null) switch (tree.getKind()) {
                    case EXPRESSION_STATEMENT, RETURN, THROW, BREAK, CONTINUE, ASSERT, YIELD -> mark(tree);
                    default -> {}
                }
                return super.scan(tree, p);
            }
            @Override public Void visitVariable(VariableTree tree, Void p) {
                Tree parent = getCurrentPath().getParentPath().getLeaf();
                if (parent instanceof BlockTree || parent instanceof ClassTree || parent instanceof ForLoopTree || parent instanceof TryTree) mark(tree);
                return super.visitVariable(tree, p);
            }
            @Override public Void visitIf(IfTree t, Void p) { mark(start(t), end(t.getCondition())); return super.visitIf(t, p); }
            @Override public Void visitWhileLoop(WhileLoopTree t, Void p) { mark(start(t), end(t.getCondition())); return super.visitWhileLoop(t, p); }
            @Override public Void visitDoWhileLoop(DoWhileLoopTree t, Void p) { mark(t.getCondition()); return super.visitDoWhileLoop(t, p); }
            @Override public Void visitForLoop(ForLoopTree t, Void p) {
                if (t.getCondition() != null) mark(t.getCondition());
                return super.visitForLoop(t, p);
            }
            @Override public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void p) { mark(start(t), end(t.getExpression())); return super.visitEnhancedForLoop(t, p); }
            @Override public Void visitSwitch(SwitchTree t, Void p) { mark(start(t), end(t.getExpression())); return super.visitSwitch(t, p); }
        }.scan(parsed.unit(), null);
        // Multi-variable declarations share a start offset: keep them a single statement.
        Map<Integer, Integer> ends = new HashMap<>();
        rawSpans.forEach(s -> ends.merge(s.start, s.end, Math::max));
        List<Span> spans = ends.entrySet().stream().map(e -> new Span(e.getKey(), e.getValue())).toList();
        Set<Integer> breaks = new HashSet<>();
        for (Span s : spans) { breaks.add(s.start); breaks.add(s.end); }
        StringBuilder generated = new StringBuilder();
        Map<Integer, Integer> startLines = new HashMap<>();
        List<Integer> lineOffsets = new ArrayList<>(List.of(0));
        int line = 1;
        for (int i = 0; i <= original.length(); i++) {
            if (breaks.contains(i) && !generated.isEmpty() && generated.charAt(generated.length() - 1) != '\n') {
                generated.append('\n'); originalOffsets.add(i); line++; lineOffsets.add(i);
            }
            startLines.put(i, line);
            if (i == original.length()) break;
            char c = original.charAt(i);
            generated.append(c); originalOffsets.add(i);
            if (c == '\n') { line++; lineOffsets.add(i + 1); }
        }
        source = generated.toString();
        String[] generatedLines = source.split("\n", -1);
        for (int n = 0; n < generatedLines.length; n++) {
            int position = lineOffsets.get(n);
            while (position < original.length() && Character.isWhitespace(original.charAt(position))) position++;
            originalLines.add((int) parsed.unit().getLineMap().getLineNumber(Math.min(position, Math.max(0, original.length() - 1))));
            Span selected = null;
            for (Span span : spans) if (position >= span.start && position < span.end
                    && (selected == null || span.end - span.start < selected.end - selected.start)) selected = span;
            boundaries.add(selected == null ? null : new Boundary(selected.start + 1, startLines.get(selected.start)));
        }
    }
    Boundary boundary(int line) { return line > 0 && line <= boundaries.size() ? boundaries.get(line - 1) : null; }
    int originalLine(int line) { return line > 0 && line <= originalLines.size() ? originalLines.get(line - 1) : line; }
    Diagnostic diagnostic(javax.tools.Diagnostic<?> d) {
        long position = d.getPosition();
        if (position < 0 || position >= originalOffsets.size()) return new Diagnostic(d.getKind().name(), originalLine((int) d.getLineNumber()), d.getColumnNumber(), d.getMessage(Locale.ENGLISH));
        int offset = originalOffsets.get((int) position), line = 1, column = 1;
        for (int i = 0; i < offset; i++) { if (original.charAt(i) == '\n') { line++; column = 1; } else column++; }
        return new Diagnostic(d.getKind().name(), line, column, d.getMessage(Locale.ENGLISH));
    }
    Step originalStep(Step s) {
        return new Step(s.index(), originalLine(s.executedLine()), originalLine(s.nextLine()), s.method(), s.variables(), s.arrays(), s.collections(),
                s.stack().stream().map(f -> new Frame(f.method(), originalLine(f.line()), f.variables())).toList(), s.transfers(), s.collectionFlows());
    }
}
