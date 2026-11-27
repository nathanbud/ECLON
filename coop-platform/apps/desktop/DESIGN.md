---
version: alpha
name: Coop Platform desktop
description: >
  A calm, dense, Apple-style desktop app for cooperative staff (tellers, officers, accountants, administrators): soft off-white surfaces on a cool grey
  canvas, frosted glass for the surfaces that float, one blue for everything that can be pressed, 13 px text and 28 px controls so a busy screen
  still fits and lines up. Written in the DESIGN.md format of VoltAgent/awesome-design-md (Google Stitch spec); the values below are the ones the
  code uses (src/theme.tsx, src/glass.ts, src/ui/notices.tsx) and DESIGN.test.ts fails if they drift apart.

colors:
  # one accent: every interactive thing, and nothing else
  primary: "#0A6CDC"
  primary-dark: "#0A84FF"
  # status: only for status (a result, a warning, a refusal), never decoration
  success: "#34C759"
  success-dark: "#30D158"
  warning: "#FF9500"
  warning-dark: "#FF9F0A"
  danger: "#FF3B30"
  danger-dark: "#FF453A"
  # surfaces (light)
  canvas: "#F3F4F6"
  paper: "#FAFAFB"
  field: "rgba(250,250,252,0.7)"
  panel: "rgba(250,250,252,0.62)"
  bar: "rgba(246,247,249,0.8)"
  wash-blue: "#DCE6F2"
  wash-lilac: "#E5E1F0"
  # surfaces (dark)
  canvas-dark: "#000000"
  paper-dark: "#1C1C1E"
  # text
  ink: "#1D1D1F"
  ink-dark: "#F5F5F7"
  secondary: "#636366"
  secondary-dark: "#98989D"
  # lines
  hairline: "rgba(0,0,0,0.07)"
  divider: "rgba(0,0,0,0.09)"
  edge: "rgba(0,0,0,0.17)"
  edge-hover: "rgba(0,0,0,0.32)"
  # interaction fills
  fill-hover: "rgba(0,0,0,0.045)"
  fill-pressed: "rgba(0,0,0,0.08)"
  # the member profile's charcoal side panel (same in both themes)
  side: "#3A3D42"
  side-text: "#F2F3F5"
  side-selected: "#F3F4F6"
  # home tiles: deep enough for white labels (WCAG AA 4.5:1)
  tile-blue: "#0062CC"
  tile-green: "#1E7B34"
  tile-orange: "#B8530A"
  tile-red: "#C8102E"
  tile-purple: "#8944AB"
  tile-teal: "#0F7A8C"
  tile-indigo: "#4F4DC4"
  tile-yellow: "#8A6100"
  tile-gray: "#5A5A5E"

typography:
  family: '-apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Segoe UI Variable", "Segoe UI", Inter, Roboto, sans-serif'
  display:     { fontSize: 34, fontWeight: 700, lineHeight: 1.15, letterSpacing: "-0.025em" }
  large-title: { fontSize: 28, fontWeight: 700, lineHeight: 1.2,  letterSpacing: "-0.025em" }
  large:       { fontSize: 26, fontWeight: 700, lineHeight: 1.2,  letterSpacing: "-0.02em" }
  title1:      { fontSize: 22, fontWeight: 700, lineHeight: 1.25, letterSpacing: "-0.02em" }
  title2:      { fontSize: 20, fontWeight: 700, lineHeight: 1.25, letterSpacing: "-0.02em" }
  title3:      { fontSize: 17, fontWeight: 600, lineHeight: 1.3,  letterSpacing: "-0.01em" }
  headline:    { fontSize: 15, fontWeight: 600, lineHeight: 1.35, letterSpacing: "-0.01em" }
  body:        { fontSize: 13, fontWeight: 400, lineHeight: 1.45, letterSpacing: 0 }
  body-strong: { fontSize: 13, fontWeight: 600, lineHeight: 1.4,  letterSpacing: 0 }
  footnote:    { fontSize: 12, fontWeight: 400, lineHeight: 1.4,  letterSpacing: 0 }
  caption:     { fontSize: 11, fontWeight: 400, lineHeight: 1.35, letterSpacing: 0 }
  overline:    { fontSize: 11, fontWeight: 600, lineHeight: 1.35, letterSpacing: "0.06em", textTransform: uppercase }

