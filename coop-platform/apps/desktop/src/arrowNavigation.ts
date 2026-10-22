/**
 * Arrow Down / Arrow Up move between the fields of a form, dialog or screen, the way Tab and Shift+Tab do.
 * One listener on the document covers every form. It stays out of the way where the arrow keys already mean something:
 * dropdowns and search boxes (combobox), radio groups, date and time fields, menus, lists, tabs, and multi-line text
 * until the caret is on its first or last line. A screen that handles the arrows itself calls preventDefault and wins.
 */

const SELECTOR = 'input, select, textarea, button, [role="combobox"], [tabindex="0"]'
const OWN_ARROWS = '[role="menu"], [role="listbox"], [role="tablist"], [role="grid"], [role="tree"], [role="radiogroup"], [data-no-arrow-nav]'
const NATIVE_ARROWS = new Set(['radio', 'date', 'time', 'datetime-local', 'month', 'week', 'range'])

const visible = (el: HTMLElement) => el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden'

function usable(el: HTMLElement) {
  if (el.matches(':disabled') || el.getAttribute('aria-hidden') === 'true' || el.tabIndex < 0) return false
  if (el instanceof HTMLInputElement && el.type === 'hidden') return false
  if (el.closest(OWN_ARROWS) || ['tab', 'menuitem', 'option'].includes(el.getAttribute('role') ?? '')) return false
  return visible(el)
}

/** The field an arrow key should leave from, or null when the key belongs to the control itself. */
function startsFrom(el: Element | null, key: string): HTMLElement | null {
  if (!(el instanceof HTMLElement) || el.closest(OWN_ARROWS)) return null
  if (el.getAttribute('role') === 'combobox' || el.closest('[role="combobox"]') || el instanceof HTMLSelectElement) return null
  if (el instanceof HTMLInputElement && NATIVE_ARROWS.has(el.type)) return null
  if (el instanceof HTMLTextAreaElement) {
    const { value, selectionStart, selectionEnd } = el
    if (key === 'ArrowUp' && value.slice(0, selectionStart).includes('\n')) return null
    if (key === 'ArrowDown' && value.slice(selectionEnd).includes('\n')) return null
  }
  return el.matches('input, textarea, button, [tabindex="0"]') ? el : null
}

function onKeyDown(e: KeyboardEvent) {
  if (e.defaultPrevented || e.isComposing || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
  const from = startsFrom(e.target as Element, e.key)
  if (!from) return
  const scope = from.closest('[role="dialog"], form, [data-arrow-scope]') ?? document.body
  const fields = Array.from(scope.querySelectorAll<HTMLElement>(SELECTOR)).filter((el) => el === from || usable(el))
  const next = fields[fields.indexOf(from) + (e.key === 'ArrowDown' ? 1 : -1)]
  if (!next) return
  e.preventDefault()
  next.focus()
  if (next instanceof HTMLInputElement && /^(text|password|email|search|tel|url|number)$/.test(next.type)) next.select()
}

/** Call once at start-up. */
export function installArrowNavigation() {
  document.addEventListener('keydown', onKeyDown)
}
