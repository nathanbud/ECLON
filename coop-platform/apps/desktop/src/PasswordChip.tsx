import { Chip } from '@mui/material'
import type { AdminUser } from './api'

const shortWhen = (iso: string) => {
  const d = new Date(iso)
  const today = new Date().toDateString() === d.toDateString()
  return d.toLocaleString(undefined, today ? { hour: 'numeric', minute: '2-digit' } : { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}

/** Where a person's password stands, in one glance. */
export default function PasswordChip({ u }: { u: AdminUser }) {
  if (u.passwordState === 'ACTIVE') return <Chip size="small" variant="outlined" color="success" label="Set" />
  if (u.passwordState === 'TEMPORARY') return <Chip size="small" variant="outlined" color="warning" label={u.tempExpiresAt ? `Temporary · until ${shortWhen(u.tempExpiresAt)}` : 'Temporary'} />
  if (u.passwordState === 'EXPIRED') return <Chip size="small" variant="outlined" color="error" label="Expired" />
  return <Chip size="small" variant="outlined" label="Not set" />
}

