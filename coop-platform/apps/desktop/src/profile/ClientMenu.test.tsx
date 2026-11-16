import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ACTIONS, ClientMenu, closeMatch } from './ClientMenu'

function menu(newRun: (() => void) | undefined) {
  const actions = ACTIONS.map((a) => ({ ...a, run: a.label === 'New' ? newRun : undefined }))
  render(<ClientMenu onFind={() => undefined} actions={actions} editOn pendingNote={null} />)
}

describe('Client Profile menu, Close Membership', () => {
  it('is in the menu, asks to close when chosen, and is off when it cannot run', async () => {
    const close = vi.fn()
    const { unmount } = render(<ClientMenu onFind={() => undefined} actions={ACTIONS} editOn pendingNote={null} onCloseMembership={close} />)
    await userEvent.click(screen.getByRole('button', { name: /Client Profile/ }))
    await userEvent.click(screen.getByRole('menuitem', { name: /Close Membership/ }))
    expect(close).toHaveBeenCalledOnce()
    unmount()
    render(<ClientMenu onFind={() => undefined} actions={ACTIONS} editOn pendingNote={null} />)
    await userEvent.click(screen.getByRole('button', { name: /Client Profile/ }))
    expect(screen.getByRole('menuitem', { name: /Close Membership/ })).toHaveAttribute('aria-disabled', 'true')
  })
})

describe('Close Membership shortcut', () => {
  const key = (init: KeyboardEventInit) => new KeyboardEvent('keydown', init)
  it('is Ctrl/Cmd+Shift+C, and plain Ctrl/Cmd+C stays Copy', () => {
    const mac = /Mac|iPhone|iPad/.test(navigator.platform)
    const mod = mac ? { metaKey: true } : { ctrlKey: true }
    expect(closeMatch(key({ key: 'C', shiftKey: true, ...mod }))).toBe(true)
    expect(closeMatch(key({ key: 'c', ...mod }))).toBe(false)
    expect(closeMatch(key({ key: 'c', shiftKey: true }))).toBe(false)
    expect(closeMatch(key({ key: 'c', shiftKey: true, altKey: true, ...mod }))).toBe(false)
  })
})

describe('Client Profile menu, New', () => {
  it('registers a new member when the person may edit clients', async () => {
    const run = vi.fn()
    menu(run)
    await userEvent.click(screen.getByRole('button', { name: /Client Profile/ }))
    await userEvent.click(screen.getByRole('menuitem', { name: /New/ }))
    expect(run).toHaveBeenCalledOnce()
  })

  it('is switched off when it cannot run', async () => {
    menu(undefined)
    await userEvent.click(screen.getByRole('button', { name: /Client Profile/ }))
    expect(screen.getByRole('menuitem', { name: /New/ })).toHaveAttribute('aria-disabled', 'true')
  })
})
