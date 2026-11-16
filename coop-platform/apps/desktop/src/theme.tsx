import { CssBaseline, ThemeProvider, alpha, createTheme } from '@mui/material'
import { LIGHT } from './glass'
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

/** Corner radius of every control: inputs, buttons, chips, toggles. */
export const RADIUS = 5
/** Corner radius of the surfaces controls sit on (cards and panels); menus and dialogs are derived from it below. */
export const SURFACE_RADIUS = 10
/** The inner padding of every input, select and text area in the app (top and bottom, left and right, in pixels). Change it here and nowhere else. */
export const INPUT_PAD = { y: 5, x: 10 } as const
const inputPad = `${INPUT_PAD.y}px ${INPUT_PAD.x}px`

/**
 * The type scale, in pixels. Every font size in the app is one of these (`typeScale.test.ts` fails on any other), so text on one screen is the same size as the same kind of
 * text on another. 13 is the size of body text, inputs, buttons, menus and table cells.
 */
export const TYPE = { caption: 11, footnote: 12, body: 13, headline: 15, title3: 17, title2: 20, title1: 22, large: 26, largeTitle: 28, display: 34 } as const
/** The height of a one-line control: an input, a select and a button are all this tall, so they line up in a row. */
export const CONTROL_H = 28
const LINE = CONTROL_H - 2 * INPUT_PAD.y // the text line inside a control, at body size
/** The same line as a multiple of the font size, so a field that sets larger type (the member finder, the sign-in form) grows with it and descenders are not cut. */
const LINE_EM = LINE / TYPE.body

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
    const accent = dark ? '#0A84FF' : '#0A6CDC'
    const danger = dark ? '#FF453A' : '#FF3B30'
    const ink = dark ? '#F5F5F7' : '#1D1D1F'
    const secondary = dark ? '#98989D' : '#636366'
    const line = dark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.09)'           // dividers and quiet edges
    const edge = dark ? 'rgba(255,255,255,0.20)' : 'rgba(0,0,0,0.17)'           // the edge of a control
    const edgeHover = dark ? 'rgba(255,255,255,0.34)' : 'rgba(0,0,0,0.32)'
    const fill = dark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.045)'          // hover on quiet controls
    const fillStrong = dark ? 'rgba(255,255,255,0.11)' : 'rgba(0,0,0,0.08)'     // pressed
    const fieldBg = dark ? 'rgba(255,255,255,0.05)' : LIGHT.field
    const ring = (c: string) => `0 0 0 3px ${alpha(c, dark ? 0.4 : 0.26)}`      // the focus ring around a control
    const floating = dark
      ? '0 0 0 0.5px rgba(255,255,255,0.14), 0 10px 32px rgba(0,0,0,0.5)'
      : '0 0 0 0.5px rgba(0,0,0,0.10), 0 10px 32px rgba(0,0,0,0.14)'
    const quick = '140ms cubic-bezier(.2,.8,.2,1)'
    const tip = dark ? 'rgba(72,72,76,0.97)' : 'rgba(38,38,40,0.94)'
    const itemSx = { fontSize: TYPE.body, lineHeight: `${LINE + 2}px`, minHeight: CONTROL_H, padding: '4px 8px', margin: '0 4px', borderRadius: RADIUS }
    return createTheme({
      palette: {
        mode: resolved,
        primary: { main: accent },
        success: { main: dark ? '#30D158' : '#34C759' },
        warning: { main: dark ? '#FF9F0A' : '#FF9500' },
        error: { main: danger },
        background: { default: dark ? '#000000' : LIGHT.canvas, paper: dark ? '#1C1C1E' : LIGHT.paper },
        text: { primary: ink, secondary },
        divider: line,
      },
      shape: { borderRadius: RADIUS },
      typography: {
        fontFamily: FONT,
        fontSize: TYPE.body,
        h1: { fontSize: TYPE.display, fontWeight: 700, letterSpacing: '-0.025em', lineHeight: 1.15 },
        h2: { fontSize: TYPE.largeTitle, fontWeight: 700, letterSpacing: '-0.025em', lineHeight: 1.2 },
        h3: { fontSize: TYPE.title1, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.25 },
        h4: { fontSize: TYPE.title2, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.25 },
        h5: { fontSize: TYPE.title3, fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1.3 },
        h6: { fontSize: TYPE.headline, fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1.35 },
        subtitle1: { fontSize: TYPE.headline, fontWeight: 500, lineHeight: 1.4 },
        subtitle2: { fontSize: TYPE.body, fontWeight: 600, lineHeight: 1.4 },
        body1: { fontSize: TYPE.body, lineHeight: 1.45, letterSpacing: 0 },
        body2: { fontSize: TYPE.footnote, lineHeight: 1.4, letterSpacing: 0 },
        caption: { fontSize: TYPE.caption, lineHeight: 1.35, letterSpacing: 0 },
        overline: { fontSize: TYPE.caption, fontWeight: 600, lineHeight: 1.35, letterSpacing: '0.06em', textTransform: 'uppercase' },
        button: { fontSize: TYPE.body, textTransform: 'none', fontWeight: 500, letterSpacing: 0 },
      },
      components: {
        MuiCssBaseline: {
          styleOverrides: {
            body: { fontSize: TYPE.body, lineHeight: 1.45, WebkitFontSmoothing: 'antialiased', MozOsxFontSmoothing: 'grayscale', textRendering: 'optimizeLegibility' },
            '::selection': { backgroundColor: alpha(accent, dark ? 0.5 : 0.24) },
            // Thin scroll bars, as on macOS.
            '*': { scrollbarWidth: 'thin', scrollbarColor: `${dark ? 'rgba(255,255,255,0.24)' : 'rgba(0,0,0,0.24)'} transparent` },
          },
        },
        // MUI 9 reads `color="textSecondary"`, not the dotted `color="text.secondary"` the screens use, so secondary text was drawn in the primary colour. The dotted names are mapped here.
        MuiTypography: {
          styleOverrides: {
            root: {
              variants: [
                { props: { color: 'text.primary' }, style: { color: ink } },
                { props: { color: 'text.secondary' }, style: { color: secondary } },
                { props: { color: 'text.disabled' }, style: { color: dark ? 'rgba(255,255,255,0.5)' : 'rgba(0,0,0,0.38)' } },
                { props: { color: 'success.main' }, style: { color: dark ? '#30D158' : '#34C759' } },
                { props: { color: 'error.main' }, style: { color: danger } },
              ],
            },
          },
        },
        MuiPaper: { styleOverrides: { root: { backgroundImage: 'none' }, rounded: { borderRadius: RADIUS } } },
        MuiCard: { styleOverrides: { root: { borderRadius: SURFACE_RADIUS, boxShadow: 'none', border: `1px solid ${line}` } } },

        // ---- buttons: no ripple and no lift; a press darkens, and the keyboard focus shows as a ring ----
        MuiButtonBase: {
          defaultProps: { disableRipple: true },
          styleOverrides: { root: { '&.Mui-focusVisible': { outline: `3px solid ${alpha(accent, dark ? 0.55 : 0.4)}`, outlineOffset: 1 } } },
        },
        MuiButton: {
          defaultProps: { disableElevation: true },
          styleOverrides: {
            // Every size is the height of an input, so a button beside a field lines up with it. `large` is the one taller size, for a screen's single main action.
            root: {
              minHeight: CONTROL_H, padding: '4px 12px', fontSize: TYPE.body, fontWeight: 500, lineHeight: '20px', borderRadius: RADIUS, letterSpacing: 0,
              transition: `background-color ${quick}, border-color ${quick}, box-shadow ${quick}, filter ${quick}`,
              // A disabled button keeps its own colours and fades, as on macOS, so it still reads as the button it will become. (MUI greys it out instead; with a fade on
              // top it all but disappeared.)
              '&.Mui-disabled': { opacity: 0.42 },
              variants: [
                ...([['primary', accent], ['error', danger], ['success', dark ? '#30D158' : '#34C759'], ['warning', dark ? '#FF9F0A' : '#FF9500']] as const).flatMap(([color, c]) => [
                  { props: { variant: 'contained' as const, color }, style: { '&.Mui-disabled': { color: '#fff', backgroundColor: c } } },
                  { props: { variant: 'text' as const, color }, style: { '&.Mui-disabled': { color: c } } },
                  { props: { variant: 'outlined' as const, color }, style: { '&.Mui-disabled': { color: color === 'primary' ? ink : c, borderColor: color === 'primary' ? edge : alpha(c, 0.5) } } },
                ]),
                { props: { color: 'inherit' as const }, style: { '&.Mui-disabled': { color: 'inherit' } } },
                {
                  props: { variant: 'contained' },
                  style: {
                    boxShadow: dark ? 'inset 0 1px 0 rgba(255,255,255,0.14)' : '0 1px 1px rgba(0,0,0,0.12), inset 0 1px 0 rgba(255,255,255,0.14)',
                    '&:hover': { filter: 'brightness(1.07)' }, '&:active': { filter: 'brightness(0.9)' },
                  },
                },
                { props: { variant: 'outlined' }, style: { padding: '3px 11px', '&:active': { backgroundColor: fillStrong } } },
                // A plain outlined button is the neutral push button: the field's fill and edge with ordinary text. A coloured one (error, success) keeps its colour.
                {
                  props: { variant: 'outlined', color: 'primary' },
                  style: { color: ink, backgroundColor: fieldBg, borderColor: edge, boxShadow: dark ? 'none' : '0 1px 1px rgba(0,0,0,0.04)', '&:hover': { backgroundColor: fill, borderColor: edgeHover } },
                },
                { props: { variant: 'outlined', color: 'inherit' }, style: { backgroundColor: fieldBg, borderColor: edge, '&:hover': { backgroundColor: fill, borderColor: edgeHover } } },
                { props: { variant: 'text' }, style: { '&:active': { backgroundColor: fillStrong } } },
                { props: { size: 'large' }, style: { minHeight: 34, padding: '7px 18px', fontSize: TYPE.headline } },
                { props: { size: 'large', variant: 'outlined' }, style: { padding: '6px 17px' } },
              ],
            },
            startIcon: { marginRight: 6, '& > *:nth-of-type(1)': { fontSize: 15 } },
            endIcon: { marginLeft: 6, '& > *:nth-of-type(1)': { fontSize: 15 } },
          },
        },
        MuiIconButton: { styleOverrides: { root: { borderRadius: RADIUS, transition: `background-color ${quick}`, '&:hover': { backgroundColor: fill }, '&:active': { backgroundColor: fillStrong } } } },
        MuiToggleButton: {
          styleOverrides: {
            root: {
              borderRadius: RADIUS, minHeight: CONTROL_H, padding: '3px 12px', fontSize: TYPE.body, fontWeight: 500, lineHeight: '20px', textTransform: 'none', letterSpacing: 0, borderColor: edge,
              '&.Mui-selected': { backgroundColor: alpha(accent, dark ? 0.26 : 0.12), color: dark ? ink : accent, '&:hover': { backgroundColor: alpha(accent, dark ? 0.32 : 0.17) } },
            },
          },
        },

        // ---- fields: one height, one edge, a soft ring when focused ----
        MuiTextField: { defaultProps: { size: 'small' } },
        MuiInputBase: { styleOverrides: { root: { fontSize: TYPE.body, lineHeight: LINE_EM }, input: { '&::placeholder': { color: secondary, opacity: 0.75 } } } },
        MuiOutlinedInput: {
          styleOverrides: {
            root: {
              borderRadius: RADIUS, fontSize: TYPE.body, backgroundColor: fieldBg, transition: `box-shadow ${quick}, background-color ${quick}`,
              '& .MuiOutlinedInput-notchedOutline': { borderColor: edge, borderWidth: 1, transition: `border-color ${quick}`, '& legend': { fontSize: `${TYPE.caption / TYPE.body}em` } },
              '&:hover:not(.Mui-focused):not(.Mui-error):not(.Mui-disabled) .MuiOutlinedInput-notchedOutline': { borderColor: edgeHover },
              '&.Mui-focused': { boxShadow: ring(accent), '& .MuiOutlinedInput-notchedOutline': { borderColor: accent, borderWidth: 1 } },
              '&.Mui-error': { '& .MuiOutlinedInput-notchedOutline': { borderColor: danger }, '&.Mui-focused': { boxShadow: ring(danger) } },
              '&.Mui-disabled': { backgroundColor: fill, '& .MuiOutlinedInput-notchedOutline': { borderColor: line } },
              // An icon inside the field already keeps its own distance from the text.
              '&.MuiInputBase-adornedStart': { paddingLeft: INPUT_PAD.x, '& .MuiOutlinedInput-input': { paddingLeft: 0 } },
              '&.MuiInputBase-adornedEnd': { paddingRight: INPUT_PAD.x, '& .MuiOutlinedInput-input': { paddingRight: 0 } },
              // A text area gets its padding on the field, not on the text inside it, or it would be counted twice.
              '&.MuiInputBase-multiline': { padding: inputPad, '& .MuiOutlinedInput-input': { padding: 0, height: 'auto' } },
            },
            input: { padding: inputPad, height: `${LINE_EM}em`, lineHeight: LINE_EM },
          },
        },
        MuiSelect: { styleOverrides: { select: { minHeight: `${LINE_EM}em`, lineHeight: LINE_EM, height: 'auto' }, icon: { fontSize: 18, right: 5 } } },
        // The resting label sits where the typed text would: same size, same left padding, centred between the top and bottom padding (MUI's own offsets assume a roomier field).
        // Raised onto the edge it is caption size (11), not MUI's three quarters, which would be under 10 pixels.
        MuiInputLabel: {
          styleOverrides: {
            root: {
              fontSize: TYPE.body, lineHeight: LINE_EM,
              '&.MuiInputLabel-outlined:not(.MuiInputLabel-shrink)': { transform: `translate(${INPUT_PAD.x}px, ${INPUT_PAD.y}px)` },
              '&.MuiInputLabel-outlined.MuiInputLabel-shrink': { transform: `translate(13px, -8px) scale(${TYPE.caption / TYPE.body})`, maxWidth: 'calc(118% - 32px)' },
            },
          },
        },
        MuiFormHelperText: { styleOverrides: { root: { fontSize: TYPE.caption, lineHeight: 1.35, margin: '3px 2px 0' } } },
        MuiFormLabel: { styleOverrides: { root: { fontSize: TYPE.body } } },
        MuiFormControlLabel: { styleOverrides: { root: { marginLeft: -6, marginRight: 14 }, label: { fontSize: TYPE.body } } },
        MuiInputAdornment: { styleOverrides: { root: { color: secondary, '& .MuiTypography-root': { fontSize: TYPE.body }, '& svg': { fontSize: 15 } } } },
        MuiCheckbox: { styleOverrides: { root: { padding: 6, borderRadius: RADIUS, '& .MuiSvgIcon-root': { fontSize: 18 } } } },
        MuiRadio: { styleOverrides: { root: { padding: 6, '& .MuiSvgIcon-root': { fontSize: 18 } } } },
        // The switch is the iOS one: a pill that fills green and a white knob that slides.
        MuiSwitch: {
          styleOverrides: {
            root: {
              width: 36, height: 22, padding: 0, margin: '0 8px 0 6px', overflow: 'visible',
              '& .MuiSwitch-switchBase': {
                padding: 2, color: '#fff', transitionDuration: '180ms',
                '&:hover': { backgroundColor: 'transparent' },
                '&.Mui-checked': { transform: 'translateX(14px)', color: '#fff', '&:hover': { backgroundColor: 'transparent' }, '& + .MuiSwitch-track': { backgroundColor: dark ? '#30D158' : '#34C759', opacity: 1 } },
                '&.Mui-disabled': { color: '#fff', '& + .MuiSwitch-track': { opacity: 0.45 } },
                '&.Mui-focusVisible': { outline: 'none', '& + .MuiSwitch-track': { boxShadow: ring(accent) } },
              },
              '& .MuiSwitch-thumb': { width: 18, height: 18, boxShadow: '0 1px 3px rgba(0,0,0,0.3)' },
              '& .MuiSwitch-track': { borderRadius: 11, opacity: 1, backgroundColor: dark ? 'rgba(120,120,128,0.36)' : 'rgba(120,120,128,0.28)', transition: 'background-color 180ms' },
            },
          },
        },

        // ---- lists that drop down: a floating panel, rows inset with rounded highlights ----
        MuiPopover: { styleOverrides: { paper: { borderRadius: SURFACE_RADIUS - 2, boxShadow: floating } } },
        MuiMenu: { styleOverrides: { paper: { borderRadius: SURFACE_RADIUS - 2, boxShadow: floating }, list: { padding: '4px 0' } } },
        MuiMenuItem: {
          styleOverrides: {
            root: {
              ...itemSx, transition: `background-color ${quick}`,
              '&:hover': { backgroundColor: fill },
              '&.Mui-focusVisible': { outline: 'none', backgroundColor: fillStrong },
              '&.Mui-selected': { backgroundColor: alpha(accent, dark ? 0.3 : 0.14), '&:hover, &.Mui-focusVisible': { backgroundColor: alpha(accent, dark ? 0.38 : 0.2) } },
            },
          },
        },
        MuiAutocomplete: {
          styleOverrides: {
            paper: { borderRadius: SURFACE_RADIUS - 2, boxShadow: floating },
            listbox: { padding: '4px 0' },
            option: { ...itemSx },
            noOptions: { fontSize: TYPE.body },
            loading: { fontSize: TYPE.body },
          },
        },
        MuiListItemButton: { styleOverrides: { root: { borderRadius: RADIUS, '&.Mui-focusVisible': { outline: 'none', backgroundColor: fillStrong } } } },
        MuiListItemText: { styleOverrides: { primary: { fontSize: TYPE.body }, secondary: { fontSize: TYPE.footnote } } },
        MuiListSubheader: { styleOverrides: { root: { fontSize: TYPE.caption, fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase', lineHeight: '28px' } } },

        // ---- small pieces ----
        MuiChip: {
          styleOverrides: {
            root: { borderRadius: RADIUS, height: 22, fontSize: TYPE.footnote, fontWeight: 500 },
            sizeSmall: { height: 20, fontSize: TYPE.caption, '& .MuiChip-label': { paddingLeft: 7, paddingRight: 7 } },
            label: { paddingLeft: 8, paddingRight: 8 },
            outlined: { borderColor: edge },
          },
        },
        MuiAvatar: { styleOverrides: { root: { borderRadius: RADIUS } } },
        // A badge with a number; the plain dot keeps its own small size.
        MuiBadge: { styleOverrides: { badge: { '&:not(.MuiBadge-dot)': { fontSize: TYPE.caption, fontWeight: 600, minWidth: 18, height: 18, padding: '0 5px' } } } },
        MuiTooltip: {
          defaultProps: { enterDelay: 400 },
          styleOverrides: {
            tooltip: { fontSize: TYPE.caption, fontWeight: 500, lineHeight: 1.35, padding: '4px 8px', borderRadius: RADIUS + 1, backgroundColor: tip, color: '#F5F5F7' },
            arrow: { color: tip },
          },
        },
        MuiTabs: { styleOverrides: { root: { minHeight: 34 }, indicator: { height: 2, borderRadius: 1 } } },
        MuiTab: {
          styleOverrides: {
            root: {
              minHeight: 34, minWidth: 0, padding: '6px 14px', fontSize: TYPE.body, fontWeight: 500, letterSpacing: 0, textTransform: 'none', color: secondary,
              '&.Mui-selected': { fontWeight: 600 },
              '&.Mui-focusVisible': { outlineOffset: -3 },
            },
          },
        },
        MuiLinearProgress: { styleOverrides: { root: { height: 4, borderRadius: 2 }, bar: { borderRadius: 2 } } },
        MuiDivider: { styleOverrides: { root: { borderColor: line } } },

        // ---- tables ----
        MuiTableCell: {
          styleOverrides: {
            root: { fontSize: TYPE.body, lineHeight: 1.4, padding: '6px 12px', borderBottomColor: line },
            head: { fontWeight: 600, fontSize: TYPE.footnote, color: secondary },
          },
        },
        MuiTablePagination: { styleOverrides: { root: { fontSize: TYPE.footnote }, selectLabel: { fontSize: TYPE.footnote }, displayedRows: { fontSize: TYPE.footnote } } },
        MuiAlert: { styleOverrides: { root: { fontSize: TYPE.body, lineHeight: 1.45, padding: '2px 12px', borderRadius: RADIUS }, icon: { fontSize: 18, marginRight: 8 } } },
        MuiAlertTitle: { styleOverrides: { root: { fontSize: TYPE.body, fontWeight: 600, marginBottom: 2 } } },

        // ---- windows over the screen ----
        // A dialog settles in with a slight scale as it fades in, and fades out a little faster than it came.
        MuiDialog: {
          defaultProps: { transitionDuration: { enter: 260, exit: 160 } },
          styleOverrides: {
            paper: {
              borderRadius: SURFACE_RADIUS + 2,
              boxShadow: dark ? '0 0 0 0.5px rgba(255,255,255,0.16), 0 24px 70px rgba(0,0,0,0.6)' : '0 0 0 0.5px rgba(0,0,0,0.12), 0 24px 70px rgba(0,0,0,0.22)',
              animation: 'coop-pop 260ms cubic-bezier(0.22, 1, 0.36, 1) both', '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
            },
          },
        },
        MuiDialogTitle: { styleOverrides: { root: { fontSize: TYPE.title3, fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1.3 } } },
        MuiDialogContentText: { styleOverrides: { root: { fontSize: TYPE.body, color: secondary } } },
        MuiBackdrop: { styleOverrides: { root: { '&:not(.MuiBackdrop-invisible)': { backgroundColor: dark ? 'rgba(0,0,0,0.5)' : 'rgba(0,0,0,0.26)' } } } },
        MuiSnackbarContent: { styleOverrides: { root: { fontSize: TYPE.body, borderRadius: SURFACE_RADIUS - 2 } } },
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