rounded:
  control: 5      # inputs, buttons, chips, toggles, avatars, alerts (kept at 5 on the owner's request)
  tooltip: 6
  menu: 8
  surface: 10     # cards, panels, glass
  dialog: 12
  notice: 16      # the centred refusal card
  pill: 11        # the switch track

spacing:
  unit: 8                 # MUI spacing(1)
  input-pad-y: 5
  input-pad-x: 10
  button-pad: "4px 12px"
  cell-pad: "6px 12px"
  menu-row-pad: "4px 8px"

sizes:
  controlHeight: 28       # input, select, every button except large
  controlHeight-large: 34 # a screen's one main action; also tabs
  chip: 22
  chip-small: 20
  switch: "36x22"
  icon-in-button: 15
  window-open-height: 720
  window-min-height: 600

motion:
  quick: "140ms cubic-bezier(.2,.8,.2,1)"      # hover, border, background
  dialog-in: "260ms cubic-bezier(0.22,1,0.36,1)"
  dialog-out: "160ms"
  switch: "180ms"
  notice-in: "420ms"                           # refusal card tilt-in
  tooltip-delay: "400ms"

components:
  button-primary:        { backgroundColor: "{colors.primary}", textColor: "#FFFFFF", typography: "{typography.body}", rounded: "{rounded.control}", height: "{sizes.controlHeight}", padding: "{spacing.button-pad}" }
  button-primary-hover:  { filter: "brightness(1.07)" }
  button-primary-active: { filter: "brightness(0.9)" }
  button-neutral:        { backgroundColor: "{colors.field}", textColor: "{colors.ink}", border: "1px solid {colors.edge}", rounded: "{rounded.control}", height: "{sizes.controlHeight}" }
  button-neutral-hover:  { backgroundColor: "{colors.fill-hover}", border: "1px solid {colors.edge-hover}" }
  button-neutral-active: { backgroundColor: "{colors.fill-pressed}" }
  button-focus:          { outline: "3px solid {colors.primary} at 40%", outlineOffset: 1 }
  button-disabled:       { opacity: 0.42 }
  input:                 { backgroundColor: "{colors.field}", border: "1px solid {colors.edge}", rounded: "{rounded.control}", height: "{sizes.controlHeight}", padding: "{spacing.input-pad-y} {spacing.input-pad-x}", typography: "{typography.body}" }
  input-hover:           { border: "1px solid {colors.edge-hover}" }
  input-focus:           { border: "2px solid {colors.primary}" }
  input-error:           { border: "1px solid {colors.danger}" }
  input-disabled:        { backgroundColor: "{colors.fill-hover}", border: "1px solid {colors.divider}" }
  switch-on:             { trackColor: "{colors.success}", knob: "#FFFFFF", rounded: "{rounded.pill}" }
  card:                  { backgroundColor: "{colors.paper}", border: "1px solid {colors.divider}", rounded: "{rounded.surface}", shadow: none }
  glass:                 { background: "linear-gradient(145deg, rgba(251,251,252,0.8), rgba(247,248,250,0.6))", backdropFilter: "blur(24px) saturate(180%)", border: "1px solid {colors.hairline}", rounded: "{rounded.surface}" }
  menu:                  { backgroundColor: "{colors.paper}", rounded: "{rounded.menu}", shadow: "{elevation.floating}" }
  menu-row-hover:        { backgroundColor: "{colors.fill-hover}" }
  menu-row-selected:     { backgroundColor: "{colors.primary} at 14%" }
  table-head:            { typography: "{typography.footnote}", fontWeight: 600, textColor: "{colors.secondary}" }
  table-cell:            { typography: "{typography.body}", padding: "{spacing.cell-pad}", borderBottom: "1px solid {colors.divider}" }
  dialog:                { rounded: "{rounded.dialog}", shadow: "{elevation.dialog}", title: "{typography.title3}" }
  tooltip:               { backgroundColor: "rgba(38,38,40,0.94)", textColor: "#F5F5F7", typography: "{typography.caption}", rounded: "{rounded.tooltip}" }
  notice-done:           { place: "bottom of the window", role: status, duration: "5s" }
  notice-refusal:        { place: "centre of the window, over dialogs", surface: "{components.glass} with blur(28px)", rounded: "{rounded.notice}", motion: "{motion.notice-in}", duration: "12s, paused on hover" }

