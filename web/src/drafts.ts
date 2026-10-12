// Unsent text per chat (localStorage hm.drafts.v1: chat id → text; "new:<profile>" is the new-chat screen).
const KEY = 'hm.drafts.v1'

function all(): Record<string, string> {
  try {
    const m = JSON.parse(localStorage.getItem(KEY) || '{}') || {}
    // One global draft before 0.7.76: it becomes the new-chat draft of the current profile.
    const old = localStorage.getItem('hm.draft')
    if (old !== null) {
      localStorage.removeItem('hm.draft')
      if (old) m[`new:${localStorage.getItem('hm.profile') || 'default'}`] = old
      localStorage.setItem(KEY, JSON.stringify(m))
    }
    return m
  } catch {
    return {}
  }
}

export function getDraft(key: string): string {
  return all()[key] || ''
}

export function setDraft(key: string, text: string): void {
  const m = all()
  if (text) {
    delete m[key] // re-insert last: the oldest drafts are dropped first
    m[key] = text
  } else delete m[key]
  const keys = Object.keys(m)
  for (const k of keys.slice(0, Math.max(0, keys.length - 100))) delete m[k]
  try {
    localStorage.setItem(KEY, JSON.stringify(m))
  } catch {
    /* ignore */
  }
}
