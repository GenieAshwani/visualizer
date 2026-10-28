import { test, expect, type Page } from '@playwright/test';

async function run(page: Page) {
  const response = page.waitForResponse(r => r.url().endsWith('/api/trace') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Debug & Visualize', exact: true }).click();
  const result = await (await response).json();
  if (result.steps.length) {
    await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeVisible();
    await expect(page.getByRole('slider', { name: 'Execution timeline' })).toHaveValue('0');
  }
  return result;
}
async function source(page: Page, text: string) {
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.evaluate(value => navigator.clipboard.writeText(value), text);
  await page.getByRole('textbox', { name: 'Editor content' }).focus();
  await page.keyboard.press('Control+a');
  await page.keyboard.press('Control+v');
}
async function stepTo(page: Page, index: number) {
  const slider = page.getByRole('slider', { name: 'Execution timeline' });
  await slider.focus();
  await slider.press('Home');
  for (let i = 0; i < index; i++) await slider.press('ArrowRight');
}

test('real swap, value transfer, line highlight, all playback controls', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await expect(page.getByText('Java engine connected')).toBeVisible();
  await expect(page.getByText(/ARRAY INITIALIZERS .* NOT EXECUTED/)).toBeVisible();
  await page.getByRole('combobox', { name: 'Playback speed' }).selectOption('0.25');
  const result = await run(page);
  expect(result.status).toBe('completed');
  const transfer = result.steps.findIndex((s: any) => s.transfers.some((t: any) => t.variable === 'temp' && t.direction === 'read'));
  expect(transfer).toBeGreaterThan(0);
  await stepTo(page, transfer - 1);
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.value-particle')).toHaveText('12');
  const first = await page.locator('.value-particle').boundingBox();
  await page.waitForTimeout(400); // observe the animation in flight
  const second = await page.locator('.value-particle').boundingBox();
  expect(first).not.toEqual(second);
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  const frozen = await page.locator('.value-particle').boundingBox();
  await page.waitForTimeout(250);
  expect(await page.locator('.value-particle').boundingBox()).toEqual(frozen);
  await expect(page.locator('[data-variable="temp"] .var-value')).toHaveText('12');
  await expect(page.locator('.executed-line').first()).toBeVisible();
  await page.screenshot({ path: 'test-results/array-transfer.png', fullPage: true });
  await page.getByRole('button', { name: 'Previous', exact: true }).click();
  await expect(page.locator('[data-variable="temp"]')).toHaveCount(0);
  await page.getByRole('slider', { name: 'Execution timeline' }).focus();
  await page.keyboard.press('End');
  await expect(page.locator('.array-cell')).toHaveText(['16', '8', '24', '12', '5']);
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Restart', exact: true }).click();
  await expect(page.getByRole('slider', { name: 'Execution timeline' })).toHaveValue('0');
  await page.getByRole('combobox', { name: 'Playback speed' }).selectOption('3');
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await expect(page.getByRole('slider', { name: 'Execution timeline' })).not.toHaveValue('0');
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  const paused = await page.getByRole('slider', { name: 'Execution timeline' }).inputValue();
  await page.waitForTimeout(600);
  await expect(page.getByRole('slider', { name: 'Execution timeline' })).toHaveValue(paused);
  expect(errors).toEqual([]);
});

test('all supplied examples execute against actual backend', async ({ page }) => {
  await page.goto('/');
  const cases: [string, string[]][] = [['Reverse an array', ['23','19','15','11','7','3']], ['Bubble sort', ['1','3','5','7','9']], ['Sum & traversal', ['4','8','15','16','23','42']], ['Methods & aliases', ['4','8','12']]];
  for (const [name, values] of cases) {
    await page.getByRole('combobox', { name: 'Choose example' }).selectOption(name);
    const result = await run(page);
    expect(result.status).toBe('completed');
    await page.getByRole('slider', { name: 'Execution timeline' }).focus();
    await page.keyboard.press('End');
    await expect(page.locator('.array-cell')).toHaveText(values);
  }
  await page.screenshot({ path: 'test-results/array-aliases.png', fullPage: true });
});

