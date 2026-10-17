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
import { DoctorPanel } from './Doctor'
import { memoryAliases, fastbrainStatus, haptic, importPet, installedPets, neuralTtsStatus, setTtsVoice, testVoice as testNaturalVoice, ttsVoices, installPet, petCatalog, selectPet, saveVoiceAliases, voiceAliases } from '../bridge'
import { dynamicColorsOn, reduceEffectsOn, savedStyle, setDynamicColors, setReduceEffects, setStyle, type Style } from '../theme'
import { Row, Section, Segmented, SelectRow, ToggleRow } from './ui'

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

const SPOTS = [
  { id: 'free', label: 'Pinned', sub: 'Drag it; it stays where you drop it' },
  { id: 'wander', label: 'Wander', sub: 'Strolls between spots' },
  { id: 'tl', label: 'Top left', sub: 'Just under the status bar' },
  { id: 'tc', label: 'Top centre', sub: 'Just under the status bar' },
  { id: 'tr', label: 'Top right', sub: 'Just under the status bar' },
  { id: 'ml', label: 'Left side', sub: 'Upper part of the side' },
  { id: 'mr', label: 'Right side', sub: 'Upper part of the side' }
]

export function IslandPage() {
  const [s, set] = useNative()
  if (!hasNativeV2() || !s) return <NoPhone />
  return (
    <>
      <Section footer="An animated pet that sits just under the system island and shows what Hermes is doing, shows voice listening, and takes files you drag onto it. It needs “Display over other apps”.">
        <ToggleRow icon="🫧" tone="gold" title="Floating pet" on={s.island} onChange={v => set('island', v)} />
        {s.island && !s.overlay && <Row icon="⚠️" tone="red" title="Allow display over other apps" sub="Needed for the island" chevron onClick={() => openOverlaySettings()} />}
      </Section>
      {s.island && (
        <>
          <PetGallery initial={s.pet.startsWith('ap-') ? s.pet.slice(3) : ''} />
          <Section title="Position" footer="Always just under the status bar or on the upper part of a side, never the lower part of the screen. Wander strolls between spots every half minute or so when it is just hanging around. Drag the pet anywhere (also over the area an app's drag-and-drop covers): it stays exactly where you drop it. Turn on “Snap to spots” to make it jump to the nearest spot instead.">
            <div className="pet-grid">
              {SPOTS.map(p => (
                <button key={p.id} className={`pet-card${s.pet_pos === p.id ? ' on' : ''}`} onClick={() => { haptic(); set('pet_pos', p.id) }}>
                  <span className="pet-name">{p.label}</span>
                  <span className="pet-sub">{p.sub}</span>
                </button>
              ))}
            </div>
          </Section>
          <Section title="When to show it" footer="On demand: hidden while idle. It appears when Hermes works, an approval waits, you drag something, or you trigger voice. A tiny invisible strip stays where the pet last sat so a drag has somewhere to land, and it blocks touches in that spot.">
            <Segmented
              options={[{ value: 'ondemand', label: 'On demand' }, { value: 'always', label: 'Always' }]}
              value={s.island_mode}
              onChange={v => set('island_mode', v)}
            />
          </Section>
          <Section footer="Approvals are asked once: on the floating pet (text and buttons), and in a notification only when the pet can't show them (it is off, or the phone is locked). The system island can't show the animated pet, so it is off by default; turn it on only if you want its status chip as well.">
            <ToggleRow icon="🛡" tone="green" title="Approval buttons on the floating pet" on={s.island_approvals} onChange={v => set('island_approvals', v)} />
            <ToggleRow icon="📌" tone="gray" title="Snap to spots after dragging" on={s.pet_snap} onChange={v => set('pet_snap', v)} />
            <ToggleRow icon="🏝" tone="gray" title="Also use the system island (static icon)" on={s.system_chip} onChange={v => set('system_chip', v)} />
          </Section>
          <Section title="Drag and drop" footer="Drag an image, file, text or link from another app (gallery, browser, files) onto the floating pet. Android doesn't let apps receive drops on the system island itself, so the drop target is the pet, which appears under the system island while you drag. Type what Hermes should do and it runs in the background. The other app must support dragging out; if not, use Share → Hermes.">
            <Row icon="🖼" tone="blue" title="Drop to ask" sub="No setting needed" />
          </Section>
        </>
      )}
    </>
  )
}

// ── Voice commands ──────────────────────────────────────────

