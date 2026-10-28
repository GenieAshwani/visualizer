package com.codeforsuccess.trace;

import com.sun.source.tree.*;
import com.sun.source.util.TreeScanner;
import javax.lang.model.element.Modifier;
import java.util.*;
import static com.codeforsuccess.trace.TraceModel.*;

/** Declaration preview. Deliberately does not interpret assignments, branches, calls, or array reads. */
public final class StaticPreview {
    public record Method(String name, String signature, int line) {}
    public record Declaration(String name, String scope, int line, String initializer, boolean known) {}
    public record Preview(String mode, boolean incomplete, Map<String, Variable> variables,
                          Map<Long, ArrayValue> arrays, List<Method> methods, Map<String, Declaration> declarations) {}
    private static final Object UNKNOWN = new Object();

    public Preview parse(String source) {
        Map<String, Variable> variables = new LinkedHashMap<>();
        Map<Long, ArrayValue> arrays = new LinkedHashMap<>();
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        List<Method> methods = new ArrayList<>();
        boolean incomplete = false;
        try {
            var parsed = JavaSyntax.parse(source);
            incomplete = parsed.incomplete();
            new TreeScanner<Void, Void>() {
                String scope = "source";
                final Deque<Map<String, Object>> constants = new ArrayDeque<>(List.of(new HashMap<>()));
                int line(Tree tree) { return (int) parsed.unit().getLineMap().getLineNumber(Math.max(0, parsed.positions().getStartPosition(parsed.unit(), tree))); }
                @Override public Void visitClass(ClassTree tree, Void p) {
                    String previous = scope; scope = tree.getSimpleName().toString(); constants.push(new HashMap<>());
                    super.visitClass(tree, p); constants.pop(); scope = previous; return null;
                }
                @Override public Void visitMethod(MethodTree tree, Void p) {
                    String previous = scope;
                    scope += "." + tree.getName() + "(" + String.join(", ", tree.getParameters().stream().map(v -> String.valueOf(v.getType())).toList()) + ")";
                    methods.add(new Method(tree.getName().toString(), (tree.getReturnType() == null ? "" : tree.getReturnType() + " ") + tree.getName() + "(" + String.join(", ", tree.getParameters().stream().map(Object::toString).toList()) + ")", line(tree)));
                    constants.push(new HashMap<>()); super.visitMethod(tree, p); constants.pop(); scope = previous; return null;
                }
                @Override public Void visitBlock(BlockTree tree, Void p) {
                    constants.push(new HashMap<>()); super.visitBlock(tree, p); constants.pop(); return null;
                }
                @Override public Void visitForLoop(ForLoopTree tree, Void p) {
                    constants.push(new HashMap<>()); super.visitForLoop(tree, p); constants.pop(); return null;
                }
                @Override public Void visitVariable(VariableTree tree, Void p) {
                    String name = tree.getName().toString();
                    if (name.equals("<error>") || variables.size() >= 256) return null;
                    String type = tree.getType() == null ? "var" : tree.getType().toString();
                    String key = scope + ":" + line(tree) + ":" + name;
                    if (variables.containsKey(key)) key += ":" + variables.size();
                    ExpressionTree init = tree.getInitializer();
                    Object value = constant(init, constants);
                    Long arrayId = null;
                    boolean known = value != UNKNOWN;
                    if (init instanceof NewArrayTree a) {
                        List<String> items = new ArrayList<>();
                        int length = -1;
                        if (a.getInitializers() != null) {
                            length = a.getInitializers().size(); known = true;
                            for (ExpressionTree item : a.getInitializers().subList(0, Math.min(length, 256))) {
                                Object element = constant(item, constants); items.add(format(element)); known &= element != UNKNOWN;
                            }
                        } else if (a.getDimensions().size() == 1 && !(a.getType() instanceof ArrayTypeTree)) {
                            Object size = constant(a.getDimensions().getFirst(), constants);
                            if (size instanceof Integer n && n >= 0) {
                                length = n; known = true;
                                String element = switch (a.getType().toString()) { case "boolean" -> "false"; case "char" -> "'\\0'"; case "float", "double" -> "0.0"; case "byte", "short", "int", "long" -> "0"; default -> "null"; };
                                items.addAll(Collections.nCopies(Math.min(n, 256), element));
                            }
                        }
                        if (length >= 0) {
                            arrayId = -(long) variables.size() - 1;
                            arrays.put(arrayId, new ArrayValue(arrayId, type.equals("var") ? a.getType() + "[]" : type, items, length));
                        }
                    }
                    variables.put(key, new Variable(type, arrayId == null ? format(value) : "Static initializer", arrayId));
                    declarations.put(key, new Declaration(name, scope, line(tree), init == null ? "Not initialized" : init.toString(), known));
                    // Only final primitive/String constant declarations can be referenced as constants.
                    constants.peek().put(name, tree.getModifiers().getFlags().contains(Modifier.FINAL) ? value : UNKNOWN);
                    return null;
                }
            }.scan(parsed.unit(), null);
        } catch (Exception ignored) { incomplete = true; }
        return new Preview("static", incomplete, variables, arrays, methods, declarations);
    }

