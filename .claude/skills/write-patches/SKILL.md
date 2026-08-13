---
name: write-patches
description: >
  Use when creating or rewriting a feature patch, adding a settings toggle,
  or touching ChatMessageCell / other stock hotspots. Trigger on "new patch",
  "add a toggle", "show X on the bubble", "use origin's version", or any temptation
  to duplicate an inugram feature. Covers the search-origin-first rule,
  the checklist, and the forward-count case study (our ChatMessageCell
  rewrite vs origin's ChatHelper.timePrefix). NOT for
  rebasing (that's inugram-rebase) and NOT for ordinary bugfixes that
  belong inline in the stock Java class.
---

# Writing patches without duplicating origin

Read `AGENTS.md` first (golden rules 3, 5, 8, 19). This skill is the
**feature-patch** rule: do not reinvent origin, do not rewrite a 29k-line
cell for something a 3-line helper hook already does.

## Hard stop — search origin first

Before writing **any** new feature:

1. Search **this repo**: `InuConfig`, `src/kotlin/helpers`, `series`,
   `patches/hooks/`.
2. Search **origin** (`teidesu/inugram`): same names, plus the helper that
   owns the surface (`ChatHelper` for bubbles, `ProfileHelper` for profile
   menu, `PullActionHelper` for dialogs pull, …).
3. If origin already has it → **keep/use theirs**. Do not add
   `patches/entiny/<same-thing>.patch`, a second `InuConfig` toggle, a
   second drawable, or a second settings row.

Duplicating origin is not "our version". It is two implementations that
fight on every merge. We already paid for this (forward count, below).

## Where the code goes

| What | Where |
| --- | --- |
| Feature logic | `src/kotlin/helpers/<area>/` (`ChatHelper`, etc.) |
| Toggle | `InuConfig` + existing settings page (`MessagesSettingsActivity` for bubble chrome) |
| Strings / drawables | `src/res/` — never inside a `.patch` |
| Stock Java | 1–3 line hook: `if (InuConfig.X.getValue()) Helper.foo(this);` or a call already in `patches/hooks/` |
| Bugfix of a stock class | inline in that Java file (this is *not* a feature) |

Bubble metadata (time, forwards, views, edited, deleted) **must** go through:

- `ChatHelper.timePrefix`
- `ChatHelper.extraTimeWidth`
- `ChatHelper.timeAdditionsHash`

Those three already have Java hooks in the cell. Opening
`ChatMessageCell.java` for a new icon-next-to-time is automatically wrong
unless you first prove those hooks cannot express it.

## Case study: forward count

Full side-by-side (our `ChatMessageCell` patch vs origin's `ChatHelper.timePrefix`): **[dont-reinvent.md](dont-reinvent.md)**.

Short version: origin already had `SHOW_FORWARDS_COUNT`. We shipped a second bicycle in the 29k-line cell. We deleted ours. Do not bring it back.

## Checklist before you write a patch

- [ ] Origin does **not** already have this (`InuConfig` / helpers / `series`).
- [ ] Existing helper/hook cannot express it (`timePrefix`, `addMenuItems`, …).
- [ ] New Java is a guard + helper call, not a rewrite of stock.
- [ ] New resources are in `src/res/`, not in the patch.
- [ ] Default-off is stock-identical.
- [ ] You are **not** about to edit `ChatMessageCell` for timestamp-adjacent UI.

If any box fails, stop and tell the user. Prefer dropping our copy and
taking origin's, the same way we did for forward count.
