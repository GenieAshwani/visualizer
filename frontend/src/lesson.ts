import type { Step } from './types';

export type LessonCheckpoint = { line: number; question: string };
export type SavedLesson = {
  id: string;
  title: string;
  mainClass: string;
  code: string;
  input: string;
  notes: string;
  checkpoints: LessonCheckpoint[];
};
export type Prediction = { question: string; expected?: string; line: number };

const LESSONS_KEY = 'codeforsuccess.lessons.v1';

function isSavedLesson(value: unknown): value is SavedLesson {
  if (!value || typeof value !== 'object') return false;
  const lesson = value as Record<string, unknown>;
  return typeof lesson.id === 'string' && typeof lesson.title === 'string'
    && typeof lesson.mainClass === 'string' && typeof lesson.code === 'string'
    && typeof lesson.input === 'string' && typeof lesson.notes === 'string'
    && Array.isArray(lesson.checkpoints)
    && lesson.checkpoints.every(checkpoint => !!checkpoint && typeof checkpoint === 'object'
      && Number.isInteger((checkpoint as LessonCheckpoint).line)
      && typeof (checkpoint as LessonCheckpoint).question === 'string');
}

export function loadSavedLessons(): { lessons: SavedLesson[]; error: string } {
  try {
    const stored = localStorage.getItem(LESSONS_KEY);
    if (!stored) return { lessons: [], error: '' };
    const parsed: unknown = JSON.parse(stored);
    if (!Array.isArray(parsed) || !parsed.every(isSavedLesson)) {
      return { lessons: [], error: 'Saved lessons have an invalid format. Save a new lesson to replace them.' };
    }
    return { lessons: parsed, error: '' };
  } catch (error) {
    return { lessons: [], error: `Could not load saved lessons: ${error instanceof Error ? error.message : 'browser storage unavailable'}` };
  }
}

export function storeSavedLessons(lessons: SavedLesson[]): void {
  localStorage.setItem(LESSONS_KEY, JSON.stringify(lessons));
}

export function parseCheckpoints(source: string): { checkpoints: LessonCheckpoint[]; invalidLine?: number } {
  const checkpoints: LessonCheckpoint[] = [];
  for (const [index, text] of source.split('\n').entries()) {
    if (!text.trim()) continue;
    const match = /^\s*(\d+)\s*:\s*(.+?)\s*$/.exec(text);
    if (!match || Number(match[1]) < 1) return { checkpoints, invalidLine: index + 1 };
    checkpoints.push({ line: Number(match[1]), question: match[2] });
  }
  return { checkpoints };
}

export function checkpointText(checkpoints: LessonCheckpoint[]): string {
  return checkpoints.map(checkpoint => `${checkpoint.line}: ${checkpoint.question}`).join('\n');
}

export function predictNextStep(current: Step | undefined, next: Step | undefined, checkpoints: LessonCheckpoint[]): Prediction | undefined {
  if (!next) return undefined;
  const changedCells: { name: string; index: number; oldValue: string; newValue: string }[] = [];
  if (current) {
    for (const array of Object.values(next.arrays)) {
      const old = current.arrays[array.id];
      if (!old) continue;
      const name = Object.entries(next.variables).find(([, variable]) => variable.arrayId === array.id)?.[0] ?? 'array';
      for (let index = 0; index < Math.min(old.values.length, array.values.length); index++) {
        if (old.values[index] !== array.values[index]) changedCells.push({ name, index, oldValue: old.values[index], newValue: array.values[index] });
      }
    }
  }
  const checkpoint = checkpoints.find(item => item.line === next.executedLine);
  const change = changedCells[0];
  if (checkpoint) return {
    question: checkpoint.question,
    line: next.executedLine,
    ...(changedCells.length === 1 && change ? { expected: change.newValue } : {}),
  };
  if (!change) return undefined;
  return {
    question: `What value will ${change.name}[${change.index}] hold after this statement? It is ${change.oldValue} now.`,
    expected: change.newValue,
    line: next.executedLine,
  };
}

export function normalizePrediction(value: string): string {
  const trimmed = value.trim();
  if (trimmed.length >= 2 && ((trimmed.startsWith('"') && trimmed.endsWith('"'))
      || (trimmed.startsWith("'") && trimmed.endsWith("'")))) return trimmed.slice(1, -1);
  return trimmed;
}
