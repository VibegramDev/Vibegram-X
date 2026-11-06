## Glyphs drawn in code

These icons are painted with `Canvas`/`Path` in Java, so the drawable swap does not reach them. Paths are relative
to `app/src/main/java/org/thunderdog/challegram/`.

Status after the Telegram for Android 12.3.1 restyle: geometry and weights follow Telegram's own drawables
(`BackDrawable`, `MenuDrawable`, `MediaActionDrawable` / `Theme.playPauseAnimator`, `CheckBoxSquare`, `CheckBoxBase`,
`RadioButton`, `CloseProgressDrawable2`, `CrossOutDrawable`) or the measured 24dp icons (1.67dp outline, 90° heads);
colours stay M3. ✅ done, ➖ already matching (comment only), ⏭ skipped.

| Status | File | Method | Glyph | Telegram reference / note |
|---|---|---|---|---|
| ✅ | `tool/DrawAlgorithms.java` | `drawPlayPause` / `buildPlayPause` | play ↔ pause morph (file/music progress, `PlayPauseButton`, `PlaybackController`, `HeaderFilling` player, theme preview) | `Theme.playPauseAnimator` in `MediaActionDrawable` (48dp ↔ the 13dp size argument): play 14.03 x 16.4dp shifted 1dp right, bars 4.69 x 15.24dp 2.93dp apart, 1.2dp corners |
| ✅ | `tool/DrawAlgorithms.java` | `drawMark` | ✕ mark (`HeaderFilling` player bar, `LiveLocationHelper`) | `BackDrawable` close: arms 8dp at 45° (shared `CLOSE_SPAN_DP`) |
| ✅ | `tool/DrawAlgorithms.java` | `drawCross` | diagonal "off" slash over an icon (mic/hang-up in `HeaderFilling`, `CallController`, `CircleButton`) | `msg_mute` / `CrossOutDrawable`: 1.67dp slash (3.67, 3.67) → (20.33, 20.33), 1.33dp clear gap cut on both sides |
| ✅ | `tool/DrawAlgorithms.java` | `drawAnimatedCross` | animated ✕ (`ClearButton`, `SimplestCheckBox` cross mode, track cancel) | `BackDrawable` / `CloseProgressDrawable2` close; overload with a stroke for `MediaActionDrawable` cancel |
| ✅ | `tool/DrawAlgorithms.java` | `drawCollapse` | chevron ↔ ✕ (`PinnedMessagesBar`, Instant View details `PageBlockRichText`) | chevron 5.33dp arms at 1.67dp morphing into the 2dp close of `BackDrawable` |
| ✅ | `tool/DrawAlgorithms.java` | `drawDirection` | chevron arrow left/right/up/down (`TGCommentButton`, `VideoTimelineView`, `RecordLockView`, `RecordAudioVideoController` "slide to cancel") | `msg_arrowright`: arms 5.33dp each axis, 90° tip, 1.67dp; new overload with arm/stroke sizes |
| ✅ | `tool/DrawAlgorithms.java` | `drawHorizontalDirection` | double chevrons (`widget/voip/SlideHintView`) | via `drawDirection` |
| ➖ | `tool/DrawAlgorithms.java` | `drawCloud` | cloud download/upload state (`FileProgressComponent`) | shape kept (Telegram has no cloud badge); glyphs inside restyled in `FileProgressComponent.drawCloudState` |
| ✅ | `tool/DrawAlgorithms.java` | `drawSimplestCheckBox` | round checkbox with check (avatar selection) | via `SimplestCheckBox` (`CheckBoxBase` mark) |
| ✅ | `widget/FileProgressComponent.java` | `drawCloudState` | download arrow / cloud / cancel ✕ over files and media | `MediaActionDrawable` ICON_DOWNLOAD / ICON_CHECK / ICON_CANCEL proportions (arrow .75 + 90° head, cancel ±7dp per 48dp with 1.5dp at 24dp) |
| ✅ | `widget/SimplestCheckBox.java` | `drawInBitmap` | filled circle with checkmark (media/chat selection) | `CheckBoxBase` mark (4dp / 9dp arms, 1.9dp); square variant uses `CheckBoxSquare` |
| ✅ | `widget/CheckBoxView.java` | `onDraw`, `drawCheckBox`, `drawCheckMark` | square checkbox + checkmark (settings rows) | `CheckBoxSquare`: 18dp, 2dp corners, mark (4,10) → (7,13) → (14,6), 2dp; marks now carry their stroke |
| ➖ | `widget/RadioView.java` | `onDraw` | radio button | already `RadioButton` at 20dp (ring r 9dp at 2dp, dot 5dp); M3 animation kept |
| ✅ | `widget/CheckView.java` | `onDraw` | circular check (selection) | `ic_ab_done` via `CheckBoxView.MARK_CHECK` |
| ✅ | `widget/SendButton.java` | `onDraw` | composer send button: edit-mode checkmark, progress | `input_done` check (1.84dp); `CloseProgressDrawable2` progress (8dp bars). Send icon itself not touched (drawable, other owner) |
| ✅ | `component/chat/SilentButton.java` | `onDraw` | slash over `outline_notifications_24` (silent broadcast) | via `drawCross` |
| ✅ | `navigation/BackHeaderButton.java` | `drawButton` | header ☰ menu ↔ ← back ↔ ✕ close morph | `MenuDrawable` / `BackDrawable` (tip 7.25dp, arms to ±6.5dp, close ±8dp) |
| ✅ | `navigation/StopwatchHeaderButton.java` | `onDraw` | stopwatch / self-destruct timer header button | `msg_stories_timer` at 1.67dp: dial r 9.5dp, stem, crown at 40°, hub |
| ✅ | `navigation/TriangleView.java` | `draw` | small dropdown triangle (`ToggleHeaderView`, `RotationControlView`) | `ic_arrow_drop_down`: 9.67 x 4.88dp |
| ✅ | `widget/ExpanderView.java` | `draw` | rotating expand arrow (`DrawerHeaderView` account switcher) | `msg_expand`: 12.5 x 6.2dp chevron, 1.67dp |
| ✅ | `widget/ClearButton.java` | `onDraw` | clear-text ✕ | `CloseProgressDrawable2`: 11.31dp span, progress arc r 8dp |
| ✅ | `widget/LinkPreviewToggleView.java` | `onDraw`, measuring | animated text lines of the link-preview position icon (with `baseline_link_preview_*` layers) | 1.67dp lines from the redrawn top layer (measured at ≥50% alpha now), 1.5dp media corners |
| ✅ | `player/RecordLockView.java` | `onDraw` | voice-record lock: shackle arc + body, pause bars, up chevron | `ChatActivityEnterView` lock: 16dp body with 3dp corners, 1.7dp shackle, 12dp stop, pause split with 3.32dp gap |
| ✅ | `data/TGMessageRich.java` | `drawChevron` | expand/collapse chevron (quote/rich blocks) | 90° chevron, 4dp arms, 1.5dp |
| ✅ | `data/TGMessageChecklist.java` | `drawContent` | done checkmark of checklist items (plus `SimplestCheckBox`) | `ic_ab_done` at 18dp (1.5dp) |
| ✅ | `data/TGMessagePoll.java` | `drawContent` | light rays around the poll explanation lamp (`deproko_baseline_lamp_*`) | `msg_emoji_objects` bulb: 1.4dp rays centred 2dp above the icon centre |
| ⏭ | `widget/MainNavigationBar.java`, `component/attach/*`, `mediaview/MediaViewController.java` | — | — | owned by others (aud/CONTEXT.md) |

### Layered drawables composed with code

| Status | Drawable | Used by | Note |
|---|---|---|---|
| ✅ | `baseline_lock_base_24` | `navigation/LockHeaderButton` | `msg_secret` body: 1.67dp outline, keyhole dot |
| ✅ | `baseline_lock_top_24` | `navigation/LockHeaderButton` | `msg_secret` shackle (1.67dp, butt legs hidden in the body stroke); still slides 8dp right when unlocked |
| ✅ | `baseline_link_preview_bg_24` | `widget/LinkPreviewToggleView` | dashed frame of 1.67dp round-capped dashes |
| ✅ | `baseline_link_preview_top_layer_24` | `widget/LinkPreviewToggleView` | 1.67dp lines, 2.5dp spacing, 6dp media block; layout read by the view at runtime |

`RecordLockView` does not use the lock layers (it draws the lock itself).
