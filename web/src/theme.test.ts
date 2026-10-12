import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// Contrast of the colour tokens in every look: normal text needs 4.5:1. Translucent surfaces are
// composited over the page background first, as they are on screen.
const base = readFileSync(join(__dirname, 'styles.css'), 'utf8')
const themed = readFileSync(join(__dirname, 'theme.css'), 'utf8')

function block(css: string, selector: string): Record<string, string> {
  const i = css.indexOf(`${selector} {`)
  if (i < 0) throw new Error(`no block ${selector}`)
  const body = css.slice(css.indexOf('{', i) + 1, css.indexOf('}', i))
  const out: Record<string, string> = {}
  for (const m of body.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)) out[m[1]] = m[2].trim()
  return out
}

type RGBA = [number, number, number, number]
function parse(c: string): RGBA {
  let m = /^#([0-9a-f]{3,8})$/i.exec(c)
  if (m) {
    let h = m[1]
    if (h.length === 3) h = [...h].map(x => x + x).join('')
    return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), 1]
  }
  m = /^rgba?\(([^)]+)\)$/.exec(c)
  if (m) {
    const p = m[1].split(',').map(x => parseFloat(x))
    return [p[0], p[1], p[2], p[3] ?? 1]
  }
  throw new Error(`cannot parse ${c}`)
}
const over = (top: RGBA, bg: RGBA): RGBA => [0, 1, 2].map(k => top[k] * top[3] + bg[k] * (1 - top[3])).concat(1) as RGBA
const lum = (c: RGBA) => {
  const f = (v: number) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2])
}
const ratio = (a: RGBA, b: RGBA) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p)
  return (x + 0.05) / (y + 0.05)
}

const root = block(base, ':root')
const looks: Record<string, Record<string, string>> = {
  'material default': root,
  'material oled': { ...root, ...block(base, ':root[data-oled]') },
  'material light': { ...root, ...block(base, ":root[data-theme='light']"), ...block(themed, ":root[data-style='material'][data-theme='light']") },
  neo: { ...root, ...block(themed, ":root[data-style='neo']") },
  glass: { ...root, ...block(themed, ":root[data-style='glass']") }
}

describe('contrast', () => {
  for (const [name, t] of Object.entries(looks)) {
    it(`${name}: text, dim text and accent text are readable`, () => {
      const bg = parse(t['--bg'])
      const surf = over(parse(t['--surface']), bg)
      const surf2 = over(parse(t['--surface-2']), bg)
      const pairs: Array<[string, RGBA, RGBA]> = [
        ['text on bg', parse(t['--text']), bg],
        ['text on surface', parse(t['--text']), surf],
        ['text on surface-2', parse(t['--text']), surf2],
        ['dim on bg', parse(t['--dim']), bg],
        ['dim on surface', parse(t['--dim']), surf],
        ['accent-ink on accent', parse(t['--accent-ink']), parse(t['--accent'])]
      ]
      for (const [what, fg, back] of pairs) expect(ratio(fg, back), `${name}: ${what}`).toBeGreaterThanOrEqual(4.5)
    })
  }
  for (const name of ['neo', 'glass']) {
    it(`${name}: status colours are readable on the page`, () => {
      const t = looks[name]
      const bg = parse(t['--bg'])
      for (const k of ['--ok', '--warn', '--err']) expect(ratio(parse(t[k]), bg), `${name}: ${k}`).toBeGreaterThanOrEqual(4.5)
    })
  }
})

describe('theme hygiene', () => {
  it('components do not add new hard-coded colours (they belong in theme.css)', () => {
    const dir = join(__dirname, 'components')
    let count = 0
    for (const f of readdirSync(dir).filter(x => x.endsWith('.tsx'))) {
      count += (readFileSync(join(dir, f), 'utf8').match(/#[0-9a-fA-F]{6}\b/g) || []).length
    }
    // 21 existed before v2 (contrast-picking colours in Markdown.tsx, theme swatches in Settings.tsx): must not grow
    expect(count).toBeLessThanOrEqual(21)
  })
})
