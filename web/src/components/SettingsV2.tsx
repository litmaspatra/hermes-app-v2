// Settings pages added in v2: the look of the app, the floating island, voice commands, and read-only auto-approve
// (allowlist editor, "Test a command", History). They talk to the phone shell through the bridge (see bridge.ts);
// in a plain browser they say there is no phone app to configure.
import { useEffect, useState } from 'react'
import {
  approvalState,
  clearHistory,
  hasNativeV2,
  loadHistory,
  nativeSettings,
  openOverlaySettings,
  requestVoicePermissions,
  saveAllowlist,
  setNative,
  testCommand,
  type AllowRule,
  type ApprovalState,
  type CommandTest,
  type HistoryEntry,
  type NativeSettings
} from '../bridge'
import { confirmDialog } from '../dialog'
import { toast } from '../store'
import { haptic } from '../bridge'
import { dynamicColorsOn, reduceEffectsOn, savedStyle, setDynamicColors, setReduceEffects, setStyle, type Style } from '../theme'
import { Row, Section, Segmented, ToggleRow } from './ui'

function NoPhone() {
  return (
    <Section footer="These options live in the Hermes phone app. Open this page there.">
      <Row title="Not available in a browser" />
    </Section>
  )
}

/** Reads the shell's settings once and keeps them in step with what the user changes. */
function useNative(): [NativeSettings | null, (name: keyof NativeSettings, value: string | number | boolean) => void] {
  const [s, setS] = useState<NativeSettings | null>(() => nativeSettings())
  useEffect(() => {
    // "Display over other apps" is granted on a system page: re-read when the user comes back.
    const again = () => {
      if (document.visibilityState === 'visible') setS(nativeSettings())
    }
    document.addEventListener('visibilitychange', again)
    return () => document.removeEventListener('visibilitychange', again)
  }, [])
  const set = (name: keyof NativeSettings, value: string | number | boolean) => {
    setNative(name, value)
    setS(prev => (prev ? { ...prev, [name]: value } : prev))
    window.setTimeout(() => setS(nativeSettings()), 400) // the shell may have refused or opened a permission page
  }
  return [s, set]
}

// ── Appearance: style ───────────────────────────────────────

const STYLES: Array<{ id: Style; label: string; sub: string }> = [
  { id: 'material', label: 'Material 3', sub: 'Default. Light and dark.' },
  { id: 'neo', label: 'Neo-brutalism', sub: 'Bold, flat, light only.' },
  { id: 'glass', label: 'Glass', sub: 'Frosted, light only.' }
]

export function StyleSection() {
  const [style, setStyleState] = useState<Style>(savedStyle)
  const [dyn, setDyn] = useState(dynamicColorsOn)
  const [reduce, setReduce] = useState(reduceEffectsOn)
  return (
    <>
      <Section title="Style" footer="Only Material 3 has a dark mode. Neo-brutalism and Glass are always light.">
        <div className="style-grid">
          {STYLES.map(s => (
            <button
              key={s.id}
              className={`style-card${style === s.id ? ' on' : ''}`}
              onClick={() => {
                haptic()
                setStyleState(s.id)
                setStyle(s.id)
              }}
            >
              <div className={`style-prev ${s.id}`}>
                <i className="a" />
                <i className="b" />
                <i className="c" />
              </div>
              <div className="style-name">
                {s.label}
                {style === s.id && <span className="tick">✓</span>}
              </div>
            </button>
          ))}
        </div>
      </Section>
      {style === 'material' && (
        <Section footer="Tints the accent colour from your wallpaper (Android 12 and newer).">
          <ToggleRow icon="🎨" tone="purple" title="Material You colours" on={dyn} onChange={v => { setDyn(v); setDynamicColors(v) }} />
        </Section>
      )}
      {style === 'glass' && (
        <Section footer="Turns off the blur and makes the panels almost opaque. Use it if the app feels slow.">
          <ToggleRow icon="✨" tone="gray" title="Reduce effects" on={reduce} onChange={v => { setReduce(v); setReduceEffects(v) }} />
        </Section>
      )}
    </>
  )
}

// ── Island ──────────────────────────────────────────────────

const PETS = [
  { id: 'cat', label: 'Cat', emoji: '🐱' },
  { id: 'dog', label: 'Dog', emoji: '🐶' },
  { id: 'fox', label: 'Fox', emoji: '🦊' },
  { id: 'panda', label: 'Panda', emoji: '🐼' },
  { id: 'bunny', label: 'Bunny', emoji: '🐰' },
  { id: 'robot', label: 'Robot', emoji: '🤖' }
]

