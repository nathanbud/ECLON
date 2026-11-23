import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { AppThemeProvider } from '../theme'
import { BusyArea, GlobalBusyBar } from './Loading'

describe('loading indicators', () => {
  it('a busy area shows its bar only after a short wait, and keeps its content from being clicked meanwhile', async () => {
    const { rerender } = render(<AppThemeProvider><BusyArea busy label="Loading the statement"><button type="button">Row</button></BusyArea></AppThemeProvider>)
    const area = screen.getByRole('button', { name: 'Row' }).closest('[aria-busy]')!
    expect(area).toHaveAttribute('aria-busy', 'true')
    expect(screen.queryByRole('progressbar')).toBeNull() // not at once: a quick reload does not flicker
    expect(await screen.findByRole('progressbar', { name: 'Loading the statement' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Row' }).parentElement).toHaveStyle({ pointerEvents: 'none' })
    rerender(<AppThemeProvider><BusyArea busy={false} label="Loading the statement"><button type="button">Row</button></BusyArea></AppThemeProvider>)
    expect(screen.queryByRole('progressbar')).toBeNull()
    expect(area).toHaveAttribute('aria-busy', 'false')
  })

  it('the bar under the title bar shows while something on screen waits for its first figures, not while a shown list refreshes itself', async () => {
    let finish: (v: string) => void = () => {}
    function Screen() {
      const [n, setN] = useState(0)
      const q = useQuery({ queryKey: ['thing', n], queryFn: () => new Promise<string>((ok) => { finish = ok }) })
      return <><span>{q.data ?? 'nothing yet'}</span><button type="button" onClick={() => setN(n + 1)}>Next</button></>
    }
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={qc}><AppThemeProvider><GlobalBusyBar /><Screen /></AppThemeProvider></QueryClientProvider>)
    expect(await screen.findByRole('progressbar', { name: 'Loading' })).toBeInTheDocument()
    finish('first')
    expect(await screen.findByText('first')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('progressbar')).toBeNull())
    // a refresh of what is already shown (as the lists that refresh themselves every few seconds) stays quiet
    void qc.refetchQueries({ queryKey: ['thing', 0] })
    await new Promise((r) => setTimeout(r, 600))
    expect(screen.queryByRole('progressbar')).toBeNull()
    finish('again')
  })
})
