// Chat titles. A branched chat is stored as "⎇ <original title>" (Hermes only keeps plain text), and is
// drawn with a proper branch icon here, so it never depends on a font having the ⎇ glyph.
export const BRANCH_MARK = '⎇'
export const isBranch = (t?: string | null): boolean => Boolean(t) && (t as string).startsWith(BRANCH_MARK)
/** The title without the branch marker (for inputs, dialogs, toasts). */
export const plainTitle = (t?: string | null): string => (t || '').replace(/^⎇\s*/, '')
/** A rename keeps the branch marker: someone editing a branch's name doesn't see or lose it. */
export const keepBranch = (original: string | undefined | null, next: string): string => (isBranch(original) && !isBranch(next) && next ? `${BRANCH_MARK} ${next}` : next)

export function BranchIcon({ size = 14 }: { size?: number }) {
  return (
    <svg className="branch-icon" viewBox="0 0 24 24" width={size} height={size} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-label="Branch">
      <circle cx="6" cy="5" r="2" />
      <circle cx="6" cy="19" r="2" />
      <circle cx="18" cy="7" r="2" />
      <path d="M6 7v10M18 9c0 5-12 3-12 8" />
    </svg>
  )
}

export function Title({ text, fallback = '' }: { text?: string | null; fallback?: string }) {
  if (!text) return <>{fallback}</>
  return isBranch(text) ? (
    <>
      <BranchIcon />
      {plainTitle(text)}
    </>
  ) : (
    <>{text}</>
  )
}
