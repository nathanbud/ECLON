import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { openLoanApplicationWindow, openLoanSetupWindow, serveDetachedWindows } from './detachedWindow'
import AccountsPage from './AccountsPage'
import ApprovalsPage from './ApprovalsPage'
import ClientProfilePage from './ClientProfilePage'
import type { Id } from './clients'
import Dashboard, { type View } from './Dashboard'
import ErrorBoundary from './ErrorBoundary'
import FrontOfficePage from './FrontOfficePage'
import LoanApplicationDialog from './LoanApplicationDialog'
import TopBar from './TopBar'
import UsersPage from './UsersPage'
import AuditLogPage from './AuditLogPage'
import PrivacyPage from './PrivacyPage'
import CicPage from './CicPage'
import LoanSetupPage from './LoanSetupPage'
import SettingsPage from './SettingsPage'
import AccountingPage from './AccountingPage'
import ProcurementPage from './ProcurementPage'
import InventoryPage from './InventoryPage'
import PosPage from './PosPage'
import AssetsPage from './AssetsPage'
import ReportsPage from './ReportsPage'
import DocumentsPage from './DocumentsPage'
import DividendsPage from './DividendsPage'
import WorkflowSettingsPage from './WorkflowSettingsPage'
import MembershipPolicyPage from './MembershipPolicyPage'
import DocumentationPage from './DocumentationPage'
import BackupPage from './BackupPage'
import BrandingPage from './BrandingPage'
import DatabaseConnectionsPage from './DatabaseConnectionsPage'
import { Box, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle } from '@mui/material'
import { unsavedWork } from './leaveGuard'
import { screenFor } from './nav/screens'
import SideNav from './nav/SideNav'
import { SessionGate } from './session'
import { ToolbarSlotProvider } from './toolbarSlot'

const titles: Record<View, string> = {
  home: 'Coop Platform', members: 'Client Profile', accounts: 'Chart of Accounts', accounting: 'Accounting', approvals: 'Waiting for you', users: 'Users', audit: 'Audit Log', loansetup: 'Loan Setup', amortization: 'Loan Setup', settings: 'Settings', frontoffice: 'Front Office', loanapp: 'Coop Platform', procurement: 'Purchasing', inventory: 'Inventory', pos: 'Point of Sale', assets: 'Fixed Assets', reports: 'Reports', documents: 'Documents', workflows: 'Approval workflows', membershippolicy: 'Membership policy', documentation: 'Documentation', branding: 'Sign-in screen', connections: 'Database connection', backup: 'Backup', privacy: 'Data privacy', cic: 'CIC submission', dividends: 'Dividends',
}

/** Where the person is: a screen, and the tab inside it when the menu or the search named one. */
interface Place { view: View; tab?: string }

