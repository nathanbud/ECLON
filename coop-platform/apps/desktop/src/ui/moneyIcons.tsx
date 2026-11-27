import type { SVGProps } from 'react'

/**
 * Money icons in the peso, drawn like the Feather icons the rest of the app uses (react-icons/fi: 24 grid, 2 px round strokes, current colour, 1em),
 * so they sit beside them. Feather has only a dollar sign; the app never shows one (moneyIcons.test.tsx fails if Feather's comes back).
 */
function Line({ children, ...props }: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 24 24" width="1em" height="1em" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"
      aria-hidden="true" focusable="false" {...props}>
      {children}
    </svg>
  )
}

/** ₱: a P with two bars, for amounts owed and paid (loans). */
export function PesoIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Line {...props}>
      <path d="M8 21V3h5a5 5 0 0 1 0 10H8" />
      <path d="M5 6.5h14" />
      <path d="M5 9.5h14" />
    </Line>
  )
}

/** A coin with ₱ on it, for cash and money kept (Front Office, savings). */
export function CoinIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <Line {...props}>
      <circle cx="12" cy="12" r="10" />
      <path d="M10.5 17V7h2.25a3 3 0 0 1 0 6H10.5" />
      <path d="M8.5 9h7.5" />
      <path d="M8.5 11h7.5" />
    </Line>
  )
}