export /** The natural voice (Piper, on the phone next to FastBrain): used for read-aloud and for spoken replies to the island. */
function NaturalVoice() {
  const [s, set] = useNative()
  const [voices, setVoices] = useState<string[]>([])
  const [status, setStatus] = useState('')
  useEffect(() => {
    setTimeout(() => {
      setVoices(ttsVoices())
      setStatus(neuralTtsStatus())
    }, 50)
  }, [])
  if (!hasNativeV2() || !s) return null
  const pretty = (v: string) => v.replace(/^([a-z]{2})_([A-Za-z]+)-/, '$1-$2 · ').replace(/-(x_low|low|medium|high)$/, ' ($1)').replace(/_/g, ' ')
  return (
    <Section title="Natural voice" footer={`${status || 'Checking…'} Piper is an open-source voice (20–65 MB per voice) that runs on the phone; it breathes between phrases and slows down for bad news. If its server isn't running the phone's own voice is used. Set it up once: bash ~/FastBrain/scripts/setup-piper.sh`}>
      <ToggleRow icon="🗣" tone="blue" title="Use the natural voice" sub="Read-aloud and spoken replies" on={s.voice_neural} onChange={v => set('voice_neural', v)} />
      {voices.length > 0 && (
        <SelectRow icon="🎙" tone="purple" title="Natural voice" options={voices.map(v => ({ value: v, label: pretty(v) }))} value={s.tts_voice} placeholder="Choose" onChange={v => { setTtsVoice(v); set('voice_neural', true) }} />
      )}
      <Row icon="▶" tone="teal" title="Test natural voice" onClick={() => { testNaturalVoice(); setTimeout(() => setStatus(neuralTtsStatus()), 600) }} />
    </Section>
  )
}

/** The pet: only AgentPets pets (agentpets.dev). The first page of the catalogue shows at once; search narrows it. */
function PetGallery({ initial }: { initial: string }) {
  const [current, setCurrent] = useState(initial)
  const [q, setQ] = useState('')
  const [list, setList] = useState<{ slug: string; name: string; url: string }[]>([])
  const [more, setMore] = useState(false)
  const [mine, setMine] = useState<string[]>(() => installedPets())
  const [busy, setBusy] = useState('')
  const load = (query: string, offset: number) => {
    setBusy('list')
    setTimeout(() => {
      const r = petCatalog(query, offset)
      setList(old => (offset ? [...old, ...r] : r))
      setMore(r.length >= 24)
      setBusy('')
      if (!r.length && !offset) toast('No pets found (or no internet)', 'error')
    }, 30)
  }
  useEffect(() => load('', 0), [])
  const get = (p: { slug: string; url: string }) => {
    setBusy(p.slug)
    setTimeout(() => {
      const err = installPet(p.slug, p.url)
      setBusy('')
      if (err) toast(`Couldn't install: ${err}`, 'error')
      else {
        setMine(installedPets())
        setCurrent(p.slug) // installPet already made it the pet
      }
    }, 30)
  }
  const sheet = (url: string) => ({ backgroundImage: `url(${url})`, backgroundSize: '800% 900%', backgroundPosition: '0 0', imageRendering: 'pixelated' as const })
  return (
    <>
      {mine.length > 0 && (
        <Section title="Your pets">
          <div className="pet-grid">
            {mine.map(sl => (
              <button key={sl} className={`pet-card${current === sl ? ' on' : ''}`} onClick={() => { haptic(); selectPet(sl); setCurrent(sl) }}>
                <span className="pet-name">{sl}</span>
                <span className="pet-sub">{current === sl ? 'In use' : 'Tap to use'}</span>
              </button>
            ))}
          </div>
        </Section>
      )}
      <Section title="Choose a pet" footer="Animated pets from agentpets.dev, made by the community. Tap one to download it (a few hundred KB) and use it. Nothing is bundled in the app.">
        <div style={{ display: 'flex', gap: 8, padding: '0 4px 8px' }}>
          <input className="input" value={q} placeholder="Search by name" onChange={e => setQ(e.target.value)} onKeyDown={e => e.key === 'Enter' && load(q, 0)} style={{ flex: 1 }} />
          <button className="pet-card" onClick={() => load(q, 0)} disabled={busy === 'list'}>Search</button>
        </div>
        <div className="pet-grid">
          {list.map(p => (
            <button key={p.slug} className="pet-card" onClick={() => get(p)} disabled={busy === p.slug}>
              <span className="pet-thumb" style={sheet(p.url)} />
              <span className="pet-name">{p.name}</span>
              <span className="pet-sub">{busy === p.slug ? 'Downloading…' : mine.includes(p.slug) ? 'Installed' : 'Tap to get'}</span>
            </button>
          ))}
        </div>
        {busy === 'list' && <div style={{ padding: 12, textAlign: 'center' }}>Loading…</div>}
        <label className="pet-card" style={{ display: 'block', margin: '8px 4px', cursor: 'pointer' }}>
          <span className="pet-name">Import a pet file</span>
          <span className="pet-sub">If downloading fails: save the pet's spritesheet from agentpets.dev in your browser, then pick it here</span>
          <input
            type="file"
            accept="image/*"
            style={{ display: 'none' }}
            onChange={e => {
              const f = e.target.files?.[0]
              if (!f) return
              const r = new FileReader()
              r.onload = () => {
                const err = importPet(f.name, String(r.result).split(',')[1] || '')
                if (err) toast(`Couldn't import: ${err}`, 'error')
                else {
                  setMine(installedPets())
                  toast('Pet imported', 'info')
                }
              }
              r.readAsDataURL(f)
            }}
          />
        </label>
        {more && busy !== 'list' && <Row title="Show more" chevron onClick={() => load(q, list.length)} />}
      </Section>
    </>
  )
}

