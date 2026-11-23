import { Alert, Box, Button, MenuItem, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { getProductRoles, setProductRoles } from './api/productRoles'
import { ReasonDialog, ScreenTitle } from './appleUi'
import { appBackground, glass } from './glass'
import { LoadingBlock } from './ui/Loading'

const KEY = ['product-roles']

/**
 * Settings > Products > Product roles: which of this cooperative's own account types the system posts to by itself. Nothing is assumed from another
 * cooperative's numbering; until a role is chosen, what needs it says so. Administrators, with a reason.
 */
export default function ProductRolesPage() {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: KEY, queryFn: getProductRoles })
  const [vp, setVp] = useState<string | null>(null)
  const [asking, setAsking] = useState(false)
  const save = useMutation({
    meta: { done: 'The product roles were saved.' },
    mutationFn: (reason: string) => setProductRoles(Number(vp), reason),
    onSuccess: (r) => { qc.setQueryData(KEY, r); setVp(null); setAsking(false) },
  })
  const r = q.data
  const chosen = vp ?? (r?.vouchersPayable != null ? String(r.vouchersPayable) : '')
  const changed = r != null && vp != null && Number(vp) !== r.vouchersPayable
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 760, mx: 'auto' }}>
        <ScreenTitle title="Product roles" sx={{ mb: 2 }}>
          Which of this cooperative&apos;s own account types the system posts to by itself. Each cooperative sets up its own products, so nothing is taken from
          another cooperative&apos;s numbering: a role not chosen here stops what needs it, with a message pointing back to this page.
        </ScreenTitle>
        <LoadingBlock q={q} what="the product roles" />
        {r && (
          <Box sx={(t) => ({ ...glass(t), p: 2 })}>
            <Typography sx={{ fontSize: 15, fontWeight: 600 }}>Vouchers payable</Typography>
            <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">
              The member account a loan&apos;s net proceeds and a cash or check voucher are credited to, and paid out from at the teller.
            </Typography>
            {r.vouchersPayable == null && (
              <Alert severity="warning" sx={{ mb: 1.5 }}>
                None is chosen yet, so loan applications, vouchers and pay-outs are refused until one is.
              </Alert>
            )}
            {r.choices.length === 0 ? (
              <Alert severity="info">This database has no fund and payable account type yet. Add one in Settings &gt; Products &gt; Fund and payable products, then choose it here.</Alert>
            ) : (
              <Box sx={{ display: 'flex', gap: 1.5, alignItems: 'center', flexWrap: 'wrap' }}>
                <TextField select size="small" label="Account type" value={chosen} onChange={(e) => setVp(e.target.value)} sx={{ minWidth: 320 }}>
                  {r.choices.map((c) => (
                    <MenuItem key={c.code} value={String(c.code)} disabled={c.disabled}>29/{c.code} {c.name}{c.disabled ? ' (switched off)' : ''}</MenuItem>
                  ))}
                </TextField>
                <Button variant="contained" disabled={!changed} onClick={() => { save.reset(); setAsking(true) }}>Save</Button>
              </Box>
            )}
          </Box>
        )}
      </Box>
      <ReasonDialog open={asking} title="Change the vouchers-payable account type" confirm="Save" busy={save.isPending}
        intro="New loan applications, vouchers and pay-outs use the type chosen. Applications already saved keep the type they were encoded with."
        error={save.error ? (save.error as Error).message : null} onCancel={() => setAsking(false)} onConfirm={(reason) => save.mutate(reason)} />
    </Box>
  )
}
