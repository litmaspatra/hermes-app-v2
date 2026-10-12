// What you asked recently (per profile, localStorage hm.recent.v1.<profile>): the empty screen suggests it again.
import { getState } from './store'

const KEY = () => `hm.recent.v1.${getState().profile}`
const MAX = 20

export function recentPrompts(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(KEY()) || '[]')
    return Array.isArray(v) ? v.filter(x => typeof x === 'string') : []
  } catch {
    return []
  }
}

export function rememberPrompt(text: string): void {
  const t = text.trim().replace(/\s+/g, ' ')
  if (t.length < 4 || t.length > 200) return
  const list = [t, ...recentPrompts().filter(x => x.toLowerCase() !== t.toLowerCase())].slice(0, MAX)
  try {
    localStorage.setItem(KEY(), JSON.stringify(list))
  } catch {
    /* ignore */
  }
}
