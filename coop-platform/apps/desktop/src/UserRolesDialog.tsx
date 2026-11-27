import { Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Switch, Typography } from '@mui/material'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { grantRole, revokeRole, type AdminUser } from './api'
import { notifyError } from './ui/notices'

/** The roles an administrator grants here (the server keeps the same list), with what each allows. Membership Officer has its own switch on the list. */
export const ROLE_WORDS: { role: string; label: string; note: string }[] = [
  { role: 'ACCOUNTANT', label: 'Accountant', note: 'Journals, memos, periods, banks, statements and the year-end close' },
  { role: 'AUDITOR', label: 'Auditor', note: 'Reads the books, the statements, the reports and the audit log; changes nothing' },
  { role: 'DATA_PROTECTION_OFFICER', label: 'Data protection officer', note: 'Requests about people’s data, incidents and privacy records' },
  { role: 'PURCHASER', label: 'Purchaser', note: 'Purchase requests, orders and suppliers' },
  { role: 'PURCHASE_APPROVER', label: 'Purchase approver', note: 'Approves purchase requests and orders' },
  { role: 'RECEIVER', label: 'Receiver', note: 'Records goods received' },
  { role: 'STOREKEEPER', label: 'Storekeeper', note: 'Keeps the products and moves stock' },
  { role: 'STOCK_APPROVER', label: 'Stock approver', note: 'Approves stock adjustments and counts' },
  { role: 'POS_CASHIER', label: 'Point of sale cashier', note: 'Sells and records returns' },
  { role: 'POS_SUPERVISOR', label: 'Point of sale supervisor', note: 'Prices, discounts and decides returns' },
  { role: 'ASSET_CUSTODIAN', label: 'Asset custodian', note: 'Keeps the fixed asset register' },
  { role: 'DOCUMENT_CLERK', label: 'Document clerk', note: 'Looks after the documents kept for any record' },
  { role: 'MANAGER', label: 'Manager', note: 'Can be named as an extra approver in a workflow' },
  { role: 'CREDIT_COMMITTEE', label: 'Credit committee', note: 'Can be named as an extra approver in a workflow' },
]

/**
 * A person's roles, one switch each: what they may do beyond their level and permissions. Granting or taking a role away applies on their next action and is
 * kept in the audit log.
 */
export default function UserRolesDialog({ user, onClose, onNotice }: { user: AdminUser; onClose: () => void; onNotice: (msg: string) => void }) {
  const qc = useQueryClient()
  const [roles, setRoles] = useState<string[]>(user.roles)
  const change = useMutation({
    mutationFn: async ({ role, on }: { role: string; on: boolean }) => { if (on) await grantRole(user.username, role); else await revokeRole(user.username, role) },
    onSuccess: (_, { role, on }) => {
      setRoles((r) => (on ? [...r, role] : r.filter((x) => x !== role)))
      const label = ROLE_WORDS.find((w) => w.role === role)?.label ?? role
      onNotice(on ? `${user.username} is now ${/^[AEIOU]/.test(label) ? 'an' : 'a'} ${label.toLowerCase()}.` : `${user.username} is no longer ${label.toLowerCase()}.`)
      void qc.invalidateQueries({ queryKey: ['users'] })
      void qc.invalidateQueries({ queryKey: ['user-profile'] })
    },
    onError: notifyError,
  })
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Roles of {user.username}</DialogTitle>
      <DialogContent>
        <Typography sx={{ mb: 1.5 }} color="text.secondary">What this person may do beyond their level. A change applies on their next action and is kept in the audit log.</Typography>
        {ROLE_WORDS.map((w) => (
          <Box key={w.role} sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1, py: 0.75, borderBottom: `1px solid ${t.palette.divider}` })}>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography variant="subtitle2" component="p">{w.label}</Typography>
              <Typography variant="body2" color="text.secondary">{w.note}</Typography>
            </Box>
            <Switch checked={roles.includes(w.role)} disabled={!user.enabled || change.isPending}
              onChange={(_, on) => change.mutate({ role: w.role, on })} slotProps={{ input: { 'aria-label': `${w.label} role` } }} />
          </Box>
        ))}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  )
}