export function IslandPage() {
  const [s, set] = useNative()
  if (!hasNativeV2() || !s) return <NoPhone />
  return (
    <>
      <Section footer="A small cat that shows what Hermes is doing, keeps approvals on screen until you answer them, and takes files you drag onto it. It needs “Display over other apps”.">
        <ToggleRow icon="🐱" tone="gold" title="Floating island" on={s.island} onChange={v => set('island', v)} />
        {s.island && !s.overlay && <Row icon="⚠️" tone="red" title="Allow display over other apps" sub="Needed for the island" chevron onClick={() => openOverlaySettings()} />}
      </Section>
      {s.island && (
        <>
          <Section title="Pet" footer="Your pet reacts to what Hermes is doing: sleeping, thinking, working, asking, happy, worried. It looks the same in every theme.">
            <div className="pet-grid">
              {PETS.map(p => (
                <button key={p.id} className={`pet-card${s.pet === p.id ? ' on' : ''}`} onClick={() => { haptic(); set('pet', p.id) }}>
                  <span className="pet-emoji">{p.emoji}</span>
                  <span className="pet-name">{p.label}</span>
                </button>
              ))}
            </div>
          </Section>
          <Section title="When to show it" footer="On demand: hidden while idle. It appears when Hermes works, an approval waits, you drag something, or you trigger voice. A tiny invisible strip at the top centre stays so a drag has somewhere to land, and it blocks touches in that spot.">
            <Segmented
              options={[{ value: 'ondemand', label: 'On demand' }, { value: 'always', label: 'Always' }]}
              value={s.island_mode}
              onChange={v => set('island_mode', v)}
            />
          </Section>
          <Section footer="The system's own island (the Live Update chip) would sit on top of this one, so it is turned off while the floating island is on. Turn this on to have both; the floating one then moves below the status bar.">
            <ToggleRow icon="🏝" tone="gray" title="Also show in the system island" on={s.system_chip} onChange={v => set('system_chip', v)} />
          </Section>
          <Section title="Drag and drop" footer="Drag an image, file, text or link from another app (gallery, browser, files) onto the island, type what Hermes should do, and it runs in the background. The other app must support dragging out; if not, use Share → Hermes.">
            <Row icon="🖼" tone="blue" title="Drop to ask" sub="No setting needed" />
          </Section>
        </>
      )}
    </>
  )
}

// ── Voice commands ──────────────────────────────────────────

export function VoiceCommandsPage() {
  const [s, set] = useNative()
  if (!hasNativeV2() || !s) return <NoPhone />
  return (
    <>
      <Section footer="Say a short command; Hermes listens only after you trigger it, then stops. Camera, reading messages or notifications, and Hermes agent actions never run by voice.">
        <ToggleRow icon="🎙" tone="teal" title="Voice commands" on={s.voice_enabled} onChange={v => set('voice_enabled', v)} />
      </Section>
      {s.voice_enabled && (
        <>
          <Section title="Triggers" footer="Corner swipe: choose Hermes as the phone’s digital assistant, then swipe in from a corner; a small panel listens without opening the app. Quick Settings: add the “Hermes listen” tile. Lock screen: tap and hold near the top centre of the lock screen (it only works there, so typing never triggers it, and it needs the island permission). With the screen fully off, no trigger is possible.">
            <Row icon="↗️" tone="gold" title="Corner swipe assistant" value={s.assistant ? 'On' : 'Off'} sub="Opens a small listening panel" chevron onClick={() => (window.HermesAndroid as unknown as { openAssistantSettings?: () => void } | undefined)?.openAssistantSettings?.()} />
            <ToggleRow icon="👆" tone="purple" title="Hold on the lock screen" on={s.hold_listen} onChange={v => set('hold_listen', v)} />
            {s.hold_listen && (
              <div className="pad">
                <Segmented
                  options={[{ value: 400, label: 'Quick' }, { value: 600, label: 'Normal' }, { value: 900, label: 'Long' }]}
                  value={s.hold_ms <= 500 ? 400 : s.hold_ms <= 750 ? 600 : 900}
                  onChange={v => set('hold_ms', v)}
                />
              </div>
            )}
          </Section>
          <Section title="While the phone is locked" footer="Calls place at once when the name matches exactly one contact. Messages are only sent when you say “send a message to NAME saying …”. Nothing private is read aloud while locked.">
            <ToggleRow icon="📞" tone="green" title="Allow calls" on={s.voice_calls_locked} onChange={v => set('voice_calls_locked', v)} />
            <ToggleRow icon="💬" tone="blue" title="Allow sending messages" on={s.voice_msgs_locked} onChange={v => set('voice_msgs_locked', v)} />
          </Section>
          <Section title="Language">
            <Segmented options={[{ value: 'en-IN', label: 'English (India)' }, { value: 'hi-IN', label: 'हिन्दी' }]} value={s.voice_lang} onChange={v => set('voice_lang', v)} />
          </Section>
          <Section footer="Calls, contacts, messages and the microphone are asked for here, or the first time a command needs them.">
            <Row icon="🔐" tone="gray" title="Allow permissions now" chevron onClick={() => requestVoicePermissions()} />
          </Section>
          <Section title="What you can say" footer="Call NAME · Send a message to NAME saying TEXT · Play / pause / next / previous · Play SONG · Timer 5 minutes · Alarm 6:30 am · Flashlight on · Volume up · What time is it · Navigate to PLACE. Hinglish works for the simple ones (“Rahul ko call karo”).">
            <Row title="Fixed list for safety" />
          </Section>
        </>
      )}
    </>
  )
}

