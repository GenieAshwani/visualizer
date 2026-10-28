package com.codeforsuccess.trace;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.codeforsuccess.trace.TraceModel.*;

class JavaTracerTest {
    private final JavaTracer tracer = new JavaTracer();
    private Result run(String body) {
        return tracer.trace(new Request("public class Main {\n public static void main(String[] args) {\n" + body + "\n }\n}", "Main"));
    }
    private void hasArray(Result result, String... values) {
        assertThat(result.steps()).anySatisfy(step -> assertThat(step.arrays().values()).anySatisfy(a -> assertThat(a.values()).containsExactly(values)));
    }
    @Test void swapCapturesRealValuesAndVerifiedTransfer() {
        Result r = run("int[] arr = {12, 8, 24, 16, 5};\nint temp = arr[0];\narr[0] = arr[3];\narr[3] = temp;\nSystem.out.println(java.util.Arrays.toString(arr));");
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.stdout()).contains("[16, 8, 24, 12, 5]");
        hasArray(r, "16", "8", "24", "12", "5");
        assertThat(r.steps()).anySatisfy(s -> assertThat(s.transfers()).contains(new Transfer("arr", 0, "temp", "12", "read")));
        assertThat(r.steps()).anySatisfy(s -> assertThat(s.transfers()).contains(new Transfer("arr", 3, "temp", "12", "write")));
        assertThat(r.steps()).anySatisfy(s -> assertThat(s.transfers()).contains(new Transfer("arr", 3, null, "16", "copy", "arr", 0)));
    }
    @Test void sortsWithNestedLoopsAndBranching() {
        Result r = run("""
            int[] a = {9, 3, 7, 1, 5};
            for (int i = 0; i < a.length - 1; i++) {
                for (int j = 0; j < a.length - i - 1; j++) {
                    if (a[j] > a[j+1]) {
                        int t = a[j];
                        a[j] = a[j+1];
                        a[j+1] = t;
                    }
                }
            }
            System.out.println(java.util.Arrays.toString(a));
            """);
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.stdout()).contains("[1, 3, 5, 7, 9]");
        hasArray(r, "1", "3", "5", "7", "9");
        assertThat(r.steps()).hasSizeGreaterThan(30);
    }
    @Test void enhancedForTraversalAndAccumulation() {
        Result r = run("int[] nums = {4,8,15};\nint sum=0;\nfor (int n : nums) {\nsum += n;\n}\nSystem.out.println(sum);");
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.stdout().trim()).isEqualTo("27");
        assertThat(r.steps()).anySatisfy(s -> { assertThat(s.variables()).containsKey("sum"); assertThat(s.variables().get("sum").value()).isEqualTo("27"); });
    }
    @Test void sameLineOperationsAreNotLost() {
        Result r = run("int[] a={1,2}; int t=a[0]; a[0]=a[1]; a[1]=t; System.out.println(a[0]);");
        assertThat(r.status()).isEqualTo("completed");
        hasArray(r, "1", "2"); hasArray(r, "2", "2"); hasArray(r, "2", "1");
        assertThat(r.steps()).hasSize(6); // initial pause + five source statements, not bytecode changes
    }
    @Test void compoundArrayStatementIsAtomicForManualNext() {
        Result r = run("int[] a={1,2};\nint i=0;\na[i++] += 3;\na[1] = 9;");
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.steps()).hasSize(5);
        var after = r.steps().get(3);
        assertThat(after.executedLine()).isEqualTo(5);
        assertThat(after.variables().get("i").value()).isEqualTo("1");
        assertThat(after.arrays().values()).singleElement().satisfies(a -> assertThat(a.values()).containsExactly("4", "2"));
        var before = r.steps().get(2);
        assertThat(before.variables().get("i").value()).isEqualTo("0");
        assertThat(before.arrays().values()).singleElement().satisfies(a -> assertThat(a.values()).containsExactly("1", "2"));
    }
    @Test void multilineStatementDoesNotCreatePartialSteps() {
        Result r = run("int[] a = {\n1,\n2,\n3\n};\na[0] =\n a[1] +\n a[2];");
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.steps()).hasSize(3);
        assertThat(r.steps().get(1).executedLine()).isEqualTo(3);
        hasArray(r, "5", "2", "3");
    }
    @Test void whitespaceMappingPreservesStringLiteralsAndCompilerCoordinates() {
        Result r = run("String text=\"int[] a={9}; // unchanged\";\nSystem.out.println(text);");
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.stdout().trim()).isEqualTo("int[] a={9}; // unchanged");
        Result invalid = run("int[] a={1}; a[0]=missing;");
        assertThat(invalid.status()).isEqualTo("compile_error");
        assertThat(invalid.diagnostics()).anySatisfy(d -> { assertThat(d.line()).isEqualTo(3); assertThat(d.column()).isEqualTo(19); });
    }
    @Test void helperMethodMutationsAndAliasesKeepIdentity() {
        Result r = tracer.trace(new Request("""
            public class Main {
              static void update(int[] values) {
                values[1] = 99;
              }
              public static void main(String[] args) throws Exception {
                int[] a = {1,2,3};
                int[] alias = a;
                update(alias);
                System.out.println(a[1]);
              }
            }
            """, "Main"));
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.stdout().trim()).isEqualTo("99");
        hasArray(r, "1", "99", "3");
        assertThat(r.steps()).anySatisfy(s -> {
            assertThat(s.variables()).containsKeys("a", "alias");
            assertThat(s.variables().get("a").arrayId()).isEqualTo(s.variables().get("alias").arrayId());
        });
        assertThat(r.steps()).anySatisfy(s -> { assertThat(s.method()).isEqualTo("Main.update"); assertThat(s.stack()).hasSize(2); });
    }
    @Test void capturesCollectionContentsAndVerifiedValueFlow() {
        Result r = run("""
            java.util.ArrayList<Integer> values = new java.util.ArrayList<>();
            int next = 7;
            values.add(next);
            int first = values.get(0);
            values.add(9);
            java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
            String key = "items";
            counts.put(key, next);
            java.util.HashSet<Integer> unique = new java.util.HashSet<>();
            unique.add(next);
            java.util.Map<String, Integer> fixed = java.util.Map.of("fixed", next);
            """);
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.steps()).filteredOn(step -> step.variables().containsKey("values")).anySatisfy(step -> {
            var values = step.variables().get("values");
            assertThat(values.collectionId()).isNotNull();
            var list = step.collections().get(values.collectionId());
            assertThat(list.supported()).isTrue();
            assertThat(list.entries()).extracting(CollectionEntry::value).contains("7");
        });
        assertThat(r.steps()).anySatisfy(step -> assertThat(step.collectionFlows()).anySatisfy(flow -> {
            assertThat(flow.source()).isEqualTo("next");
            assertThat(flow.target()).isEqualTo("values[0]");
            assertThat(flow.value()).isEqualTo("7");
            assertThat(flow.direction()).isEqualTo("write");
        }));
        assertThat(r.steps()).anySatisfy(step -> assertThat(step.collectionFlows()).anySatisfy(flow -> {
            assertThat(flow.source()).isEqualTo("first");
            assertThat(flow.target()).isEqualTo("values[0]");
            assertThat(flow.value()).isEqualTo("7");
            assertThat(flow.direction()).isEqualTo("read");
        }));
        assertThat(r.steps()).anySatisfy(step -> assertThat(step.collections().values()).anySatisfy(map -> {
            assertThat(map.supported()).isTrue();
            assertThat(map.entries()).contains(new CollectionEntry("\"items\"", "7"));
        }));
        assertThat(r.steps()).anySatisfy(step -> assertThat(step.collections().values()).anySatisfy(collection -> {
            assertThat(collection.kind()).isEqualTo("set");
            assertThat(collection.entries()).contains(new CollectionEntry(null, "7"));
        }));
        assertThat(r.steps()).anySatisfy(step -> assertThat(step.collections().values()).anySatisfy(map -> {
            assertThat(map.type()).contains("Map1");
            assertThat(map.entries()).contains(new CollectionEntry("\"fixed\"", "7"));
        }));
    }
    @Test void recursiveCallsExposeSeparateFramesAndLocals() {
        Result r = tracer.trace(new Request("""
            public class Main {
              static int factorial(int n) {
                if (n <= 1) return 1;
                return n * factorial(n - 1);
              }
              public static void main(String[] args) {
                int result = factorial(3);
              }
            }
            """, "Main"));
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.steps()).anySatisfy(step -> {
            assertThat(step.stack()).hasSizeGreaterThan(2);
            assertThat(step.stack()).filteredOn(frame -> frame.method().equals("Main.factorial"))
                    .extracting(frame -> frame.variables().get("n").value()).contains("3", "2", "1");
        });
    }
    @Test void reverseWhileLoopAndEmptyArrays() {
        Result r = run("""
            int[] arr={1,2,3,4};
            int[] empty=new int[0];
            int l=0, r=arr.length-1;
            while(l<r) {
              int t=arr[l];
              arr[l]=arr[r];
              arr[r]=t;
              l++;
              r--;
            }
            """);
        assertThat(r.status()).isEqualTo("completed");
        hasArray(r, "4", "3", "2", "1"); hasArray(r);
    }
    @Test void reportsCompilerAndRuntimeErrors() {
        Result invalid = run("int[] a = ;");
        assertThat(invalid.status()).isEqualTo("compile_error");
        assertThat(invalid.diagnostics()).anySatisfy(d -> { assertThat(d.line()).isEqualTo(3); assertThat(d.kind()).isEqualTo("ERROR"); });
        Result bounds = run("int[] a={1};\na[8]=4;");
        assertThat(bounds.status()).isEqualTo("runtime_error");
        assertThat(bounds.message()).contains("ArrayIndexOutOfBoundsException");
        assertThat(bounds.steps()).isNotEmpty();
    }
    @Test void sendsSavedLessonInputToTheDebuggee() {
        Result result = tracer.trace(new Request(
                """
                import java.io.BufferedReader;
                import java.io.InputStreamReader;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
                        int[] values = {Integer.parseInt(input.readLine()), Integer.parseInt(input.readLine())};
                        values[0] += values[1];
                        System.out.println(values[0]);
                    }
                }
                """,
                "Main", "4\n7\n"));
        assertThat(result.status()).as("%s stdout=%s stderr=%s steps=%d", result.message(), result.stdout(), result.stderr(), result.steps().size()).isEqualTo("completed");
        assertThat(result.stdout().trim()).isEqualTo("11");
        hasArray(result, "11", "7");
    }
    @Test void primitiveTypesPackagesAndStaticArrays() {
        Result r = tracer.trace(new Request("""
            package example;
            public class Demo {
              static int[] shared = {3, 4};
              public static void main(String[] args) {
                double[] d = {1.5, 2.5};
                boolean[] b = {true, false};
                shared[0] = 9;
                System.out.println(d[0] + ":" + b[0]);
              }
            }
            """, "example.Demo"));
        assertThat(r.status()).isEqualTo("completed");
        hasArray(r, "1.5", "2.5"); hasArray(r, "true", "false"); hasArray(r, "9", "4");
        assertThat(r.stdout()).contains("1.5:true");
    }
    @Test void limitsInfiniteLoopAndCapsArrays() {
        Result r = run("int[] a=new int[300];\nint i=0;\nwhile(true) { i++; }");
        assertThat(r.status()).isEqualTo("limit_reached");
        assertThat(r.durationMs()).isLessThan(17_000);
        assertThat(r.steps()).hasSizeLessThanOrEqualTo(1800);
        assertThat(r.steps()).anySatisfy(s -> { assertThat(s.variables()).containsKey("i"); assertThat(Integer.parseInt(s.variables().get("i").value())).isGreaterThan(2); });
        assertThat(r.steps()).anySatisfy(s -> assertThat(s.arrays().values()).anySatisfy(a -> { assertThat(a.length()).isEqualTo(300); assertThat(a.values()).hasSize(256); }));
    }
}
