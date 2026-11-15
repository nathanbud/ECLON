import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { installArrowNavigation } from './arrowNavigation'
import { adoptSessionFromMainWindow, LOAN_APP_ROUTE, LOAN_SETUP_ROUTE, revealThisWindow } from './detachedWindow'
import LoanApplicationWindow from './LoanApplicationWindow'
import LoanSetupWindow from './LoanSetupWindow'
import CloseOnEscape from './nav/CloseOnEscape'
import { SessionGate } from './session'
import { AppThemeProvider } from './theme'
import { Notices, noticeMutationCache } from './ui/notices'

installArrowNavigation()
const queryClient = new QueryClient({ mutationCache: noticeMutationCache() })

const isSetup = window.location.hash.startsWith(LOAN_SETUP_ROUTE)
const detached = isSetup || window.location.hash.startsWith(LOAN_APP_ROUTE)
if (detached) document.documentElement.dataset.win = 'pre'

// A detached form window starts without a sign-in, so it asks the main window for it before showing anything.
;(detached ? adoptSessionFromMainWindow() : Promise.resolve()).then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <AppThemeProvider>
          {detached ? <SessionGate>{isSetup ? <LoanSetupWindow /> : <LoanApplicationWindow />}<CloseOnEscape what={isSetup ? 'Loan Setup' : 'loan application'} /></SessionGate> : <App />}
          <Notices />
        </AppThemeProvider>
      </QueryClientProvider>
    </StrictMode>,
  )
  if (detached) requestAnimationFrame(() => requestAnimationFrame(() => void revealThisWindow()))
})
// Should the first screen never get that far, the window must not stay hidden.
if (detached) setTimeout(() => void revealThisWindow(), 5000)
