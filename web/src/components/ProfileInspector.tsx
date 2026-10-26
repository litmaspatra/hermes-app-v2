// Profile inspector: SOUL.md, memory and skills of one profile in tabs, opened from a bot's page (Bots.tsx).
// Layout idea from Hermes Relay's Profile Inspector (MIT, Axiom Labs); no code copied. Uses only calls this app
// already makes, each scoped with `profile=` (skills, plugin memory) or a path (SOUL.md).
import { useState } from 'react'
import { api } from '../api'
import { errText } from '../gateway'
import { haptic } from '../bridge'
import { toast, useStore } from '../store'
import { ScreenShell, Status, useLoader } from './Screens'

type Tab = 'soul' | 'memory' | 'skills'
interface Snap {
  entries: string[]
  used: number
  limit: number
}
interface Skill {
  name: string
  description?: string
  category?: string
  enabled?: boolean
}
const enc = encodeURIComponent

export function ProfileInspectorScreen() {
  const name = useStore(s => s.screenProfile) || 'default'
  const [tab, setTab] = useState<Tab>('soul')
  return (
    <ScreenShell title={name === 'default' ? 'Profile' : name}>
      <div className="segmented">
        {(['soul', 'memory', 'skills'] as Tab[]).map(t => (
          <button key={t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>
            {t === 'soul' ? 'SOUL' : t === 'memory' ? 'Memory' : 'Skills'}
          </button>
        ))}
      </div>
      {tab === 'soul' && <SoulTab name={name} />}
      {tab === 'memory' && <MemoryTab name={name} />}
      {tab === 'skills' && <SkillsTab name={name} />}
    </ScreenShell>
  )
}

function SoulTab({ name }: { name: string }) {
  const { data, error, loading, setData } = useLoader(
    () => api<{ content?: string; exists?: boolean }>('GET', `/api/profiles/${enc(name)}/soul`, undefined, { profile: false }),
    [name]
  )
  const [draft, setDraft] = useState<string | null>(null)
  const text = draft ?? data?.content ?? ''
  const dirty = draft != null && draft !== (data?.content ?? '')
  const save = async () => {
    try {
      await api('PUT', `/api/profiles/${enc(name)}/soul`, { content: text }, { profile: false })
      setData({ content: text, exists: true })
      setDraft(null)
      toast('Saved — new chats use it')
    } catch (e) {
      toast(errText(e), 'error')
    }
  }
  return (
    <div className="pad">
      <Status loading={loading} error={error} empty={false} emptyText="" />
      {!loading && !error && data?.exists === false && <div className="dim small">No SOUL.md yet; saving creates it.</div>}
      {!loading && !error && (
        <>
          <textarea className="sheet-input mono soul-editor" value={text} onChange={e => setDraft(e.target.value)} placeholder="Who this bot is, how it talks, what it focuses on…" />
          <div className="sheet-actions">
            <button className="btn" disabled={!dirty} onClick={() => setDraft(null)}>
              Revert
            </button>
            <button className="btn primary" disabled={!dirty} onClick={() => void save()}>
              Save
            </button>
          </div>
        </>
      )}
    </div>
  )
}

function MemoryTab({ name }: { name: string }) {
  const { data, error, loading } = useLoader(
    () => api<{ memory: Snap; user: Snap }>('GET', `/api/plugins/hermes-mobile/memory?profile=${enc(name)}`, undefined, { profile: false }),
    [name]
  )
  const [which, setWhich] = useState<'memory' | 'user'>('memory')
  const snap = data?.[which]
  const pct = snap ? Math.min(100, Math.round((snap.used / Math.max(1, snap.limit)) * 100)) : 0
  return (
    <div className="pad">
      <div className="segmented">
        <button className={which === 'memory' ? 'on' : ''} onClick={() => setWhich('memory')}>
          Memory
        </button>
        <button className={which === 'user' ? 'on' : ''} onClick={() => setWhich('user')}>
          About you
        </button>
      </div>
      <Status loading={loading} error={error} empty={!loading && !error && !snap?.entries.length} emptyText="Nothing saved yet" />
      {snap && (
        <div className="usage">
          <div className="usage-bar">
            <span style={{ width: `${pct}%` }} className={pct > 92 ? 'full' : ''} />
          </div>
          <span className="dim small">
            {snap.used.toLocaleString()} / {snap.limit.toLocaleString()} characters · {snap.entries.length} entries
          </span>
        </div>
      )}
      {snap?.entries.map((e, i) => (
        <div key={i} className="mem-entry">
          {e}
        </div>
      ))}
      <div className="dim small pad">Read-only here. Switch to this profile to edit its memory.</div>
    </div>
  )
}

function SkillsTab({ name }: { name: string }) {
  const { data, error, loading, setData } = useLoader(() => api<Skill[]>('GET', `/api/skills?profile=${enc(name)}`, undefined, { profile: false }), [name])
  const [q, setQ] = useState('')
  const rows = (data || []).filter(s => !q.trim() || `${s.name} ${s.description || ''}`.toLowerCase().includes(q.trim().toLowerCase()))
  const toggle = async (s: Skill) => {
    haptic()
    const enabled = !s.enabled
    const set = (v: boolean) => setData(d => (d || []).map(x => (x.name === s.name ? { ...x, enabled: v } : x)))
    set(enabled)
    try {
      await api('PUT', '/api/skills/toggle', { name: s.name, enabled, profile: name }, { profile: false })
    } catch (e) {
      set(!enabled)
      toast(errText(e), 'error')
    }
  }
  return (
    <div>
      <div className="search-row pad">
        <input value={q} onChange={e => setQ(e.target.value)} placeholder="Search skills" />
      </div>
      <Status loading={loading} error={error} empty={!loading && !error && !rows.length} emptyText="No skills" />
      {rows.map(s => (
        <div key={s.name} className="list-row">
          <div className="list-main">
            <span className="list-title">{s.name}</span>
            <span className="list-sub">{s.description}</span>
          </div>
          <button className={`switch${s.enabled ? ' on' : ''}`} aria-label="Enabled" onClick={() => void toggle(s)}>
            <span />
          </button>
        </div>
      ))}
    </div>
  )
}