elevation:
  flat: none
  hairline: "0 1px 2px rgba(20,40,90,0.06)"
  glass: "inset 0 1px 0 rgba(255,255,255,0.55), 0 1px 2px rgba(20,40,90,0.06), 0 2px 8px rgba(20,40,90,0.05)"
  glass-elevated: "inset 0 1px 0 rgba(255,255,255,0.55), 0 1px 2px rgba(20,40,90,0.06), 0 8px 24px rgba(20,40,90,0.10)"
  floating: "0 0 0 0.5px rgba(0,0,0,0.10), 0 10px 32px rgba(0,0,0,0.14)"
  dialog: "0 0 0 0.5px rgba(0,0,0,0.12), 0 24px 70px rgba(0,0,0,0.22)"
---

# Coop Platform desktop: DESIGN.md

## Overview

A working tool for people who spend the whole day in it: tellers at the window, Membership Officers, accountants, administrators. It borrows the calm of
macOS (system font, soft surfaces, frosted glass, one blue) but is built for density: 13 px text, 28 px controls, tables with tight rows, so a teller's
screen holds a member, their balances and the form at once without scrolling.

**Key characteristics**
- One accent, `{colors.primary}`, for everything that can be pressed or is selected. Colour otherwise means status (green done, amber warning, red
  refused), never decoration. The one exception is the home tiles, which are coloured by area of work.
- Every one-line control is `{sizes.controlHeight}` tall, so a field, a select and a button placed in a row line up exactly.
- Off-white, never pure white: `{colors.paper}` on `{colors.canvas}`, with thin dark hairlines for edges. Pure white is only for print slips and
  signature boxes.
- Glass (`{components.glass}`) for surfaces that sit over the coloured wash or float over the screen; plain cards for content.
- Plain words. Screens say *member*, not *client*; a refusal says who, why, whether anything was saved and what to do next.
- Light and dark themes, following Windows unless the person picks one. Every value below has a dark twin in `theme.tsx`.

## Colors

### Brand & Accent
- `{colors.primary}` (`{colors.primary-dark}` in dark): buttons, links, the focused field's border, the selected menu row and toggle (as a 12 to 14 %
  tint), the text selection, the focus ring. **No second accent colour.**

### Status
- `{colors.success}`: done, a switch that is on, a balance that agrees.
- `{colors.warning}`: needs attention, due soon, an estimate.
- `{colors.danger}`: refused, overdue, a field in error, a destructive button. Never for a plain negative amount in a ledger (credits are shown with
  their sign or column, not in red).

### Surface
- `{colors.canvas}`: the window, under a soft wash of `{colors.wash-blue}` (top left) and `{colors.wash-lilac}` (bottom right) so glass has something to
  frost (`appBackground` in `glass.ts`).
- `{colors.paper}`: dialogs, menus, tables, cards.
- `{colors.field}`, `{colors.panel}`, `{colors.bar}`: a field, a panel and the title bar on glass.
- `{colors.side}`: the member profile's charcoal side panel, the same in both themes, with `{colors.side-selected}` as the open section's bar.

### Text
- `{colors.ink}` for text (about 16:1 on paper), `{colors.secondary}` for labels, table heads, hints and anything second in importance. Both pass WCAG AA.

