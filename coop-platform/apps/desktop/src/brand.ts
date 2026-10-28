/**
 * What the sign-in screen says about the cooperative and the system: its name, the acronym, an optional logo and one line naming the system. Change the words here; the screen follows.
 * `coop` is the acronym in the old database's branches table (ACRONYM). To show the cooperative's own logo, put the file in `public/` (for example `public/logo.svg`) and set `logoUrl: '/logo.svg'`;
 * until then the Coop Platform mark is shown.
 */
export const brand = {
  product: 'Coop Platform',
  coop: 'BSUCMPC',
  logoUrl: null as string | null,
  system: 'Enterprise Cooperative Information System',
}
