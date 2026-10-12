// Tick state of markdown task-list checkboxes. The reply text itself is Hermes's, so ticks are kept on this
// phone, keyed by a hash of the message plus the box's position; they survive reopening the chat.
import { useSyncExternalStore } from 'react'

const KEY = 'hm.tasks.v1'
const MAX_MESSAGES = 300

let state: Record<string, Record<number, boolean>> = (() => {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '{}')
  } catch {
    return {}
  }
})()
const subs = new Set<() => void>()

export function hashText(s: string): string {
  let h = 5381
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0
  return (h >>> 0).toString(36)
}

export function setTask(seed: string, idx: number, on: boolean): void {
  const cur = { ...(state[seed] || {}), [idx]: on }
  const next = { ...state }
  delete next[seed] // re-insert last: the oldest messages fall off first
  next[seed] = cur
  const keys = Object.keys(next)
  for (const k of keys.slice(0, Math.max(0, keys.length - MAX_MESSAGES))) delete next[k]
  state = next
  try {
    localStorage.setItem(KEY, JSON.stringify(state))
  } catch {
    /* ignore */
  }
  subs.forEach(f => f())
}

/** Ticked? The user's own choice wins over what the markdown said (`[x]` / `[ ]`). */
export function useTask(seed: string, idx: number, initial: boolean): boolean {
  return useSyncExternalStore(
    cb => (subs.add(cb), () => subs.delete(cb)),
    () => state[seed]?.[idx] ?? initial
  )
}

export const subscribeTasks = (cb: () => void): (() => void) => (subs.add(cb), () => subs.delete(cb))
