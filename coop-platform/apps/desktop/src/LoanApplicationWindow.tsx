import { Alert, Box, Button, Chip, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import {
  approveLoanApplication, approveLoanRelease, cancelLoanApplication, disapproveLoanApplication, getLoanApplication, getLoanRelease, postLoanRelease, verifyLoanApplication,
  type LoanApplicationDetail,
} from './api'
import { announceLoanApplicationSaved, closeThisWindow, FORM_SIZE, resizeThisWindow, SEARCH_SIZE } from './detachedWindow'
import { appear } from './motion'
import { money } from './FoTransactionForm'
import LoanApplicationDialog from './LoanApplicationDialog'
import LoanApplicantCard from './LoanApplicantCard'
import LoanStepper from './LoanStepper'


const STEPS = [{ label: 'Profiling' }, { label: 'Deduction' }, { label: 'Verification' }, { label: 'Approval' }, { label: 'Loan Release' }]
const NEW = 1, VERIFIED = 2, APPROVED = 3, DISAPPROVED = 4, CANCELLED = 5

const cell = { fontVariantNumeric: 'tabular-nums' } as const

function Lines({ d }: { d: LoanApplicationDetail }) {
  return (
    <Table size="small" aria-label="Release voucher preview">
      <TableHead><TableRow><TableCell>Line</TableCell><TableCell>Account</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
      <TableBody>
        {d.lines.map((l, i) => (
          <TableRow key={i}>
            <TableCell>{l.description}</TableCell><TableCell>{l.account ?? '—'}</TableCell>
            <TableCell align="right" sx={cell}>{l.amount > 0 ? money(l.amount) : ''}</TableCell>
            <TableCell align="right" sx={cell}>{l.amount < 0 ? money(-l.amount) : ''}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

function Summary({ d }: { d: LoanApplicationDetail }) {
  const a = d.item
  return (
    <Box sx={{ mb: 1.5 }}>
      <Typography sx={{ fontSize: 18, fontWeight: 700, letterSpacing: '-0.01em' }}>{a.clientName ?? `Client ${a.clientId}`} · {money(a.amount)}</Typography>
      <Typography sx={{ fontSize: 13 }} color="text.secondary">
        {a.documentNo} · {a.typeName} · {a.terms} months · loan ref {a.refNo} · encoded by {a.encodedByName ?? '—'} on {a.date}
      </Typography>
      {d.coMakerHolds.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography sx={{ fontSize: 11.5, fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase' }} color="text.secondary">Co-maker holds</Typography>
          {d.coMakerHolds.map((h) => (
            <Typography key={h.clientId} sx={{ fontSize: 13, ...cell }}>
              {h.name ?? `Client ${h.clientId}`} · {money(h.amount)} on {h.accountType ?? h.slt} {h.refNo} · {h.placed ? 'held' : 'held when the release is posted'}
            </Typography>
          ))}
        </Box>
      )}
    </Box>
  )
}

/** One step: a heading that says what this person is deciding, the content, and the actions at the foot. */
function StepPage({ title, hint, children, actions }: { title: string; hint: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <Box sx={{ flex: 1, overflow: 'auto', px: 3, py: 1 }}>
        <Typography component="h2" sx={{ fontSize: 20, fontWeight: 600, letterSpacing: '-0.01em' }}>{title}</Typography>
        <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">{hint}</Typography>
        {children}
      </Box>
      {actions && <Box sx={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 1, p: 2, flexWrap: 'wrap' }}>{actions}</Box>}
    </Box>
  )
}

/**
 * The loan application as five steps in one window: Profiling (the form), Deduction (the net-proceeds breakdown, entered in
 * Profiling and reviewed here), Verification, Approval and Loan Release. The step in hand comes from the application's status, so opening an application
 * later resumes where it stopped. The server still decides who may do each step; a refusal is shown as it comes.
 */
export default function LoanApplicationWindow() {
  const qc = useQueryClient()
  const q = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  const num = (k: string) => (q.has(k) ? Number(q.get(k)) : undefined)
  const opened = num('a')
  const [appId, setAppId] = useState<number | undefined>(opened)
  const [viewStep, setViewStep] = useState<number | null>(null)
  // Until the application is saved, the form is in two parts (the loan, then its deductions); saving is what completes the Deduction step.
  const [profiled, setProfiled] = useState(false)
  // Changing the member remounts the form, which clears it, as picking a different member does.
  const [formKey, setFormKey] = useState(0)
  const [picked, setPicked] = useState<{ branchCode: number; clientId: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [ending, setEnding] = useState<'cancel' | 'disapprove' | null>(null)
  const [reason, setReason] = useState('')
  const close = () => { void closeThisWindow() }

  const detail = useQuery({ queryKey: ['loan-application', appId], queryFn: () => getLoanApplication(appId!), enabled: appId != null, retry: false })
  const d = detail.data
  const status = d?.item.status
  const vYear = status === APPROVED ? d?.item.voucherYear : null
  const vCtl = status === APPROVED ? d?.item.voucherControlNo : null
  const voucher = useQuery({ queryKey: ['loan-release', vYear, vCtl], queryFn: () => getLoanRelease(vYear!, vCtl!), enabled: vYear != null && vCtl != null, retry: false })
  const released = voucher.data?.voucher.status === 'POSTED'

  const stopped = status === DISAPPROVED ? 'Disapproved' : status === CANCELLED ? 'Cancelled' : undefined
  const current = appId == null ? (profiled ? 1 : 0) : status == null || stopped ? 1 : status === NEW ? 2 : status === VERIFIED ? 3 : released ? 5 : 4
  const applicant = d ? { branchCode: d.item.branchCode, clientId: d.item.clientId } : picked
  // The member can be changed only while the application is unsaved and the member was not fixed by the address.
  const canChangeMember = appId == null && num('c') == null
  // Small while it is only a member search; large once there is a loan to profile.
  const hasApplicant = applicant != null
  useEffect(() => { void resizeThisWindow(hasApplicant ? FORM_SIZE : SEARCH_SIZE) }, [hasApplicant])
  const viewing = viewStep ?? Math.min(current, 4)

  const refresh = () => {
    for (const k of ['loan-application', 'loan-applications', 'loan-release', 'loan-releases', 'member-loans']) void qc.invalidateQueries({ queryKey: [k] })
    announceLoanApplicationSaved()
  }
  const done = () => { setError(null); setViewStep(null); setEnding(null); setReason(''); refresh() }
  const fail = (e: unknown) => setError((e as Error).message)
  const verify = useMutation({ mutationFn: () => verifyLoanApplication(appId!), onSuccess: done, onError: fail })
  const approve = useMutation({ mutationFn: () => approveLoanApplication(appId!), onSuccess: done, onError: fail })
  const end = useMutation({
    mutationFn: () => (ending === 'cancel' ? cancelLoanApplication : disapproveLoanApplication)(appId!, reason.trim()),
    onSuccess: done, onError: fail,
  })
  const release = useMutation({
    mutationFn: (kind: 'approve' | 'post') => (kind === 'approve' ? approveLoanRelease : postLoanRelease)(vYear!, vCtl!),
    onSuccess: done, onError: fail,
  })
  const busy = verify.isPending || approve.isPending || end.isPending || release.isPending

  const canEnd = status === NEW || status === VERIFIED || status === APPROVED
  const endRow = canEnd && (
    <>
      {ending && <TextField size="small" label={ending === 'cancel' ? 'Reason for cancelling' : 'Reason for disapproving'} value={reason} onChange={(e) => setReason(e.target.value)} autoFocus sx={{ minWidth: 240 }} />}
      {ending && <Button color="error" variant="outlined" disabled={busy || !reason.trim()} onClick={() => end.mutate()}>{ending === 'cancel' ? 'Confirm cancel' : 'Confirm disapprove'}</Button>}
      {ending && <Button color="inherit" onClick={() => { setEnding(null); setReason('') }}>Back</Button>}
      {!ending && status !== APPROVED && <Button color="error" disabled={busy} onClick={() => setEnding('disapprove')}>Disapprove</Button>}
      {!ending && <Button color="error" disabled={busy} onClick={() => setEnding('cancel')}>Cancel application</Button>}
      <Box sx={{ flex: 1 }} />
    </>
  )
  const problems = d?.problems.map((p) => <Alert key={p} severity="warning" sx={{ mb: 1 }}>{p}</Alert>)
  const needDetail = (render: (x: LoanApplicationDetail) => ReactNode) => detail.isLoading ? <Skeleton variant="rounded" height={160} />
    : detail.error ? <Alert severity="info">{(detail.error as Error).message}</Alert> : d ? render(d) : null
  const errorAlert = error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>

  let page: ReactNode = null
  if (appId == null) {
    page = null // the form itself is shown, in two parts
  } else if (viewing === 2) {
    page = (
      <StepPage title="Verification" hint="Check the application, the member's record and documents, and the figures from the Deduction step."
        actions={<>{endRow}{status === NEW && <Button variant="contained" disabled={busy} onClick={() => verify.mutate()}>Verify</Button>}
          {status != null && status !== NEW && !stopped && <Chip color="success" variant="outlined" label="Verified" />}</>}>
        {errorAlert}{needDetail((x) => <><Summary d={x} />{problems}<Lines d={x} /></>)}
      </StepPage>
    )
  } else if (viewing === 1) {
    page = (
      <StepPage title="Deduction" hint="Fees, share capital and pay-offs come out of the proceeds. They were entered with the application; this is the net the member receives."
        actions={<>{endRow}{!stopped && <Chip color="success" variant="outlined" label="Saved with the application" />}</>}>
        {errorAlert}{needDetail((x) => (
          <>
            <Summary d={x} />{problems}<Lines d={x} />
            <Typography sx={{ mt: 1.5, ...cell }}>Deductions {money(x.totalDeductions)} · Net proceeds <strong>{money(x.netProceeds)}</strong></Typography>
            <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">To change a deduction, disapprove or cancel this application and encode it again.</Typography>
          </>
        ))}
      </StepPage>
    )
  } else if (viewing === 3) {
    page = (
      <StepPage title="Approval" hint="Approving creates the release voucher. The encoder cannot approve, and the verifier cannot approve unless an administrator allowed it."
        actions={<>{endRow}{(status === NEW || status === VERIFIED) && <Button variant="contained" disabled={busy || !!d?.problems.length} onClick={() => approve.mutate()}>Approve</Button>}
          {status === APPROVED && <Chip color="success" variant="outlined" label="Approved" />}</>}>
        {errorAlert}{needDetail((x) => (
          <>
            <Summary d={x} />{problems}
            <Typography sx={cell}>Net proceeds <strong>{money(x.netProceeds)}</strong> after deductions of {money(x.totalDeductions)}</Typography>
          </>
        ))}
      </StepPage>
    )
  } else if (viewing === 4) {
    const v = voucher.data?.voucher
    page = (
      <StepPage title="Loan Release" hint="The voucher is approved by a second person and posted by a third. Posting writes the loan ledger entries dated today."
        actions={<>{endRow}
          {v?.status === 'WAITING FOR APPROVAL' && <Button variant="contained" disabled={busy || !!voucher.data?.problems.length} onClick={() => release.mutate('approve')}>Approve voucher</Button>}
          {v?.status === 'APPROVED' && <Button variant="contained" color="success" disabled={busy || !!voucher.data?.problems.length} onClick={() => release.mutate('post')}>Post release</Button>}
          {v?.status === 'POSTED' && <Chip color="success" label="Released" />}</>}>
        {errorAlert}
        {status !== APPROVED && <Alert severity="info">The application must be approved before it can be released.</Alert>}
        {voucher.isLoading && <Skeleton variant="rounded" height={160} />}
        {v && (
          <>
            <Typography sx={{ fontSize: 13, mb: 1 }} color="text.secondary">
              Voucher {v.documentNo} · {v.clientName ?? `Client ${v.clientId}`} · dated {v.date} · {v.status === 'WAITING FOR APPROVAL' ? 'waiting for approval' : v.status.toLowerCase()}{v.approvedByName ? ` · approved by ${v.approvedByName}` : ''}
            </Typography>
            {voucher.data?.problems.map((p) => <Alert key={p} severity="warning" sx={{ mb: 1 }}>{p}</Alert>)}
            <Table size="small">
              <TableHead><TableRow><TableCell>Account</TableCell><TableCell>Ledger</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
              <TableBody>
                {voucher.data?.lines.map((l) => (
                  <TableRow key={l.seq}>
                    <TableCell>{l.account}</TableCell><TableCell>{l.slc ? `${l.slc}/${l.slt}/${l.refNo} (event ${l.event})` : '—'}</TableCell>
                    <TableCell align="right" sx={cell}>{l.amount > 0 ? money(l.amount) : ''}</TableCell>
                    <TableCell align="right" sx={cell}>{l.amount < 0 ? money(-l.amount) : ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </>
        )}
      </StepPage>
    )
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100dvh' }}>
      {applicant && (
        <Box sx={{ ...appear, px: 3, pt: 2, display: 'grid', gridTemplateColumns: canChangeMember ? '3fr 1fr' : '1fr', gap: 1.5, alignItems: 'stretch' }}>
          <LoanApplicantCard key={`${applicant.branchCode}-${applicant.clientId}`} id={applicant} />
          {canChangeMember && (
            <Box sx={{ mb: 1.5, display: 'flex' }}>
              <Button variant="outlined" fullWidth onClick={() => { setFormKey((k) => k + 1); setPicked(null); setProfiled(false); setViewStep(null) }}>Change member</Button>
            </Box>
          )}
        </Box>
      )}
      {/* The member search comes first and has no steps; the indicator appears once there is an applicant. */}
      {applicant && <Box sx={appear}><LoanStepper steps={STEPS} current={current} viewing={viewing} onView={setViewStep} stopped={stopped} /></Box>}
      {stopped && <Alert severity="warning" sx={{ mx: 3, mb: 1 }}>This application was {stopped.toLowerCase()}. Nothing further can be done with it.</Alert>}
      <Box sx={{ flex: 1, minHeight: 0 }}>
        {/* Profiling stays mounted while another step is looked at, so a form that was just saved is not lost. */}
        {opened == null ? (
          <Box sx={{ height: '100%', display: viewing <= (appId == null ? 1 : 0) ? 'block' : 'none' }}>
            <LoanApplicationDialog key={formKey} embedded part={viewing === 1 ? 'deduction' : 'profile'} onNext={() => { setProfiled(true); setViewStep(null) }} onBack={() => setViewStep(0)} onApplicant={setPicked} branchCode={num('b')} clientId={num('c')} typeCode={num('t')} onClose={close}
              onSaved={(r) => { setAppId(r.applicationId); setViewStep(null) }} />
          </Box>
        ) : viewing === 0 && (
          <StepPage title="Profiling" hint="The application as it was encoded." actions={<Button onClick={close}>Close window</Button>}>
            {needDetail((x) => <Summary d={x} />)}
          </StepPage>
        )}
        {page}
      </Box>
    </Box>
  )
}
