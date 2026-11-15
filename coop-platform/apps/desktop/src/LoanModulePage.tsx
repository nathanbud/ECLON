import { Alert, Box, Button, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { FiFilePlus } from 'react-icons/fi'
import { getLoanAppPermissions, getLoanApplications } from './api'
import { openLoanApplicationWindow, openLoanSetupWindow } from './detachedWindow'
import { appBackground } from './glass'
import LoanApplicationDialog from './LoanApplicationDialog'
import { LoanApplicationsPanel } from './LoanApplicationsPanel'
import { FILTERS, LoanMenu, NEW_LOAN_SHORTCUT, type LoanFilter } from './LoanMenu'
import { useMe } from './session'
import { ToolbarPortal } from './toolbarSlot'

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)

const STEPS = [
  ['1', 'Profiling', 'Encode the application: member, loan type, principal, terms, collateral, co-makers.'],
  ['2', 'Deduction', 'Fees, share capital and pay-offs that come out of the proceeds.'],
  ['3', 'Verification', 'Check the application and its figures. Not by the person who encoded it.'],
  ['4', 'Approval', 'Approve or disapprove. Approving makes the release voucher.'],
] as const

/**
 * Loan Module: where a loan application is started and goes through its steps, Profiling, Deduction, Verification and Approval. Releasing the
 * money is not done here: the release voucher is approved and posted, and the cash paid out, in Front Office > Disburse, by the people with the
 * Cash Voucher right (Settings > Users > Transaction Types).
 */
export default function LoanModulePage() {
  const perms = useQuery({ queryKey: ['loan-app-permissions'], queryFn: getLoanAppPermissions, staleTime: 60_000 })
  const p = perms.data
  const anyStep = !p || p.canAdd || p.canDeduct || p.canVerify || p.canApprove
  // The form opens in a window of its own; inside a browser that cannot open one, it opens here instead.
  const [inline, setInline] = useState(false)
  const start = () => { if (!openLoanApplicationWindow({})) setInline(true) }
  const mayStart = p == null || p.canAdd
  const me = useMe()
  const [filter, setFilter] = useState<LoanFilter>('all')
  // the same list the panel shows (one request, shared through the query cache), for the counts in the menu
  const list = useQuery({ queryKey: ['loan-applications'], queryFn: getLoanApplications, refetchInterval: 30_000, retry: false, enabled: anyStep })
  const counts = list.data ? Object.fromEntries(FILTERS.map((f) => [f.key, list.data!.filter(f.test).length])) as Record<LoanFilter, number> : null
  // Ctrl+N (⌘N on a Mac) starts a new application, as it starts a new member on Client Profile. Not while a dialog or menu is open.
  const startRef = useRef(start)
  startRef.current = start
  const mayRef = useRef(mayStart)
  mayRef.current = mayStart
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (!(isMac ? e.metaKey : e.ctrlKey) || e.shiftKey || e.altKey || e.key.toLowerCase() !== 'n' || e.repeat) return
      if (document.querySelector('.MuiModal-root:not(.MuiModal-hidden), [role="dialog"], [role="listbox"], [role="menu"]')) return
      e.preventDefault() // the browser would open a new window of its own
      if (mayRef.current) startRef.current()
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [])

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <ToolbarPortal>
        <LoanMenu onNew={start} mayNew={mayStart} filter={filter} onFilter={setFilter} counts={counts}
          onLoanSetup={me.admin ? () => { void openLoanSetupWindow('types') } : undefined} />
      </ToolbarPortal>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 1.5, flexWrap: 'wrap' }}>
          <Box sx={{ flex: 1, minWidth: 240 }}>
            <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em' }}>Loan Module</Typography>
            <Typography color="text.secondary" sx={{ fontSize: 13 }}>Start a loan application and take it through its steps up to approval.</Typography>
          </Box>
          <Button variant="contained" startIcon={<FiFilePlus />} disabled={!mayStart} onClick={start} aria-keyshortcuts={isMac ? 'Meta+N' : 'Control+N'}
            title={!mayStart ? 'Your account may not do the Profiling step.' : `New loan application (${NEW_LOAN_SHORTCUT})`}>
            New loan application
            <Box component="kbd" aria-hidden sx={{ ml: 1, px: 0.6, borderRadius: '4px', fontSize: 11, fontFamily: 'inherit', bgcolor: 'rgba(255,255,255,0.22)' }}>{NEW_LOAN_SHORTCUT}</Box>
          </Button>
        </Box>

        <Box component="ol" aria-label="Loan steps" sx={{ listStyle: 'none', p: 0, m: 0, mb: 2, display: 'grid', gap: 1, gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', md: 'repeat(5, 1fr)' } }}>
          {STEPS.map(([n, name, what]) => (
            <Box component="li" key={n} sx={{ border: 1, borderColor: 'divider', borderRadius: '8px', p: 1.25 }}>
              <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{n} · {name}</Typography>
              <Typography color="text.secondary" sx={{ fontSize: 12 }}>{what}</Typography>
            </Box>
          ))}
          <Box component="li" sx={{ border: 1, borderColor: 'divider', borderStyle: 'dashed', borderRadius: '8px', p: 1.25 }}>
            <Typography sx={{ fontSize: 13, fontWeight: 600 }}>5 · Loan Release</Typography>
            <Typography color="text.secondary" sx={{ fontSize: 12 }}>In Front Office &gt; Disburse, by those allowed to release money (the Cash Voucher right).</Typography>
          </Box>
        </Box>

        {!anyStep && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Your account has none of the loan application steps. An administrator can give them in Settings &gt; Users: open your user, tab Receivables, Loan process.
          </Alert>
        )}
        {anyStep && <LoanApplicationsPanel full filter={filter} onShowAll={() => setFilter('all')} />}
      </Box>
      {inline && <LoanApplicationDialog onClose={() => setInline(false)} />}
    </Box>
  )
}
