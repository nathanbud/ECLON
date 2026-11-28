import {
  Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Stack, Switch, TextField, Typography,
  Tooltip,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiAlertTriangle, FiCheckCircle, FiCircle, FiMinusCircle } from 'react-icons/fi'
import {
  getCloseReview, getCloseStatus, setClosePolicy, signOffMonth, tickCloseItem, type CloseItem, type CloseReview, type CloseScope,
} from './api'
import { ReasonDialog } from './appleUi'
import { glass } from './glass'
import { tint } from './theme'
import { notifyError } from './ui/notices'
import { BusyArea, LoadingBlock } from './ui/Loading'

const STATUS: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  OK: { label: 'Checked', color: tint.green[0], icon: <FiCheckCircle /> },
  DONE: { label: 'Done', color: tint.green[0], icon: <FiCheckCircle /> },
  EXPLAINED: { label: 'Accepted', color: tint.blue[0], icon: <FiCheckCircle /> },
  NOT_APPLICABLE: { label: 'Not needed', color: tint.gray[0], icon: <FiMinusCircle /> },
  ATTENTION: { label: 'Needs attention', color: tint.orange[0], icon: <FiAlertTriangle /> },
  OPEN: { label: 'To do', color: tint.gray[0], icon: <FiCircle /> },
}

const fmtAt = (at: string | null) => (at ? at.replace('T', ' ').slice(0, 16) : '')

/**
 * A month-end review or a year-end checklist: each item with what the application found, or confirmed by an accountant. An item the application finds
 * wanting is accepted only with an explanation (10 characters or more); every tick is kept in the audit log.
 */
