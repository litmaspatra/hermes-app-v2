// Slash-command + skill catalog for the composer's autocomplete.
// Instant by design: served from memory / localStorage (per profile), refreshed in the background
// when the app connects. Names come from the fast commands.catalog RPC; skill descriptions come
// from the slower /api/skills scan and are merged in whenever they arrive.
import { useSyncExternalStore } from 'react'
import type { CommandsCatalogResult } from '@hermes/shared/gateway-contract.generated'
import { api } from './api'
import { rpc } from './gateway'
import { getState } from './store'
// Hermes Desktop's own classification: null = works in a GUI; otherwise why it doesn't
// (terminal / messaging / advanced / settings / hidden / composer-voice). Copied from the phone's
// Hermes (apps/desktop/src/lib/desktop-slash-registry.json).
import desktopRegistry from '../vendor/desktop-slash-registry.json'

const REGISTRY = desktopRegistry as Record<string, string | null>
/** Commands this app already covers with its own buttons/screens (or that make no sense on a phone). */
const APP_COVERED = new Set([
  'new', 'reset', 'clear', 'start', 'resume', 'sessions', 'model', 'reasoning', 'stop', 'profile', 'title',
  'memory', 'exit', 'quit', 'skin', 'pet', 'hatch', 'generate-pet', 'palette', 'timestamps', 'ts', 'paste',
  'image', 'compose', 'voice'
])
const guiHidden = new Set<string>()

export interface CommandEntry {
  name: string // without the leading slash
  desc: string
  kind: 'command' | 'skill'
}

/** Hermes names already carry a slash ("/airtable"); never double it. */
export const bare = (s: string) => s.replace(/^\/+/, '').trim()

const STORE_KEY = (profile: string) => `hm.cmds.v1.${profile}`
const REFRESH_AFTER_MS = 10 * 60 * 1000

const memory = new Map<string, CommandEntry[]>()
const refreshedAt = new Map<string, number>()
const inflight = new Map<string, Promise<void>>()
const listeners = new Set<() => void>()
const emit = () => listeners.forEach(l => l())

function read(profile: string): CommandEntry[] | null {
  if (memory.has(profile)) return memory.get(profile)!
  try {
    const raw = localStorage.getItem(STORE_KEY(profile))
    if (raw) {
      const list = JSON.parse(raw) as CommandEntry[]
      memory.set(profile, list)
      return list
    }
  } catch {
    /* corrupt / unavailable storage: refetch */
  }
  return null
}

function write(profile: string, list: CommandEntry[]): void {
  memory.set(profile, list)
  try {
    localStorage.setItem(STORE_KEY(profile), JSON.stringify(list))
  } catch {
    /* quota: memory copy still works */
  }
  emit()
}

const sortEntries = (l: CommandEntry[]) => l.sort((a, b) => a.name.localeCompare(b.name))

async function fetchCatalog(profile: string): Promise<void> {
  // 1) names: fast RPC — the list is usable as soon as this lands
  const cat = await rpc<CommandsCatalogResult>('commands.catalog')
  const previous = new Map((read(profile) || []).map(e => [e.name, e.desc]))
  guiHidden.clear()
  for (const [cmd, meta] of Object.entries(cat.commands || {})) if (meta?.desktop) guiHidden.add(bare(cmd))
  const out = new Map<string, CommandEntry>()
  for (const c of cat.categories || [])
    for (const [cmd, desc] of c.pairs || []) out.set(bare(cmd), { name: bare(cmd), desc: desc || '', kind: 'command' })
  for (const [cmd, desc] of cat.pairs || [])
    if (!out.has(bare(cmd))) out.set(bare(cmd), { name: bare(cmd), desc: desc || '', kind: 'command' })
  for (const key of Object.keys(cat.skills || {})) {
    const name = bare(key)
    if (!out.has(name)) out.set(name, { name, desc: previous.get(name) || 'Skill', kind: 'skill' })
  }
  write(profile, sortEntries([...out.values()]))

  // 2) skill descriptions: slow disk scan on the phone — merged in the background
  try {
    const skills = await api<{ name: string; description?: string }[]>('GET', '/api/skills')
    const desc = new Map(skills.map(s => [s.name, s.description || '']))
    const cur = read(profile) || []
    write(profile, cur.map(e => (e.kind === 'skill' && desc.get(e.name) ? { ...e, desc: desc.get(e.name)! } : e)))
  } catch {
    /* names alone are enough */
  }
}

/** Refresh in the background (on connect / profile switch); cheap no-op when recently done. */
export function refreshCommands(force = false): void {
  const profile = getState().profile
  if (getState().conn !== 'open' || inflight.has(profile)) return
  if (!force && Date.now() - (refreshedAt.get(profile) || 0) < REFRESH_AFTER_MS && read(profile)) return
  const p = fetchCatalog(profile)
    .then(() => void refreshedAt.set(profile, Date.now()))
    .catch(() => undefined)
    .finally(() => inflight.delete(profile))
  inflight.set(profile, p)
}

/** Current profile's catalog, instantly (null only on the very first run before any fetch). */
export function useCommandCatalog(): CommandEntry[] | null {
  const profile = getState().profile
  return useSyncExternalStore(
    cb => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    () => read(profile)
  )
}

/** Prefix match on the typed name; commands before skills, shorter names first. */
/** False for commands that only make sense in the terminal / messaging apps, or that the app has a button for. */
export function usableInApp(c: CommandEntry): boolean {
  if (c.kind === 'skill') return true
  if (APP_COVERED.has(c.name)) return false
  if (REGISTRY[`/${c.name}`]) return false
  return !guiHidden.has(c.name)
}

export function matchCommands(all: CommandEntry[], typed: string, limit = 40): CommandEntry[] {
  const q = bare(typed).toLowerCase()
  return all
    .filter(c => usableInApp(c) && c.name.toLowerCase().startsWith(q))
    .sort((a, b) => (a.kind === b.kind ? a.name.length - b.name.length || a.name.localeCompare(b.name) : a.kind === 'command' ? -1 : 1))
    .slice(0, limit)
}
