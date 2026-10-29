import { createContext, useContext } from 'react'

// ---- small building blocks ----------------------------------------------------------------------------------------

// ---- editing ------------------------------------------------------------------------------------------------------

// Values live in input fields. They stay locked until the page is in edit mode; then every field that is bound to a
// column (it has a `col`) unlocks, and what you type is collected in `draft`, keyed by column name.
interface EditState { editing: boolean; draft: Record<string, string>; set: (col: string, value: string, original: string) => void }
export const EditCtx = createContext<EditState>({ editing: false, draft: {}, set: () => {} })
export const useEdit = () => useContext(EditCtx)

export type Value = string | number | null | undefined
type Kind = 'text' | 'date' | 'year' | 'money'

/** What a field needs to be editable: the column it writes to, the stored value it started from, and its type. */
export interface Bind { col?: string; raw?: Value; kind?: Kind }

export function useBound({ col, raw, kind = 'text' }: Bind) {
  const { editing, draft, set } = useEdit()
  const live = editing && !!col
  const stored = raw == null ? '' : String(raw)
  const original = kind === 'date' ? stored.slice(0, 10) : stored
  return {
    live,
    current: live ? draft[col!] ?? original : original,
    change: (v: string) => set(col!, v, original),
  }
}

export const fieldSx = (live: boolean) => ({
  '& .MuiOutlinedInput-root': { fontSize: 14, backgroundColor: live ? 'background.paper' : 'transparent' },
  '& .MuiOutlinedInput-notchedOutline': { borderColor: live ? 'text.disabled' : 'divider' },
  '& .MuiOutlinedInput-input': { paddingTop: '5px', paddingBottom: '5px', paddingLeft: '10px', paddingRight: '10px' },
  '& .MuiOutlinedInput-root.MuiInputBase-multiline': { padding: '5px 10px' },
  '& .MuiInputLabel-root': { fontSize: 13 },
  ...(live ? {} : { '& .MuiOutlinedInput-root:hover .MuiOutlinedInput-notchedOutline': { borderColor: 'divider' } }),
}) as const