test('student edited code determines values and changing code invalidates old trace', async ({ page }) => {
  await page.goto('/');
  await source(page, 'public class Main {\n public static void main(String[] args) {\n int[] custom = {31, 47, 83};\n custom[1] = 123;\n System.out.println(custom[1]);\n }\n}');
  const result = await run(page);
  expect(result.stdout.trim()).toBe('123');
  await page.getByRole('slider', { name: 'Execution timeline' }).focus();
  await page.keyboard.press('End');
  await expect(page.locator('.array-cell')).toHaveText(['31','123','83']);
  await source(page, 'public class Main { public static void main(String[] args) { int[] broken = ; } }');
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  const invalid = await run(page);
  expect(invalid.status).toBe('compile_error');
  await expect(page.getByRole('log')).toContainText('Java could not compile');
  await expect(page.getByRole('log')).toContainText('Line 1');
});

test('runtime failure keeps the partial trace', async ({ page }) => {
  await page.goto('/');
  await source(page, 'public class Main {\n public static void main(String[] args) {\n int[] a = {5};\n a[99] = 2;\n }\n}');
  const result = await run(page);
  expect(result.status).toBe('runtime_error');
  await expect(page.getByRole('log')).toContainText('ArrayIndexOutOfBoundsException');
  await page.getByRole('slider', { name: 'Execution timeline' }).focus();
  await page.keyboard.press('End');
  await expect(page.locator('.array-cell')).toHaveText(['5']);
});

test('mobile layout stays within the viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await run(page);
  await stepTo(page, 2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/array-mobile.png', fullPage: true });
});

test('compact laptop layout keeps long-code controls and explanation above the playback dock', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 });
  await page.goto('/');
  const code = [
    'public class Main {',
    ' public static void main(String[] args) {',
    ...Array.from({ length: 80 }, (_, i) => `  // Keep the editor content long: ${i + 1}`),
    '  int[] a = {1, 2, 3};',
    '  a[0] = 4;',
    ' }',
    '}',
  ].join('\n');
  await source(page, code);
  const result = await run(page);
  expect(result.status).toBe('completed');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.explanation')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeVisible();
  const layout = await page.evaluate(() => {
    const bounds = (selector: string) => document.querySelector(selector)!.getBoundingClientRect();
    const dock = bounds('.playback');
    return {
      explanationAboveDock: bounds('.explanation').bottom <= dock.top,
      editorControlsAboveDock: bounds('.editor-footer').bottom <= dock.top,
      dockWithinViewport: dock.bottom <= window.innerHeight,
    };
  });
  expect(layout).toEqual({
    explanationAboveDock: true,
    editorControlsAboveDock: true,
    dockWithinViewport: true,
  });
});

