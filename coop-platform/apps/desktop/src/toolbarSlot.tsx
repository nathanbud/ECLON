import { Box } from '@mui/material'
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

// The title bar owns a slot next to the app name; a screen can fill it with its own menu, like a native menu bar.
const Ctx = createContext<{
  el: HTMLElement | null
  setEl: (e: HTMLElement | null) => void
  hasMenu: boolean
  setHasMenu: (v: boolean) => void
}>({ el: null, setEl: () => {}, hasMenu: false, setHasMenu: () => {} })

export function ToolbarSlotProvider({ children }: { children: ReactNode }) {
  const [el, setEl] = useState<HTMLElement | null>(null)
  const [hasMenu, setHasMenu] = useState(false)
  return <Ctx.Provider value={{ el, setEl, hasMenu, setHasMenu }}>{children}</Ctx.Provider>
}

/** True while the current screen has put its own menu in the title bar (so the plain title text is not needed). */
export const useHasMenu = () => useContext(Ctx).hasMenu

/** Rendered once, inside the title bar. */
export function ToolbarSlotTarget() {
  const { setEl } = useContext(Ctx)
  return <Box ref={setEl} sx={{ display: 'flex', alignItems: 'center', height: '100%', flexShrink: 0 }} />
}

/** Renders its children inside the title bar's slot. */
export function ToolbarPortal({ children }: { children: ReactNode }) {
  const { el, setHasMenu } = useContext(Ctx)
  useEffect(() => {
    setHasMenu(true)
    return () => setHasMenu(false)
  }, [setHasMenu])
  return el ? createPortal(children, el) : null
}
