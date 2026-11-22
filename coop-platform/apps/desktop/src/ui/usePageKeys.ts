import { useEffect, useRef } from 'react'

/**
 * Page Up and Page Down call {@code step} with -1 or 1 while {@code enabled}, as the old system's windows move between records; not while typing in a
 * field, or with a dialog or a list open.
 */
export function usePageKeys(enabled: boolean, step: (d: -1 | 1) => void) {
  const latest = useRef(step)
  useEffect(() => { latest.current = step })
  useEffect(() => {
    if (!enabled) return
    const onKey = (e: KeyboardEvent) => {
      if ((e.key !== 'PageUp' && e.key !== 'PageDown') || e.defaultPrevented) return
      const t = e.target
      if (t instanceof HTMLElement && (t.closest('input, textarea, [role="dialog"], [role="listbox"]') || t.isContentEditable)) return
      e.preventDefault()
      latest.current(e.key === 'PageUp' ? -1 : 1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [enabled])
}