export function Checklist({ branchCode, scope, period, canEdit }: { branchCode: number; scope: CloseScope; period: string; canEdit: boolean }) {
  const qc = useQueryClient()
  const key = ['close-review', branchCode, scope, period]
  const q = useQuery({ queryKey: key, queryFn: () => getCloseReview(branchCode, scope, period) })
  const [asking, setAsking] = useState<CloseItem | null>(null)
  const [note, setNote] = useState('')
  const tick = useMutation({
    onError: notifyError,
    mutationFn: ({ item, done, note: n }: { item: string; done: boolean; note?: string }) => tickCloseItem(branchCode, scope, period, item, done, n),
    onSuccess: (r) => { qc.setQueryData(key, r); qc.invalidateQueries({ queryKey: ['close-status'] }); setAsking(null); setNote('') },
  })
  const r = q.data
  const locked = !!r?.signedBy
  return (
    <Box>
      <LoadingBlock q={q} what="the checklist" />
      {r && (
        <BusyArea busy={q.isFetching && !q.isLoading} label="Checking the books">
          <Box sx={(t) => ({ ...glass(t), p: 0, overflow: 'hidden' })} role="list" aria-label={scope === 'month' ? `Review of ${period}` : `Year-end checklist ${period}`}>
            {r.items.map((i, n) => {
              const s = STATUS[i.status] ?? STATUS.OPEN
              const pending = i.status === 'OPEN' || i.status === 'ATTENTION'
              return (
                <Box key={i.key} role="listitem" sx={(t) => ({ display: 'flex', gap: 1.25, alignItems: 'flex-start', px: 1.75, py: 1.25, borderTop: n === 0 ? 0 : `1px solid ${t.palette.divider}` })}>
                  <Box aria-hidden sx={{ color: s.color, fontSize: 16, mt: '2px' }}>{s.icon}</Box>
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography variant="subtitle2" component="p">{i.label}</Typography>
                    <Typography variant="body2" color="text.secondary">{i.help}</Typography>
                    {i.detail && <Typography variant="body2" sx={{ color: i.status === 'ATTENTION' ? tint.orange[0] : 'text.secondary' }}>{i.detail}</Typography>}
                    {i.by && <Typography variant="body2" color="text.secondary">{i.automatic ? 'Accepted' : 'Done'} by {i.by}, {fmtAt(i.at)}{i.note ? `: “${i.note}”` : ''}</Typography>}
                  </Box>
                  <Typography variant="body2" sx={{ color: s.color, whiteSpace: 'nowrap', mt: '2px' }}>{s.label}</Typography>
                  {canEdit && !locked && (
                    pending
                      ? <Button size="small" onClick={() => { tick.reset(); setNote(''); setAsking(i) }}>{i.automatic ? 'Explain and accept' : 'Mark done'}</Button>
                      : i.by ? <Button size="small" color="inherit" disabled={tick.isPending} onClick={() => tick.mutate({ item: i.key, done: false })}>Undo</Button> : null
                  )}
                </Box>
              )
            })}
          </Box>
          <Typography variant="body2" sx={{ mt: 1 }} color={r.complete ? 'success.main' : 'text.secondary'} role="status">
            {r.complete ? 'Every item is done.' : `${r.open} item${r.open === 1 ? '' : 's'} still to do.`}
          </Typography>
        </BusyArea>
      )}
      <Dialog open={asking != null} onClose={tick.isPending ? undefined : () => setAsking(null)} fullWidth maxWidth="xs">
        <DialogTitle>{asking?.label}</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
          {asking?.detail && asking.automatic && <Alert severity="warning">{asking.detail}</Alert>}
          <TextField autoFocus multiline minRows={2} label={asking?.automatic ? 'Why it can be accepted' : 'Note (optional)'} value={note} onChange={(e) => setNote(e.target.value)}
            required={asking?.automatic} slotProps={{ htmlInput: { maxLength: 500 } }}
            helperText={asking?.automatic ? 'At least 10 characters; kept with the close and in the audit log.' : undefined} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAsking(null)} disabled={tick.isPending}>Cancel</Button>
          <Button variant="contained" disabled={tick.isPending || (!!asking?.automatic && note.trim().length < 10)}
            onClick={() => asking && tick.mutate({ item: asking.key, done: true, note: note.trim() || undefined })}>{asking?.automatic ? 'Accept' : 'Mark done'}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

/** The last twelve ended months, newest first. */
function pastMonths(): string[] {
  const now = new Date()
  return Array.from({ length: 12 }, (_, i) => {
    const d = new Date(now.getFullYear(), now.getMonth() - 1 - i, 1)
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  })
}

/** The month-end review: pick a month, work through its checklist, and sign it off. Signing off closes the month only when the policy requires monthly closing. */
export function MonthReview({ branchCode, canEdit }: { branchCode: number; canEdit: boolean }) {
  const qc = useQueryClient()
  const [months] = useState(pastMonths)
  const [month, setMonth] = useState(months[0])
  const [signing, setSigning] = useState(false)
  const [note, setNote] = useState('')
  const review = useQuery({ queryKey: ['close-review', branchCode, 'month', month], queryFn: () => getCloseReview(branchCode, 'month', month) })
  const sign = useMutation({
    onError: notifyError,
    meta: { done: `${month} was signed off.` },
    mutationFn: () => signOffMonth(branchCode, month, note.trim() || undefined),
    onSuccess: (r: CloseReview) => {
      qc.setQueryData(['close-review', branchCode, 'month', month], r); qc.invalidateQueries({ queryKey: ['close-status'] }); qc.invalidateQueries({ queryKey: ['periods'] })
      setSigning(false); setNote('')
    },
  })
  const r = review.data
  return (
    <Box>
      <Stack direction="row" spacing={2} useFlexGap sx={{ mb: 1.5, alignItems: 'center', flexWrap: 'wrap' }}>
        <TextField select size="small" label="Month" value={month} onChange={(e) => setMonth(e.target.value)} sx={{ width: 160 }}>
          {months.map((m) => <MenuItem key={m} value={m}>{m}</MenuItem>)}
        </TextField>
        <Typography variant="body2" sx={{ flex: 1, minWidth: 220 }} color="text.secondary">
          {r?.monthlyClose ? 'Monthly closing is required: signing a month off also closes its accounting period.' : 'Monthly closing is optional here: signing off records the review and leaves the month open.'}
        </Typography>
        {r?.signedBy
          ? <Typography sx={{ color: tint.green[0] }}>Signed off by {r.signedBy}, {fmtAt(r.signedAt)}</Typography>
          : canEdit && <Tooltip title={!r?.complete ? 'Finish every item first' : ''}><span><Button variant="contained" disabled={!r?.complete || !r.ended} onClick={() => { sign.reset(); setSigning(true) }}>Sign off {month}</Button></span></Tooltip>}
      </Stack>
      <Checklist key={month} branchCode={branchCode} scope="month" period={month} canEdit={canEdit} />
      <Dialog open={signing} onClose={sign.isPending ? undefined : () => setSigning(false)} fullWidth maxWidth="xs">
        <DialogTitle>Sign off {month}</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
          <Typography>{r?.monthlyClose ? 'The month’s accounting period will be closed: nothing more can be posted into it.' : 'The review is recorded with your name; the month stays open.'}</Typography>
          <TextField label="Note (optional)" value={note} onChange={(e) => setNote(e.target.value)} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 500 } }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSigning(false)} disabled={sign.isPending}>Cancel</Button>
          <Button variant="contained" disabled={sign.isPending} onClick={() => sign.mutate()}>Sign off</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

