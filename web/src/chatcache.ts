// Last-seen items of recently viewed chats, so opening one draws it at once while Hermes's
// session.resume (a state.db read on the phone) is still on its way. Memory holds a few chats; the
// one you were last in is also kept in localStorage for a cold start. Never a source of truth: the
// resume's transcript replaces it as soon as it lands.
import type { ChatItem } from './store'

type Entry = { storedId: string; title: string; items: ChatItem[] }

const MEM = 8
const KEEP = 60 // items kept per chat: the Chat view draws the last 40
const DISK_KEY = 'hm.chatcache.v1'
const DISK_MAX = 1_500_000 // chars

const mem = new Map<string, Entry>()

function trim(items: ChatItem[]): ChatItem[] {
  // Never keep a half-streamed reply or a spinning tool card: the preview is a still picture.
  return items.slice(-KEEP).map(it =>
    it.kind === 'assistant' && it.streaming ? { ...it, streaming: false } : it
  )
}

export function rememberChat(profile: string, storedId: string, title: string, items: ChatItem[]): void {
  if (!storedId || !items.length) return
  const e: Entry = { storedId, title, items: trim(items) }
  mem.delete(`${profile}:${storedId}`)
  mem.set(`${profile}:${storedId}`, e)
  while (mem.size > MEM) mem.delete(mem.keys().next().value as string)
}

export function cachedChat(profile: string, storedId: string): Entry | null {
  const e = mem.get(`${profile}:${storedId}`)
  if (e) return e
  const d = readDisk()
  return d && d.profile === profile && d.storedId === storedId ? d : null
}

export function forgetChat(profile: string, storedId: string): void {
  mem.delete(`${profile}:${storedId}`)
  const d = readDisk()
  if (d && d.profile === profile && d.storedId === storedId) {
    try {
      localStorage.removeItem(DISK_KEY)
    } catch {
      /* ignore */
    }
  }
}

/** Keep one chat for the next cold start (called when the app goes to the background). */
export function saveChatToDisk(profile: string, storedId: string, title: string, items: ChatItem[]): void {
  if (!storedId || !items.length) return
  try {
    let tail = trim(items)
    let raw = JSON.stringify({ profile, storedId, title, items: tail })
    // Photos inline as data URLs can make a chat huge: fall back to fewer items, else skip.
    while (raw.length > DISK_MAX && tail.length > 5) {
      tail = tail.slice(Math.ceil(tail.length / 2))
      raw = JSON.stringify({ profile, storedId, title, items: tail })
    }
    if (raw.length > DISK_MAX) localStorage.removeItem(DISK_KEY)
    else localStorage.setItem(DISK_KEY, raw)
  } catch {
    /* quota: ignore */
  }
}

function readDisk(): (Entry & { profile: string }) | null {
  try {
    const v = JSON.parse(localStorage.getItem(DISK_KEY) || 'null') as (Entry & { profile: string }) | null
    return v && Array.isArray(v.items) && typeof v.storedId === 'string' ? v : null
  } catch {
    return null
  }
}
