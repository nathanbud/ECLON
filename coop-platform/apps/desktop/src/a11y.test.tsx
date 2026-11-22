import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import axe from 'axe-core'
import type { ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import DocumentationPage from './DocumentationPage'
import { FindBody } from './FindClientDialog'
import { CloseDialog } from './MemberRequests'
import { AppThemeProvider } from './theme'
import { ReasonDialog } from './ui/dialogs'

vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), searchMembers: vi.fn(async () => ({ content: [], totalElements: 0, number: 0, size: 8 })) }))

/** Runs axe on what is rendered. jsdom cannot judge colour contrast or layout, so those two rule groups are left to a person (and to the contrast figures in glass.ts). */
async function violations(ui: ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider>{ui}</AppThemeProvider></QueryClientProvider>)
  const result = await axe.run(document.body, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } })
  return result.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(' | ')})`)
}

describe('accessibility (axe)', () => {
  it('Find Member has no violations', async () => {
    expect(await violations(<FindBody onPick={() => {}} inline />)).toEqual([])
  })

  it('the reason dialog has no violations', async () => {
    expect(await violations(<ReasonDialog title="Reject" label="Reason" onRun={async () => {}} onClose={() => {}} />)).toEqual([])
  })

  it('the close-membership dialog has no violations', async () => {
    expect(await violations(<CloseDialog branchCode={1} clientId={1} name="Reyes, Ana" onClose={() => {}} onDone={() => {}} />)).toEqual([])
  })

  it('the documentation page has no violations', async () => {
    expect(await violations(<DocumentationPage />)).toEqual([])
  })
})
