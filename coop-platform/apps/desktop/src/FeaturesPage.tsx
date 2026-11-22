import { Alert, Box, Button, Stack, Switch, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { getFeatures, saveFeatures, type FeatureRow } from './api/features'
import { appBackground, glass } from './glass'
import { useLeaveGuard } from './leaveGuard'
import { can } from './nav/screens'
import { useMe } from './session'
import { LoadingBlock } from './ui/Loading'
import { ScreenTitle } from './appleUi'

const KEY = ['features']

/**
 * Features (Settings > System, super administrators change, administrators see): which areas this cooperative uses. Each database is its own cooperative,
 * and not every cooperative offers savings, time deposits, loans or a store. An area switched off is hidden from the menu, tiles, search, member profile and
 * teller window, its accounts no longer open for new members, and the server refuses its actions. Nothing is deleted: switching it back on brings it back.
 */
export default function FeaturesPage() {
  const q = useQuery({ queryKey: KEY, queryFn: getFeatures })
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 820, mx: 'auto' }}>
        <ScreenTitle title="Features" sx={{ mb: 2 }}>
          The areas this cooperative uses. Switch off what it does not offer: the area disappears from the menu, tiles, search, member profile and teller window,
          members are no longer given its accounts, and its actions are refused. Nothing is deleted, so switching it back on brings everything back. Membership,
          share capital, the books and the teller&apos;s cash are always on.
        </ScreenTitle>
        {q.data ? <List key={JSON.stringify(q.data)} rows={q.data} /> : <LoadingBlock q={q} what="the features" />}
      </Box>
    </Box>
  )
}

function List({ rows }: { rows: FeatureRow[] }) {
  const qc = useQueryClient()
  const me = useMe()
  const editable = can(me, 'superadmin')
  const [draft, setDraft] = useState<Record<string, boolean>>(() => Object.fromEntries(rows.map((r) => [r.key, r.on])))
  const [reason, setReason] = useState('')
  const changes = Object.fromEntries(rows.filter((r) => draft[r.key] !== r.on).map((r) => [r.key, draft[r.key]]))
  const count = Object.keys(changes).length
  useLeaveGuard(count ? 'The features have changes that are not saved.' : null)
  const save = useMutation({
    mutationFn: () => saveFeatures(changes, reason.trim()),
    meta: { done: 'Features saved and recorded in the audit log. Open windows update within about 15 seconds.' },
    onSuccess: (fresh) => {
      qc.setQueryData(KEY, fresh)
      setReason('')
      void qc.invalidateQueries()
    },
  })

  return (
    <Stack spacing={2}>
      {!editable && <Alert severity="info">Only a super administrator switches these. They are shown here so you can see what this cooperative uses.</Alert>}
      <Box component="section" aria-label="Features" sx={(t) => ({ ...glass(t), p: 0, overflow: 'hidden' })}>
        {rows.map((r, i) => (
          <Stack key={r.key} direction="row" sx={(t) => ({ px: 2, py: 1.25, gap: 2, alignItems: 'center', borderTop: i ? `1px solid ${t.palette.divider}` : 0,
            bgcolor: draft[r.key] !== r.on ? t.palette.action.selected : undefined })}>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{r.label}</Typography>
              <Typography sx={{ fontSize: 12 }} color="text.secondary">{r.note}</Typography>
            </Box>
            <Typography sx={{ fontSize: 12, width: 24 }} color={draft[r.key] ? 'success.main' : 'text.secondary'}>{draft[r.key] ? 'On' : 'Off'}</Typography>
            <Switch checked={!!draft[r.key]} disabled={!editable} onChange={(_, v) => setDraft((d) => ({ ...d, [r.key]: v }))}
              slotProps={{ input: { 'aria-label': `${r.label} ${draft[r.key] ? 'on' : 'off'}` } }} />
          </Stack>
        ))}
      </Box>
      {editable && (
        <Box sx={(t) => ({ ...glass(t), p: 2, position: 'sticky', bottom: 8 })}>
          {save.error && <Alert severity="error" sx={{ mb: 1.5 }} onClose={() => save.reset()}>{(save.error as Error).message}</Alert>}
          <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1.5, alignItems: { sm: 'center' } }}>
            <TextField label="Reason for the change" value={reason} onChange={(e) => setReason(e.target.value)} disabled={!count} sx={{ flex: 1 }}
              helperText={count ? `${count} change${count > 1 ? 's' : ''} not saved. Kept in the audit log with each change.` : 'Switch an area above, then give the reason here.'} />
            <Stack direction="row" sx={{ gap: 1 }}>
              <Button onClick={() => { setDraft(Object.fromEntries(rows.map((r) => [r.key, r.on]))); setReason('') }} disabled={!count || save.isPending}>Discard</Button>
              <Button variant="contained" disabled={!count || reason.trim().length < 3 || save.isPending} onClick={() => save.mutate()}>Save changes</Button>
            </Stack>
          </Stack>
        </Box>
      )}
    </Stack>
  )
}
