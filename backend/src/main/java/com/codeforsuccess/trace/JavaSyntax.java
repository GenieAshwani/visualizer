package com.codeforsuccess.trace;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.*;
import javax.tools.*;
import java.net.URI;
import java.util.List;

/** Parse only: no attribution, compilation, class loading, or execution. javac recovers partial trees. */
final class JavaSyntax {
    record Parsed(CompilationUnitTree unit, SourcePositions positions, boolean incomplete) {}
    static Parsed parse(String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject input = new SimpleJavaFileObject(URI.create("string:///Preview.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(input));
            CompilationUnitTree unit = task.parse().iterator().next();
            return new Parsed(unit, Trees.instance(task).getSourcePositions(), diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
        }
    }
}