### Hairlines & Borders
- `{colors.hairline}` round glass, `{colors.divider}` between rows and round cards, `{colors.edge}` round a control, `{colors.edge-hover}` when the
  mouse is over it.

### Tiles
- The home tiles use the deep `tile-*` fills so a white label reaches 4.5:1; the bright Apple tints (`tint` in `theme.tsx`) are only for accents on
  neutral surfaces.

## Typography

### Font Family
The system font: SF Pro on macOS, Segoe UI Variable on Windows (where the app is used), then Inter and Roboto. No web fonts (the Content Security Policy
would block them in the installed app anyway).

### Hierarchy
| Token | Size | Weight | Use |
|---|---|---|---|
| `display` | 34 | 700 | the sign-in screen's name |
| `large-title` | 28 | 700 | a big figure on a dashboard tile |
| `large` | 26 | 700 | |
| `title1` | 22 | 700 | a screen's title |
| `title2` | 20 | 700 | a section of a long screen |
| `title3` | 17 | 600 | a dialog's title, a card's heading |
| `headline` | 15 | 600 | a group heading, the large button |
| `body` | 13 | 400 | text, inputs, buttons, menus, table cells |
| `body-strong` | 13 | 600 | a label that leads, a total |
| `footnote` | 12 | 400 | table heads (600), secondary list lines, pagination |
| `caption` | 11 | 400 | helper text under a field, chips, tooltips, a raised field label |
| `overline` | 11 | 600 | upper-case group labels in lists and menus |

### Principles
- **Only these sizes.** `typeScale.test.ts` fails on any other `fontSize` or an odd `fontWeight`. Need something between? Use the size above or below.
- Large text gets tighter letter-spacing (down to -0.025em); 13 px and smaller stay at 0.
- Numbers in tables are right-aligned, in the body size, with tabular figures where columns are compared.
- Money is in pesos, shown with `₱` or the `PesoIcon` / `CoinIcon` (`ui/moneyIcons.tsx`), never a dollar sign.

## Layout

### Spacing System
- Base unit `{spacing.unit}` (MUI `spacing(1)`); use multiples (0.5, 1, 1.5, 2, 3) rather than odd pixels.
- Inputs pad `{spacing.input-pad-y}` by `{spacing.input-pad-x}`, set once in the theme (`INPUT_PAD`). **Never pad an input on a screen**
  (`inputPadding.test.tsx`).
- Table cells pad `{spacing.cell-pad}`; menu rows `{spacing.menu-row-pad}`, inset 4 px from the menu's edge.

### Grid & Container
- A screen is a title row (`ScreenTitle`: title, an ⓘ for its explanation, actions at the right), then its content in cards.
- Forms flow fields across a grid (`minmax(200px, 1fr)`), grouped in `fieldset` sections whose legend sits in the top border.
- The home screen is a board of square tiles, areas side by side, two tiles to a column, scrolling down only, never sideways.

### Whitespace Philosophy
Dense but not crowded: the space between groups is larger than the space inside them, so grouping is visible without boxes inside boxes. Explanations
hide behind an ⓘ (`InfoTip`, `Hinted`, `AboutNote`) rather than paragraphs under titles; what a person must act on (errors, limits, statuses, counts)
stays on the screen.

## Elevation & Depth

| Level | Token | Used for |
|---|---|---|
| Flat | `{elevation.flat}` | cards and tables: a `{colors.divider}` border, no shadow |
| Glass | `{elevation.glass}` | glass panels on the wash; lit top rim, faint blue-grey shadow |
| Glass, raised | `{elevation.glass-elevated}` | the main card of a screen (sign-in form, profile) |
| Floating | `{elevation.floating}` | menus, selects, autocomplete lists, popovers |
| Dialog | `{elevation.dialog}` | dialogs, over a 26 % dark backdrop |
| Notice | glass + layered shadows | the centred refusal card, above dialogs |

