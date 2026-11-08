import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CloseDialog } from './MemberRequests'
import { AppThemeProvider } from './theme'

const requestClosure = vi.fn()
const upload = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getDocumentSetup: async () => ({ extensions: ['pdf', 'png', 'jpg'], maxMegabytes: 10, entityTypes: [], minRetentionYears: {} }),
  requestClosure: (...a: unknown[]) => requestClosure(...a),
  uploadDocument: (...a: unknown[]) => upload(...a),
}))

const pdf = (name: string, size = 100) => new File([new Uint8Array(size).fill(1)], name, { type: 'application/pdf' })

function show() {
  const onDone = vi.fn()
  const onClose = vi.fn()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><CloseDialog branchCode={1} clientId={100} name="Reyes, Ana" onClose={onClose} onDone={onDone} /></AppThemeProvider></QueryClientProvider>)
  return { onDone, onClose }
}

describe('closing a membership with proof documents', () => {
  beforeEach(() => {
    requestClosure.mockReset().mockResolvedValue(undefined)
    upload.mockReset().mockResolvedValue({})
  })

  it('still works with no documents', async () => {
    const { onDone } = show()
    await userEvent.click(screen.getByRole('button', { name: 'Send for approval' }))
    await waitFor(() => expect(onDone).toHaveBeenCalledWith('Closing sent for approval'))
    expect(upload).not.toHaveBeenCalled()
  })

  it('sends the request first, then attaches each proof to the member as an internal document', async () => {
    const { onDone, onClose } = show()
    const user = userEvent.setup()
    await screen.findByRole('button', { name: 'Attach proof' })
    await waitFor(() => expect(screen.getByLabelText('Proof documents')).toHaveAttribute('accept', '.pdf,.png,.jpg'))
    await user.upload(screen.getByLabelText('Proof documents'), [pdf('letter.pdf'), pdf('id.pdf', 2048)])
    const list = screen.getByRole('list', { name: 'Documents to attach' })
    expect(list).toHaveTextContent('letter.pdf')
    expect(list).toHaveTextContent('2 KB')
    await user.click(screen.getByRole('button', { name: 'Remove id.pdf' }))
    expect(list).not.toHaveTextContent('id.pdf')

    await user.click(screen.getByRole('button', { name: 'Send for approval' }))
    await waitFor(() => expect(onDone).toHaveBeenCalledWith('Closing sent for approval with 1 document'))
    expect(requestClosure).toHaveBeenCalledOnce()
    expect(requestClosure.mock.invocationCallOrder[0]).toBeLessThan(upload.mock.invocationCallOrder[0])
    expect(upload).toHaveBeenCalledOnce()
    expect(upload.mock.calls[0][1]).toMatchObject({ entityType: 'MEMBER', entityId: '100', classification: 'INTERNAL', title: 'Closure proof: letter.pdf' })
    expect(onClose).toHaveBeenCalled()
  })

  it('refuses a file type that cannot be kept, a file that is too big and an empty one, in words', async () => {
    show()
    const user = userEvent.setup({ applyAccept: false }) // the picker would hide such a file; a dropped or renamed one still arrives
    await waitFor(() => expect(screen.getByLabelText('Proof documents')).toHaveAttribute('accept', '.pdf,.png,.jpg'))
    await user.upload(screen.getByLabelText('Proof documents'), [new File(['x'], 'run.exe'), pdf('big.pdf', 11 * 1024 * 1024), pdf('empty.pdf', 0)], )
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('run.exe: this kind of file cannot be kept')
    expect(alert).toHaveTextContent('big.pdf: larger than 10 MB')
    expect(alert).toHaveTextContent('empty.pdf: the file is empty')
    expect(screen.queryByRole('list', { name: 'Documents to attach' })).not.toBeInTheDocument()
  })

  it('keeps the request when a proof fails to upload, and says which one and where to attach it', async () => {
    upload.mockRejectedValueOnce(new Error('You may not attach documents to this kind of record.'))
    const { onDone } = show()
    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByLabelText('Proof documents')).toHaveAttribute('accept', '.pdf,.png,.jpg'))
    await user.upload(screen.getByLabelText('Proof documents'), [pdf('letter.pdf')])
    await user.click(screen.getByRole('button', { name: 'Send for approval' }))
    await waitFor(() => expect(onDone).toHaveBeenCalled())
    const msg = onDone.mock.calls[0][0] as string
    expect(msg).toContain('Closing sent for approval, but not attached')
    expect(msg).toContain('letter.pdf')
    expect(msg).toContain('Documents tab')
  })

  it('attaches at most five', async () => {
    show()
    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByLabelText('Proof documents')).toHaveAttribute('accept', '.pdf,.png,.jpg'))
    await user.upload(screen.getByLabelText('Proof documents'), Array.from({ length: 6 }, (_, i) => pdf(`p${i}.pdf`, 10 + i)))
    expect(screen.getAllByRole('listitem')).toHaveLength(5)
    expect(await screen.findByRole('alert')).toHaveTextContent('Only 5 documents')
    expect(screen.getByRole('button', { name: 'Attach proof' })).toBeDisabled()
  })
})
