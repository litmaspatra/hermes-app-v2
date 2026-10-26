// The short word Hermes shows while it thinks ("pondering…", "deliberating…", "initializing…") for the phone's island.
// Hermes prefixes it with a little face and trailing dots: "(｡•́︿•̀｡) pondering..." becomes "Pondering…".
// Anything that is not a short plain word or two (reasoning text, file paths, code) gives '' and is not sent.
export function statusWord(raw: string): string {
  const words = String(raw || '')
    .split(/\s+/)
    .filter(w => /[A-Za-z]/.test(w) && !/[()（）<>[\]{}]/.test(w))
  let t = words.join(' ').replace(/[\s.…:·]+$/u, '').trim()
  if (!t || t.length > 32 || words.length > 4 || /[/\\`_=@#]/.test(t)) return ''
  t = t.charAt(0).toUpperCase() + t.slice(1)
  return t + '…'
}