// ── Approvals: read-only auto-approve, allowlist, history ───────

const when = (t: number) => new Date(t).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
const split = (v: string) => v.split(/[\s,]+/).map(x => x.trim()).filter(Boolean)

export function ApprovalsPage() {
  const [s, set] = useNative()
  const [st, setSt] = useState<ApprovalState | null>(() => approvalState())
  const [rules, setRules] = useState<AllowRule[]>(() => st?.rules ?? [])
  const [prog, setProg] = useState('')
  const [subs, setSubs] = useState('')
  const [cmd, setCmd] = useState('')
  const [res, setRes] = useState<CommandTest | null>(null)
  const [hist, setHist] = useState<HistoryEntry[]>(loadHistory)
  if (!hasNativeV2() || !s || !st) return <NoPhone />

  const commit = (next: AllowRule[]) => {
    const err = saveAllowlist(next)
    if (err) return toast(`Not saved: ${err}`, 'error')
    setRules(next)
    setHist(loadHistory())
  }
  const add = async () => {
    const p = prog.trim()
    if (!/^[A-Za-z0-9._+-]{1,40}$/.test(p)) return toast('Enter just the program name, for example: tree', 'warn')
    if (st.writers.includes(p)) {
      const ok = await confirmDialog({ title: `Allow “${p}” without asking?`, message: `${p} can change or delete things. Hermes could run it without asking you, whatever it does. Only add it if you are sure.`, confirmLabel: 'Allow it', danger: true })
      if (!ok) return
    }
    commit([...rules.filter(r => r.program !== p), { program: p, subs: split(subs), deny: [], only: [], notes: '' }])
    setProg('')
    setSubs('')
  }
  return (
    <>
      <Section footer="Off by default. When on, a command that only reads (ls, cat, git status…) and is on the list below is approved once, without asking, and logged in History. Anything else, or anything touching keys, tokens or .env files, still asks you. Pipes, redirects, ; && and $() always ask.">
        <ToggleRow icon="🛡" tone="green" title="Auto-approve read-only commands" on={s.auto_readonly} onChange={v => set('auto_readonly', v)} />
      </Section>
      <Section title="Allowlist" footer="Each program may only be used the way its note says. Entries with sub-commands (git: status, log…) allow only those. Dangerous flags stay blocked.">
        <div className="rule-list">
          {rules.map(r => (
            <div className="rule-row" key={r.program}>
              <div className="rule-main">
                <div className="rule-prog">{r.program}{r.subs.length ? ` ${r.subs.join(' | ')}` : ''}</div>
                {r.notes && <div className="rule-note">{r.notes}</div>}
              </div>
              <button className="rule-del" aria-label={`Remove ${r.program}`} onClick={() => { haptic(); commit(rules.filter(x => x.program !== r.program)) }}>✕</button>
            </div>
          ))}
        </div>
        <div className="rule-add">
          <input value={prog} onChange={e => setProg(e.target.value)} placeholder="program, e.g. tree" autoCapitalize="none" autoCorrect="off" spellCheck={false} />
          <input value={subs} onChange={e => setSubs(e.target.value)} placeholder="sub-commands (optional)" autoCapitalize="none" autoCorrect="off" spellCheck={false} />
          <button className="btn" onClick={() => void add()}>Add</button>
        </div>
        <Row title="Reset to defaults" danger onClick={() => { haptic(); commit(st.defaults) }} />
      </Section>
      <Section title="Test a command" footer="Shows what would happen with the list above, without running anything.">
        <div className="test-box">
          <input value={cmd} onChange={e => { setCmd(e.target.value); setRes(null) }} placeholder="e.g. git log --oneline" autoCapitalize="none" autoCorrect="off" spellCheck={false} />
          <button className="btn" onClick={() => setRes(testCommand(cmd, rules))}>Test</button>
        </div>
        {res && (
          <div className={`test-out ${res.allow ? 'allow' : 'ask'}`}>
            {res.allow ? '✓ Would be approved automatically: ' : '? Would ask you: '}
            {res.reason}
            {res.risk ? ` · ⚠ ${res.risk}` : ''}
          </div>
        )}
      </Section>
      <Section title="History" footer="Approvals, timeouts, voice commands and allowlist changes. Stored only on this phone (last 500).">
        {hist.length === 0 && <Row title="Nothing yet" />}
        {hist.slice(0, 100).map((h, i) => (
          <div className="hist-row" key={`${h.t}-${i}`}>
            <div className="hist-top"><span>{h.source}</span><span>{when(h.t)}</span></div>
            {h.text && <div className="hist-text">{h.text}</div>}
            <div className="hist-dec">{h.decision}</div>
          </div>
        ))}
        {hist.length > 0 && <Row title="Clear history" danger onClick={() => { clearHistory(); setHist([]) }} />}
      </Section>
    </>
  )
}
