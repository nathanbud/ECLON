import { Alert, Box, FormControlLabel, Switch, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { changeMembershipPolicy, getMembershipPolicy, type MembershipPolicy } from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'

/** One policy switch: what it does, and a reason that must be given before it can be moved (the reason goes to the audit log). */
function PolicySwitch({ name, label, on, children, superadminOnly = false }: { name: keyof MembershipPolicy; label: string; on: boolean; children: ReactNode; superadminOnly?: boolean }) {
  const me = useMe()
  const may = superadminOnly ? me.roles.some((r) => r.replace('ROLE_', '') === 'SUPERADMIN') : me.admin
  const qc = useQueryClient()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const save = useMutation({
    mutationFn: (value: boolean) => changeMembershipPolicy({ [name]: value }, reason),
    onSuccess: () => { setError(null); setSaved(true); setReason(''); void qc.invalidateQueries({ queryKey: ['membership-policy'] }) },
    onError: (e) => setError((e as Error).message),
  })
  const ready = may && !save.isPending && reason.trim().length >= 3
  return (
    <Box sx={(t) => ({ p: 2, mb: 2, ...glass(t) })}>
      <FormControlLabel control={<Switch checked={on} disabled={!ready} onChange={(_, v) => { setSaved(false); save.mutate(v) }} />} label={label} />
      <Typography sx={{ fontSize: 13, mt: 0.5, mb: 1.5 }} color="text.secondary">{children}</Typography>
      {may
        ? <TextField size="small" fullWidth label="Reason for changing (needed to switch)" value={reason} onChange={(e) => { setReason(e.target.value); setSaved(false) }} />
        : <Typography sx={{ fontSize: 13 }} color="text.secondary">Only {superadminOnly ? 'a super administrator' : 'an administrator'} can change this.</Typography>}
      {error && <Alert severity="error" sx={{ mt: 1 }}>{error}</Alert>}
      {saved && <Typography sx={{ fontSize: 13, mt: 1 }} color="success.main">Saved and recorded in the audit log.</Typography>}
    </Box>
  )
}

/**
 * Membership policy switches: whether a returning member pays share capital again and whether withdrawals need a good KYC record (administrators), and
 * whether Membership Officers approve their own registrations (super administrators only). All off by default.
 */
export default function MembershipPolicyPage() {
  const policy = useQuery({ queryKey: ['membership-policy'], queryFn: getMembershipPolicy })
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 760, mx: 'auto' }}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 2 }}>Membership policy</Typography>
        {policy.isError && <Alert severity="error" sx={{ mb: 2 }}>{(policy.error as Error).message}</Alert>}
        <PolicySwitch name="returningPaysShareCapital" label="A returning member must pay share capital again" on={policy.data?.returningPaysShareCapital ?? false}>
          When on, a Membership Officer cannot approve a reactivation until the member's share capital account holds the type's minimum (1,000.00 for a Regular member; an Associate has none). Take the payment on the member's Share Capital tab first. If the member has no active share account, the approval opens it and the audit row notes that the payment is still due. Off by default.
        </PolicySwitch>
        <PolicySwitch name="kycBlocksWithdrawals" label="Withdrawals need a good KYC record" on={policy.data?.kycBlocksWithdrawals ?? false}>
          When on, nothing is paid out to a member whose KYC record is missing, incomplete or has an expired ID: savings withdrawals, share capital withdrawals, and time deposit pay-outs and interest withdrawals are refused with the reason, until the record is put right in the member's KYC section. A review that is merely overdue does not stop a withdrawal; the teller sees a warning instead. Check the KYC review list before switching this on: most members have no record yet. Off by default.
        </PolicySwitch>
        <PolicySwitch name="officerRegistersDirectly" superadminOnly label="Membership Officers approve their own registrations" on={policy.data?.officerRegistersDirectly ?? false}>
          When on, a new member registered by a Membership Officer is opened at once, with the accounts SL Controls name, without a second officer&apos;s approval; each one is recorded in the audit log as registered directly. Anyone who is not a Membership Officer still waits for one. Closing, reactivating and beneficiary changes still need a second officer. Use it only where there is a single officer; the second person is the safeguard against a wrong or made-up member. Only a super administrator can change it. Off by default.
        </PolicySwitch>
      </Box>
    </Box>
  )
}
