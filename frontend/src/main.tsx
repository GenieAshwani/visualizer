import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import Editor, { loader, type OnMount } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-editor/esm/vs/basic-languages/java/java.contribution';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import { ArrowUpRight, BookOpen, Braces, Check, ChevronRight, CircleHelp, Code2, Eye, EyeOff, Layers3, LoaderCircle, Maximize2, Minimize2, Pause, Play, RotateCcw, RotateCw, SkipBack, SkipForward, SquareTerminal, X } from 'lucide-react';
import { ArrayStage, describe } from './ArrayStage';
import { examples } from './examples';
import { checkpointText, loadSavedLessons, normalizePrediction, parseCheckpoints, predictNextStep, storeSavedLessons, type SavedLesson } from './lesson';
import type { Trace, Preview } from './types';
import './styles.css';
import './lesson-ui.css';

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });
monaco.editor.defineTheme('cfs-night', { base: 'vs-dark', inherit: true, rules: [{ token: 'keyword', foreground: 'C2A2FF' }, { token: 'number', foreground: 'F8C781' }, { token: 'string', foreground: 'A8D7A1' }, { token: 'comment', foreground: '606C80' }], colors: { 'editor.background': '#141820', 'editor.foreground': '#D4DAE5', 'editorLineNumber.foreground': '#4B5568', 'editorLineNumber.activeForeground': '#AC91F5', 'editor.lineHighlightBackground': '#1B202D', 'editor.selectionBackground': '#7251B844', 'editorCursor.foreground': '#C4AAFF', 'editorGutter.background': '#141820' } });

