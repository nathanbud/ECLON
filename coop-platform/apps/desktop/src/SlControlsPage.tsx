import { Alert, Box, Button, Chip, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { DatabaseChangeNotice } from './DatabaseChangeNotice'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { getSlControls, saveSlControls, type SlChange, type SlControls } from './api/slControls'
import { appBackground, glass } from './glass'
import { useLeaveGuard } from './leaveGuard'
import { LoadingBlock } from './ui/Loading'
import { money } from './ui/format'
import { notifyError } from './ui/notices'
import { ScreenTitle } from './appleUi'

const key = (memberType: number, slc: number, slt: number) => `${memberType}/${slc}/${slt}`
const SHARE_CAPITAL = 31

/** The switches as they are stored, by member type, class and type. */
function storedOf(c: SlControls): Map<string, boolean> {
  const m = new Map<string, boolean>()
  for (const g of c.classes) for (const t of g.types) for (const mt of c.memberTypes) m.set(key(mt.code, t.slc, t.slt), !!t.on[String(mt.code)])
  return m
}

/** The member types left with no share capital account that opens by itself, with the switches as drafted (the server refuses to save that). */
export function withoutShareCapital(c: SlControls, draft: Map<string, boolean>): string[] {
  const share = c.classes.find((g) => g.slc === SHARE_CAPITAL)?.types ?? []
  return c.memberTypes.filter((mt) => !share.some((t) => !t.disabled && draft.get(key(mt.code, t.slc, t.slt)))).map((mt) => mt.name)
}

/**
 * SL Controls (Settings > Membership, administrators): which accounts open by themselves, empty, when a Membership Officer approves a new member or reactivates one, one switch
 * per account type and member type. Switches are changed on screen first, then saved together with one reason, which goes to the audit log with each change.
 */
export default function SlControlsPage() {
  const q = useQuery({ queryKey: ['sl-controls'], queryFn: getSlControls })
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 960, mx: 'auto' }}>
        <ScreenTitle title="SL Controls" sx={{ mb: 2 }}>
          The accounts that open by themselves when a Membership Officer approves a new member, or reactivates one who no longer holds them. They open empty: the first share
          capital payment and savings deposit are taken at the teller. Loans and time deposits open when they are taken out.
        </ScreenTitle>
        {q.data ? <Controls c={q.data} /> : <LoadingBlock q={q} what="the SL Controls" />}
      </Box>
    </Box>
  )
}

