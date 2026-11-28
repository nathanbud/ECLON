import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SignatureDialog from './SignatureDialog'
import { Notices } from './ui/notices'
import { AppThemeProvider } from './theme'

const upload = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  uploadMemberSignature: (...a: unknown[]) => upload(...a),
}))

// jsdom has no canvas: a stand-in context that accepts every drawing call, and a canvas that "encodes" to a small JPEG.
const strokes = vi.fn()
beforeEach(() => {
  upload.mockReset().mockResolvedValue(undefined)
  strokes.mockReset()
  const ctx = new Proxy({}, { get: (_t, name) => (name === 'stroke' ? strokes : () => {}), set: () => true })
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(ctx as unknown as CanvasRenderingContext2D)
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((cb) => cb(new Blob([new Uint8Array([0xff, 0xd8, 0xff, 0xe0])], { type: 'image/jpeg' })))
})
afterEach(() => vi.restoreAllMocks())

function show(onDone = vi.fn(), onClose = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><SignatureDialog id={{ branchCode: 1, clientId: 77 }} name="Budikey, Nathaniel" onClose={onClose} onDone={onDone} /><Notices /></AppThemeProvider></QueryClientProvider>)
  return { onDone, onClose }
}

const sign = (pad: HTMLElement) => {
  fireEvent.pointerDown(pad, { clientX: 10, clientY: 10, pointerId: 1 })
  fireEvent.pointerMove(pad, { clientX: 60, clientY: 30, pointerId: 1 })
  fireEvent.pointerUp(pad, { pointerId: 1 })
}

describe('signature pad', () => {
  it('cannot be saved until something is drawn', () => {
    show()
    expect(screen.getByRole('button', { name: 'Save signature' })).toBeDisabled()
    expect(screen.getByText('Sign here')).toBeInTheDocument()
  })

  it('draws a stroke, then saves it for the member and says so', async () => {
    const { onDone, onClose } = show()
    sign(screen.getByLabelText('Signature pad'))
    expect(strokes).toHaveBeenCalled()
    expect(screen.queryByText('Sign here')).not.toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Save signature' }))
    await waitFor(() => expect(upload).toHaveBeenCalledTimes(1))
    expect(upload.mock.calls[0].slice(0, 2)).toEqual([1, 77])
    expect(upload.mock.calls[0][2]).toBeInstanceOf(Blob)
    await waitFor(() => expect(onDone).toHaveBeenCalledWith('Signature saved'))
    expect(onClose).toHaveBeenCalled()
  })

  it('clears the pad so it can be saved only after signing again', async () => {
    show()
    sign(screen.getByLabelText('Signature pad'))
    await userEvent.setup().click(screen.getByRole('button', { name: 'Clear' }))
    expect(screen.getByRole('button', { name: 'Save signature' })).toBeDisabled()
  })

  it('shows the reason when the server refuses the signature and keeps the pad open', async () => {
    upload.mockRejectedValue(new Error('The signature is too large (at most 1 MB).'))
    const { onClose } = show()
    sign(screen.getByLabelText('Signature pad'))
    await userEvent.setup().click(screen.getByRole('button', { name: 'Save signature' }))
    expect(await screen.findByText(/too large/)).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Save signature' })).toBeEnabled()
  })
})

describe('signature on file', () => {
  it('shows the signature first and offers to replace it, then opens the pad', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={qc}><AppThemeProvider><SignatureDialog id={{ branchCode: 1, clientId: 77 }} name="Budikey, Nathaniel" current="blob:sig" onClose={() => {}} onDone={() => {}} /><Notices /></AppThemeProvider></QueryClientProvider>)
    expect(screen.getByRole('img', { name: /Signature of Budikey/ })).toBeInTheDocument()
    expect(screen.queryByLabelText('Signature pad')).not.toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Replace signature' }))
    expect(screen.getByLabelText('Signature pad')).toBeInTheDocument()
  })

  it('only shows what is on file to a person who may not capture', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={qc}><AppThemeProvider><SignatureDialog id={{ branchCode: 1, clientId: 77 }} name="Budikey, Nathaniel" current={null} canCapture={false} onClose={() => {}} onDone={() => {}} /><Notices /></AppThemeProvider></QueryClientProvider>)
    expect(screen.getByText('No signature on file')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /signature$/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Signature pad')).not.toBeInTheDocument()
  })
})

