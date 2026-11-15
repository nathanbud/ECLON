import { MenuItem, TextField, type SxProps, type Theme } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { listBranches } from '../api'

/**
 * Chooses a branch by name instead of asking for its number. Shows nothing while the cooperative has a single branch (there is nothing to
 * choose); the screen keeps using that branch. Start it on the person's own branch ({@code me.branchCode}).
 */
export function BranchSelect({ value, onChange, label = 'Branch', sx }: { value: number; onChange: (code: number) => void; label?: string; sx?: SxProps<Theme> }) {
  const { data } = useQuery({ queryKey: ['branches'], queryFn: listBranches, staleTime: 10 * 60_000 })
  if (!data || data.length <= 1) return null
  return (
    <TextField select size="small" label={label} value={data.some((b) => b.code === value) ? value : ''} onChange={(e) => onChange(Number(e.target.value))} sx={{ minWidth: 180, ...sx }}>
      {data.map((b) => <MenuItem key={b.code} value={b.code}>{b.name?.trim() || `Branch ${b.code}`}</MenuItem>)}
    </TextField>
  )
}