function AliasEditor() {
  const [text, setText] = useState(() => voiceAliases())
  return (
    <Section title="Your names for people" footer="You never have to type these: they are read from memory (FastBrain and Hindsight) and learned the first time you answer “Did you mean…?” or “Which one…?”. Anything typed here wins over memory.">
      {memoryAliases() && <details style={{ padding: '4px 16px' }}><summary className="dim small">From memory ({memoryAliases().split('\n').filter(Boolean).length})</summary><pre className="dim small" style={{ whiteSpace: 'pre-wrap' }}>{memoryAliases()}</pre></details>}
      <textarea
        className="input"
        rows={5}
        value={text}
        placeholder={'papa = Ramesh Sharma\nboss = Dr Mehta'}
        onChange={e => setText(e.target.value)}
        onBlur={() => {
          const err = saveVoiceAliases(text)
          if (err) toast(`Not saved: ${err}`, 'error')
        }}
        style={{ width: '100%', fontFamily: 'monospace' }}
      />
    </Section>
  )
}


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
          <Section title="Triggers" footer="Corner swipe: choose Hermes as the phone’s digital assistant, then swipe in from a corner; the floating pet listens (it also works from the lock screen if your phone allows assistants there). Quick Settings: add the “Hermes listen” tile. Apps can’t read touches or buttons while the screen is off or locked, so those are the two triggers.">
            <Row icon="↗️" tone="gold" title="Corner swipe assistant" value={s.assistant ? 'On' : 'Off'} sub="Choose Hermes as the digital assistant" chevron onClick={() => (window.HermesAndroid as unknown as { openAssistantSettings?: () => void } | undefined)?.openAssistantSettings?.()} />
          </Section>
          <Section title="While the phone is locked" footer="Calls place at once when the name matches exactly one contact. Messages are only sent when you say “send a message to NAME saying …”. Nothing private is read aloud while locked.">
            <ToggleRow icon="📞" tone="green" title="Allow calls" on={s.voice_calls_locked} onChange={v => set('voice_calls_locked', v)} />
            <ToggleRow icon="💬" tone="blue" title="Allow sending messages" on={s.voice_msgs_locked} onChange={v => set('voice_msgs_locked', v)} />
          </Section>
          <Section title="Smarter voice" footer="Commands go to FastBrain on the phone for music, apps, aliases and more; calls and messages match your contacts here first, so they start at once and understand misheard names. Anything else is asked to Hermes. Say “let's talk” to keep a conversation going (it listens again after each answer until you say “stop”). Needs scripts/fastbrain-router-tcp.py running in Termux for FastBrain.">
            <ToggleRow icon="🧠" tone="teal" title="Use FastBrain" on={s.voice_fastbrain} onChange={v => set('voice_fastbrain', v)} />
            <ToggleRow icon="💬" tone="blue" title="Ask Hermes what FastBrain can't do" on={s.voice_hermes} onChange={v => set('voice_hermes', v)} />
            <ToggleRow icon="🔁" tone="gold" title="Keep listening after Hermes answers" on={s.voice_follow} onChange={v => set('voice_follow', v)} />
            <Row icon="🩺" tone="teal" title="Test FastBrain connection" onClick={() => toast(fastbrainStatus(), 'info')} />
          </Section>
          <Section title="Voice and brain doctor" footer="Checks FastBrain, the MiniLM brain, Piper with its four voices and memory, and installs or starts what is missing. It also runs by itself when the app starts.">
            <DoctorPanel />
          </Section>
          <AliasEditor />
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
  const [st] = useState<ApprovalState | null>(() => approvalState())
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
