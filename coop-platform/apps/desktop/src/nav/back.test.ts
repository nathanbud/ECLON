import { afterEach, describe, expect, it, vi } from 'vitest'
import { escapeAction, LIMIT, listenForEscape, pushed } from './back'

const press = (target: EventTarget = document.body, init: KeyboardEventInit = {}) => {
  const e = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true, ...init })
  target.dispatchEvent(e)
  return e
}
const settle = () => new Promise((r) => setTimeout(r, 5))

describe('what Esc does', () => {
  it('goes back only when nothing else wants it', () => {
    const base = { overlay: false, typing: false, usedByScreen: false, modifier: false }
    expect(escapeAction(base)).toBe('back')
    expect(escapeAction({ ...base, overlay: true })).toBe('ignore')
    expect(escapeAction({ ...base, usedByScreen: true })).toBe('ignore')
    expect(escapeAction({ ...base, modifier: true })).toBe('ignore')
    expect(escapeAction({ ...base, typing: true })).toBe('leave-field')
    expect(escapeAction({ ...base, typing: true, overlay: true })).toBe('ignore')
  })
})

describe('the history of screens', () => {
  const same = (a: string, b: string) => a === b
  it('keeps the newest last, skips the same screen twice in a row and keeps at most the limit', () => {
    expect(pushed(['home'], 'accounting', same)).toEqual(['home', 'accounting'])
    expect(pushed(['home', 'accounting'], 'accounting', same)).toEqual(['home', 'accounting'])
    const long = pushed(Array.from({ length: LIMIT }, (_, i) => `s${i}`), 'last', same)
    expect(long).toHaveLength(LIMIT)
    expect(long.at(-1)).toBe('last')
    expect(long[0]).toBe('s1')
  })
})

describe('listening for Esc', () => {
  let stop: () => void = () => {}
  afterEach(() => { stop(); document.body.innerHTML = '' })

  it('goes back on a plain press', async () => {
    const back = vi.fn()
    stop = listenForEscape(back)
    press()
    await settle()
    expect(back).toHaveBeenCalledOnce()
  })

  it('leaves a dialog to close itself, even when the dialog is gone by the time the press is handled', async () => {
    const back = vi.fn()
    stop = listenForEscape(back)
    const dialog = document.createElement('div')
    dialog.setAttribute('role', 'dialog')
    document.body.appendChild(dialog)
    dialog.addEventListener('keydown', () => dialog.remove()) // a dialog that closes on Esc
    press(dialog)
    await settle()
    expect(back).not.toHaveBeenCalled()
  })

  it('lets a screen use Esc for its own step', async () => {
    const back = vi.fn()
    const own = (e: KeyboardEvent) => e.preventDefault()
    window.addEventListener('keydown', own)
    stop = listenForEscape(back)
    press()
    await settle()
    window.removeEventListener('keydown', own)
    expect(back).not.toHaveBeenCalled()
  })

  it('first leaves a field being typed in, then goes back', async () => {
    const back = vi.fn()
    stop = listenForEscape(back)
    const input = document.createElement('input')
    document.body.appendChild(input)
    input.focus()
    press(input)
    await settle()
    expect(document.activeElement).not.toBe(input)
    expect(back).not.toHaveBeenCalled()
    press()
    await settle()
    expect(back).toHaveBeenCalledOnce()
  })

  it('does nothing while the side menu is open, or with a modifier key held', async () => {
    const back = vi.fn()
    stop = listenForEscape(back, () => true)
    press()
    await settle()
    stop()
    stop = listenForEscape(back)
    press(document.body, { shiftKey: true })
    await settle()
    expect(back).not.toHaveBeenCalled()
  })
})
