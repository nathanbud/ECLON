import { Box, Button } from '@mui/material'
import { useState } from 'react'
import { SURFACE_RADIUS } from './theme'
import { FiCheck, FiCopy } from 'react-icons/fi'

/** A password shown once: large, in groups of four for reading out, selectable, with a Copy button. Used for a reissued temporary password and a new user's first one. */
export default function OneTimePassword({ password }: { password: string }) {
  const [copied, setCopied] = useState(false)
  const groups = password.match(/.{1,4}/g) ?? []
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(password)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch { /* the password is on screen; it can be read out */ }
  }
  return (
    <>
      <Box
        role="group" aria-label="Temporary password"
        sx={(t) => ({
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 1.5, py: 2.25, px: 2, borderRadius: `${SURFACE_RADIUS + 2}px`,
          bgcolor: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.07)' : 'rgba(0,0,0,0.045)',
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', fontSize: 22, fontWeight: 600, letterSpacing: '0.06em', userSelect: 'all',
        })}
      >
        {groups.map((g, i) => <span key={i}>{g}</span>)}
      </Box>
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 1.5 }}>
        <Button size="small" startIcon={copied ? <FiCheck /> : <FiCopy />} onClick={() => void copy()}>{copied ? 'Copied' : 'Copy'}</Button>
      </Box>
    </>
  )
}
