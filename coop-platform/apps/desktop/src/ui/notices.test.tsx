import { Table, TableBody } from '@mui/material'
import { QueryClient, QueryClientProvider, useMutation } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { LoadingRow } from './Loading'
import { Notices, noticeMutationCache, notifyError } from './notices'

function Poster({ fail }: { fail?: boolean }) {
  const m = useMutation({
    meta: { done: (_d, v) => `Journal ${String(v)} posted to the ledger.` },
    mutationFn: async (ref: string) => { if (fail) throw new Error('refused'); return ref },
  })
  return <button onClick={() => m.mutate('GJ-12')}>Post</button>
}

const withClient = (ui: React.ReactNode) => (
  <QueryClientProvider client={new QueryClient({ mutationCache: noticeMutationCache() })}>{ui}<Notices /></QueryClientProvider>
)

describe('done notices', () => {
  it('says the action worked, in the words the screen gave', async () => {
    render(withClient(<Poster />))
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))
    expect(await screen.findByText('Journal GJ-12 posted to the ledger.')).toBeInTheDocument()
  })

  it('says nothing when the action fails', async () => {
    render(withClient(<Poster fail />))
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: 'Post' })) })
    expect(screen.queryByText(/posted to the ledger/)).toBeNull()
  })
})

describe('refusals', () => {
  it('pop up as a card in the middle of the window, as an alert, instead of inside the screen', async () => {
    render(withClient(<button onClick={() => notifyError(new Error('Only a super administrator can do this.'))}>Try</button>))
    await userEvent.click(screen.getByRole('button', { name: 'Try' }))
    const popup = await screen.findByRole('alert')
    expect(popup).toHaveTextContent('Only a super administrator can do this.')
    expect(popup).toHaveAttribute('data-kind', 'error')
    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('LoadingRow', () => {
  const table = (q: { isLoading: boolean; isError: boolean; error: unknown }, errors?: boolean) =>
    render(<Table><TableBody><LoadingRow q={q} what="the assets" errors={errors} /></TableBody></Table>)

  it('shows the wait, then the failure, then nothing', () => {
    const { unmount } = table({ isLoading: true, isError: false, error: null })
    expect(screen.getByRole('status')).toHaveTextContent('Loading the assets…')
    unmount()
    const second = table({ isLoading: false, isError: true, error: new Error('Cannot reach the server.') })
    expect(screen.getByText('Cannot reach the server.')).toBeInTheDocument()
    second.unmount()
    table({ isLoading: false, isError: false, error: null })
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('can leave the failure to the screen', () => {
    table({ isLoading: false, isError: true, error: new Error('refused') }, false)
    expect(screen.queryByText('refused')).toBeNull()
  })
})
