import { CssBaseline, ThemeProvider, createTheme } from '@mui/material'
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

export type ThemeMode = 'light' | 'dark' | 'system'

const KEY = 'coop.themeMode'
const FONT = '-apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Segoe UI Variable", "Segoe UI", Inter, Roboto, sans-serif'

// Apple system colours as [light, dark], used for tiles and accents.
export const tint = {
  blue: ['#007AFF', '#0A84FF'], green: ['#34C759', '#30D158'], orange: ['#FF9500', '#FF9F0A'],
  red: ['#FF3B30', '#FF453A'], purple: ['#AF52DE', '#BF5AF2'], teal: ['#30B0C7', '#40C8E0'],
  indigo: ['#5856D6', '#5E5CE6'], yellow: ['#FFCC00', '#FFD60A'], gray: ['#636366', '#8E8E93'],
} as const
export type Tint = keyof typeof tint

/** Corner radius for every box and component in the app. */
export const RADIUS = 5

// Tile fills deep enough for white labels to reach WCAG AA (4.5:1); `tint` stays for accents on neutral surfaces.
export const tileFill: Record<Tint, string> = {
  blue: '#0062CC', green: '#1E7B34', orange: '#B8530A', red: '#C8102E', purple: '#8944AB',
  teal: '#0F7A8C', indigo: '#4F4DC4', yellow: '#8A6100', gray: '#5A5A5E',
}

function readStored(): ThemeMode {
  try {
    const v = localStorage.getItem(KEY)
    if (v === 'light' || v === 'dark' || v === 'system') return v
  } catch { /* storage unavailable */ }
  return 'system'
}

const Ctx = createContext<{ mode: ThemeMode; resolved: 'light' | 'dark'; setMode: (m: ThemeMode) => void }>(
  { mode: 'system', resolved: 'light', setMode: () => {} })

export const useThemeMode = () => useContext(Ctx)

export function AppThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(readStored)
  const [systemDark, setSystemDark] = useState(() => window.matchMedia('(prefers-color-scheme: dark)').matches)

  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const on = (e: MediaQueryListEvent) => setSystemDark(e.matches)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])

  const resolved = mode === 'system' ? (systemDark ? 'dark' : 'light') : mode
  const setMode = (m: ThemeMode) => {
    setModeState(m)
    try { localStorage.setItem(KEY, m) } catch { /* ignore */ }
  }

  const theme = useMemo(() => {
    const dark = resolved === 'dark'
    return createTheme({
      palette: {
        mode: resolved,
        primary: { main: dark ? '#0A84FF' : '#007AFF' },
        success: { main: dark ? '#30D158' : '#34C759' },
        warning: { main: dark ? '#FF9F0A' : '#FF9500' },
        error: { main: dark ? '#FF453A' : '#FF3B30' },
        background: { default: dark ? '#000000' : '#F2F2F7', paper: dark ? '#1C1C1E' : '#FFFFFF' },
        text: { primary: dark ? '#F5F5F7' : '#1D1D1F', secondary: dark ? '#98989D' : '#6E6E73' },
        divider: dark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.08)',
      },
      shape: { borderRadius: RADIUS },
      typography: {
        fontFamily: FONT,
        h4: { fontWeight: 700, letterSpacing: '-0.02em' },
        h6: { fontWeight: 600, letterSpacing: '-0.01em' },
        button: { textTransform: 'none', fontWeight: 600 },
      },
      components: {
        MuiPaper: { styleOverrides: { root: { backgroundImage: 'none' }, rounded: { borderRadius: RADIUS } } },
        MuiOutlinedInput: { styleOverrides: { root: { borderRadius: RADIUS, fontSize: 13.5 } } },
        MuiChip: { styleOverrides: { root: { borderRadius: RADIUS } } },
        MuiAvatar: { styleOverrides: { root: { borderRadius: RADIUS } } },
        MuiIconButton: { styleOverrides: { root: { borderRadius: RADIUS } } },
        MuiToggleButton: { styleOverrides: { root: { borderRadius: RADIUS } } },
        MuiInputLabel: { styleOverrides: { root: { fontSize: 13.5 } } },
        MuiTextField: { defaultProps: { size: 'small' } },
        MuiTableCell: { styleOverrides: { root: { fontSize: 13, padding: '6px 12px' }, head: { fontWeight: 600, fontSize: 12, color: dark ? '#98989D' : '#6E6E73' } } },
        MuiTablePagination: { styleOverrides: { root: { fontSize: 12.5 }, selectLabel: { fontSize: 12.5 }, displayedRows: { fontSize: 12.5 } } },
        MuiAlert: { styleOverrides: { root: { fontSize: 13, padding: '2px 12px', borderRadius: RADIUS } } },
        MuiDialog: { styleOverrides: { paper: { borderRadius: RADIUS } } },
      },
    })
  }, [resolved])

  useEffect(() => { document.documentElement.dataset.theme = resolved }, [resolved])

  return (
    <Ctx.Provider value={{ mode, resolved, setMode }}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </Ctx.Provider>
  )
}
