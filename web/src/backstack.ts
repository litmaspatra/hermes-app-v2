// Android Back button: the top-most registered layer (dialog, picker, sheet, settings sub-page) handles it first.
import { useEffect, useRef } from 'react'

const stack: Array<() => void> = []

/** Called by window.hermesBack: closes the top layer, returns whether there was one. */
export function backTop(): boolean {
  const top = stack[stack.length - 1]
  if (!top) return false
  top()
  return true
}

/** While `active`, Back calls `onBack` (later registrations sit on top of earlier ones). */
export function useBackHandler(onBack: () => void, active = true): void {
  const ref = useRef(onBack)
  ref.current = onBack
  useEffect(() => {
    if (!active) return
    const h = () => ref.current()
    stack.push(h)
    return () => {
      const i = stack.lastIndexOf(h)
      if (i >= 0) stack.splice(i, 1)
    }
  }, [active])
}
