import { keyframes } from '@mui/material'

const rise = keyframes`from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; }`

/** A short fade-and-rise for something that has just appeared. Switched off when the system asks for less motion. */
export const appear = {
  animation: `${rise} 260ms cubic-bezier(0.22, 1, 0.36, 1) both`,
  '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
} as const