### Decorative Depth
- Depth comes from the surface first (canvas, paper, glass), shadow second. Content does not cast shadows; only what floats over the screen does.
- The one 3D moment is the refusal card: it tilts up into place (perspective 900 px, from `rotateX(-22deg)` and 24 px lower, `{motion.notice-in}`).
- Every animation is switched off under `prefers-reduced-motion`.

## Shapes

### Border Radius Scale
`{rounded.control}` controls → `{rounded.tooltip}` tooltips → `{rounded.menu}` menus → `{rounded.surface}` cards and glass → `{rounded.dialog}`
dialogs → `{rounded.notice}` the refusal card. The bigger and more separate a surface, the rounder. The switch track and the profile photo are the only
fully round shapes.

### Icons
Feather icons (`react-icons/fi`): 24 grid, 2 px round strokes, current colour, 1em. Our own icons (`ui/moneyIcons.tsx`) are drawn the same way. 15 px
inside buttons and fields, 18 px in alerts.

## Components

### Title bar and navigation
- The title bar (`TopBar`) carries the logo button (opens the side menu, with the waiting count), the business day chip, search (Ctrl+K) and the user.
- The side menu (`SideNav`) is hidden until the logo or M is pressed; it opens under the title bar and closes on a choice, Esc or a click outside.
- Esc goes back one screen (`nav/back.ts`); H goes home. Both ask first when there is unsaved work.

### Buttons
- **Primary** (`contained`): the action the screen is for, one per area. Hover brightens 7 %, press darkens 10 %; no ripple, no lift.
- **Neutral** (`outlined`, default colour): everything else. The field's fill and edge with ink text; hover fills `{colors.fill-hover}`.
- **Text**: low-weight actions in a row or table (Edit, Remove).
- **Destructive**: `color="error"`. An action that posts to the ledger or cannot be undone uses `ConfirmButton`, whose question says what will happen
  (amount, who, date) and that it cannot be edited.
- **Large**: `{sizes.controlHeight-large}`, `headline` text, only for a screen's single main action (Sign in).
- **Focus**: a 3 px ring in the accent (`{components.button-focus}`), only for the keyboard.
- **Disabled**: keeps its colours and fades to 42 %; say why on hover ("Fill in … first.").

