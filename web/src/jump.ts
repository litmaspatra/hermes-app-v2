// Message search → open the chat at the hit. The dashboard's search gives a chat id and a snippet with the
// matches marked >>>like this<<<, but no message id, so the message is found again here by its text.
import type { ChatItem } from './store'

export interface Jump {
  storedId: string
  terms: string[]
  snippet: string
}

/** Marked words and the snippet's plain text. */
export function parseSnippet(snippet: string): { terms: string[]; plain: string } {
  const clean = snippet.replace(/\\+[nt]/g, ' ').replace(/\\+"/g, '"')
  const terms = [...new Set([...clean.matchAll(/>>>(.*?)<<</g)].map(m => m[1].trim().toLowerCase()).filter(Boolean))]
  const plain = clean.replace(/>>>|<<</g, '').replace(/\s+/g, ' ').trim()
  return { terms, plain }
}

export const itemText = (it: ChatItem): string =>
  it.kind === 'user' || it.kind === 'assistant' ? it.text : it.kind === 'tool' ? `${it.context || ''} ${it.resultText || ''}` : ''

/** Index of the message the snippet came from, or -1. Every marked word counts; a longer piece of the snippet
 * found verbatim counts more. Ties go to the latest message. */
export function findHit(items: ChatItem[], terms: string[], plain: string): number {
  // Pieces of the snippet between "…" (FTS elides around the match), longest first.
  const pieces = plain
    .toLowerCase()
    .split(/…|\.\.\./)
    .map(p => p.trim())
    .filter(p => p.length >= 3)
    .sort((a, b) => b.length - a.length)
  let best = -1
  let bestScore = 0
  items.forEach((it, i) => {
    const t = itemText(it).replace(/\s+/g, ' ').toLowerCase()
    if (!t) return
    let score = terms.filter(w => t.includes(w)).length
    for (const p of pieces) if (t.includes(p)) score += 1 + p.length / 10
    if (score > 0 && score >= bestScore) {
      best = i
      bestScore = score
    }
  })
  return best
}

/** Mark the words inside a message with the CSS Custom Highlight API (no DOM changes; React keeps its tree). */
export function highlightWords(el: Element, terms: string[], name = 'hm-hit'): () => void {
  const hl = (globalThis as unknown as { CSS?: { highlights?: Map<string, unknown> } }).CSS?.highlights
  const Highlight = (globalThis as unknown as { Highlight?: new (...r: Range[]) => unknown }).Highlight
  if (!hl || !Highlight || !terms.length) return () => {}
  const ranges: Range[] = []
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const text = (n.textContent || '').toLowerCase()
    for (const w of terms) {
      for (let at = text.indexOf(w); at >= 0 && ranges.length < 600; at = text.indexOf(w, at + w.length)) {
        const r = document.createRange()
        r.setStart(n, at)
        r.setEnd(n, at + w.length)
        ranges.push(r)
      }
    }
  }
  hl.set(name, new Highlight(...ranges))
  return () => hl.delete(name)
}

/** Indices of the messages whose text contains `q` (case-insensitive), newest first. */
export function findMatches(items: ChatItem[], q: string): number[] {
  const n = q.trim().toLowerCase()
  if (n.length < 1) return []
  const out: number[] = []
  for (let i = items.length - 1; i >= 0; i--) if (itemText(items[i]).toLowerCase().includes(n)) out.push(i)
  return out
}

/** The first place `term` appears inside `el`, as a Range (to scroll it into view). */
export function firstRange(el: Element, term: string): Range | null {
  const w = term.trim().toLowerCase()
  if (!w) return null
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const at = (n.textContent || '').toLowerCase().indexOf(w)
    if (at >= 0) {
      const r = document.createRange()
      r.setStart(n, at)
      r.setEnd(n, at + w.length)
      return r
    }
  }
  return null
}
