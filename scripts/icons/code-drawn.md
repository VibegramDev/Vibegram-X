## Glyphs drawn in code (not converted yet)

These icons are painted with `Canvas`/`Path` in Java, so the drawable swap does not reach them. Paths are relative
to `app/src/main/java/org/thunderdog/challegram/`.

| File | Method | Glyph |
|---|---|---|
| `tool/DrawAlgorithms.java` | `drawPlayPause` / `buildPlayPause` | play ↔ pause morph (file/music progress, `PlayPauseButton`, `PlaybackController`, `HeaderFilling` player, theme preview) |
| `tool/DrawAlgorithms.java` | `drawMark` | checkmark (`HeaderFilling` player bar, `LiveLocationHelper`) |
| `tool/DrawAlgorithms.java` | `drawCross` | diagonal "off" slash over an icon (mic/hang-up in `HeaderFilling`, `CallController`, `CircleButton`) |
| `tool/DrawAlgorithms.java` | `drawAnimatedCross` | animated ✕ (`ClearButton`, `SimplestCheckBox` cross mode) |
| `tool/DrawAlgorithms.java` | `drawCollapse` | chevron ↔ ✕ (`PinnedMessagesBar`, Instant View details `PageBlockRichText`) |
| `tool/DrawAlgorithms.java` | `drawDirection` | chevron arrow left/right/up/down (`TGCommentButton`, `VideoTimelineView`, `RecordLockView`, `RecordAudioVideoController` "slide to cancel") |
| `tool/DrawAlgorithms.java` | `drawHorizontalDirection` | double chevrons (`widget/voip/SlideHintView`) |
| `tool/DrawAlgorithms.java` | `drawCloud` | cloud download/upload state (`FileProgressComponent`) |
| `tool/DrawAlgorithms.java` | `drawSimplestCheckBox` | round checkbox with check (avatar selection) |
| `widget/FileProgressComponent.java` | `drawCloudState` | download arrow / cloud / cancel ✕ over files and media |
| `widget/SimplestCheckBox.java` | `drawInBitmap` | filled circle with checkmark (media/chat selection) |
| `widget/CheckBoxView.java` | `onDraw`, `drawCheckBox` | square checkbox + checkmark (settings rows) |
| `widget/RadioView.java` | `onDraw` | radio button |
| `widget/CheckView.java` | `onDraw` | circular check (selection) |
| `widget/SendButton.java` | `onDraw` | composer send button: edit-mode checkmark (two rotated bars), progress arc |
| `component/chat/SilentButton.java` | `onDraw` | slash over `outline_notifications_24` (silent broadcast) |
| ✅ `navigation/BackHeaderButton.java` | `drawButton` | header ☰ menu ↔ ← back ↔ ✕ close morph |
| `navigation/StopwatchHeaderButton.java` | `onDraw` | stopwatch / self-destruct timer header button |
| `navigation/TriangleView.java` | `draw` | small dropdown triangle (`ToggleHeaderView`, `RotationControlView`) |
| `widget/ExpanderView.java` | `draw` | rotating expand arrow (`DrawerHeaderView` account switcher) |
| `widget/ClearButton.java` | `onDraw` | clear-text ✕ |
| `widget/LinkPreviewToggleView.java` | `onDraw` | animated text lines of the link-preview position icon (with `baseline_link_preview_*` layers) |
| `player/RecordLockView.java` | `onDraw` | voice-record lock: shackle arc + body, pause bars, up chevron |
| `data/TGMessageRich.java` | `drawChevron` | expand/collapse chevron (quote/rich blocks) |
| `data/TGMessageChecklist.java` | `drawContent` | done checkmark of checklist items (plus `SimplestCheckBox`) |
| `data/TGMessagePoll.java` | `drawContent` | light rays around the poll explanation lamp (`deproko_baseline_lamp_*`) |
