import { useEffect, useLayoutEffect, useRef } from 'react';
import gsap from 'gsap';
import { Braces, Layers3, ArrowRight } from 'lucide-react';
import type { Step, Preview } from './types';

export function describe(step?: Step, before?: Step): string {
  if (!step) return 'Your code. Made visible.';
  for (const array of Object.values(step.arrays)) {
    const old = before?.arrays[array.id];
    if (!old) continue;
    const index = array.values.findIndex((value, i) => old.values[i] !== value);
    if (index < 0) continue;
    const name = Object.entries(step.variables).find(([, variable]) => variable.arrayId === array.id)?.[0] ?? 'array';
    return `${name}[${index}] changed from ${old.values[index]} to ${array.values[index]}. The executed statement updated this array cell.`;
  }
  for (const flow of step.collectionFlows ?? []) {
    if (flow.direction === 'read') return `Read ${flow.value} from ${flow.target} into ${flow.source}.`;
    return `Move ${flow.value} from ${flow.source} into ${flow.target}.`;
  }
  for (const collection of Object.values(step.collections ?? {})) {
    const old = before?.collections?.[collection.id];
    if (!collection.supported || !old?.supported) continue;
    const name = Object.entries(step.variables).find(([, variable]) => variable.collectionId === collection.id)?.[0] ?? 'collection';
    if (collection.kind === 'map') {
      const changed = collection.entries.find(entry => old.entries.find(previous => previous.key === entry.key)?.value !== entry.value);
      if (changed) return `${name}.put(${changed.key}, ${changed.value}) updated the map entry for key ${changed.key}. Size: ${old.size} → ${collection.size}.`;
      const removed = old.entries.find(entry => !collection.entries.some(current => current.key === entry.key));
      if (removed) return `${name} removed the entry for key ${removed.key}. Size: ${old.size} → ${collection.size}.`;
    } else {
      const added = collection.entries.find((entry, index) => {
        const occurrencesBefore = old.entries.filter(previous => previous.value === entry.value).length;
        const occurrencesNow = collection.entries.slice(0, index + 1).filter(current => current.value === entry.value).length;
        return occurrencesNow > occurrencesBefore;
      });
      if (added) return `${name} now contains ${added.value}. Size: ${old.size} → ${collection.size}.`;
      const removed = old.entries.find(entry => !collection.entries.some(current => current.value === entry.value));
      if (removed) return `${name} no longer contains ${removed.value}. Size: ${old.size} → ${collection.size}.`;
      const changedIndex = collection.entries.findIndex((entry, index) => old.entries[index]?.value !== entry.value);
      if (changedIndex >= 0) return `${name}[${changedIndex}] changed from ${old.entries[changedIndex]?.value ?? 'empty'} to ${collection.entries[changedIndex].value}.`;
    }
  }
  if (step.transfers.length) {
    const t = step.transfers[0];
    if (t.direction === 'copy') return `Copy ${t.value} from ${t.array}[${t.index}] into ${t.targetArray}[${t.targetIndex}].`;
    return t.direction === 'read' ? `Copy ${t.value} from ${t.array}[${t.index}] into ${t.variable}.` : `Write ${t.value} from ${t.variable} into ${t.array}[${t.index}].`;
  }
  for (const [name, variable] of Object.entries(step.variables)) {
    const old = before?.variables[name];
    if (variable.arrayId != null && old?.arrayId !== variable.arrayId) return `${name} points to an array with ${step.arrays[variable.arrayId]?.length ?? 0} elements.`;
    if (variable.arrayId == null && old?.value !== variable.value) return old ? `${name} changes from ${old.value} to ${variable.value}.` : `${name} is now ${variable.value}.`;
  }
  return step.executedLine > 0 ? `Line ${step.executedLine} executed. Next: line ${step.nextLine}.` : `Entered ${step.method}. Ready to follow the code.`;
}

