import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppThemeProvider } from '../theme'
import { VoiceControl } from './VoiceControl'

// The recognizer needs a microphone and a 40 MB model, so it is replaced by one the test drives by hand.
let installed = true
let said: ((text: string) => void) | null = null
let partial: ((text: string) => void) | null = null
const stopped = vi.fn()
vi.mock('./engine', () => ({
  modelInstalled: async () => installed,
  startListening: async (l: { onPartial: (t: string) => void; onSentence: (t: string) => void }, ready?: () => void) => {
    said = l.onSentence
    partial = l.onPartial
    ready?.()
    return { stop: stopped }
  },
}))

function show(handle = vi.fn(() => ({ message: 'Opening Journals', done: true }))) {
  render(<AppThemeProvider><VoiceControl handle={handle} /></AppThemeProvider>)
  return handle
}

describe('the microphone button', () => {
  beforeEach(() => {
    installed = true
    said = null
    partial = null
    stopped.mockClear()
    try { localStorage.clear() } catch { /* none */ }
  })

  it('is disabled, and says how to install the model, when the model is missing', async () => {
    installed = false
    show()
    expect(await screen.findByRole('button', { name: 'Voice commands are not installed' })).toBeDisabled()
  })

  it('asks once what the microphone is used for before it listens', async () => {
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Voice commands' }))
    expect(screen.getByRole('dialog', { name: 'Use voice commands?' })).toBeInTheDocument()
    expect(screen.getByText(/Nothing is sent over the network/)).toBeInTheDocument()
    expect(said, 'not listening until agreed').toBeNull()
    await user.click(screen.getByRole('button', { name: 'Start listening' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Stop listening' })).toHaveAttribute('aria-pressed', 'true'))
  })

  it('does not ask again once agreed, carries out what is said, and turns itself off', async () => {
    const handle = show()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Voice commands' }))
    await user.click(screen.getByRole('button', { name: 'Start listening' }))
    await waitFor(() => expect(said).not.toBeNull())

    said?.('open journals')
    expect(handle).toHaveBeenCalledWith('open journals')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Voice commands' })).toHaveAttribute('aria-pressed', 'false'))
    expect(stopped, 'the microphone is turned off').toHaveBeenCalled()
    expect(screen.getByRole('status')).toHaveTextContent('Opening Journals')

    await user.click(screen.getByRole('button', { name: 'Voice commands' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('keeps listening when a sentence was not understood, and shows what it heard as it goes', async () => {
    const handle = show(vi.fn(() => ({ message: 'Didn’t catch “banana”', done: false })))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Voice commands' }))
    await user.click(screen.getByRole('button', { name: 'Start listening' }))
    await waitFor(() => expect(partial).not.toBeNull())
    partial?.('open ban')
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('open ban'))
    said?.('banana')
    expect(handle).toHaveBeenCalled()
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Didn’t catch'))
    expect(screen.getByRole('button', { name: 'Stop listening' })).toBeInTheDocument()
  })

  it('stops on Escape and turns the microphone off', async () => {
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Voice commands' }))
    await user.click(screen.getByRole('button', { name: 'Start listening' }))
    await screen.findByRole('button', { name: 'Stop listening' })
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Voice commands' })).toHaveAttribute('aria-pressed', 'false'))
    expect(stopped).toHaveBeenCalled()
  })
})
