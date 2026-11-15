/**
 * Escape goes back, like a browser's Back: what decides it, kept apart from the screens so it can be tested.
 *
 * Esc first closes what is open on top (a dialog, a menu, a drop-down list, the side menu; MUI and the menu do that themselves), then lets a
 * screen use it for its own step (cancelling an edit or a draft: such a screen calls preventDefault), then leaves a field being typed in,
 * and only then goes back to the previous screen. So one press never does two things.
 */

/** Something open on top of the screen that Esc closes first. Checked when the key goes down, before anything reacts to it. */
export function overlayOpen(doc: Document = document): boolean {
  return !!doc.querySelector('.MuiModal-root:not(.MuiModal-hidden), [role="dialog"], [role="listbox"], [role="menu"]')
}

export function isTyping(target: EventTarget | null): boolean {
  const t = target as HTMLElement | null
  return !!t && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))
}

export type EscapeAction = 'ignore' | 'leave-field' | 'back'

/** What one press of Esc does, from what was true when it went down and whether a screen already used it. */
export function escapeAction(s: { overlay: boolean; typing: boolean; usedByScreen: boolean; modifier: boolean }): EscapeAction {
  if (s.modifier || s.overlay || s.usedByScreen) return 'ignore'
  if (s.typing) return 'leave-field'
  return 'back'
}

/** The screens visited before this one, newest last; at most {@link LIMIT}. */
export const LIMIT = 30

export function pushed<T>(history: T[], current: T, same: (a: T, b: T) => boolean): T[] {
  const last = history[history.length - 1]
  if (last !== undefined && same(last, current)) return history
  return [...history, current].slice(-LIMIT)
}

/**
 * Listens for Esc on this window and calls {@code onBack} (or leaves the field) when nothing else used it. The state is read in the capture
 * phase, before a dialog closes itself, and the decision is taken after every other handler has run, so a screen's own Esc wins.
 */
export function listenForEscape(onBack: () => void, extraOverlay: () => boolean = () => false): () => void {
  const seen = new WeakMap<KeyboardEvent, { overlay: boolean; typing: boolean }>()
  const capture = (e: KeyboardEvent) => {
    if (e.key === 'Escape') seen.set(e, { overlay: overlayOpen() || extraOverlay(), typing: isTyping(e.target) })
  }
  const bubble = (e: KeyboardEvent) => {
    if (e.key !== 'Escape' || e.repeat) return
    const at = seen.get(e) ?? { overlay: overlayOpen() || extraOverlay(), typing: isTyping(e.target) }
    setTimeout(() => {
      const action = escapeAction({ ...at, usedByScreen: e.defaultPrevented, modifier: e.ctrlKey || e.metaKey || e.altKey || e.shiftKey })
      if (action === 'leave-field') (e.target as HTMLElement).blur()
      else if (action === 'back') onBack()
    }, 0)
  }
  window.addEventListener('keydown', capture, true)
  window.addEventListener('keydown', bubble)
  return () => {
    window.removeEventListener('keydown', capture, true)
    window.removeEventListener('keydown', bubble)
  }
}
