package com.codeforsuccess.trace;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.net.URI;
import java.util.*;
import static com.codeforsuccess.trace.TraceModel.*;

/** Optional semantic decoration. Runtime values ALWAYS come from JDI, never this AST. */
final class SourceTransfers {
    record Access(int line, String array, ExpressionTree index, String variable, boolean read) {}
    record Copy(int line, String source, ExpressionTree sourceIndex, String target, ExpressionTree targetIndex) {}
    record CollectionMutation(int line, String collection, String operation, List<? extends ExpressionTree> arguments) {}
    record CollectionRead(int line, String collection, String operation, ExpressionTree argument, String target) {}
    record Resolved(String value, String variable) {}
    private final List<Access> accesses = new ArrayList<>();
    private final List<Copy> copies = new ArrayList<>();
    private final List<CollectionMutation> collectionMutations = new ArrayList<>();
    private final List<CollectionRead> collectionReads = new ArrayList<>();
    SourceTransfers(String source) {
        try {
            JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return source; }
            };
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, d -> {}, List.of("-proc:none"), null, List.of(file));
            CompilationUnitTree unit = task.parse().iterator().next();
            Trees trees = Trees.instance(task);
            new TreeScanner<Void, Void>() {
                void add(Tree node, ExpressionTree target, ExpressionTree value) {
                    int line = (int) unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit, node));
                    if (value instanceof ArrayAccessTree from && target instanceof ArrayAccessTree to
                            && from.getExpression() instanceof IdentifierTree fromName && to.getExpression() instanceof IdentifierTree toName)
                        copies.add(new Copy(line, fromName.getName().toString(), from.getIndex(), toName.getName().toString(), to.getIndex()));
                    if (value instanceof ArrayAccessTree a && target instanceof IdentifierTree v && a.getExpression() instanceof IdentifierTree name)
                        accesses.add(new Access(line, name.getName().toString(), a.getIndex(), v.getName().toString(), true));
                    if (target instanceof ArrayAccessTree a && value instanceof IdentifierTree v && a.getExpression() instanceof IdentifierTree name)
                        accesses.add(new Access(line, name.getName().toString(), a.getIndex(), v.getName().toString(), false));
                }
                @Override public Void visitVariable(VariableTree node, Void p) {
                    if (node.getInitializer() instanceof ArrayAccessTree a && a.getExpression() instanceof IdentifierTree name) {
                        int line = (int) unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit, node));
                        accesses.add(new Access(line, name.getName().toString(), a.getIndex(), node.getName().toString(), true));
                    }
                    if (node.getInitializer() instanceof MethodInvocationTree invocation)
                        addCollectionRead(invocation, node.getName().toString(), unit, trees);
                    return super.visitVariable(node, p);
                }
                @Override public Void visitAssignment(AssignmentTree node, Void p) {
                    add(node, node.getVariable(), node.getExpression());
                    if (node.getVariable() instanceof IdentifierTree target && node.getExpression() instanceof MethodInvocationTree invocation)
                        addCollectionRead(invocation, target.getName().toString(), unit, trees);
                    return super.visitAssignment(node, p);
                }
                @Override public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
                    if (node.getMethodSelect() instanceof MemberSelectTree select
                            && select.getExpression() instanceof IdentifierTree collection)
                        collectionMutations.add(new CollectionMutation(
                                (int) unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit, node)),
                                collection.getName().toString(), select.getIdentifier().toString(), node.getArguments()));
                    return super.visitMethodInvocation(node, p);
                }
                private void addCollectionRead(MethodInvocationTree node, String target, CompilationUnitTree unit, Trees trees) {
                    if (node.getMethodSelect() instanceof MemberSelectTree select
                            && select.getExpression() instanceof IdentifierTree collection
                            && List.of("get").contains(select.getIdentifier().toString())
                            && node.getArguments().size() == 1) {
                        collectionReads.add(new CollectionRead(
                                (int) unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit, node)),
                                collection.getName().toString(), select.getIdentifier().toString(), node.getArguments().getFirst(), target));
                    }
                }
            }.scan(unit, null);
        } catch (Exception ignored) { /* AST decoration must never break execution. */ }
    }
    List<Transfer> between(Step before, Step after) {
        if (before == null || !before.method().equals(after.method())) return List.of();
        List<Transfer> result = new ArrayList<>();
        for (Copy c : copies) {
            if (c.line != after.executedLine()) continue;
            try {
                int fromIndex = evaluate(c.sourceIndex, before.variables()), toIndex = evaluate(c.targetIndex, before.variables());
                Long fromId = before.variables().get(c.source).arrayId(), toId = before.variables().get(c.target).arrayId();
                String value = before.arrays().get(fromId).values().get(fromIndex);
                if (!before.arrays().get(toId).values().get(toIndex).equals(after.arrays().get(toId).values().get(toIndex))
                        && value.equals(after.arrays().get(toId).values().get(toIndex)))
                    result.add(new Transfer(c.source, fromIndex, null, value, "copy", c.target, toIndex));
            } catch (Exception ignored) {}
        }
        for (Access a : accesses) {
            if (a.line != after.executedLine()) continue;
            try {
                int index = evaluate(a.index, before.variables());
                Variable array = before.variables().get(a.array);
                ArrayValue oldArray = before.arrays().get(array.arrayId());
                ArrayValue newArray = after.arrays().get(array.arrayId());
                if (a.read) {
                    Variable value = after.variables().get(a.variable);
                    if (value != null && !value.equals(before.variables().get(a.variable)) && value.value().equals(oldArray.values().get(index)))
                        result.add(new Transfer(a.array, index, a.variable, value.value(), "read"));
                } else {
                    Variable value = before.variables().get(a.variable);
                    if (value != null && !oldArray.values().get(index).equals(newArray.values().get(index)) && value.value().equals(newArray.values().get(index)))
                        result.add(new Transfer(a.array, index, a.variable, value.value(), "write"));
                }
            } catch (Exception ignored) { /* unsupported expression or invisible local: show diff only */ }
        }
        return result;
    }
    List<CollectionFlow> collectionFlows(Step before, Step after) {
        if (before == null || !before.method().equals(after.method())) return List.of();
        List<CollectionFlow> result = new ArrayList<>();
        for (CollectionMutation mutation : collectionMutations) {
            if (mutation.line != after.executedLine()) continue;
            Variable collectionVariable = before.variables().get(mutation.collection);
            if (collectionVariable == null || collectionVariable.collectionId() == null) continue;
            CollectionValue old = before.collections().get(collectionVariable.collectionId());
            CollectionValue current = after.collections().get(collectionVariable.collectionId());
            if (old == null || current == null || !old.supported() || !current.supported()) continue;
            try {
                int index = -1;
                Resolved source = null;
                String expectedKey = null;
                switch (mutation.operation) {
                    case "add", "offer", "push" -> {
                        if (mutation.arguments.size() != 1) continue;
                        source = resolve(mutation.arguments.getFirst(), before.variables());
                        index = newOccurrence(old.entries(), current.entries(), source.value());
                        if (mutation.operation.equals("push")) index = current.entries().isEmpty() ? -1 : 0;
                    }
                    case "put" -> {
                        if (mutation.arguments.size() != 2) continue;
                        Resolved key = resolve(mutation.arguments.get(0), before.variables());
                        source = resolve(mutation.arguments.get(1), before.variables());
                        expectedKey = key.value();
                        index = -1;
                        for (int i = 0; i < current.entries().size(); i++) {
                            CollectionEntry entry = current.entries().get(i);
                            CollectionEntry previous = null;
                            for (CollectionEntry item : old.entries()) {
                                if (Objects.equals(item.key(), expectedKey)) { previous = item; break; }
                            }
                            if (Objects.equals(entry.key(), expectedKey) && Objects.equals(entry.value(), source.value())
                                    && (previous == null || !Objects.equals(previous.value(), entry.value()))) { index = i; break; }
                        }
                    }
                    case "set" -> {
                        if (mutation.arguments.size() != 2) continue;
                        index = evaluate(mutation.arguments.get(0), before.variables());
                        source = resolve(mutation.arguments.get(1), before.variables());
                        if (index < 0 || index >= current.entries().size() || index >= old.entries().size()
                                || Objects.equals(old.entries().get(index).value(), current.entries().get(index).value())) index = -1;
                        else if (!Objects.equals(current.entries().get(index).value(), source.value())) index = -1;
                    }
                    default -> { continue; }
                }
                if (index < 0 || source == null || source.variable() == null) continue;
                String target = expectedKey == null ? mutation.collection + "[" + index + "]" : mutation.collection + "[" + expectedKey + "]";
                result.add(new CollectionFlow(collectionVariable.collectionId(), index, source.variable(), target, source.value(), "write"));
            } catch (Exception ignored) { /* Only verified collection changes get a movement animation. */ }
        }
        for (CollectionRead read : collectionReads) {
            if (read.line != after.executedLine()) continue;
            Variable collectionVariable = before.variables().get(read.collection);
            Variable resultVariable = after.variables().get(read.target);
            if (collectionVariable == null || collectionVariable.collectionId() == null || resultVariable == null) continue;
            CollectionValue collection = before.collections().get(collectionVariable.collectionId());
            if (collection == null || !collection.supported()) continue;
            try {
                int index;
                if (collection.kind().equals("map")) {
                    String key = resolve(read.argument, before.variables()).value();
                    index = -1;
                    for (int i = 0; i < collection.entries().size(); i++)
                        if (Objects.equals(collection.entries().get(i).key(), key)) { index = i; break; }
                } else {
                    index = evaluate(read.argument, before.variables());
                }
                if (index < 0 || index >= collection.entries().size()) continue;
                String value = collection.kind().equals("map") ? collection.entries().get(index).value() : collection.entries().get(index).value();
                if (!Objects.equals(resultVariable.value(), value)) continue;
                String source = read.collection + (collection.kind().equals("map")
                        ? "[" + collection.entries().get(index).key() + "]" : "[" + index + "]");
                result.add(new CollectionFlow(collectionVariable.collectionId(), index, read.target, source, value, "read"));
            } catch (Exception ignored) { /* Reads animate only when the captured result matches the observed entry. */ }
        }
        return result;
    }
    private int newOccurrence(List<CollectionEntry> before, List<CollectionEntry> after, String value) {
        int beforeCount = (int) before.stream().filter(entry -> Objects.equals(entry.value(), value)).count();
        int afterCount = 0;
        for (int i = 0; i < after.size(); i++) {
            if (Objects.equals(after.get(i).value(), value) && ++afterCount > beforeCount) return i;
        }
        return -1;
    }
    private Resolved resolve(ExpressionTree expression, Map<String, Variable> variables) {
        if (expression instanceof IdentifierTree identifier) {
            Variable variable = variables.get(identifier.getName().toString());
            if (variable == null) throw new IllegalArgumentException("Unknown source variable");
            return new Resolved(variable.value(), identifier.getName().toString());
        }
        if (expression instanceof LiteralTree literal) {
            Object value = literal.getValue();
            if (value instanceof String text) return new Resolved("\"" + text + "\"", null);
            if (value instanceof Character character) return new Resolved("'" + character + "'", null);
            return new Resolved(String.valueOf(value), null);
        }
        throw new IllegalArgumentException("Unsupported collection argument");
    }
    private int evaluate(ExpressionTree tree, Map<String, Variable> vars) {
        if (tree instanceof LiteralTree l) return ((Number) l.getValue()).intValue();
        if (tree instanceof IdentifierTree i) return Integer.parseInt(vars.get(i.getName().toString()).value());
        if (tree instanceof ParenthesizedTree p) return evaluate(p.getExpression(), vars);
        if (tree instanceof BinaryTree b) {
            int left = evaluate(b.getLeftOperand(), vars), right = evaluate(b.getRightOperand(), vars);
            return switch (b.getKind()) { case PLUS -> left + right; case MINUS -> left - right; case MULTIPLY -> left * right; case DIVIDE -> left / right; case REMAINDER -> left % right; default -> throw new IllegalArgumentException(); };
        }
        throw new IllegalArgumentException();
    }
}
