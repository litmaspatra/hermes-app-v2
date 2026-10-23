// Cold-start timing for the phone: milliseconds from the WebView's navigation start (page load, not the native
// app launch) to the first render and to the first non-empty chat list. Shown in the Hermes hub footer.
const KEY = 'hm.startup'
const t: { render?: number; list?: number } = {}

export function markStartup(what: 'render' | 'list'): void {
  if (t[what] != null) return
  t[what] = Math.round(performance.now())
  try {
    localStorage.setItem(KEY, JSON.stringify(t))
  } catch {
    /* private mode */
  }
}

export function startupText(): string {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) || '{}') as { render?: number; list?: number }
    if (v.render == null) return ''
    return `start ${v.render} ms${v.list != null ? ` · chat list ${v.list} ms` : ''}`
  } catch {
    return ''
  }
}