function Controls({ c }: { c: SlControls }) {
  const qc = useQueryClient()
  const stored = useMemo(() => storedOf(c), [c])
  const [draft, setDraft] = useState<Map<string, boolean>>(() => new Map(stored))
  const [reason, setReason] = useState('')
  const changes: SlChange[] = []
  for (const [k, on] of draft) {
    if (stored.get(k) === on) continue
    const [clientType, slc, slt] = k.split('/').map(Number)
    changes.push({ clientType, slc, slt, on })
  }
  const missingShare = withoutShareCapital(c, draft)
  useLeaveGuard(changes.length ? 'The SL Controls have changes that are not saved.' : null)
  const save = useMutation({
    onError: notifyError,
    mutationFn: () => saveSlControls(changes, reason.trim()),
    meta: { done: 'SL Controls saved and recorded in the audit log.' },
    onSuccess: (fresh) => {
      qc.setQueryData(['sl-controls'], fresh)
      setDraft(new Map(storedOf(fresh)))
      setReason('')
    },
  })
  const flip = (k: string, on: boolean) => setDraft((d) => new Map(d).set(k, on))
  const canSave = c.installed && changes.length > 0 && reason.trim().length >= 3 && missingShare.length === 0 && !save.isPending

  return (
    <>
      {!c.installed && (
        <DatabaseChangeNotice>SL Controls are not set up on this database yet, so the built-in list below is used and cannot be changed for now.</DatabaseChangeNotice>
      )}
      {c.classes.map((g) => (
        <Box key={g.slc} component="section" aria-label={g.name} sx={(t) => ({ ...glass(t), p: 0, mb: 2, overflow: 'hidden' })}>
          <Box sx={{ px: 2, pt: 1.5, pb: 1 }}>
            <Typography variant="h6" component="h2">{g.name}</Typography>
            <Typography variant="body2" color="text.secondary">{g.note}</Typography>
          </Box>
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell sx={{ fontWeight: 600 }}>Account type</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>Minimum balance</TableCell>
                  {c.memberTypes.map((mt) => <TableCell key={mt.code} align="center" sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>{mt.name}</TableCell>)}
                </TableRow>
              </TableHead>
              <TableBody>
                {g.types.length === 0 && (
                  <TableRow><TableCell colSpan={2 + c.memberTypes.length}><Typography color="text.secondary">No account type of this class is set up.</Typography></TableCell></TableRow>
                )}
                {g.types.map((t) => (
                  <TableRow key={t.slt} hover>
                    <TableCell>
                      <Stack direction="row" sx={{ alignItems: 'center', gap: 1 }}>
                        <Typography sx={{ color: t.disabled ? 'text.disabled' : undefined }}>{t.name}</Typography>
                        <Typography variant="caption" component="span" color="text.secondary">{g.slc}/{t.slt}</Typography>
                        {t.missing
                          ? <Chip size="small" color="warning" label="Not set up on this database: switch it off" />
                          : t.disabled && <Chip size="small" label="Switched off in Account Types" />}
                      </Stack>
                    </TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{t.minBalance ? money(t.minBalance) : '–'}</TableCell>
                    {c.memberTypes.map((mt) => {
                      const k = key(mt.code, t.slc, t.slt)
                      const on = !!draft.get(k)
                      const changed = stored.get(k) !== on
                      return (
                        <TableCell key={mt.code} align="center" sx={(th) => ({ bgcolor: changed ? th.palette.action.selected : undefined })}>
                          <Switch
                            checked={on} onChange={(_, v) => flip(k, v)}
                            disabled={!c.installed || (t.disabled && !on)}
                            slotProps={{ input: { 'aria-label': `${t.name} opens for ${mt.name} members` } }}
                          />
                        </TableCell>
                      )
                    })}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        </Box>
      ))}

      {c.installed && (
        <Box sx={(t) => ({ ...glass(t), p: 2, position: 'sticky', bottom: 8 })}>
          {missingShare.length > 0 && (
            <Alert severity="warning" sx={{ mb: 1.5 }}>
              {missingShare.join(' and ')} members would have no share capital account. Membership means subscribing to the cooperative&apos;s share capital, so keep one switched on.
            </Alert>
          )}
          <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1.5, alignItems: { sm: 'center' } }}>
            <TextField label="Reason for the change" value={reason} onChange={(e) => setReason(e.target.value)} disabled={changes.length === 0} sx={{ flex: 1 }}
              helperText={changes.length ? `${changes.length} change${changes.length > 1 ? 's' : ''} not saved. Kept in the audit log with each change.` : 'Change a switch above, then give the reason here.'} />
            <Stack direction="row" sx={{ gap: 1 }}>
              <Button onClick={() => { setDraft(new Map(stored)); setReason(''); save.reset() }} disabled={changes.length === 0 || save.isPending}>Discard</Button>
              <Button variant="contained" onClick={() => save.mutate()} disabled={!canSave}>Save changes</Button>
            </Stack>
          </Stack>
          {c.lastChangedBy && c.lastChangedBy !== 'system' && (
            <Typography variant="body2" sx={{ mt: 1 }} color="text.secondary">Last changed by {c.lastChangedBy}{c.lastChangedAt ? ` on ${c.lastChangedAt.replace('T', ' ').slice(0, 16)}` : ''}.</Typography>
          )}
        </Box>
      )}
    </>
  )
}