/** The closing policy: whether monthly closing is required, and when the year-end close is due. Administrators change it with a reason. */
export function ClosingPolicy({ branchCode, admin }: { branchCode: number; admin: boolean }) {
  const qc = useQueryClient()
  const status = useQuery({ queryKey: ['close-status', branchCode], queryFn: () => getCloseStatus(branchCode) })
  const [draft, setDraft] = useState<{ monthlyClose: boolean; yearEndDueDays: string } | null>(null)
  const [asking, setAsking] = useState(false)
  const save = useMutation({
    meta: { done: 'The closing policy was saved.' },
    mutationFn: (reason: string) => setClosePolicy({ monthlyClose: draft!.monthlyClose, yearEndDueDays: Number(draft!.yearEndDueDays), reason }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['close-status'] }); qc.invalidateQueries({ queryKey: ['close-review'] }); setDraft(null); setAsking(false) },
  })
  const p = status.data?.policy
  if (!p) return null
  const editing = draft ?? { monthlyClose: p.monthlyClose, yearEndDueDays: String(p.yearEndDueDays) }
  const changed = draft != null && (draft.monthlyClose !== p.monthlyClose || Number(draft.yearEndDueDays) !== p.yearEndDueDays)
  return (
    <Box sx={(t) => ({ ...glass(t), p: 1.5, mb: 2, display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' })}>
      <Typography variant="subtitle2" component="p">Closing policy</Typography>
      <FormControlLabel disabled={!admin} control={<Switch checked={editing.monthlyClose} onChange={(e) => setDraft({ ...editing, monthlyClose: e.target.checked })} />}
        label="Monthly closing required" />
      <TextField size="small" type="number" label="Year-end close due (days after 31 December)" value={editing.yearEndDueDays} disabled={!admin}
        onChange={(e) => setDraft({ ...editing, yearEndDueDays: e.target.value })} sx={{ width: 290 }} slotProps={{ htmlInput: { min: 1, max: 365 } }} />
      {admin && changed && <Button variant="contained" onClick={() => { save.reset(); setAsking(true) }}>Save</Button>}
      <ReasonDialog open={asking} title="Change the closing policy" confirm="Save" busy={save.isPending} error={save.error ? (save.error as Error).message : null}
        onCancel={() => setAsking(false)} onConfirm={(r) => save.mutate(r)} />
    </Box>
  )
}

/** A notice on Accounting when the year-end close is overdue, or months wait for their review while monthly closing is required. */
export function CloseStatusNotice({ branchCode }: { branchCode: number }) {
  const status = useQuery({ queryKey: ['close-status', branchCode], queryFn: () => getCloseStatus(branchCode), staleTime: 60_000 })
  const s = status.data
  if (!s) return null
  return (
    <>
      {s.yearToClose != null && (
        <Alert severity={s.yearOverdue ? 'error' : 'warning'} sx={{ mb: 1.5 }}>
          {s.yearOverdue
            ? `The year-end close of ${s.yearToClose} is overdue: it was due by ${s.yearDue}. Finish its checklist and close the year under Year-end close.`
            : `The books for ${s.yearToClose} are still open. The year-end close is due by ${s.yearDue}.`}
        </Alert>
      )}
      {s.monthsToReview.length > 0 && (
        <Alert severity="info" sx={{ mb: 1.5 }}>
          Monthly closing is required, and {s.monthsToReview.length === 1 ? `${s.monthsToReview[0]} is` : `${s.monthsToReview.length} months (${s.monthsToReview.join(', ')}) are`} not signed off yet.
        </Alert>
      )}
    </>
  )
}

/** The checklist of the year to close next (the year after the last closed one): the year-end close waits until every item is done. */
export function YearChecklist({ branchCode, canEdit }: { branchCode: number; canEdit: boolean }) {
  const status = useQuery({ queryKey: ['close-status', branchCode], queryFn: () => getCloseStatus(branchCode) })
  // last year, when no year was ever closed here
  const [lastYear] = useState(() => new Date().getFullYear() - 1)
  const s = status.data
  if (!s) return <LoadingBlock q={status} what="the year to close" />
  const year = s.yearToClose ?? (s.lastClosedYear != null ? s.lastClosedYear + 1 : lastYear)
  return (
    <Box sx={{ mb: 2.5 }}>
      <Typography variant="h6" component="p" sx={{ mb: 0.5 }}>Year-end checklist {year}</Typography>
      <Typography variant="body2" sx={{ mb: 1.25 }} color="text.secondary">
        The closing of {year} can be prepared and posted only once every item is done: the year-end adjustments made, and anything the books show wanting explained.
      </Typography>
      <Checklist branchCode={branchCode} scope="year" period={String(year)} canEdit={canEdit} />
    </Box>
  )
}