test('live preview follows incomplete typing without executing Java', async ({ page }) => {
  let executions = 0;
  page.on('request', r => { if (r.url().endsWith('/api/trace')) executions++; });
  await page.goto('/');
  await source(page, 'class Main { static void helper() {} void demo() { int[] arr = {1, 2,');
  await expect(page.locator('.array-cell')).toHaveText(['1', '2', '?']);
  await expect(page.getByText('Incomplete syntax supported')).toBeVisible();
  await page.keyboard.type('9');
  await expect(page.locator('.array-cell')).toHaveText(['1', '2', '9']);
  await expect(page.getByText('void helper()', { exact: true })).toBeVisible();
  await expect(page.locator('.executed-line')).toHaveCount(0);
  await expect(page.locator('.value-particle')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Previous', exact: true })).toBeDisabled();
  await source(page, 'class Main { void demo() { int[] arr={4,8}; int i=0; int temp=arr[0]; arr[0]=99; System.out.println("must not execute"); } }');
  await expect(page.locator('.array-cell')).toHaveText(['4', '8']);
  await expect(page.locator('[data-variable="i"] .var-value')).toHaveText('0');
  await expect(page.locator('[data-variable="temp"] .var-value')).toHaveText('?');
  await expect(page.getByRole('log')).not.toContainText('must not execute');
  expect(executions).toBe(0);
  await page.screenshot({ path: 'test-results/live-preview.png', fullPage: true });
});

test('collection entries and recursive stack frames follow actual recorded state', async ({ page }) => {
  await page.goto('/');
  await source(page, `import java.util.ArrayList;
import java.util.HashMap;
public class Main {
  static int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
  }
  public static void main(String[] args) {
    ArrayList<Integer> values = new ArrayList<>();
    int next = 7;
    values.add(next);
    int first = values.get(0);
    HashMap<String, Integer> counts = new HashMap<>();
    counts.put("items", next);
    int result = factorial(3);
  }
}`);
  const trace = await run(page);
  expect(trace.status).toBe('completed');
  const listStep = trace.steps.findIndex((step: any) =>
    Object.values(step.collections).some((collection: any) => collection.kind === 'list' && collection.entries.some((entry: any) => entry.value === '7')));
  expect(listStep).toBeGreaterThan(0);
  const flowStep = trace.steps.findIndex((step: any) => step.collectionFlows.some((flow: any) => flow.source === 'next' && flow.value === '7'));
  expect(flowStep).toBeGreaterThan(0);
  await stepTo(page, flowStep - 1);
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.value-particle')).toHaveText('7');
  await expect(page.locator('.collection-object')).toContainText('values');
  const readStep = trace.steps.findIndex((step: any) =>
    step.collectionFlows.some((flow: any) => flow.direction === 'read' && flow.source === 'first' && flow.value === '7'));
  expect(readStep).toBeGreaterThan(flowStep);
  await stepTo(page, readStep - 1);
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.value-particle')).toHaveText('7');
  await stepTo(page, trace.steps.length - 1);
  await expect(page.getByRole('article', { name: 'map counts' })).toContainText('"items"');
  await expect(page.getByRole('article', { name: 'map counts' })).toContainText('7');

  const recursiveStep = trace.steps.findIndex((step: any) =>
    step.stack.filter((frame: any) => frame.method === 'Main.factorial').length >= 3);
  expect(recursiveStep).toBeGreaterThan(0);
  await stepTo(page, recursiveStep);
  await expect(page.getByRole('region', { name: 'Call stack with local variables' })).toBeVisible();
  await expect(page.getByText('int n = 3', { exact: true })).toBeVisible();
  await expect(page.getByText('int n = 2', { exact: true })).toBeVisible();
  await expect(page.getByText('int n = 1', { exact: true })).toBeVisible();
});

test('Debug stays paused and manual Next/Previous advance whole statements', async ({ page }) => {
  await page.goto('/');
  await source(page, 'public class Main { public static void main(String[] args) { int[] arr={1,2}; int i=0; arr[i++] += 3; arr[1]=9; } }');
  const result = await run(page);
  expect(result.status).toBe('completed');
  expect(result.steps).toHaveLength(5);
  await page.waitForTimeout(1400);
  await expect(page.getByRole('slider', { name: 'Execution timeline' })).toHaveValue('0');
  await expect(page.locator('.array-cell')).toHaveCount(0);
  await expect(page.locator('.value-particle')).toHaveCount(0);
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.array-cell')).toHaveText(['1', '2']);
  await expect(page.getByRole('slider', { name: 'Execution timeline' })).toHaveValue('1');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('[data-variable="i"] .var-value')).toHaveText('0');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.array-cell')).toHaveText(['4', '2']);
  await expect(page.locator('[data-variable="i"] .var-value')).toHaveText('1');
  await expect(page.locator('.cell-column').nth(1).locator('.pointers')).toContainText('i');
  await page.getByRole('button', { name: 'Previous', exact: true }).click();
  await expect(page.locator('.array-cell')).toHaveText(['1', '2']);
  await expect(page.locator('[data-variable="i"] .var-value')).toHaveText('0');
  await expect(page.getByRole('slider', { name: 'Execution timeline' })).toHaveValue('2');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.array-cell')).toHaveText(['4', '2']);
  await page.getByRole('slider', { name: 'Execution timeline' }).focus();
  await page.keyboard.press('End');
  await expect(page.locator('.array-cell')).toHaveText(['4', '9']);
  await page.getByRole('button', { name: 'Restart', exact: true }).click();
  await expect(page.locator('.array-cell')).toHaveCount(0);
  await expect(page.getByRole('slider', { name: 'Execution timeline' })).toHaveValue('0');
  await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeVisible();
  await page.getByRole('combobox', { name: 'Playback speed' }).selectOption('3');
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeVisible();
  await expect(page.locator('.array-cell')).toHaveText(['4', '9']);
  await expect(page.locator('.value-particle')).toHaveCount(0);
});

