package com.codeforsuccess.trace;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StaticPreviewTest {
    private final StaticPreview preview = new StaticPreview();
    @Test void previewsIncompleteSourceAndMethodsWithoutExecution() {
        var p = preview.parse("public class Main { static void helper() {} public static void main(String[] args) { int[] arr = {3, 8, -2}; int i=0; int temp = arr[0]; System.exit(1);");
        assertThat(p.incomplete()).isTrue();
        assertThat(p.mode()).isEqualTo("static");
        assertThat(p.methods()).extracting(StaticPreview.Method::name).contains("helper", "main");
        assertThat(p.arrays().values()).anySatisfy(a -> assertThat(a.values()).containsExactly("3", "8", "-2"));
        assertThat(p.variables().entrySet()).anySatisfy(e -> { assertThat(e.getKey()).endsWith(":temp"); assertThat(e.getValue().value()).isEqualTo("?"); });
    }
    @Test void toleratesUnfinishedArrayInitializer() {
        var p = preview.parse("class Main { void main() { int[] arr = {1, 2,");
        assertThat(p.incomplete()).isTrue();
        assertThat(p.arrays().values()).anySatisfy(a -> assertThat(a.values()).startsWith("1", "2"));
    }
    @Test void doesNotApplyAssignmentsOrRunCallsOrBranches() {
        var p = preview.parse("class Main { void main() { int[] a={1,2}; a[0]=99; int n=read(); if(false){ int x=5; } } int read(){throw new Error();} }");
        assertThat(p.arrays().values()).anySatisfy(a -> assertThat(a.values()).containsExactly("1", "2"));
        assertThat(p.variables().entrySet()).anySatisfy(e -> { assertThat(e.getKey()).endsWith(":n"); assertThat(e.getValue().value()).isEqualTo("?"); });
    }
    @Test void recognizesConstantsAndDefaultsWithoutGuessingMutableValues() {
        var p = preview.parse("class Main { void main() { final int N=2+1; int[] a=new int[N]; int n=2; n=5; int[] unknown=new int[n]; boolean[] b=new boolean[2]; } }");
        assertThat(p.arrays().values()).anySatisfy(a -> assertThat(a.values()).containsExactly("0", "0", "0"));
        assertThat(p.arrays().values()).anySatisfy(a -> assertThat(a.values()).containsExactly("false", "false"));
        assertThat(p.variables().entrySet()).anySatisfy(e -> { assertThat(e.getKey()).endsWith(":unknown"); assertThat(e.getValue().arrayId()).isNull(); assertThat(e.getValue().value()).isEqualTo("?"); });
    }
    @Test void keepsSameNamedLocalsInDifferentMethodsAndIgnoresComments() {
        var p = preview.parse("class Main { void first(){int[] a={1};} void second(){int[] a={2};} // int[] fake={99};\n}");
        assertThat(p.arrays()).hasSize(2);
        assertThat(p.declarations().values()).extracting(StaticPreview.Declaration::name).doesNotContain("fake");
    }
}
