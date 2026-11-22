import { Alert, Box, Button, Checkbox, Chip, FormControlLabel, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { DatabaseChangeNotice } from './DatabaseChangeNotice'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiAlertTriangle, FiCheckCircle, FiInfo, FiXCircle } from 'react-icons/fi'
import { checkStartDay, endDay, getBusinessDay, getDayJobs, getDrawerOpen, reopenDay, runDayJobs, setDrawerOpenPolicy, startDay, type BusinessDayState, type DayCheck, type DayJobs } from './api'
import { useMe } from './session'
import { listTable, PageTitle, SectionLabel, Surface } from './appleUi'
import { appBackground } from './glass'
import { ConfirmButton, ReasonDialog } from './ui/dialogs'
import { LoadingBlock, LoadingRow } from './ui/Loading'

/** At least this many letters for a reason (closing a day that failed a check, reopening one); the server checks the same. */
const MIN_REASON = 10

const plusDays = (iso: string, n: number) => {
  const d = new Date(`${iso}T00:00:00`)
  d.setDate(d.getDate() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** One End Day check: a check mark, a cross for one that must pass, or a warning sign for one to review; its label and what was found. */
function CheckRow({ c }: { c: DayCheck }) {
  const icon = c.ok ? <FiCheckCircle /> : c.mustPass ? <FiXCircle /> : <FiAlertTriangle />
  const color = c.ok ? 'success.main' : c.mustPass ? 'error.main' : 'warning.main'
  return (
    <Box component="li" aria-label={`${c.label}: ${c.ok ? 'passed' : c.mustPass ? 'failed' : 'to review'}`}
      sx={{ display: 'flex', gap: 1.25, alignItems: 'flex-start', py: 1, px: 1.5, borderTop: 1, borderColor: 'divider', '&:first-of-type': { borderTop: 0 } }}>
      <Box component="span" aria-hidden sx={{ color, fontSize: 17, mt: '1px', flex: 'none' }}>{icon}</Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
          {c.label}{!c.ok && !c.mustPass && <Typography component="span" sx={{ fontSize: 12, fontWeight: 400, ml: 1 }} color="text.secondary">to review</Typography>}
        </Typography>
        {c.detail && <Typography sx={{ fontSize: 12 }} color="text.secondary">{c.detail}</Typography>}
      </Box>
    </Box>
  )
}

function StartPanel({ s }: { s: BusinessDayState }) {
  const qc = useQueryClient()
  const [date, setDate] = useState(s.suggested ?? s.calendarDate)
  const [confirmed, setConfirmed] = useState(false)
  const check = useQuery({ queryKey: ['business-day-start-check', date], queryFn: () => checkStartDay(date), enabled: !!date, retry: false })
  const start = useMutation({
    meta: { done: (r: unknown) => `Business day ${(r as BusinessDayState).open?.words ?? ''} started. Postings now carry this date.` },
    mutationFn: () => startDay(date, confirmed),
    onSuccess: (r) => { qc.setQueryData(['business-day'], r); void qc.invalidateQueries({ queryKey: ['business-day-status'] }) },
  })
  const warnings = check.data?.warnings ?? []
  const problem = check.data?.problem
  const ready = !!date && !!check.data && !problem && (warnings.length === 0 || confirmed)
  const min = s.lastClosed ? plusDays(s.lastClosed.date, 1) : undefined
  return (
    <Box sx={{ p: 2 }}>
      <Typography sx={{ fontSize: 13, mb: 1.5 }}>
        Opening a day lets the branch post again. Everything posted until End Day carries the date you open, even after midnight.
      </Typography>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ alignItems: { sm: 'center' }, mb: 1.5 }}>
        <TextField type="date" label="Business date" value={date} onChange={(e) => { setDate(e.target.value); setConfirmed(false) }} sx={{ width: 200 }}
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { min, max: s.calendarDate } }}
          helperText={s.suggested === date ? 'Suggested: the latest working day up to today' : ' '} />
        <ConfirmButton variant="contained" disabled={!ready || start.isPending} title="Start the business day"
          message={<>Open <b>{check.data && !problem ? date : ''}</b> for the branch. From now until End Day, every deposit, withdrawal, payment and voucher is dated on it.</>}
          onConfirm={() => start.mutate()}>
          Start Day
        </ConfirmButton>
      </Stack>
      {check.error && <Alert severity="error" sx={{ mb: 1 }}>{(check.error as Error).message}</Alert>}
      {problem && <Alert severity="error" sx={{ mb: 1 }}>{problem}</Alert>}
      {warnings.length > 0 && (
        <Alert severity="warning" sx={{ mb: 1 }}>
          <Box component="ul" sx={{ m: 0, pl: 2 }}>{warnings.map((w) => <li key={w}>{w}</li>)}</Box>
          <FormControlLabel sx={{ mt: 0.5 }} control={<Checkbox size="small" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />}
            label={<Typography sx={{ fontSize: 13 }}>I have checked this; open the day anyway.</Typography>} />
        </Alert>
      )}
      {start.error && <Alert severity="error">{(start.error as Error).message}</Alert>}
    </Box>
  )
}

