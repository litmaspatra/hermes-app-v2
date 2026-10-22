import { useEffect, useState } from 'react'
import { doctorFix, doctorRefresh, doctorState, haptic, isNative, type Doctor } from '../bridge'
import { Spinner } from './Spinner'

// The doctor lives on the phone's Linux side (services.py in the Hermes plugin, inside Hermes' own process). It checks FastBrain,
// the MiniLM brain, Piper and its voices, Hindsight and the contact-alias memory, and installs or starts what is missing.
// This is only its window: a ✓ or ✕ per piece and one button.

const AUTO_KEY = 'hm.doctorAuto'
const AUTO_EVERY_MS = 6 * 3600_000

const fixable = (d: Doctor | null) => !!d?.checks?.some(c => !c.ok && c.fix)

/**
 * Once per app start (at most every 6 hours): ask the doctor, and if something it can install or start is missing, let it do
 * it. That is the "initial setup": FastBrain, MiniLM, Piper and four voices arrive by themselves, nobody types a command.
 */
export function autoDoctor(): void {
  if (!isNative) return
  let last = 0
  try {
    last = Number(localStorage.getItem(AUTO_KEY) || 0)
  } catch {
    /* private mode */
  }
  doctorRefresh()
  let tries = 0
  const t = setInterval(() => {
    const d = doctorState()
    if (d && !d.down) {
      clearInterval(t)
      if (fixable(d) && !d.task?.running && Date.now() - last > AUTO_EVERY_MS) {
        try {
          localStorage.setItem(AUTO_KEY, String(Date.now()))
        } catch {
          /* private mode */
        }
        doctorFix()
      }
    } else if (++tries > 40) {
      clearInterval(t) // the companion never answered (Hermes isn't installed yet): the Setup check says why
    } else if (tries % 5 === 0) {
      doctorRefresh()
    }
  }, 2000)
}

export function DoctorPanel() {
  const [d, setD] = useState<Doctor | null>(null)
  useEffect(() => {
    if (!isNative) return
    doctorRefresh()
    let n = 0
    const t = setInterval(() => {
      n++
      const s = doctorState()
      setD(s)
      if ((s?.task?.running && n % 3 === 0) || n % 8 === 0) doctorRefresh()
    }, 1500)
    return () => clearInterval(t)
  }, [])
  if (!isNative) return null
  if (!d) {
    return (
      <div className="dim small" style={{ padding: '8px 16px' }}>
        <Spinner small /> Checking the voice and brain…
      </div>
    )
  }
  if (d.down) {
    return (
      <div className="setup-help" style={{ padding: '8px 16px' }}>
        Hermes' helper isn't answering yet. {d.why}
      </div>
    )
  }
  const running = !!d.task?.running
  const bad = (d.checks ?? []).filter(c => !c.ok)
  return (
    <div style={{ padding: '4px 0' }}>
      <ul className="setup-list">
        {(d.checks ?? []).map(c => (
          <li key={c.id} className={`setup-item ${c.ok ? 'ok' : 'bad'}`}>
            <span className={`setup-mark ${c.ok ? 'ok' : 'bad'}`}>{c.ok ? '✓' : '✕'}</span>
            <div className="setup-body">
              <div className="setup-title">{c.title}</div>
              {!c.ok && c.detail && <div className="setup-help">{c.detail}</div>}
            </div>
          </li>
        ))}
      </ul>
      {running && (
        <div className="dim small" style={{ padding: '8px 16px' }}>
          <Spinner small /> {d.task?.step || 'Working…'} (downloads can take several minutes; you can leave this page)
        </div>
      )}
      {!running && d.task?.done && d.task.ok === false && <div className="setup-help" style={{ padding: '8px 16px' }}>{d.task.step}</div>}
      <div className="setup-actions" style={{ padding: '8px 16px' }}>
        <button
          className="btn primary"
          disabled={running || !fixable(d)}
          onClick={() => {
            haptic()
            doctorFix()
            setTimeout(doctorRefresh, 800)
          }}
        >
          {running ? 'Fixing…' : bad.some(c => c.fix) ? (bad.some(c => c.id === 'kokoro_files') ? 'Fix everything (downloads about 120 MB)' : 'Fix everything') : 'Nothing to fix'}
        </button>
        <button className="btn" onClick={() => { haptic(); doctorRefresh() }}>Check again</button>
      </div>
    </div>
  )
}
