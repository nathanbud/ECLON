/**
 * What the sign-in screen says about the cooperative and the system. Change the words here; the screen follows.
 * `coop` is the acronym in the old database's branches table (ACRONYM). To show the cooperative's own logo, put the file in `public/` (for example `public/logo.svg`) and set `logoUrl: '/logo.svg'`;
 * until then the Coop Platform mark is shown.
 */
export const brand = {
  product: 'Coop Platform',
  coop: 'BSUCMPC',
  logoUrl: null as string | null,
  tagline: 'Every member, every peso, in one place.',
  summary: 'Membership, savings, loans, the teller window and the books, kept together in one careful system.',
  facts: [
    { title: 'Members and share capital', text: 'Registration, profiles, fingerprints and the yearly dividend.' },
    { title: 'Savings, time deposits and loans', text: 'From the application to the last payment, with every schedule worked out.' },
    { title: 'Teller window and accounting', text: 'Drawers, vouchers, journals and statements that always balance.' },
    { title: 'Careful by design', text: 'A second person approves what matters, and every change is recorded.' },
  ],
  system: 'Enterprise Cooperative Information System',
}