export function ArrayStage({ step, before, speed, forward, playing, preview, replayKey = 0 }: { step?: Step; before?: Step; speed: number; forward: boolean; playing: boolean; preview?: Preview; replayKey?: number }) {
  const stage = useRef<HTMLDivElement>(null);
  const animation = useRef<gsap.core.Timeline | undefined>(undefined);
  const previousAnimationStep = useRef<Step | undefined>(undefined);
  useLayoutEffect(() => {
    if (!stage.current || !step || step.index === 0 || !forward) return;
    const root = stage.current;
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const duration = reduced ? 0 : .52;
    const particles: HTMLElement[] = [];
    const context = gsap.context(() => {
      const timeline = gsap.timeline();
      timeline.timeScale(speed);
      animation.current = timeline;
      timeline.fromTo('[data-changed="true"]', { scale: .9, y: 7 }, { scale: 1, y: 0, duration, ease: 'back.out(1.6)' }, 0);
      if (!forward || reduced) return;
      for (const transfer of step.transfers) {
        const id = step.variables[transfer.array]?.arrayId ?? before?.variables[transfer.array]?.arrayId;
        const cell = root.querySelector(`[data-cell="${id}:${transfer.index}"]`);
        const targetId = transfer.targetArray ? step.variables[transfer.targetArray]?.arrayId : undefined;
        const variable = transfer.direction === 'copy' ? root.querySelector(`[data-cell="${targetId}:${transfer.targetIndex}"]`) : root.querySelector(`[data-variable="${CSS.escape(transfer.variable ?? '')}"]`);
        if (!cell || !variable) continue;
        const from = (transfer.direction !== 'write' ? cell : variable).getBoundingClientRect();
        const to = (transfer.direction !== 'write' ? variable : cell).getBoundingClientRect();
        const bounds = root.getBoundingClientRect();
        const particle = document.createElement('div');
        particle.className = 'value-particle';
        particle.textContent = transfer.value;
        particle.setAttribute('aria-hidden', 'true');
        root.append(particle);
        particles.push(particle);
        timeline.fromTo(particle, { left: from.left + from.width / 2 - bounds.left, top: from.top + from.height / 2 - bounds.top, opacity: 1, scale: 1 },
          { left: to.left + to.width / 2 - bounds.left, top: to.top + to.height / 2 - bounds.top, duration: duration * 1.4, ease: 'power2.inOut', onComplete: () => { particle.remove(); } }, 0);
      }
      for (const flow of step.collectionFlows ?? []) {
        const variable = root.querySelector(`[data-variable="${CSS.escape(flow.source)}"]`);
        const entry = root.querySelector(`[data-collection-entry="${flow.collectionId}:${flow.index}"]`);
        if (!variable || !entry) continue;
        const source = flow.direction === 'read' ? entry : variable;
        const target = flow.direction === 'read' ? variable : entry;
        const from = source.getBoundingClientRect(), to = target.getBoundingClientRect(), bounds = root.getBoundingClientRect();
        const particle = document.createElement('div');
        particle.className = 'value-particle';
        particle.textContent = flow.value;
        particle.setAttribute('aria-hidden', 'true');
        root.append(particle);
        particles.push(particle);
        timeline.fromTo(particle, { left: from.left + from.width / 2 - bounds.left, top: from.top + from.height / 2 - bounds.top, opacity: 1, scale: 1 },
          { left: to.left + to.width / 2 - bounds.left, top: to.top + to.height / 2 - bounds.top, duration: duration * 1.4, ease: 'power2.inOut', onComplete: () => particle.remove() }, 0);
      }
    }, root);
    return () => { context.revert(); animation.current = undefined; particles.forEach(p => p.remove()); };
  }, [step, before, forward, replayKey]);
  useEffect(() => { animation.current?.timeScale(speed); }, [speed]);
  // A manual Next starts its own animation; Pause freezes an in-flight autoplay animation.
  useEffect(() => {
    if (playing) animation.current?.resume();
    else if (previousAnimationStep.current === step) animation.current?.pause();
    previousAnimationStep.current = step;
  }, [playing, step]);

  const view = step ?? preview;
  const staticMode = !step && !!preview;
  const arrays = Object.values(view?.arrays ?? {});
  const collections = Object.values(view?.collections ?? {});
  const stack = step?.stack ?? [];
  const scalars = Object.entries(view?.variables ?? {}).filter(([, v]) => v.arrayId == null && v.collectionId == null);
  const label = (key: string) => staticMode ? preview?.declarations[key]?.name ?? key : key;
  return <div ref={stage} className="stage">
    {!view ? <div className="empty-stage"><div className="empty-icon"><Braces size={35}/></div><h2>Give your code a little motion.</h2><p>Write Java to preview declarations.<br/>Debug &amp; Visualize to inspect real execution.</p><div className="empty-hint"><span>01 Write</span><ArrowRight size={13}/><span>02 Debug</span><ArrowRight size={13}/><span>03 Explore</span></div></div> : <>
      <div className="memory-heading"><span><Layers3 size={14}/>{staticMode ? 'ARRAY INITIALIZERS · NOT EXECUTED' : 'RUNTIME MEMORY'}</span><span>{arrays.length} {staticMode ? 'declarations' : arrays.length === 1 ? 'array' : 'arrays'}</span></div>
      <div className="array-space">
        {arrays.length === 0 && <p className="subtle waiting">{staticMode ? 'Array initializers will appear as you type. Unknown values are marked ?.' : 'No array is in scope yet. Step forward to its initialization.'}</p>}
        {arrays.map(array => {
          const keys = Object.entries(view.variables).filter(([, v]) => v.arrayId === array.id).map(([name]) => name);
          const names = keys.map(label);
          return <section className="array-object" key={array.id} aria-label={`Array ${names.join(', ') || array.id}`}>
            <div className="array-label"><strong>{names.join(' · ') || `array #${array.id}`}</strong><span>{array.type}</span><span className="array-length">length <b>{array.length}</b></span></div>
            {staticMode && <p className="subtle preview-scope">{preview?.declarations[keys[0]]?.scope} · declaration on line {preview?.declarations[keys[0]]?.line}</p>}
            <div className="cells-scroll"><div className="cells">
              {array.values.map((value, i) => {
                const changed = !staticMode && before?.arrays[array.id]?.values[i] !== value;
                const transfer = step?.transfers.some(t => (step.variables[t.array]?.arrayId === array.id && t.index === i) || (t.targetArray && step.variables[t.targetArray]?.arrayId === array.id && t.targetIndex === i));
                // Numerical locals are shown as value markers, not asserted to be index variables.
                const pointers = staticMode ? [] : scalars.filter(([, v]) => /^(int|short|byte|long)$/.test(v.type) && v.value === String(i));
                return <div className="cell-column" key={i}><span className="cell-index">{i}</span><div data-cell={`${array.id}:${i}`} data-changed={changed} className={`array-cell ${changed ? 'changed' : ''} ${transfer ? 'accessed' : ''}`} title={`${names[0] ?? 'array'}[${i}] = ${value}`}>{value}</div><div className="pointers">{pointers.map(([name]) => <span key={name}>↑ {name}</span>)}</div></div>;
              })}
              {array.length === 0 && <span className="subtle">Empty array · 0 elements</span>}
            </div></div>
            {array.length > array.values.length && <p className="subtle">Showing the first {array.values.length} of {array.length} elements.</p>}
          </section>;
        })}
      </div>
      <div className="variables-heading"><span>{staticMode ? 'DECLARED VARIABLES' : 'LOCAL VARIABLES'}</span><span>{staticMode ? 'Initializers only · ? = unknown' : 'Current stack frame'}</span></div>
      <div className="variables">
        {scalars.map(([name, variable]) => {
          const changed = !staticMode && before?.variables[name]?.value !== variable.value;
          return <div className={`variable ${changed ? 'changed' : ''}`} data-variable={label(name)} data-changed={changed} key={name} title={staticMode ? `${preview?.declarations[name]?.scope} · ${preview?.declarations[name]?.initializer}` : undefined}><div><span className="var-type">{variable.type}</span><strong>{label(name)}</strong></div><span className="var-value">{variable.value}</span></div>;
        })}
        {scalars.length === 0 && <p className="subtle">Variables appear here as they enter scope.</p>}
      </div>
      {collections.length > 0 && <section className="collections-section" aria-label="Collection contents">
        <div className="variables-heading"><span>COLLECTION CONTENTS</span><span>Observed from the running JVM</span></div>
        <div className="collection-grid">
          {collections.map(collection => {
            const names = Object.entries(view.variables).filter(([, variable]) => variable.collectionId === collection.id).map(([name]) => label(name));
            const previous = staticMode ? undefined : before?.collections?.[collection.id];
            return <article className="collection-object" key={collection.id} aria-label={`${collection.kind} ${names.join(', ') || collection.id}`}>
              <div className="collection-heading"><strong>{names.join(' · ') || `collection #${collection.id}`}</strong><span>{collection.type}</span><span>{collection.size} {collection.size === 1 ? 'item' : 'items'}</span></div>
              {!collection.supported ? <p className="subtle">{collection.note || 'Contents for this collection implementation are not available.'}</p> : collection.entries.length === 0 ? <p className="subtle">Empty collection</p> : <div className={`collection-entries ${collection.kind}`}>
                {collection.entries.map((entry, index) => {
                  const old = previous?.entries ?? [];
                  const changed = collection.kind === 'map'
                    ? old.find(previousEntry => previousEntry.key === entry.key)?.value !== entry.value
                    : old.filter(previousEntry => previousEntry.value === entry.value).length < collection.entries.slice(0, index + 1).filter(current => current.value === entry.value).length;
                  return <div className={`collection-entry ${changed ? 'changed' : ''}`} data-changed={changed} data-collection-entry={`${collection.id}:${index}`} key={`${entry.key ?? 'item'}:${index}`}>
                    {collection.kind === 'list' && <span className="collection-index">{index}</span>}
                    {collection.kind === 'map' ? <><span className="collection-key">{entry.key}</span><span className="collection-arrow">→</span><strong>{entry.value}</strong></> : <strong>{entry.value}</strong>}
                  </div>;
                })}
              </div>}
              {collection.truncated && <p className="subtle">Showing {collection.entries.length} of {collection.size} items.</p>}
            </article>;
          })}
        </div>
      </section>}
      {!staticMode && stack.length > 1 && <section className="call-stack-view" aria-label="Call stack with local variables">
        <div className="variables-heading"><span>CALL STACK · {stack.length} FRAMES</span><span>Each frame keeps its own local values</span></div>
        <div className="call-stack-frames">
          {stack.map((frame, index) => <article className={`call-frame ${index === 0 ? 'active' : ''}`} key={`${frame.method}:${index}`}>
            <div className="call-frame-heading"><strong>{frame.method}</strong><span>{index === 0 ? 'current frame' : `caller ${index}`}</span><span>line {frame.line}</span></div>
            <div className="call-frame-values">{Object.entries(frame.variables).map(([name, variable]) => <span key={name}><small>{variable.type}</small> {name} = {variable.value}</span>)}</div>
          </article>)}
        </div>
      </section>}
      {staticMode && <><div className="variables-heading"><span>DECLARED METHODS</span><span>Not a runtime call stack</span></div><div className="preview-methods">{preview?.methods.map((method, index) => <div className="stack-frame" key={index}><strong>{method.signature}</strong><span>line {method.line}</span></div>)}</div></>}
      <div className="scope-bar"><span className="live-dot"/>{staticMode ? 'Static preview · no Java has executed' : step?.method}<span>{staticMode ? preview?.incomplete ? 'Incomplete syntax supported' : 'Syntax parsed' : `stack depth ${step?.stack.length}`}</span></div>
    </>}
  </div>;
}