### Inputs & Forms
- Outlined, `size="small"`, `{sizes.controlHeight}` tall, label floating into the border at caption size.
- Hover darkens the edge; focus makes it a 2 px accent border (not a halo, which cannot follow the label's gap); error turns it red.
- Required fields carry `*`; an empty required field turns red with its reason only after the person leaves it.
- Helper text (caption) under the field is for errors, limits and live figures; an explanation goes in an ⓘ beside it.
- Enter moves to the next missing field or the next button in step-by-step forms.

### Switches, checkboxes, toggles
- The iOS switch: a grey pill that fills `{colors.success}` with a sliding white knob. A switch takes effect at once; a checkbox is part of a form
  that is saved.
- Toggle buttons (segmented choices) tint the chosen one in the accent at 12 %.

### Cards & Containers
- `{components.card}` for content; `{components.glass}` (`glass()` in `glass.ts`) for surfaces on the wash or floating. Use `LIGHT.*` for new light
  surfaces, never `#fff` or a new hex.

### Tables & Lists
- Head in footnote, 600, secondary colour; rows in body size with a divider under each.
- A row that opens something opens it from anywhere on the row; its switches and buttons keep only their own job (`keepToControl`).
- Loading: `LoadingRow` as the first row (or `LoadingBlock`), which also shows why it failed, so a failure is never taken for an empty list.
- Reloading with old figures on screen: `BusyArea` dims it and runs a thin bar along its top.
- Money right-aligned; codes shown through `labelOf`, never raw.

### Menus & Lists that drop down
- `{components.menu}`: rows inset, rounded, `{colors.fill-hover}` on hover, accent tint when selected; upper-case `overline` subheaders.

### Dialogs
- `{components.dialog}`: scale-and-fade in over 260 ms, out faster (160 ms); title in `title3`; the main action at the bottom right.

### Notices
- **Done** (`meta: { done }` on a mutation): a short line at the bottom of the window, 5 s (`{components.notice-done}`). No Snackbar per screen.
- **Refused** (`notifyError(e)`): `{components.notice-refusal}`, a frosted glass card in the centre of the window, over dialogs, with a red alert
  mark, "This could not be done", the server's sentence and a Close button; 12 s, paused while the mouse is on it. It does not push the screen
  down or block it.
- **Your access was changed**: top right (`AccessWatcher`).
- Field errors stay under their field; a list that failed to load says so where the list would be.

### Chips, badges, tooltips
- Chips 22 px (20 small), control radius, footnote text: statuses and tags.
- Badges carry counts in caption, 600.
- Tooltips: dark, caption, after 400 ms.

### Tabs
- 34 px, body text in the secondary colour, the open one in 600 with a 2 px accent bar.

## Do's and Don'ts

### Do
- Take every value from the theme (`TYPE`, `CONTROL_H`, `RADIUS`, `SURFACE_RADIUS`, `INPUT_PAD`, `LIGHT`, `tint`, `tileFill`, `glass()`); add a new
  value to the theme, not to a screen.
- Keep one accent; use status colours only for status.
- Give every control hover, pressed, keyboard-focus and disabled states (the theme does; a custom control must too).
- Write messages a beginner understands: what happened, whether anything was saved, what to do. Show server messages through `errorFrom`.
- Put an explanation behind an ⓘ; keep errors, limits and statuses on screen.
- Check both themes and a 720 px tall window.

### Don't
- Don't set a font size, button height, input padding or border on a screen (`typeScale.test.ts`, `inputPadding.test.tsx`).
- Don't use pure white surfaces, a second accent colour, or new hard-coded hex values.
- Don't show refusals as inline boxes that push content down; use `notifyError`.
- Don't build `Request failed (status)` yourself, show raw codes, or use the dollar sign.
- Don't animate without a `prefers-reduced-motion` fallback, or use ripples and lifting buttons.
- Don't copy another company's brand look (logo, colours, name) into the app; a cooperative's own branding is set in Settings > Organization.

## Responsive Behavior

| Window | What changes |
|---|---|
| ≥ 900 px wide (`md`) | side-by-side layouts: profile panel beside its content, sign-in two columns |
| < 900 px wide | panels stack, the sign-in screen becomes one column |
| 720 px tall (opening size) | the profile's Personal section fits without scrolling; keep new screens within it |
| 600 px tall (minimum) | content scrolls; title bar and actions stay put |

- Desktop first: mouse and keyboard. Every action has a keyboard path (Tab order, Enter, Esc, the shortcuts on Settings > Documentation).
- Touch targets are not enlarged, but nothing depends on hover alone: a tooltip's text is also the control's accessible description.
- Tiles fill down, two to a column; the board never scrolls sideways.

## Iteration Guide

For people and agents changing a screen:
1. Read this file and `src/theme.tsx` first; refer to tokens by name (`{colors.primary}`, `TYPE.body`), never inline a value.
2. Change one component or screen at a time, and run `npm run check` (type-check, lint, Vitest), which includes the type scale, input padding and
   `DESIGN.test.ts` checks.
3. A new look for every screen goes in the theme; a value that changes here changes in `theme.tsx` / `glass.ts` in the same commit, and the other way
   round.
4. A new screen is added once in `nav/screens.tsx`; a new shortcut or rule is also written on Settings > Documentation.
5. Look at it in light and dark, and at the opening window size, before calling it done.

## Known Gaps

- The dark theme's values are in `theme.tsx` but only the main ones are listed here (`*-dark`).
- No documented spacing scale beyond the 8 px unit; screens use `spacing()` multiples by eye.
- Charts have no shared palette yet (the dashboards that need them are not built).
- Printed documents (invoices, schedules, passbook pages) follow their paper forms, not this file.
- Not checked with Windows High Contrast mode or 200 % text scaling.
- The silent printing and the real passbook printer layout are unverified, so print styling may still change.
