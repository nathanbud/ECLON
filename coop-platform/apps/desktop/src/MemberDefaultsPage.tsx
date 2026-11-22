import { Alert, Box, Button, Checkbox, FormControlLabel, FormGroup, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { getMemberDefaultsSetup, saveMemberDefaults, type MemberDefaultsSetup } from './api/memberDefaults'
import { appBackground, glass } from './glass'
import { useLeaveGuard } from './leaveGuard'
import { LoadingBlock } from './ui/Loading'
import { ScreenTitle } from './appleUi'

const KEY = ['member-defaults-setup']

/**
 * Member defaults (Settings > Membership): the department and office a new member is given when the registration does not say, and the member types this
 * cooperative registers. Each database is its own cooperative, so nothing is assumed; a super administrator chooses them, with a reason kept in the audit log.
 * Administrators see them.
 */
export default function MemberDefaultsPage() {
  const q = useQuery({ queryKey: KEY, queryFn: getMemberDefaultsSetup })
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 720, mx: 'auto' }}>
        <ScreenTitle title="Member defaults" sx={{ mb: 2 }}>
          What a new member is given in this cooperative when the registration does not say, and which kinds of member can be registered. The accounts a member gets
          are chosen in Settings &gt; Products &gt; Account opening (SL Controls).
        </ScreenTitle>
        {q.data ? <Form key={JSON.stringify(q.data.applies)} s={q.data} /> : <LoadingBlock q={q} what="the member defaults" />}
      </Box>
    </Box>
  )
}

function Form({ s }: { s: MemberDefaultsSetup }) {
  const qc = useQueryClient()
  const [dept, setDept] = useState(s.applies.department == null ? '' : String(s.applies.department))
  const [office, setOffice] = useState(s.applies.office == null ? '' : String(s.applies.office))
  const [types, setTypes] = useState<number[]>(s.applies.types.map((t) => t.code))
  const [reason, setReason] = useState('')
  const stored = JSON.stringify([s.applies.department ?? '', s.applies.office ?? '', s.applies.types.map((t) => t.code)])
  const now = JSON.stringify([dept === '' ? '' : Number(dept), office === '' ? '' : Number(office), types])
  const changed = stored !== now
  useLeaveGuard(changed ? 'The member defaults have changes that are not saved.' : null)
  const save = useMutation({
    mutationFn: () => saveMemberDefaults(dept === '' ? null : Number(dept), office === '' ? null : Number(office), types, reason.trim()),
    meta: { done: 'Member defaults saved and recorded in the audit log.' },
    onSuccess: (fresh) => {
      qc.setQueryData(KEY, fresh)
      void qc.invalidateQueries({ queryKey: ['member-defaults'] })
    },
  })
  const toggle = (code: number, on: boolean) => setTypes((t) => (on ? [...t, code].sort((a, b) => a - b) : t.filter((x) => x !== code)))
  const ro = !s.editable
  const ready = changed && types.length > 0 && reason.trim().length >= 3 && !save.isPending

  return (
    <Stack spacing={2}>
      {ro && <Alert severity="info">Only a super administrator changes these. They are shown here so you can see what applies.</Alert>}
      {s.applies.types.length === 0 && <Alert severity="warning">No member type is chosen, so no one can be registered in this cooperative yet.</Alert>}
      <Box sx={(t) => ({ ...glass(t), p: 2 })}>
        <Stack spacing={2}>
          <TextField select label="Department a new member is given" value={dept} onChange={(e) => setDept(e.target.value)} disabled={ro}
            helperText={s.departments.length ? 'Used when the registration does not choose one.' : 'This database has no departments yet: add them in Settings > Organization > Departments.'}>
            <MenuItem value="">None chosen (the registration must give one)</MenuItem>
            {s.departments.map((d) => <MenuItem key={d.code} value={String(d.code)} disabled={d.disabled}>{d.code} · {d.name}{d.disabled ? ' (switched off)' : ''}</MenuItem>)}
          </TextField>
          <TextField select label="Office a new member is given" value={office} onChange={(e) => setOffice(e.target.value)} disabled={ro}
            helperText={s.offices.length ? 'Used when the registration does not choose one.' : 'This database has no offices yet: add them in Settings > Organization > Offices.'}>
            <MenuItem value="">None chosen (the registration must give one)</MenuItem>
            {s.offices.map((o) => <MenuItem key={o.code} value={String(o.code)}>{o.code} · {o.name}</MenuItem>)}
          </TextField>
          <Box component="fieldset" sx={{ border: 0, m: 0, p: 0 }}>
            <Typography component="legend" sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>Member types that can be registered</Typography>
            {s.memberTypes.length === 0
              ? <Typography sx={{ fontSize: 13 }} color="text.secondary">This database has no member types yet: load them in Settings &gt; System &gt; Starter setup.</Typography>
              : (
                <FormGroup sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' } }}>
                  {s.memberTypes.map((t) => (
                    <FormControlLabel key={t.code} disabled={ro} label={<Typography sx={{ fontSize: 13 }}>{t.name}</Typography>}
                      control={<Checkbox size="small" checked={types.includes(t.code)} onChange={(_, on) => toggle(t.code, on)} />} />
                  ))}
                </FormGroup>
              )}
          </Box>
        </Stack>
      </Box>
      {!ro && (
        <Box sx={(t) => ({ ...glass(t), p: 2 })}>
          {save.error && <Alert severity="error" sx={{ mb: 1.5 }} onClose={() => save.reset()}>{(save.error as Error).message}</Alert>}
          {types.length === 0 && <Alert severity="warning" sx={{ mb: 1.5 }}>Choose at least one member type, or no one can be registered.</Alert>}
          <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1.5, alignItems: { sm: 'center' } }}>
            <TextField label="Reason for the change" value={reason} onChange={(e) => setReason(e.target.value)} disabled={!changed} sx={{ flex: 1 }}
              helperText={changed ? 'Kept in the audit log with each change.' : 'Change a choice above, then give the reason here.'} />
            <Button variant="contained" disabled={!ready} onClick={() => save.mutate()}>Save</Button>
          </Stack>
        </Box>
      )}
    </Stack>
  )
}
