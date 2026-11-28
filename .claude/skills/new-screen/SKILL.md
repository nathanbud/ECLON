---
name: new-screen
description: Checklist for adding or reworking a screen, tab or dialog of the Coop Platform desktop app (coop-platform/apps/desktop) so it follows DESIGN.md and the app's shared pieces from the start. Use when building a new screen, tab, panel or dialog, moving a screen, or reworking an existing one (for example after an Impeccable audit), before saying the screen is done.
---

# A new or reworked screen

The rules live in `coop-platform/apps/desktop/DESIGN.md` (the look) and `CLAUDE.md` (where things go). This is the order to apply them in, so a screen
is right the first time instead of drifting (the Users page did: its own success pop-up, loading block, inline error boxes, faded rows, typed font
sizes). Paths below are under `coop-platform/apps/desktop/src` unless they say otherwise.

## 1. Before writing

- Read the parts of `DESIGN.md` the screen needs (Components, Do's and Don'ts) and look at a screen of the same kind that already follows them
  (the Users page, `UsersPage.tsx`, is the reference for a list with row actions).
- Decide who may open it, which feature switch hides it (Settings > System > Features), and which server endpoints it calls. The server checks
  rights again on every call; the screen only decides what is shown.
- The app's screens are Impeccable's **Operate** mode: scanability, consistency and the known pattern win over expression.

## 2. Where it goes

- **Listed once** in `nav/screens.tsx` (`id`, `label`, one-line `note`, `domain`, `icon`, `view`, optional `tab`, `who`, `tile`, `keywords`), and a
  case in the switch in `App.tsx`. The side menu, home tiles and search read that list; add it nowhere else. Menu entries are `role=link`, tiles are
  buttons (the e2e specs rely on that).
- A Settings item: the card in `SettingsPage.tsx`; who may open it still comes from `can` in `nav/screens.tsx`.
- Calls go in `api/<topic>.ts`; shared helpers in `ui/`. A screen that needs a table the database lacks shows `DatabaseChangeNotice`.
- **Settings > Documentation** (`DocumentationPage.tsx`): add the screen's shortcuts, where its tasks are done and any rule it enforces.

## 3. Building it: use the shared pieces

| Need | Use | Never |
|---|---|---|
| Title and what the screen is for | `ScreenTitle` (or `PageTitle`) from `appleUi.tsx`, the explanation in its ⓘ | a paragraph under the title |
| Explaining a field | `Hinted tip=...` around it; `helperText` only for its error, a limit or a live figure | long helper text |
| Explaining how part of a screen works | `AboutNote` | a blue box |
| A list loading or failing | `LoadingRow` as the first row of the table body (`LoadingBlock` outside a table) | a Skeleton, a blank area, a separate error box |
| A list reloading with old figures on screen | `BusyArea busy=...`, `placeholderData: keepPreviousData` | nothing (people act on figures about to change) |
| An action that worked | `meta: { done: '...' }` on the mutation, or `notify(text)` (`ui/notices.tsx`) | a Snackbar of the screen's own |
| An action refused | `notifyError(e)` (centred glass card over dialogs) or `onError: notifyError`; an audited change with a reason uses `ReasonDialog` (`appleUi.tsx`), which pops its `error` up itself | an `Alert` in the page or dialog that pushes content down |
| A check that runs while typing, "may be a duplicate, confirm it", a count that asks for a reason | shown in the dialog, next to what the person does about it (the teller dialogs, `DrawerOpen`, `DraftPostDialog` do this) | a pop-up (it would repeat every few seconds or hide the question) |
| A field's own problem | the field's `error` + `helperText`, shown once the person leaves it | a pop-up |
| A failed request's words | `errorFrom` / `friendlyError` (`api/http.ts`) | building `Request failed (status)` |
| Posting to the ledger or anything that cannot be undone | `ConfirmButton` (`ui/dialogs.tsx`), saying what happens (amount, who, date) and that it cannot be edited; one request key per action (`useRequestKey`, `ui/requestKey.ts`) | a plain button |
| Choosing a ledger account / branch | `ui/AccountPicker` / `ui/BranchSelect` | a typed number |
| A stored code (status, kind, level) | `labelOf(code, words?)` (`ui/labels.ts`; add a words map there, as `USER_LEVEL_WORDS`) | the raw code, or just lowercased |
| Why a control is disabled, who allowed it | `Explained` (`ui/Explained.tsx`) plus the input's `aria-describedby` | `title=` (keyboard and screen readers never get it) |
| A switched-off or inactive record | a `Chip` saying so | fading the row (`opacity` drops text below 4.5:1) |
| A row that opens something | `hover tabIndex={0}`, `onClick`, Enter in `onKeyDown`, an `aria-label` saying what opens; the row's own switches and buttons stop the click (`keepToControl` in `UsersPage.tsx`); the theme shows its focus | a click target on the name only |
| Money icons | `PesoIcon` / `CoinIcon` (`ui/moneyIcons.tsx`) | Feather's dollar sign |
| Unsaved work | `useLeaveGuard(message|null)` (`leaveGuard.ts`); Esc handled by the screen calls `preventDefault` | leaving without asking |

