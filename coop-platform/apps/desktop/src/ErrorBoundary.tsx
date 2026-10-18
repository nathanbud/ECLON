import { Alert, Box, Button, Typography } from '@mui/material'
import { Component, type ErrorInfo, type ReactNode } from 'react'

/**
 * Catches a screen that fails while drawing, so the person sees a message and a way back instead of a blank window. The title bar
 * stays outside it, and changing {@code resetKey} (the screen being shown) clears the error.
 */
export default class ErrorBoundary extends Component<{ children: ReactNode; resetKey?: string }, { error: Error | null }> {
  state: { error: Error | null } = { error: null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Screen failed to draw', error, info.componentStack)
  }

  componentDidUpdate(prev: { resetKey?: string }) {
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.setState({ error: null })
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <Box role="alert" sx={{ p: 4, maxWidth: 560, mx: 'auto' }}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, mb: 1 }}>This screen could not be shown</Typography>
        <Alert severity="error" sx={{ mb: 2 }}>{this.state.error.message}</Alert>
        <Typography sx={{ mb: 2 }} color="text.secondary">Nothing was lost on the server. Try again, or go back to the dashboard from the home button.</Typography>
        <Button variant="contained" onClick={() => this.setState({ error: null })}>Try again</Button>
      </Box>
    )
  }
}