function EndPanel({ s }: { s: BusinessDayState }) {
  const qc = useQueryClient()
  const [reason, setReason] = useState('')
  const failing = s.checks.filter((c) => c.mustPass && !c.ok)
  const end = useMutation({
    meta: { done: () => `Business day ${s.open?.words ?? ''} ended. Nothing more can be posted until the next day is started.` },
    mutationFn: () => endDay(failing.length > 0 ? reason.trim() : null),
    onSuccess: (r) => { qc.setQueryData(['business-day'], r); setReason(''); void qc.invalidateQueries({ queryKey: ['business-day-status'] }) },
  })
  const ready = failing.length === 0 || reason.trim().length >= MIN_REASON
  return (
    <Box sx={{ p: 2 }}>
      {failing.length === 0
        ? <Alert severity="success" sx={{ mb: 1.5 }}>Every check that must pass passes. The day can be ended.</Alert>
        : (
          <>
            <Alert severity="error" sx={{ mb: 1.5 }}>
              {failing.length === 1 ? 'One check that must pass does not.' : `${failing.length} checks that must pass do not.`} Settle {failing.length === 1 ? 'it' : 'them'} first,
              or give the reason for ending the day anyway. The reason and the failed checks are kept with the day and in the audit log.
            </Alert>
            <TextField label="Reason for ending the day anyway" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth multiline minRows={2} sx={{ mb: 1.5 }}
              helperText={reason.trim().length < MIN_REASON ? `At least ${MIN_REASON} letters.` : ' '} slotProps={{ htmlInput: { maxLength: 300 } }} />
          </>
        )}
      <ConfirmButton variant="contained" color={failing.length > 0 ? 'warning' : 'primary'} disabled={!ready || end.isPending} title="End the business day"
        message={<>Close <b>{s.open?.words}</b>. Nothing more can be posted on it, and nothing can be posted at all until the next day is started. Only an administrator can reopen it.{failing.length > 0 && <> It closes with {failing.length === 1 ? 'a check' : 'checks'} failing, for the reason given.</>}</>}
        onConfirm={() => end.mutate()}>
        {end.isPending ? 'Running the jobs and ending…' : 'End Day'}
      </ConfirmButton>
      {end.error && <Alert severity="error" sx={{ mt: 1.5 }}>{(end.error as Error).message}</Alert>}
    </Box>
  )
}

/**
 * The end-of-day jobs of the open day (balance snapshot, loan aging and penalties, time deposit maturities, interest accruals due, cheques past their
 * clearing day). End Day runs them by itself before closing; they can be run earlier here. A step that failed is tried again on the next run; the
 * steps that read the day are done again at End Day so they see the whole day.
 */
