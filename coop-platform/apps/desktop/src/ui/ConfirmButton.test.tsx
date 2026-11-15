import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmButton } from './dialogs'

describe('ConfirmButton', () => {
  it('says what will happen and acts only when confirmed', async () => {
    const post = vi.fn()
    render(<ConfirmButton variant="contained" title="Post journal GJ-12?" message="It writes 5,000.00 to the general ledger." onConfirm={post}>Post</ConfirmButton>)
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))
    expect(screen.getByRole('dialog', { name: 'Post journal GJ-12?' })).toHaveTextContent('It writes 5,000.00 to the general ledger.')
    expect(post).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(post).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Post' }))
    await userEvent.click(screen.getAllByRole('button', { name: 'Post' }).at(-1)!)
    expect(post).toHaveBeenCalledOnce()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('stays disabled when the action is not allowed', () => {
    render(<ConfirmButton disabled title="t" message="m" onConfirm={() => {}}>Post</ConfirmButton>)
    expect(screen.getByRole('button', { name: 'Post' })).toBeDisabled()
  })
})