**Look:**
- Take every value from the theme (`theme.tsx`: `TYPE`, `CONTROL_H`, `RADIUS`, `SURFACE_RADIUS`, `INPUT_PAD`, `tint`, `tileFill`; `glass.ts`: `LIGHT`,
  `glass()`, `appBackground`).
- Text sizes come from Typography **variants**: `body1` 13, `body2` 12, `subtitle2` 13/600 (give it `component="p"`, MUI makes it an `h6`),
  `caption` 11, `overline` for small upper-case headings, `h5` for a dialog-sized title. Don't set `fontSize` on text, padding on an input, a
  button height, a radius or a hex colour on a screen (`typeScale.test.ts` and `inputPadding.test.tsx` fail on some of these). An icon's size by
  `fontSize` is fine.
- One accent (`primary`) for what can be pressed or selected. Green, amber and red mean status only; the theme's `warning` amber is readable as text,
  while `tint.orange` is decoration only.
- Controls in a row line up because they are all `CONTROL_H` tall; the single main action is `variant="contained"`, everything else outlined or text.
- Screen readers: every control has a name that says what it is, not its value ("Enabled, ben", not "ben is enabled"); tables have an
  `aria-label`; the page sits in `main`; headings go in order.

## 4. Tests

- A Vitest screen test beside the screen (`<Screen>.test.tsx`): render it inside `QueryClientProvider` and `AppThemeProvider`, with `<Notices />`
  mounted when it shows notices, and the `api` module mocked. Cover:
  - the list or form showing the data;
  - what the person may and may not do (disabled or hidden controls);
  - a refusal showing as `role="alert"` and not inside the page or dialog;
  - loading and a failed load;
  - any shortcut.
- Query by role and accessible name (`getByRole('switch', { name: 'Enabled, ben' })`), and check explanations with `toHaveAccessibleDescription`.
- Backend changes follow the `verify-coop` skill.

## 5. Before calling it done

1. `npm run check` in `coop-platform/apps/desktop` (type-check, lint, all Vitest tests including `DESIGN.test.ts`).
2. Impeccable's detector on the changed files, from `coop-platform/apps/desktop`:
   `sh ../../../.claude/skills/impeccable/scripts/impeccable detect --json <files>`. Fix what it finds or say why it is a false positive.
   Where Impeccable and DESIGN.md disagree, DESIGN.md wins; raise the conflict with the owner instead of applying it.
3. For a larger screen, `/impeccable audit <file>`.
4. Look at it in the running app when possible (`npm run dev`), light and dark, at the 720 px opening height.
5. Update the docs:
   - `DESIGN.md` when the screen adds a new kind of component or token (and the theme in the same commit);
   - `CLAUDE.md`'s summary and the note in `coop-platform/documentation/state/` when it is a feature;
   - `coop-platform/documentation/module-status.md` for the module's line.
6. Commit and push to the feature branch, as CLAUDE.md says. Say plainly what was not checked (for example, not looked at in the running app).