function JobsPanel({ s }: { s: BusinessDayState }) {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['business-day-jobs', s.open?.date], queryFn: () => getDayJobs(s.open?.date), retry: false })
  const run = useMutation({
    meta: { done: (r: unknown) => ((r as DayJobs).status === 'FAILED' ? 'The end-of-day jobs ran; a step failed and is tried again on the next run.' : 'The end-of-day jobs ran.') },
    mutationFn: runDayJobs,
    onSuccess: (r) => { qc.setQueryData(['business-day-jobs', s.open?.date], r); void qc.invalidateQueries({ queryKey: ['business-day'] }) },
  })
  const j = q.data
  if (q.isLoading || !j) return <LoadingBlock q={q} what="the end-of-day jobs" />
  if (!j.available) return <DatabaseChangeNotice>The end-of-day jobs are not set up in this database yet.</DatabaseChangeNotice>
  return (
    <Box>
      <Box component="ul" aria-label="End-of-day jobs" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {j.steps.map((st) => {
          const done = st.status === 'DONE', failed = st.status === 'FAILED'
          return (
            <Box component="li" key={st.key} aria-label={`${st.label}: ${st.status.toLowerCase()}`}
              sx={{ display: 'flex', gap: 1.25, alignItems: 'flex-start', py: 1, px: 1.5, borderTop: 1, borderColor: 'divider', '&:first-of-type': { borderTop: 0 } }}>
              <Box component="span" aria-hidden sx={{ color: done ? 'success.main' : failed ? 'error.main' : 'text.secondary', fontSize: 17, mt: '1px', flex: 'none' }}>
                {done ? <FiCheckCircle /> : failed ? <FiXCircle /> : <FiInfo />}
              </Box>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
                  {st.label}
                  <Typography component="span" sx={{ fontSize: 12, fontWeight: 400, ml: 1 }} color="text.secondary">
                    {done ? `done ${st.finishedAt ?? ''}` : failed ? `failed (${st.attempts} ${st.attempts === 1 ? 'try' : 'tries'})` : 'not run yet'}
                  </Typography>
                </Typography>
                {(st.error ?? st.result) && <Typography sx={{ fontSize: 12 }} color={failed ? 'error' : 'text.secondary'}>{st.error ?? st.result}</Typography>}
              </Box>
            </Box>
          )
        })}
      </Box>
      <Box sx={{ px: 2, pb: 1.5, pt: 0.5, display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
        {s.mayEnd && <Button size="small" variant="outlined" disabled={run.isPending} onClick={() => run.mutate()}>{run.isPending ? 'Running… (this can take a minute)' : 'Run the jobs now'}</Button>}
        <Typography sx={{ fontSize: 12 }} color="text.secondary">End Day runs them by itself before it closes the day. Nothing is posted by them except the interest runs that fall due, which wait for approval.</Typography>
      </Box>
      {run.error && <Alert severity="error" sx={{ mx: 2, mb: 1.5 }}>{(run.error as Error).message}</Alert>}
    </Box>
  )
}

/** Whether tellers and cashiers must count their drawer at the start of the day before taking or paying out cash (administrators change it). */
function DrawerRule() {
  const qc = useQueryClient()
  const me = useMe()
  const admin = me.admin
  const q = useQuery({ queryKey: ['drawer-open'], queryFn: getDrawerOpen, retry: false })
  const [asking, setAsking] = useState<boolean | null>(null)
  const policyOn = q.data?.policyOn
  if (!q.data?.available && !admin) return null
  return (
    <Box sx={{ p: 2, display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
      <Box sx={{ flex: 1, minWidth: 240 }}>
        <Typography sx={{ fontSize: 13, fontWeight: 600 }}>Open the drawer before cash</Typography>
        <Typography sx={{ fontSize: 12 }} color="text.secondary">
          {policyOn === false ? 'Off: tellers may take and pay out cash without counting their drawer first.'
            : 'On: each teller and cashier counts their drawer once at the start of the business day (Cash Handover > Open my drawer) before any cash crosses the window.'}
        </Typography>
      </Box>
      {admin && policyOn !== undefined && (
        <FormControlLabel control={<Switch checked={policyOn} onChange={(e) => setAsking(e.target.checked)} />} label={<Typography sx={{ fontSize: 13 }}>{policyOn ? 'On' : 'Off'}</Typography>} />
      )}
      {asking !== null && (
        <ReasonDialog title={asking ? 'Require opening the drawer' : 'Stop requiring the drawer count'} label="Why (kept in the audit log)" onClose={() => setAsking(null)}
          onRun={(reason) => setDrawerOpenPolicy(asking, reason).then(() => { void qc.invalidateQueries({ queryKey: ['drawer-open'] }) })} />
      )}
    </Box>
  )
}

/**
 * Front Office > Business Day: Start Day and End Day, the way core banking systems run the day. Postings carry the branch's open business date and are
 * refused while none is open. End Day checks that every teller who handled cash closed the drawer, no hand-off is waiting and the day balances; a day
 * can be ended with a failed check only with a reason. An administrator may reopen the last closed day. The rights are Start Day and End Day in
 * Settings > Users > Transactions.
 */
export default function BusinessDayPage() {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['business-day'], queryFn: getBusinessDay, refetchInterval: 30_000, retry: false })
  const [reopening, setReopening] = useState(false)
  const s = q.data
  const behind = s?.open && s.open.date !== s.calendarDate
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 900, mx: 'auto' }}>
        <PageTitle title="Business Day">
          The branch posts on one business day at a time. Start Day opens it in the morning; End Day checks the drawers and the books and closes it.
          While no day is open, nothing can be posted.
        </PageTitle>
        <LoadingBlock q={q} what="the business day" />
        {s && !s.available && (
          <DatabaseChangeNotice>The business day is not set up in this database yet; until it is, postings carry the calendar date.</DatabaseChangeNotice>
        )}
        {s && s.available && (
          <Stack spacing={2.5}>
            <Box>
              <SectionLabel>Today</SectionLabel>
              <Surface label="Business day status">
                <Box sx={{ p: 2, display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
                  <Box sx={{ flex: 1, minWidth: 220 }}>
                    <Typography sx={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.01em' }}>{s.open ? s.open.words : 'No business day is open'}</Typography>
                    <Typography sx={{ fontSize: 13 }} color="text.secondary">
                      {s.open ? `Started by ${s.open.openedBy} at ${s.open.openedAt}.`
                        : s.lastClosed ? `The last day, ${s.lastClosed.words}, was ended by ${s.lastClosed.closedBy} at ${s.lastClosed.closedAt}.` : 'No business day has been started yet.'}
                    </Typography>
                  </Box>
                  <Chip color={s.open ? 'success' : 'default'} label={s.open ? 'Open for posting' : 'Posting closed'} />
                </Box>
                {behind && (
                  <Alert severity="warning" icon={<FiInfo />} sx={{ mx: 2, mb: 2 }}>
                    The open business day is not today ({s.calendarDate}). Postings are still dated {s.open!.words}. End it and start today when that day's work is done.
                  </Alert>
                )}
                {!s.inUse && <Alert severity="info" sx={{ mx: 2, mb: 2 }}>This server does not require a business day; postings carry the calendar date.</Alert>}
              </Surface>
            </Box>

            {!s.open && (
              <Box>
                <SectionLabel>Start Day</SectionLabel>
                <Surface label="Start Day">
                  {s.mayStart ? <StartPanel key={s.suggested ?? ''} s={s} />
                    : <Typography sx={{ p: 2, fontSize: 13 }} color="text.secondary">Your account does not have the Start Day right. An administrator gives it in Settings &gt; Users, tab Transactions, Business day.</Typography>}
                </Surface>
              </Box>
            )}

            {s.open && (
              <Box>
                <SectionLabel>End Day checks</SectionLabel>
                <Surface label="End Day checks">
                  <Box component="ul" aria-label="End Day checks" sx={{ listStyle: 'none', m: 0, p: 0 }}>
                    {s.checks.map((c) => <CheckRow key={c.key} c={c} />)}
                  </Box>
                  <Box sx={{ px: 2, pb: 1 }}>
                    <Button size="small" onClick={() => void qc.invalidateQueries({ queryKey: ['business-day'] })} disabled={q.isFetching}>{q.isFetching ? 'Checking…' : 'Check again'}</Button>
                  </Box>
                </Surface>
                <Box sx={{ mt: 2.5 }}>
                  <SectionLabel>End-of-day jobs</SectionLabel>
                  <Surface label="End-of-day jobs"><JobsPanel s={s} /></Surface>
                </Box>
                <Box sx={{ mt: 2.5 }}>
                  <SectionLabel>End Day</SectionLabel>
                  <Surface label="End Day">
                    {s.mayEnd ? <EndPanel s={s} />
                      : <Typography sx={{ p: 2, fontSize: 13 }} color="text.secondary">Your account does not have the End Day right. An administrator gives it in Settings &gt; Users, tab Transactions, Business day.</Typography>}
                  </Surface>
                </Box>
              </Box>
            )}

            <Box>
              <SectionLabel>Rules</SectionLabel>
              <Surface label="Business day rules"><DrawerRule /></Surface>
            </Box>

            <Box>
              <SectionLabel>Recent days</SectionLabel>
              <Surface label="Recent business days">
                <Table size="small" aria-label="Recent business days" sx={listTable}>
                  <TableHead><TableRow><TableCell>Business day</TableCell><TableCell>Started</TableCell><TableCell>Ended</TableCell><TableCell>Note</TableCell></TableRow></TableHead>
                  <TableBody>
                    <LoadingRow q={q} what="the days" />
                    {s.recent.length === 0 && <TableRow><TableCell colSpan={4}><Typography sx={{ fontSize: 13 }} color="text.secondary">No business day yet.</Typography></TableCell></TableRow>}
                    {s.recent.map((d) => (
                      <TableRow key={d.date}>
                        <TableCell>{d.words}</TableCell>
                        <TableCell>{d.openedBy}<Typography sx={{ fontSize: 12 }} color="text.secondary">{d.openedAt}</Typography></TableCell>
                        <TableCell>{d.status === 'OPEN' ? <Chip size="small" color="success" variant="outlined" label="Open" /> : <>{d.closedBy}<Typography sx={{ fontSize: 12 }} color="text.secondary">{d.closedAt}</Typography></>}</TableCell>
                        <TableCell sx={{ maxWidth: 320 }}>
                          {d.overrideReason
                            ? <><Typography sx={{ fontSize: 13, color: 'warning.main', fontWeight: 600 }}>Ended with checks failing</Typography><Typography sx={{ fontSize: 12 }}>{d.overrideReason}</Typography><Typography sx={{ fontSize: 12 }} color="text.secondary">{d.failedChecks}</Typography></>
                            : d.status === 'CLOSED' ? <Typography sx={{ fontSize: 12 }} color="text.secondary">All checks passed</Typography> : null}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </Surface>
              <Stack direction="row" spacing={1.5} sx={{ mt: 1.5, alignItems: 'center', flexWrap: 'wrap' }}>
                {s.mayReopen && <Button color="warning" onClick={() => setReopening(true)}>Reopen {s.lastClosed?.words}</Button>}
                {s.legacyDate && <Typography sx={{ fontSize: 12 }} color="text.secondary">The old system's own business date was {s.legacyDate}; it is not changed from here.</Typography>}
              </Stack>
            </Box>
          </Stack>
        )}
      </Box>
      {reopening && s?.lastClosed && (
        <ReasonDialog title={`Reopen ${s.lastClosed.words}`} label={`Why it is reopened (at least ${MIN_REASON} letters)`} onClose={() => setReopening(false)}
          onRun={(reason) => reopenDay(reason).then((r) => { qc.setQueryData(['business-day'], r); void qc.invalidateQueries({ queryKey: ['business-day-status'] }) })} />
      )}
    </Box>
  )
}