function App() {
  const [code, setCode] = useState<string>(examples['Swap two values']);
  const [example, setExample] = useState('Swap two values');
  const [mainClass, setMainClass] = useState('Main');
  const [trace, setTrace] = useState<Trace>();
  const [preview, setPreview] = useState<Preview>();
  const [previewMessage, setPreviewMessage] = useState('Parsing Java syntax…');
  const [position, setPosition] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState('');
  const [tab, setTab] = useState<'console' | 'stack'>('console');
  const [help, setHelp] = useState(false);
  const [ready, setReady] = useState(false);
  const [forward, setForward] = useState(true);
  const [savedLessonState, setSavedLessonState] = useState(loadSavedLessons);
  const [activeLessonId, setActiveLessonId] = useState('');
  const [lessonToolsOpen, setLessonToolsOpen] = useState(false);
  const [lessonTitle, setLessonTitle] = useState('');
  const [lessonNotes, setLessonNotes] = useState('');
  const [lessonInput, setLessonInput] = useState('');
  const [checkpointSource, setCheckpointSource] = useState('');
  const [predictionMode, setPredictionMode] = useState(false);
  const [predictionInput, setPredictionInput] = useState('');
  const [predictionLocked, setPredictionLocked] = useState(false);
  const [predictionFeedback, setPredictionFeedback] = useState('');
  const [replayKey, setReplayKey] = useState(0);
  const [projectionMode, setProjectionMode] = useState(false);
  const [showCode, setShowCode] = useState(true);
  const [showConsole, setShowConsole] = useState(true);
  const editor = useRef<monaco.editor.IStandaloneCodeEditor | undefined>(undefined);
  const decorations = useRef<monaco.editor.IEditorDecorationsCollection | undefined>(undefined);
  const step = trace?.steps[position];
  const before = position > 0 ? trace?.steps[position - 1] : undefined;
  const count = trace?.steps.length ?? 0;
  const activeLesson = savedLessonState.lessons.find(lesson => lesson.id === activeLessonId);
  const nextPrediction = useMemo(
    () => predictNextStep(step, trace?.steps[position + 1], activeLesson?.checkpoints ?? []),
    [step, trace, position, activeLesson],
  );

  useEffect(() => { fetch('/api/health').then(r => setReady(r.ok)).catch(() => setReady(false)); }, []);
  useEffect(() => {
    const controller = new AbortController();
    setPreview(undefined);
    setPreviewMessage('Parsing Java syntax…');
    const timer = window.setTimeout(async () => {
      try {
        const response = await fetch('/api/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code }), signal: controller.signal });
        if (!response.ok) throw new Error('Live preview is unavailable. Check the backend or source length.');
        const result: Preview = await response.json();
        if (controller.signal.aborted) return;
        setPreview(result);
        setPreviewMessage('Static declarations and initial values only; assignments are not applied. Click Debug & Visualize, then Next to see executed changes.');
      } catch (e) {
        if (!controller.signal.aborted) setPreviewMessage(e instanceof Error ? e.message : 'Live preview unavailable.');
      }
    }, 250);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [code]);
  useEffect(() => {
    if (!playing || !count) return;
    if (predictionMode && nextPrediction && !predictionLocked) {
      setPlaying(false);
      return;
    }
    const timer = window.setTimeout(() => {
      if (position >= count - 1) { setPlaying(false); return; }
      setForward(true); setPosition(p => Math.min(p + 1, count - 1));
    }, 1150 / speed);
    return () => clearTimeout(timer);
  }, [playing, position, count, speed, predictionMode, predictionLocked, nextPrediction]);
  useEffect(() => {
    editor.current?.updateOptions({ fontSize: projectionMode ? 18 : 13 });
    editor.current?.layout();
  }, [projectionMode, showCode]);
  useEffect(() => {
    const line = step?.executedLine || step?.nextLine;
    decorations.current?.set(line ? [{ range: new monaco.Range(line, 1, line, 1), options: { isWholeLine: true, className: 'executed-line', glyphMarginClassName: 'executed-glyph', overviewRuler: { color: '#42dfc2', position: monaco.editor.OverviewRulerLane.Full } } }] : []);
    if (line) editor.current?.revealLineInCenterIfOutsideViewport(line);
  }, [step]);
  useEffect(() => {
    const model = editor.current?.getModel();
    if (model) monaco.editor.setModelMarkers(model, 'java-trace', (trace?.diagnostics ?? []).filter(d => d.line > 0).map(d => ({ startLineNumber: d.line, endLineNumber: d.line, startColumn: Math.max(1, d.column), endColumn: Math.max(2, d.column + 1), message: d.message, severity: d.kind === 'ERROR' ? monaco.MarkerSeverity.Error : monaco.MarkerSeverity.Warning })));
  }, [trace]);
  const mount: OnMount = e => { editor.current = e; decorations.current = e.createDecorationsCollection(); };
  function invalidate() { setTrace(undefined); setPlaying(false); setPosition(0); setPredictionInput(''); setPredictionLocked(false); setPredictionFeedback(''); setError(''); }
  function seek(next: number) { setPlaying(false); setForward(next > position); setPosition(next); setPredictionInput(''); setPredictionLocked(false); setPredictionFeedback(''); }
  function advance() {
    if (!trace || position >= count - 1 || (predictionMode && nextPrediction && !predictionLocked)) return;
    if (predictionMode && nextPrediction) {
      const correct = nextPrediction.expected != null
        && normalizePrediction(predictionInput) === normalizePrediction(nextPrediction.expected);
      setPredictionFeedback(nextPrediction.expected == null
        ? 'Prediction recorded. Compare your reasoning with the revealed execution state.'
        : correct
          ? `Correct — the executed trace confirms ${nextPrediction.expected}.`
          : `The trace shows ${nextPrediction.expected}. Compare it with your prediction and the highlighted change.`);
    } else setPredictionFeedback('');
    setPlaying(false);
    setForward(true);
    setPosition(position + 1);
    setPredictionInput('');
    setPredictionLocked(false);
  }
  function saveLesson() {
    const title = lessonTitle.trim();
    if (!title) { setError('Give this lesson a title before saving.'); return; }
    const parsedCheckpoints = parseCheckpoints(checkpointSource);
    if (parsedCheckpoints.invalidLine) { setError(`Checkpoint ${parsedCheckpoints.invalidLine} must use “line number: question”.`); return; }
    const lesson: SavedLesson = {
      id: activeLessonId || crypto.randomUUID(),
      title,
      mainClass,
      code,
      input: lessonInput,
      notes: lessonNotes,
      checkpoints: parsedCheckpoints.checkpoints,
    };
    const lessons = [lesson, ...savedLessonState.lessons.filter(item => item.id !== lesson.id)];
    try {
      storeSavedLessons(lessons);
      setSavedLessonState({ lessons, error: '' });
      setActiveLessonId(lesson.id);
      setLessonToolsOpen(false);
      setError('');
    } catch (cause) {
      setError(`Could not save this lesson in browser storage: ${cause instanceof Error ? cause.message : 'storage unavailable'}`);
    }
  }
  function loadLesson(id: string) {
    const lesson = savedLessonState.lessons.find(item => item.id === id);
    if (!lesson) return;
    setActiveLessonId(lesson.id);
    setLessonTitle(lesson.title);
    setCode(lesson.code);
    setMainClass(lesson.mainClass);
    setLessonInput(lesson.input);
    setLessonNotes(lesson.notes);
    setCheckpointSource(checkpointText(lesson.checkpoints));
    setExample('');
    setPredictionFeedback('');
    invalidate();
  }
  async function run() {
    setRunning(true); invalidate();
    try {
      const response = await fetch('/api/trace', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code, mainClass, input: lessonInput }), signal: AbortSignal.timeout(30_000) });
      if (!response.ok) throw new Error(response.status === 429 ? 'Another program is running. Try again shortly.' : `The tracer returned HTTP ${response.status}. Check the Java backend.`);
      const result: Trace = await response.json();
      setReady(true); setTrace(result); setForward(true); setPosition(0);
      setPlaying(false);
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to reach the Java backend.'); }
    finally { setRunning(false); }
  }
  return <div className={`app-shell ${projectionMode ? 'projection-mode' : ''}`}>
    <header className="topbar"><a className="brand" href="/" aria-label="CodeForSuccess home"><span className="brand-mark"><Code2 size={23}/></span><span>code<span className="brand-light">for</span>success<span className="brand-dot">.</span></span></a><span className="top-divider"/><span className="workspace-name">Learning workspace</span><div className="top-right"><span className="milestone">DSA LAB</span><button className="help-button" onClick={() => setHelp(true)}><CircleHelp size={16}/> How it works</button><div className="avatar">CS</div></div></header>
    <main>
      <div className="breadcrumb">Workspace<ChevronRight size={13}/>Data structures<ChevronRight size={13}/><span>Arrays</span></div>
      <div className="page-heading"><div><div className="eyebrow"><span/> LEARN BY SEEING</div><h1>Less guessing. <span>More understanding.</span></h1><p>Turn your Java code into a story you can follow, one step at a time.</p></div><div className="engine-badge"><span className={ready ? 'live-dot' : 'offline-dot'}/><div><strong>{ready ? 'Java engine connected' : 'Java engine offline'}</strong><span>Real execution · JDK 21</span></div></div></div>
      <div className="lab-toolbar"><div className="structure-tab"><Layers3 size={17}/>Arrays<span>01</span></div><div className="toolbar-note">A closer look at every element.</div><label className="example-picker">Try an example<select aria-label="Choose example" value={example} disabled={running} onChange={e => { setExample(e.target.value); setCode(examples[e.target.value as keyof typeof examples]); setMainClass('Main'); setLessonInput(''); setLessonTitle(''); setLessonNotes(''); setCheckpointSource(''); setActiveLessonId(''); invalidate(); }}><option value="" disabled>Select an example…</option>{Object.keys(examples).map(name => <option key={name}>{name}</option>)}</select></label>{savedLessonState.lessons.length > 0 && <label className="saved-lesson-picker">Saved lesson<select aria-label="Load saved lesson" value={activeLessonId} onChange={e => loadLesson(e.target.value)}><option value="">Choose lesson…</option>{savedLessonState.lessons.map(lesson => <option key={lesson.id} value={lesson.id}>{lesson.title}</option>)}</select></label>}<button className="toolbar-action" onClick={() => { setLessonTitle(activeLesson?.title ?? ''); setLessonNotes(activeLesson?.notes ?? ''); setLessonInput(activeLesson?.input ?? lessonInput); setCheckpointSource(activeLesson ? checkpointText(activeLesson.checkpoints) : ''); setLessonToolsOpen(open => !open); }}><BookOpen size={14}/>{lessonToolsOpen ? 'Close lesson plan' : 'Prepare lesson'}</button><button className="toolbar-action projection-toggle" onClick={() => setProjectionMode(active => !active)}>{projectionMode ? <Minimize2 size={14}/> : <Maximize2 size={14}/ >}{projectionMode ? 'Exit projection' : 'Projection view'}</button>{projectionMode && <><button className="toolbar-action" onClick={() => setShowCode(show => !show)}>{showCode ? <EyeOff size={14}/> : <Eye size={14}/>} {showCode ? 'Hide code' : 'Show code'}</button><button className="toolbar-action" onClick={() => setShowConsole(show => !show)}>{showConsole ? <EyeOff size={14}/> : <Eye size={14}/>} {showConsole ? 'Hide console' : 'Show console'}</button></>}</div>
      {lessonToolsOpen && <section className="lesson-tools-panel" aria-label="Prepare lesson">
        <div className="lesson-tools-heading"><div><BookOpen size={17}/><div><strong>Prepare a reusable lesson</strong><span>Saved in this browser. Include a prompt as “line: question”.</span></div></div><button aria-label="Close lesson plan" onClick={() => setLessonToolsOpen(false)}><X size={16}/></button></div>
        <div className="lesson-fields">
          <label>Lesson title<input aria-label="Lesson title" value={lessonTitle} onChange={e => setLessonTitle(e.target.value)} placeholder="Array traversal — predict the sum"/></label>
          <label>Sample program input<textarea aria-label="Sample program input" value={lessonInput} onChange={e => setLessonInput(e.target.value)} placeholder={'One input value per line, for example:\n5\n8'} rows={3}/></label>
          <label>Teacher notes<textarea aria-label="Teacher notes" value={lessonNotes} onChange={e => setLessonNotes(e.target.value)} placeholder="Learning goal, discussion cues, or lesson plan…" rows={3}/></label>
          <label>Prediction checkpoints<textarea aria-label="Prediction checkpoints" value={checkpointSource} onChange={e => setCheckpointSource(e.target.value)} placeholder={'4: What value will arr[0] hold after this line?'} rows={3}/></label>
        </div>
        <div className="lesson-tools-footer"><span>Checkpoints use the Java editor’s original line numbers.</span><button className="save-lesson-button" onClick={saveLesson}><Check size={15}/>Save lesson</button></div>
      </section>}
      {savedLessonState.error && <p className="lesson-storage-warning" role="status">{savedLessonState.error}</p>}
      {activeLesson?.notes && !lessonToolsOpen && <p className="lesson-note active-lesson-note">{activeLesson.notes}</p>}
      <div className={`workbench ${projectionMode && !showCode ? 'code-hidden' : ''}`}>
        {(!projectionMode || showCode) && <section className="editor-panel" aria-label="Java code editor">
          <div className="panel-header"><div className="file-tab"><span className="java-icon">J</span>{mainClass.split('.').pop() || 'Main'}.java<span className="file-dot"/></div><span className="language-tag">JAVA 21</span></div>
          <div className="editor-wrap"><Editor height="100%" language="java" theme="cfs-night" value={code} onMount={mount} onChange={value => { setCode(value ?? ''); invalidate(); }} options={{ fontSize: 13, fontFamily: "'Cascadia Code', 'Consolas', monospace", fontLigatures: true, minimap: { enabled: false }, padding: { top: 22, bottom: 16 }, lineNumbersMinChars: 3, glyphMargin: true, scrollBeyondLastLine: false, smoothScrolling: true, automaticLayout: true, readOnly: running, tabSize: 4, wordWrap: 'off', renderLineHighlight: 'none', overviewRulerBorder: false }} loading={<span className="editor-loading">Loading Java editor…</span>}/></div>
          <div className="editor-footer"><label>Entry class <input aria-label="Entry class" value={mainClass} disabled={running} onChange={e => { setMainClass(e.target.value); invalidate(); }}/></label><button className="run-button" disabled={running || !code.trim()} onClick={run}>{running ? <LoaderCircle className="spin" size={15}/> : <Play size={15} fill="currentColor"/>}{running ? 'Tracing…' : 'Debug & Visualize'}</button></div>
        </section>}
        <section className="visual-panel" aria-label="Execution visualization"><div className="panel-header"><div className="visual-title"><Braces size={17}/>Visualization</div><div className={`status-pill ${trace?.status === 'completed' ? 'success' : ''}`}><span/>{running ? 'Executing Java' : playing ? 'Playing trace' : step ? 'Debug paused' : 'Live preview'}</div></div>
          <div className="visual-body"><div className="visual-caption"><span>{step ? 'ARRAY EXPLORER' : 'STATIC PREVIEW · NOT EXECUTED'}</span><div><span className="legend-dot violet"/>Changed<span className="legend-dot mint"/>Accessed</div></div><ArrayStage step={step} before={before} speed={speed} forward={forward} playing={playing} preview={preview} replayKey={replayKey}/></div>
          <div className="explanation" aria-live="polite"><span className="step-symbol">{step ? <ChevronRight size={18}/> : <CircleHelp size={17}/>}</span><div><span>{step ? `STEP ${position + 1}${step.executedLine > 0 ? ` · LINE ${step.executedLine}` : ' · START'}` : 'LIVE PREVIEW · NO EXECUTION'}</span><p>{running ? 'Compiling your code and recording actual JVM state…' : step ? describe(step, before) : previewMessage}</p></div></div>
          {predictionMode && predictionFeedback && <p className="prediction-feedback" role="status">{predictionFeedback}</p>}
          {predictionMode && nextPrediction && <section className="prediction-card" aria-label="Prediction checkpoint"><div><span>PREDICT BEFORE ADVANCING · LINE {nextPrediction.line}</span><p>{nextPrediction.question}</p></div><div className="prediction-answer"><input aria-label="Your prediction" value={predictionInput} disabled={predictionLocked} onChange={e => { setPredictionInput(e.target.value); setPredictionLocked(false); }} onKeyDown={e => { if (e.key === 'Enter' && predictionInput.trim()) setPredictionLocked(true); }} placeholder="Type your prediction"/><button disabled={!predictionInput.trim() || predictionLocked} onClick={() => setPredictionLocked(true)}>{predictionLocked ? 'Prediction locked' : 'Commit prediction'}</button></div></section>}
        </section>
      </div>
      <section className="playback" aria-label="Debug controls">
        <div className="playback-heading"><strong>Step controls</strong><span>{count ? `Step ${position + 1} of ${count}` : 'Run your code to enable stepping'}</span></div>
        <div className="play-buttons">
          <button title="Restart" aria-label="Restart" disabled={!count} onClick={() => seek(0)}><RotateCcw size={15}/>Restart</button>
          <button title="Previous" aria-label="Previous" disabled={!count || position === 0} onClick={() => seek(position - 1)}><SkipBack size={15}/>Previous</button>
          <button title="Replay current step" aria-label="Replay current step" disabled={!step || position === 0} onClick={() => { setForward(true); setReplayKey(key => key + 1); }}><RotateCw size={15}/>Replay step</button>
          <button className="play-button" title={playing ? 'Pause' : 'Play'} aria-label={playing ? 'Pause' : 'Play'} disabled={!count || (predictionMode && !!nextPrediction)} onClick={() => { if (position === count - 1) setPosition(0); setForward(true); setPlaying(p => !p); }}>{playing ? <Pause size={15} fill="currentColor"/> : <Play size={15} fill="currentColor"/>}{playing ? 'Pause' : 'Play'}</button>
          <button title="Next" aria-label="Next" disabled={!count || position >= count - 1 || (predictionMode && !!nextPrediction && !predictionLocked)} onClick={advance}><SkipForward size={15}/>Next</button>
        </div>
        <div className="timeline">
          <label htmlFor="execution-timeline">Jump to step</label>
          <input id="execution-timeline" aria-label="Execution timeline" type="range" min={0} max={Math.max(1, count - 1)} value={position} disabled={!count} onChange={e => seek(Number(e.target.value))} style={{ '--progress': `${count > 1 ? position / (count - 1) * 100 : 0}%` } as React.CSSProperties}/>
          <div><span>{count ? 'Drag to inspect any recorded state' : 'Debug & Visualize to enable stepping'}</span><span><b>{count ? position + 1 : 0}</b> / {count} steps</span></div>
        </div>
        <label className="speed-control">Speed<select aria-label="Playback speed" value={speed} onChange={e => setSpeed(Number(e.target.value))}>{[.25, .5, 1, 1.5, 2, 3].map(n => <option key={n} value={n}>{n}×</option>)}</select></label>
        <label className="prediction-toggle"><input type="checkbox" checked={predictionMode} onChange={e => { setPredictionMode(e.target.checked); setPredictionInput(''); setPredictionLocked(false); setPredictionFeedback(''); }}/><span>Predict before reveal</span></label>
      </section>
      {(!projectionMode || showConsole) && <section className="console-panel"><div className="console-header"><button className={tab === 'console' ? 'active' : ''} onClick={() => setTab('console')}><SquareTerminal size={15}/>Console{trace && <span className="count-badge">{trace.diagnostics.length || (trace.stdout ? 1 : 0)}</span>}</button><button className={tab === 'stack' ? 'active' : ''} onClick={() => setTab('stack')}><Layers3 size={14}/>Call stack</button><span>{trace ? `${trace.durationMs} ms · ${trace.status.replaceAll('_', ' ')}` : 'Output from your program'}</span></div><div className="console-content" role="log">{error && <p className="error-text">{error}</p>}{tab === 'console' ? <>{!trace && !error && <p className="console-placeholder"><span>❯</span> {running ? 'javac → JVM → JDI trace. Your program is running…' : 'Your program’s output will appear here.'}</p>}{trace && <><p className={trace.status === 'completed' ? 'console-success' : 'error-text'}>{trace.status === 'completed' && <Check size={14}/>} {trace.message}</p>{trace.diagnostics.map((d, i) => <p className="error-text" key={i}>Line {d.line}:{d.column} — {d.message}</p>)}{(trace.stdout || trace.stderr) && <><span className="output-label">FULL RUN OUTPUT · independent of playback position</span><pre>{trace.stdout}{trace.stderr}</pre></>}</>}</> : step ? step.stack.map((frame, i) => <div className="stack-frame" key={i}><span>#{i}</span><strong>{frame.method}</strong><span>line {frame.line}</span></div>) : <p className="console-placeholder">Debug a program to inspect its runtime call stack. Declared methods appear in live preview.</p>}</div></section>}
      <footer className="page-footer"><span><span className="live-dot"/>Powered by actual Java execution. No simulated results.</span><button onClick={() => setHelp(true)}>Built for your next breakthrough<ArrowUpRight size={14}/></button></footer>
    </main>
    {help && <div className="modal-backdrop" onClick={() => setHelp(false)}><section role="dialog" aria-modal="true" aria-label="How Array Lab works" className="help-modal" onClick={e => e.stopPropagation()}><button aria-label="Close help" className="close-modal" onClick={() => setHelp(false)}><X size={20}/></button><span className="eyebrow">UNDER THE HOOD</span><h2>Your Java. A real JVM.</h2><p>Write a complete Java program with a main method, then click Debug &amp; Visualize. We compile it with debug symbols and use Java’s debugging interface to capture actual array contents, variables, and stack frames.</p><p>Playback shows the state after the highlighted line’s effect. Debug starts paused. Next advances one source statement; Play is optional. Statements sharing a line have separate steps. Loop conditions and updates are also steps. Previous and Restart replay captured states without executing your program again.</p><p>While typing, static preview shows parsed declarations, methods, and known initializer values, even in incomplete code. It does not apply assignments, execute methods, or simulate loops. Unknown values are marked ?.</p><p>Violet marks changed values; mint marks a verified transfer. Arrows below cells show integer variables whose current value equals that index. The console shows output from the entire run.</p><p>Single-threaded programs, one source file, no interactive input. Arrays display up to 256 elements. Runs are limited to 12 seconds and 1,800 visible steps.</p><div className="local-notice">Local development only: submitted Java runs with your machine’s permissions. Use trusted code. A public student deployment requires isolated execution workers.</div><button className="run-button" onClick={() => setHelp(false)}>Let’s explore<ChevronRight size={16}/></button></section></div>}
  </div>;
}

createRoot(document.getElementById('root')!).render(<App/>);
