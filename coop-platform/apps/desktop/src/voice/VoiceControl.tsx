import { Box, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, IconButton, Tooltip, Typography } from '@mui/material'
import { keyframes } from '@mui/system'
import { useCallback, useEffect, useRef, useState } from 'react'
import { FiMic, FiMicOff } from 'react-icons/fi'
import { modelInstalled, startListening, type Session } from './engine'

const CONSENT = 'coop.voice.consent'
const IDLE_MS = 15_000

const pulse = keyframes`
  0% { box-shadow: 0 0 0 0 rgba(200, 16, 46, 0.45); }
  70% { box-shadow: 0 0 0 8px rgba(200, 16, 46, 0); }
  100% { box-shadow: 0 0 0 0 rgba(200, 16, 46, 0); }
`

const consented = () => { try { return localStorage.getItem(CONSENT) === '1' } catch { return false } }
const remember = () => { try { localStorage.setItem(CONSENT, '1') } catch { /* storage unavailable: asked again next time */ } }

type State = 'idle' | 'loading' | 'listening'

/** What the app did with a sentence: a line for the person to read, and whether the microphone can go off now (a command was carried out). */
export interface Handled { message: string; done: boolean }

/**
 * The microphone button in the title bar (and the V key). Press it, say what you want ("open journals", "go home", "find client Santos"), and it turns itself off again once a
 * command has been carried out, or after a few quiet seconds, or on Esc. Voice only moves around and searches: it never saves, approves or posts anything.
 * Speech is recognized on this computer; nothing is sent anywhere.
 */
export function VoiceControl({ handle }: { handle: (heard: string) => Handled }) {
  const [state, setState] = useState<State>('idle')
  const [installed, setInstalled] = useState<boolean | null>(null)
  const [line, setLine] = useState('')
  const [asking, setAsking] = useState(false)
  const session = useRef<Session | null>(null)
  const quiet = useRef<ReturnType<typeof setTimeout> | null>(null)
  const handleRef = useRef(handle)
  handleRef.current = handle
  const token = useRef(0)

  useEffect(() => { void modelInstalled().then(setInstalled) }, [])

  const clearQuiet = () => { if (quiet.current) { clearTimeout(quiet.current); quiet.current = null } }

  const stop = useCallback((message?: string) => {
    token.current += 1
    clearQuiet()
    session.current?.stop()
    session.current = null
    setState('idle')
    if (message !== undefined) setLine(message)
  }, [])

  const begin = useCallback(async () => {
    const mine = ++token.current
    setLine('')
    setState('loading')
    try {
      const s = await startListening({
        onPartial: (text) => {
          if (token.current !== mine) return
          setLine(text)
          clearQuiet()
          quiet.current = setTimeout(() => stop('Stopped listening'), IDLE_MS)
        },
        onSentence: (text) => {
          if (token.current !== mine) return
          const r = handleRef.current(text)
          setLine(r.message)
          if (r.done) stop(r.message)
        },
        onError: (message) => { if (token.current === mine) stop(message) },
      }, () => {
        if (token.current !== mine) return
        setState('listening')
        setLine('Listening…')
        quiet.current = setTimeout(() => stop('Stopped listening'), IDLE_MS)
      })
      if (token.current !== mine) { s.stop(); return }
      session.current = s
    } catch (e) {
      if (token.current === mine) stop((e as Error).message)
    }
  }, [stop])

  const toggle = useCallback(() => {
    if (state !== 'idle') { stop(''); return }
    if (!consented()) { setAsking(true); return }
    void begin()
  }, [state, begin, stop])

  // Esc stops listening; V starts or stops it. Like H and M, V does nothing while typing or with a dialog open.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && state !== 'idle') { e.preventDefault(); stop(''); return }
      if (e.key.toLowerCase() !== 'v' || e.ctrlKey || e.metaKey || e.altKey || e.shiftKey || e.repeat || installed !== true) return
      const t = e.target as HTMLElement | null
      if (t && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))) return
      if (document.querySelector('[role="dialog"], [role="menu"], [role="listbox"]')) return
      toggle()
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [state, installed, stop, toggle])

  // Leaving the screen turns the microphone off.
  useEffect(() => () => { clearQuiet(); session.current?.stop() }, [])

  if (installed === null) return null
  const on = state !== 'idle'
  const label = installed ? (on ? 'Stop listening' : 'Voice commands') : 'Voice commands are not installed'
  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, minWidth: 0 }}>
        {line && (
          <Typography role="status" aria-live="polite" noWrap title={line} sx={{ fontSize: 12, opacity: 0.85, maxWidth: { xs: 90, md: 220 } }}>{line}</Typography>
        )}
        <Tooltip title={installed ? (on ? 'Stop listening (Esc)' : 'Voice commands (V)') : 'Voice commands need the speech model. Run scripts\\fetch-voice-model.ps1, then restart the app.'}>
          <span>
            <IconButton
              aria-label={label} aria-pressed={on} aria-keyshortcuts="V" disabled={!installed} onClick={toggle} size="small"
              sx={{
                borderRadius: '5px', p: 0.5, fontSize: 15, color: 'inherit', opacity: installed ? 0.8 : 0.4, '&:hover': { opacity: 1 },
                ...(state === 'listening' && { color: '#fff', bgcolor: '#C8102E', opacity: 1, animation: `${pulse} 1.4s ease-out infinite`, '&:hover': { bgcolor: '#C8102E' }, '@media (prefers-reduced-motion: reduce)': { animation: 'none' } }),
                ...(state === 'loading' && { opacity: 1, bgcolor: 'action.selected' }),
              }}
            >
              {installed ? <FiMic /> : <FiMicOff />}
            </IconButton>
          </span>
        </Tooltip>
      </Box>

      <Dialog open={asking} onClose={() => setAsking(false)} aria-labelledby="voice-title">
        <DialogTitle id="voice-title">Use voice commands?</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 1 }}>
            The microphone listens only while the button is red. Your speech is turned into words on this computer by a small model that comes with the app. Nothing is sent over the
            network and no audio is saved.
          </DialogContentText>
          <DialogContentText sx={{ mb: 1 }}>
            Voice can open screens and search for members. It never saves, approves or posts anything.
          </DialogContentText>
          <DialogContentText>
            Try: “open journals”, “go home”, “find client Santos”, “open savings”. Press V or the microphone to start, Esc to stop.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAsking(false)}>Not now</Button>
          <Button variant="contained" autoFocus onClick={() => { remember(); setAsking(false); void begin() }}>Start listening</Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
