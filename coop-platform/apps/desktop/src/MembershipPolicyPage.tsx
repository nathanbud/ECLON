import { Alert, Box, FormControlLabel, Stack, Switch, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { getMembershipPolicy, setMembershipPolicy } from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'

/** Membership policy switches (administrators). First switch: must a returning member pay share capital again. */
export default function MembershipPolicyPage() {
  const me = useMe()
  const qc = useQueryClient()
  const policy = useQuery({ queryKey: ['membership-policy'], queryFn: getMembershipPolicy })
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const save = useMutation({
    mutationFn: (on: boolean) => setMembershipPolicy(on, reason),
    onSuccess: () => { setError(null); setSaved(true); setReason(''); void qc.invalidateQueries({ queryKey: ['membership-policy'] }) },
    onError: (e) => setError((e as Error).message),
  })
  const on = policy.data?.returningPaysShareCapital ?? false
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 760, mx: 'auto' }}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 2 }}>Membership policy</Typography>
        {policy.isError && <Alert severity="error">{(policy.error as Error).message}</Alert>}
        <Box sx={(t) => ({ p: 2, ...glass(t) })}>
          <FormControlLabel
            control={<Switch checked={on} disabled={!me.admin || save.isPending || !reason.trim() || reason.trim().length < 3} onChange={(_, v) => { setSaved(false); save.mutate(v) }} />}
            label="A returning member must pay share capital again"
          />
          <Typography sx={{ fontSize: 13, mt: 0.5, mb: 1.5 }} color="text.secondary">
            When on, a Membership Officer cannot approve a reactivation until the member's share capital account holds the type's minimum (1,000.00 for a Regular member; an Associate has none). Take the payment on the member's Share Capital tab first. If the member has no active share account, the approval opens it and the audit row notes that the payment is still due. Off by default.
          </Typography>
          {me.admin ? (
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
              <TextField size="small" label="Reason for changing (needed to switch)" value={reason} onChange={(e) => { setReason(e.target.value); setSaved(false) }} sx={{ flex: 1 }} />
            </Stack>
          ) : <Typography sx={{ fontSize: 13 }} color="text.secondary">Only an administrator can change this.</Typography>}
          {error && <Alert severity="error" sx={{ mt: 1 }}>{error}</Alert>}
          {saved && <Typography sx={{ fontSize: 13, mt: 1 }} color="success.main">Saved and recorded in the audit log.</Typography>}
        </Box>
      </Box>
    </Box>
  )
}