    private static Object constant(ExpressionTree tree, Deque<Map<String, Object>> scopes) {
        try {
            if (tree instanceof LiteralTree l) return l.getValue();
            if (tree instanceof ParenthesizedTree p) return constant(p.getExpression(), scopes);
            if (tree instanceof IdentifierTree i) {
                for (Map<String, Object> scope : scopes) if (scope.containsKey(i.getName().toString())) return scope.get(i.getName().toString());
            }
            if (tree instanceof UnaryTree u) {
                Object value = constant(u.getExpression(), scopes);
                if (u.getKind() == Tree.Kind.LOGICAL_COMPLEMENT && value instanceof Boolean b) return !b;
                if (value instanceof Integer n) return switch (u.getKind()) { case UNARY_MINUS -> -n; case UNARY_PLUS -> n; case BITWISE_COMPLEMENT -> ~n; default -> UNKNOWN; };
                if (value instanceof Long n) return switch (u.getKind()) { case UNARY_MINUS -> -n; case UNARY_PLUS -> n; case BITWISE_COMPLEMENT -> ~n; default -> UNKNOWN; };
                if (value instanceof Double n) return switch (u.getKind()) { case UNARY_MINUS -> -n; case UNARY_PLUS -> n; default -> UNKNOWN; };
                if (value instanceof Float n) return switch (u.getKind()) { case UNARY_MINUS -> -n; case UNARY_PLUS -> n; default -> UNKNOWN; };
            }
            if (tree instanceof BinaryTree b) {
                Object left = constant(b.getLeftOperand(), scopes), right = constant(b.getRightOperand(), scopes);
                if (left == UNKNOWN || right == UNKNOWN) return UNKNOWN;
                if (b.getKind() == Tree.Kind.PLUS && (left instanceof String || right instanceof String)) return String.valueOf(left) + right;
                // Preserve Java int overflow and integer division; unsupported numeric promotions stay unknown.
                if (left instanceof Integer l && right instanceof Integer r) return switch (b.getKind()) {
                    case PLUS -> l + r; case MINUS -> l - r; case MULTIPLY -> l * r; case DIVIDE -> l / r; case REMAINDER -> l % r;
                    case LEFT_SHIFT -> l << r; case RIGHT_SHIFT -> l >> r; case UNSIGNED_RIGHT_SHIFT -> l >>> r;
                    case AND -> l & r; case OR -> l | r; case XOR -> l ^ r; default -> UNKNOWN;
                };
            }
        } catch (ArithmeticException ignored) {}
        return UNKNOWN;
    }
    private static String format(Object value) {
        if (value == UNKNOWN) return "?";
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + s.replace("\n", "\\n").replace("\r", "\\r") + "\"";
        if (value instanceof Character c) return "'" + c + "'";
        return value.toString();
    }
}
