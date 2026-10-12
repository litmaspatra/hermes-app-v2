// Themes: Default (dark), OLED black, Light (white) and System (follows the phone: OLED black when the
// phone is dark, Light when it is light). Stored in localStorage `hm.theme`; the page's <html> gets
// data-theme="default|oled|light" (plus data-oled for the old OLED rules) and Android gets the matching
// background so the status/navigation bars and their icons match.
export type Theme = 'default' | 'oled' | 'light' | 'system'
export type Resolved = 'default' | 'oled' | 'light'
// The LOOK of the app. Material 3 is the default and the only one with a dark mode (the Theme choice below it:
// Default, OLED black, Light, System). Neo-brutalism and Glassmorphism are light only: they ignore that choice.
export type Style = 'material' | 'neo' | 'glass'

const KEY = 'hm.theme'
const STYLE_KEY = 'hm.style'
const DYN_KEY = 'hm.dynamic'
const REDUCE_KEY = 'hm.reduce'
const BG: Record<Resolved, string> = { default: '#0E1115', oled: '#000000', light: '#FFFFFF' }

type Native = {
  setBackground?: (hex: string) => void
  isSystemDark?: () => boolean
  setNative?: (name: string, value: string) => void
  systemColors?: () => string
}
const native = () => window.HermesAndroid as unknown as Native | undefined

export function savedTheme(): Theme {
  try {
    const t = localStorage.getItem(KEY)
    if (t === 'default' || t === 'oled' || t === 'light' || t === 'system') return t
    return localStorage.getItem('hm.oled') === '1' ? 'oled' : 'default' // before themes existed
  } catch {
    return 'default'
  }
}

/** Is the phone in dark mode right now? (Android tells us; a browser uses prefers-color-scheme.) */
export function systemDark(): boolean {
  try {
    const n = native()
    if (n?.isSystemDark) return n.isSystemDark()
  } catch {
    /* fall through */
  }
  return !window.matchMedia('(prefers-color-scheme: light)').matches
}

export const resolveTheme = (t: Theme): Resolved => (t === 'system' ? (systemDark() ? 'oled' : 'light') : t)

let current: Theme = 'default'

export function savedStyle(): Style {
  try {
    const s = localStorage.getItem(STYLE_KEY)
    return s === 'neo' || s === 'glass' ? s : 'material'
  } catch {
    return 'material'
  }
}

export const dynamicColorsOn = (): boolean => {
  try {
    return localStorage.getItem(DYN_KEY) !== '0'
  } catch {
    return true
  }
}

export const reduceEffectsOn = (): boolean => {
  try {
    return localStorage.getItem(REDUCE_KEY) === '1'
  } catch {
    return false
  }
}

const STYLE_BG: Record<Exclude<Style, 'material'>, string> = { neo: '#fff6e5', glass: '#dcd6f7' }
const DYN_VARS = ['--accent', '--accent-2', '--accent-ink', '--user', '--user-border', '--switch-on']

/** Material You colours (Android 12+): tint the accent family from the wallpaper. Other styles and older phones keep the fixed palette. */
function applyDynamic(style: Style, resolved: Resolved): void {
  const el = document.documentElement
  for (const v of DYN_VARS) el.style.removeProperty(v)
  if (style !== 'material' || !dynamicColorsOn()) return
  let c: { a1?: Record<string, string> } | null = null
  try {
    c = JSON.parse(native()?.systemColors?.() || 'null')
  } catch {
    c = null
  }
  const a = c?.a1
  if (!a || !a['600'] || !a['200']) return
  if (resolved === 'light') {
    el.style.setProperty('--accent', a['600'])
    el.style.setProperty('--accent-2', a['700'] || a['600'])
    el.style.setProperty('--accent-ink', '#ffffff')
    el.style.setProperty('--user', a['100'] || a['200'])
    el.style.setProperty('--user-border', a['200'])
    el.style.setProperty('--switch-on', a['200'])
  } else {
    el.style.setProperty('--accent', a['200'])
    el.style.setProperty('--accent-2', a['300'] || a['200'])
    el.style.setProperty('--accent-ink', a['800'] || '#1a1307')
    el.style.setProperty('--user', a['800'] || '#2a2418')
    el.style.setProperty('--user-border', a['700'] || '#3a3020')
    el.style.setProperty('--switch-on', a['700'] || '#3a2e14')
  }
}

let lastNative = ''
export function applyTheme(t: Theme): void {
  current = t
  const style = savedStyle()
  const r: Resolved = style === 'material' ? resolveTheme(t) : 'light' // only Material has a dark mode
  const el = document.documentElement
  el.dataset.theme = r
  el.dataset.style = style
  el.toggleAttribute('data-oled', r === 'oled')
  el.toggleAttribute('data-reduce', reduceEffectsOn())
  applyDynamic(style, r)
  try {
    native()?.setBackground?.(style === 'material' ? BG[r] : STYLE_BG[style])
    // the phone's floating island dresses itself in the same style
    const sig = `${style}|${reduceEffectsOn()}`
    if (sig !== lastNative) {
      lastNative = sig
      native()?.setNative?.('theme', style)
      native()?.setNative?.('reduce_effects', String(reduceEffectsOn()))
    }
  } catch {
    /* older shell */
  }
}

export function setStyle(s: Style): void {
  try {
    localStorage.setItem(STYLE_KEY, s)
  } catch {
    /* ignore */
  }
  applyTheme(current)
}

export function setDynamicColors(on: boolean): void {
  try {
    localStorage.setItem(DYN_KEY, on ? '1' : '0')
  } catch {
    /* ignore */
  }
  applyTheme(current)
}

export function setReduceEffects(on: boolean): void {
  try {
    localStorage.setItem(REDUCE_KEY, on ? '1' : '0')
  } catch {
    /* ignore */
  }
  applyTheme(current)
}

export function setTheme(t: Theme): void {
  try {
    localStorage.setItem(KEY, t)
  } catch {
    /* ignore */
  }
  applyTheme(t)
}

// "System" follows the phone live: Android calls this when the phone switches light/dark…
declare global {
  interface Window {
    __hmSystemTheme?: () => void
  }
}
window.__hmSystemTheme = () => {
  if (current === 'system') applyTheme('system')
}
// …and a plain browser tells us through the media query.
window.matchMedia('(prefers-color-scheme: light)').addEventListener?.('change', () => window.__hmSystemTheme?.())