test('step explanation describes the actual array mutation and highlights the statement', async ({ page }) => {
  await page.goto('/');
  await source(page, 'public class Main {\n public static void main(String[] args) {\n  int[] arr = {12, 8};\n  arr[0] = 3;\n }\n}');
  const result = await run(page);
  expect(result.status).toBe('completed');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.array-cell')).toHaveText(['3', '8']);
  await expect(page.locator('.explanation')).toContainText('arr[0] changed from 12 to 3');
  await expect(page.locator('.executed-line').first()).toBeVisible();
});

test('prediction mode commits an answer before reveal and checks it against the trace', async ({ page }) => {
  await page.goto('/');
  await source(page, 'public class Main {\n public static void main(String[] args) {\n  int[] arr = {12, 8};\n  arr[0] = 3;\n }\n}');
  await run(page);
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByLabel('Predict before reveal').check();
  await expect(page.getByRole('region', { name: 'Prediction checkpoint' })).toContainText('arr[0]');
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await page.getByRole('textbox', { name: 'Your prediction' }).fill('3');
  await page.getByRole('button', { name: 'Commit prediction' }).click();
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('.array-cell')).toHaveText(['3', '8']);
  await expect(page.getByRole('status')).toContainText('Correct');
  await page.getByRole('button', { name: 'Previous', exact: true }).click();
  await expect(page.locator('.array-cell')).toHaveText(['12', '8']);
});

test('replay current step animates the recorded transfer without advancing', async ({ page }) => {
  await page.goto('/');
  await source(page, 'public class Main {\n public static void main(String[] args) {\n  int[] arr = {12, 8};\n  int temp = arr[0];\n }\n}');
  await run(page);
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.locator('[data-variable="temp"] .var-value')).toHaveText('12');
  await expect(page.locator('.value-particle')).toHaveText('12');
  await page.waitForTimeout(700);
  await expect(page.locator('.value-particle')).toHaveCount(0);
  await page.getByRole('button', { name: 'Replay current step' }).click();
  await expect(page.locator('.value-particle')).toHaveText('12');
  await expect(page.getByRole('slider', { name: 'Execution timeline' })).toHaveValue('2');
  await expect(page.locator('[data-variable="temp"] .var-value')).toHaveText('12');
});

test('saved lesson restores sample input and line checkpoints', async ({ page }) => {
  await page.goto('/');
  await source(page, 'import java.util.Scanner;\npublic class Main {\n public static void main(String[] args) {\n  Scanner input = new Scanner(System.in);\n  int[] arr = {input.nextInt(), input.nextInt()};\n  arr[0] += arr[1];\n  System.out.println(arr[0]);\n }\n}');
  await page.getByRole('button', { name: 'Prepare lesson' }).click();
  await page.getByRole('textbox', { name: 'Lesson title' }).fill('Input-driven array sum');
  await page.getByRole('textbox', { name: 'Sample program input' }).fill('4\n7\n');
  await page.getByRole('textbox', { name: 'Teacher notes' }).fill('Ask students how the index changes.');
  await page.getByRole('textbox', { name: 'Prediction checkpoints' }).fill('6: What sum will arr[0] contain?');
  await page.getByRole('button', { name: 'Save lesson' }).click();
  await expect(page.getByLabel('Load saved lesson')).toHaveValue(/.+/);
  await page.reload();
  await page.getByLabel('Load saved lesson').selectOption({ label: 'Input-driven array sum' });
  const response = page.waitForResponse(r => r.url().endsWith('/api/trace') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Debug & Visualize', exact: true }).click();
  const result = await (await response).json();
  expect(result.status).toBe('completed');
  expect(result.stdout.trim()).toBe('11');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await page.getByLabel('Predict before reveal').check();
  await expect(page.getByRole('region', { name: 'Prediction checkpoint' })).toContainText('What sum will arr[0] contain?');
});

test('projection view can hide and restore code and console', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Projection view' }).click();
  await page.getByRole('button', { name: 'Hide code' }).click();
  await expect(page.getByRole('region', { name: 'Java code editor' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Show code' }).click();
  await expect(page.getByRole('region', { name: 'Java code editor' })).toBeVisible();
  await page.getByRole('button', { name: 'Hide console' }).click();
  await expect(page.locator('.console-panel')).toHaveCount(0);
  await page.getByRole('button', { name: 'Show console' }).click();
  await expect(page.locator('.console-panel')).toBeVisible();
  await page.getByRole('button', { name: 'Exit projection' }).click();
});
