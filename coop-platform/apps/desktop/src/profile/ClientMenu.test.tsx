import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ACTIONS, ClientMenu } from './ClientMenu'

function menu(newRun: (() => void) | undefined) {
  const actions = ACTIONS.map((a) => ({ ...a, run: a.label === 'New' ? newRun : undefined }))
  render(<ClientMenu onFind={() => undefined} actions={actions} editOn pendingNote={null} />)
}

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