function Signed() {
  const qc = useQueryClient()
  const [place, setPlace] = useState<Place>({ view: 'home' })
  const { view, tab } = place
  // A loan application saved in its own window refreshes the lists here.
  useEffect(() => {
    const stop = serveDetachedWindows(() => { qc.invalidateQueries({ queryKey: ['member-loans'] }); qc.invalidateQueries({ queryKey: ['loan-applications'] }); qc.invalidateQueries({ queryKey: ['loan-releases'] }) })
    return () => { void stop.then((f) => f()) }
  }, [qc])
  // The open client lives here so the app-wide search can open a profile from any screen.
  const [client, setClient] = useState<Id | null>(null)
  // Screens opened from Settings go back to Settings, not to the dashboard.
  const [cameFromSettings, setCameFromSettings] = useState(false)
  const [leaving, setLeaving] = useState<(() => void) | null>(null)
  // The menu of screens is hidden until the logo (or M) opens it.
  const [menuOpen, setMenuOpen] = useState(false)
  const [leavingText, setLeavingText] = useState('')
  /** Does the move, or asks first when the screen holds unsaved work. Every way of leaving a screen goes through here: the menu, the tiles, the search, Home and the H key. */
  const guarded = (move: () => void) => {
    const unsaved = unsavedWork()
    if (unsaved) { setLeavingText(unsaved); setLeaving(() => move); return }
    move()
  }
  const goTo = (v: View, t: string | undefined, fromSettings: boolean) => guarded(() => { setCameFromSettings(fromSettings && v !== 'home'); setPlace({ view: v, tab: t }) })
  // The Loan Application tile goes straight to the form in its own window; the in-app dialog is only the fallback if that window cannot open.
  const navigate = (v: View, t?: string, fromSettings = false) => {
    if (v === 'loanapp' && openLoanApplicationWindow({})) return
    if ((v === 'loansetup' || v === 'amortization') && openLoanSetupWindow(v === 'amortization' ? 'amortization' : 'types')) return
    if (v === view && t === tab) return
    goTo(v, t, fromSettings)
  }
  const openClient = (id: Id) => guarded(() => { setClient(id); setCameFromSettings(false); setPlace({ view: 'members' }) })
  const here = screenFor(view, tab)?.label ?? titles[view]
  // The window title names the screen, for the task bar, the window list and screen readers.
  useEffect(() => { document.title = view === 'home' ? 'Coop Platform' : `${here} · Coop Platform` }, [view, here])
  /** Straight to the dashboard, asking first when a screen holds unsaved work. */
  const goHome = () => { setMenuOpen(false); guarded(() => { setCameFromSettings(false); setPlace({ view: 'home' }) }) }
  // M opens and closes the menu of screens (the logo does the same). Like H, it is ignored while typing, with a modifier key held, or while a dialog is open.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() !== 'm' || e.ctrlKey || e.metaKey || e.altKey || e.shiftKey || e.repeat) return
      const t = e.target as HTMLElement | null
      if (t && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))) return
      if (document.querySelector('[role="dialog"], [role="listbox"]')) return
      setMenuOpen((o) => !o)
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [])
  // H goes to the main dashboard from any screen. It is ignored while typing, with a modifier key held, or while a dialog is open, so it never interrupts work.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() !== 'h' || e.ctrlKey || e.metaKey || e.altKey || e.shiftKey || e.repeat) return
      const t = e.target as HTMLElement | null
      if (t && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))) return
      if (document.querySelector('[role="dialog"], [role="menu"], [role="listbox"]')) return
      goHome()
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  })
  return (
    <ToolbarSlotProvider>
      <Dialog open={!!leaving} onClose={() => setLeaving(null)} aria-labelledby="leave-title">
        <DialogTitle id="leave-title">Leave without saving?</DialogTitle>
        <DialogContent><DialogContentText>{leavingText} Leaving this screen will lose it.</DialogContentText></DialogContent>
        <DialogActions>
          <Button onClick={() => setLeaving(null)} autoFocus>Stay</Button>
          <Button color="error" onClick={() => { const go = leaving; setLeaving(null); go?.() }}>Leave</Button>
        </DialogActions>
      </Dialog>
      <TopBar
        title={here}
        onHome={view === 'home' ? undefined : () => guarded(() => { if (cameFromSettings && view !== 'settings') { setCameFromSettings(false); setPlace({ view: 'settings' }) } else setPlace({ view: 'home' }) })}
        onNavigate={navigate} onOpenClient={openClient}
        onMenu={() => setMenuOpen((o) => !o)} menuOpen={menuOpen}
      />
      <Box sx={{ display: 'flex', alignItems: 'flex-start' }}>
        <SideNav view={view} tab={tab} onNavigate={navigate} open={menuOpen} onClose={() => setMenuOpen(false)} />
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <ErrorBoundary resetKey={`${view}/${tab ?? ''}`}>
            {(view === 'home' || view === 'loanapp') && <Dashboard onOpen={navigate} />}
            {view === 'loanapp' && <LoanApplicationDialog onClose={() => setPlace({ view: 'home' })} />}
            {view === 'members' && <ClientProfilePage selected={client} onSelect={setClient} />}
            {view === 'accounts' && <AccountsPage />}
            {view === 'accounting' && <AccountingPage key={tab ?? ''} initialTab={tab} />}
            {view === 'approvals' && <ApprovalsPage onOpenClient={openClient} onOpen={navigate} />}
            {view === 'users' && <UsersPage />}
            {view === 'audit' && <AuditLogPage />}
            {view === 'privacy' && <PrivacyPage />}
            {view === 'cic' && <CicPage />}
            {view === 'dividends' && <DividendsPage />}
            {view === 'loansetup' && <LoanSetupPage key="types" />}
            {view === 'amortization' && <LoanSetupPage key="amortization" initialTab="amortization" />}
            {view === 'settings' && <SettingsPage onOpen={(v) => navigate(v, undefined, true)} />}
            {view === 'frontoffice' && <FrontOfficePage />}
            {view === 'procurement' && <ProcurementPage key={tab ?? ''} initialTab={tab} />}
            {view === 'inventory' && <InventoryPage key={tab ?? ''} initialTab={tab} />}
            {view === 'pos' && <PosPage />}
            {view === 'assets' && <AssetsPage />}
            {view === 'reports' && <ReportsPage onOpen={navigate} />}
            {view === 'documents' && <DocumentsPage />}
            {view === 'workflows' && <WorkflowSettingsPage />}
            {view === 'membershippolicy' && <MembershipPolicyPage />}
            {view === 'documentation' && <DocumentationPage />}
            {view === 'branding' && <BrandingPage />}
            {view === 'connections' && <DatabaseConnectionsPage />}
            {view === 'backup' && <BackupPage />}
          </ErrorBoundary>
        </Box>
      </Box>
    </ToolbarSlotProvider>
  )
}

export default function App() {
  return (
    <SessionGate>
      <Signed />
    </SessionGate>
  )
}
