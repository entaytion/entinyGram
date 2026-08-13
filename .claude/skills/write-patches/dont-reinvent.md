# Don't reinvent origin

If inugram already has the feature, **use it**. Do not ship a second toggle, a second drawable, and a rewrite of `ChatMessageCell` (29k lines). That is a second copy of the same feature. The two copies will conflict on the next merge.

This file is the case study. The checklist lives in `SKILL.md`. `AGENTS.md` rule 19 points here.

---

## What we wanted

Show how many times a post was forwarded, next to the timestamp.

Origin already did this: `InuConfig.SHOW_FORWARDS_COUNT` + `ChatHelper.timePrefix`.

We did it again anyway.

---

## Ours (dropped) — `patches/entiny/post-forwards-count.patch`

53 lines, **all in `ChatMessageCell.java`**. Four new fields on the cell, a custom drawable, a second config key, and a copy of the views-drawing path.

```java
// four extra fields on a 29k-line class
public StaticLayout inu_forwardsLayout;
public int inu_forwardsTextWidth;
public String inu_currentForwardsString;
public android.graphics.drawable.Drawable inu_forwardsDrawable;
```

```java
// measure — own toggle, own width math, not ChatHelper.extraTimeWidth
if (desu.inugram.InuConfig.SHOW_POST_FORWARDS_COUNT.getValue()
        && messageObject.messageOwner != null
        && messageObject.messageOwner.forwards > 0) {
    inu_currentForwardsString = String.format("%s",
            LocaleController.formatShortNumber(messageObject.messageOwner.forwards, null));
    inu_forwardsTextWidth = (int) Math.ceil(Theme.chat_timePaint.measureText(inu_currentForwardsString));
    timeWidth += inu_forwardsTextWidth + drawableWidth + dp(10);
}
```

```java
// draw — copy-paste of views, custom ic_repost_mini, group-transition offsets by hand
if (inu_forwardsLayout != null) {
    float forwardsX = (transitionParams.shouldAnimateTimeX ? this.timeX : timeX) + offsetX;
    inu_forwardsDrawable = getContext().getResources().getDrawable(R.drawable.ic_repost_mini).mutate();
    // colorFilter, setDrawableBounds, canvas.translate, SpoilerEffect.layoutDrawMaybe...
}
```

Also: `SHOW_POST_FORWARDS_COUNT` (second toggle), `ic_repost_mini.xml`, extra strings. **No `timeAdditionsHash`**, so the cell often did not relayout when the count appeared.

---

## Origin's (kept) — zero ChatMessageCell lines

The timestamp row already calls `ChatHelper.timePrefix` / `extraTimeWidth` / `timeAdditionsHash`. She added forwards there, next to edited / deleted / translate.

```kotlin
private fun getForwardsCount(msg: MessageObject?): Int {
    if (msg == null || !InuConfig.SHOW_FORWARDS_COUNT.value) return 0
    return msg.messageOwner?.forwards ?: 0
}

fun timePrefix(msg: MessageObject?, time: CharSequence?, edited: Boolean = false): CharSequence? {
    val sb = SpannableStringBuilder()
    val forwards = getForwardsCount(msg)
    if (forwards > 0) {
        appendTimeIcon(sb, R.drawable.mini_forwarded, sizeDp = 11f, translateYDp = 0f)
        sb.append(" ").append(LocaleController.formatShortNumber(forwards, null)).append("  ")
    }
    // deleted / edited / translate use the same builder
    return sb
}

fun extraTimeWidth(...): Int { /* +11dp when count > 0 */ }
fun timeAdditionsHash(...): Int { /* hash * 31 + 4 + count → cell invalidates */ }
```

- Toggle: `InuConfig.SHOW_FORWARDS_COUNT` (default off).
- Settings: entinyGram → Сообщения → Прочее → «Показывать число пересылок».
- Icon: stock `mini_forwarded`, already used in the time row.
- Java: the existing 1-line time hook. **Not a new patch on the cell.**

---

## Side by side

| | Ours | Origin (inugram) |
| --- | --- | --- |
| Where | `ChatMessageCell.java` × 4 call sites | `ChatHelper.kt` |
| Stock hotspot | yes (29k-line file, #1 rebase casualty) | no |
| Toggle | `SHOW_POST_FORWARDS_COUNT` (duplicate) | `SHOW_FORWARDS_COUNT` |
| Drawable | new `ic_repost_mini` | stock `mini_forwarded` |
| Relayout | missed `timeAdditionsHash` | hash + extra width |
| On origin merge | conflicts + broken braces + dup strings | Kotlin helper, untouched |
| Lines of Java | 53 in the cell | 0 for this feature |

---

## Why origin's is better (plain)

1. **One owner.** Timestamp extras already live in `ChatHelper`. Forwards is another extra, not a new drawing engine.
2. **Layout works.** Hash + width are the same path as "edited". Ours skipped them, so the number could overlap or not show until scroll.
3. **The cell is poison.** Every origin update touches `ChatMessageCell`. 53 lines there is 53 lines to re-resolve forever. 20 lines in `ChatHelper` are not in the stgit stack.
4. **One toggle.** Two rows that do the same thing will fight in settings and in prefs.
5. **It already existed.** The search was `SHOW_FORWARDS_COUNT` / `timePrefix`. We did not search origin first.

---

## What you do next time

1. Search origin: `InuConfig`, `src/kotlin/helpers`, `series`.
2. If it exists → keep it. Delete our copy. Stop.
3. If it does not exist → put logic in the helper that already owns the surface. Java is a 1–3 line hook.
4. Do **not** open `ChatMessageCell` for anything next to the timestamp unless `timePrefix` cannot express it. Prove that first.

We deleted `post-forwards-count`. Do not bring it back.
