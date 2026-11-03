/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 08/03/2017
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.chat.Waveform;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageFileLocal;
import org.thunderdog.challegram.loader.ImageMp3File;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.ImageVideoThumbFile;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.mediaview.MediaViewThumbLocation;
import org.thunderdog.challegram.mediaview.disposable.DisposableMediaViewController;
import org.thunderdog.challegram.player.TGPlayerController;
import org.thunderdog.challegram.telegram.TGLegacyAudioManager;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccentColor;
import org.thunderdog.challegram.telegram.TdlibFilesManager;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.DrawAlgorithms;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.TGMimeType;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.DrawableProvider;
import org.thunderdog.challegram.util.text.Highlight;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.stt.SttManager;
import org.thunderdog.challegram.ui.SettingsVoiceTranscriptionController;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.widget.FileProgressComponent;

import java.io.File;
import java.util.Locale;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.util.ViewProvider;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.StringUtils;
import me.vkryl.core.unit.ByteUnit;

public class FileComponent extends BaseComponent implements FileProgressComponent.SimpleListener, TGLegacyAudioManager.PlayListener, TGPlayerController.TrackListener, SttManager.TranscriptionListener {
  private @Nullable TdApi.Document doc;
  private @Nullable TdApi.Audio audio;
  private @Nullable TdApi.VoiceNote voice;
  private FileProgressComponent progress;
  private boolean needOpenIn;

  private boolean isPlaying;
  private long playDuration = -1, playPosition = -1;
  private int playSeconds;

  public boolean hasPreview;
  public ImageFile miniThumbnail, preview, fullPreview;
  private boolean mayBeTransparent;

  private @Nullable String title;
  private boolean needFakeTitle;
  private @Nullable String subtitle, subtitleMeasure;
  private @Nullable Waveform waveform;

  private @Nullable Text trimmedTitle, trimmedSubtitle;
  private float sizeWidth;

  private final TGMessage context;
  private final TdApi.Message message;

  // DOCUMENT

  public boolean needOpenIn () {
    return needOpenIn;
  }

  public FileComponent (@NonNull TGMessage context, @NonNull TdApi.Message message, @NonNull TdApi.Document doc) {
    this.context = context;
    this.message = message;
    if (this.needOpenIn = TD.isSupportedMusic(doc)) {
      // TdApi.VoiceNote fakeAudio = new TdApi.VoiceNote(0, null, doc.mimeType, doc.document);
      // setVoice(fakeAudio, null, null);
      TdApi.Audio fakeAudio = TD.newFakeAudio(doc);
      setAudio(fakeAudio, null, null);
      if (fakeAudio.audio.size < ByteUnit.KIB.toBytes(128)) {
        progress.setNoCloud();
      }
    } else {
      setDoc(doc);
    }
  }

  public void setDoc (@NonNull TdApi.Document doc) {
    this.doc = doc;
    this.title = doc.fileName;
    if (this.title == null || this.title.length() == 0) {
      this.title = getTitleFromMime(doc.mimeType);
    }
    this.needFakeTitle = Text.needFakeBold(title);
    initSubtitle();
    this.hasPreview = false;
    this.preview = null;
    this.miniThumbnail = null;
    this.fullPreview = null;
    if (doc.thumbnail == null && /*context.isSending() &&*/ TGMimeType.isImageMimeType(U.resolveMimeType(doc.document.local.path))) {
      hasPreview = true;

      preview = new ImageFileLocal(doc.document.local.path);
      preview.setProbablyRotated();
      preview.setDecodeSquare(true);
      preview.setSize(Screen.dp(80f, 3f));
      preview.setScaleType(ImageFile.CENTER_CROP);
    } else if (doc.thumbnail != null) {
      hasPreview = true;

      if (doc.minithumbnail != null) {
        miniThumbnail = new ImageFileLocal(doc.minithumbnail);
        miniThumbnail.setScaleType(ImageFile.CENTER_CROP);
        miniThumbnail.setDecodeSquare(true);
      } else {
        miniThumbnail = null;
      }

      preview = TD.toImageFile(context.tdlib(), doc.thumbnail);
      if (preview != null) {
        preview.setDecodeSquare(true);
        preview.setSize(Screen.dp(80f, 3f));
        preview.setScaleType(ImageFile.CENTER_CROP);
        preview.setNoBlur();
      }

      if (TGMimeType.isImageMimeType(doc.mimeType)) {
        createFullPreview();
      }
    }

    this.progress = new FileProgressComponent(context.context(), context.tdlib(), TdlibFilesManager.DOWNLOAD_FLAG_FILE, hasPreview && TGMimeType.isImageMimeType(doc.mimeType), message != null ? message.chatId : context.getChatId(), message != null ? message.id : context.getId());
    this.progress.setBackgroundColorProvider(context);
    this.progress.setSimpleListener(this);
    this.progress.setDocumentMetadata(doc, !hasPreview);
    if (hasPreview) {
      this.progress.setBackgroundColor(0x44000000);
    } else {
      this.progress.setBackgroundColorId(TdlibAccentColor.getFileColorId(doc, context.isOutgoingBubble()));
    }
    this.progress.setFile(doc.document, context.getMessage());
    if (viewProvider != null) {
      this.progress.setViewProvider(viewProvider);
    }
  }

  // AUDIO

  public FileComponent (@NonNull TGMessage context, @NonNull TdApi.Message message, @NonNull TdApi.Audio audio, TdApi.Message playPauseFile, TGPlayerController.PlayListBuilder playListBuilder) {
    this.context = context;
    this.message = message;

    setAudio(audio, playPauseFile, playListBuilder);
  }

  public void setAudio (TdApi.Audio audio, TdApi.Message playPauseFile, TGPlayerController.PlayListBuilder playListBuilder) {
    this.audio = audio;
    this.title = TD.getTitle(audio);
    this.needFakeTitle = Text.needFakeBold(title);
    initSubtitle();

    this.preview = null;
    this.miniThumbnail = null;
    this.fullPreview = null;
    this.hasPreview = audio.albumCoverThumbnail != null && (Config.ALLOW_BOT_COVERS || context.getMessage().viaBotUserId == 0); // preventing shit covers from @music and other bots
    if (hasPreview) {
      if (audio.albumCoverMinithumbnail != null) {
        miniThumbnail = new ImageFileLocal(audio.albumCoverMinithumbnail);
        miniThumbnail.setScaleType(ImageFile.CENTER_CROP);
        miniThumbnail.setDecodeSquare(true);
      } else {
        miniThumbnail = null;
      }

      preview = TD.toImageFile(context.tdlib(), audio.albumCoverThumbnail);
      if (preview != null) {
        preview.setDecodeSquare(true);
        preview.setScaleType(ImageFile.CENTER_CROP);
        preview.setNoBlur();
      }

      if (TD.isFileLoaded(audio.audio)) { // TODO: check why there was albumCoverThumbnail before
        createFullPreview();
      }
    }

    this.progress = new FileProgressComponent(context.context(), context.tdlib(), TdlibFilesManager.DOWNLOAD_FLAG_MUSIC, preview != null, message != null ? message.chatId : context.getChatId(), message != null ? message.id : context.getId());
    this.progress.setBackgroundColorProvider(context);
    this.progress.setSimpleListener(this);
    if (hasPreview) {
      progress.setBackgroundColor(Config.COVER_OVERLAY);
    } else {
      progress.setBackgroundColorId(context.isOutgoingBubble() ? ColorId.bubbleOut_file : ColorId.file);
    }
    this.progress.setPlayPauseFile(playPauseFile != null ? playPauseFile : TD.newFakeMessage(audio), playListBuilder);
    if (viewProvider != null) {
      this.progress.setViewProvider(viewProvider);
    }
  }

  // VOICE

  private float unreadFactor;

  public FileComponent (@NonNull TGMessage context, @NonNull TdApi.Message message, @NonNull TdApi.VoiceNote voice, TdApi.Message playPauseFile, TGPlayerController.PlayListBuilder playListBuilder) {
    this.context = context;
    this.message = message;

    setVoice(voice, playPauseFile, playListBuilder);
  }

  public void setVoice (TdApi.VoiceNote voice, TdApi.Message playPauseFile, TGPlayerController.PlayListBuilder playListBuilder) {
    this.voice = voice;

    initSubtitle();
    this.waveform = new Waveform(voice.waveform, Waveform.MODE_BITMAP, context.isOutgoingBubble());
    this.unreadFactor = playPauseFile != context.getMessage() || context.isContentRead() ? 0f : 1f;

    this.progress = new FileProgressComponent(context.context(), context.tdlib(), TdlibFilesManager.DOWNLOAD_FLAG_VOICE, false,message != null ? message.chatId : context.getChatId(), message != null ? message.id : context.getId());
    this.progress.setBackgroundColorProvider(context);
    this.progress.setSimpleListener(this);
    this.progress.setBackgroundColorId(context.isOutgoingBubble() ? ColorId.bubbleOut_file : ColorId.file);
    this.progress.setPlayPauseFile(playPauseFile != null ? playPauseFile : TD.newFakeMessage(voice), playListBuilder, this);
    if (this.viewProvider != null) {
      this.progress.setViewProvider(viewProvider);
    }

    this.sttKey = context.getChatId() != 0 && !TD.isSelfDestructTypeImmediately(message) ? SttManager.key(voice.voice.id, voice.voice.remote != null ? voice.voice.remote.uniqueId : null) : null;
    if (sttKey != null) {
      SttManager.instance().addTranscriptionListener(this);
    }

    if (TD.isSelfDestructTypeImmediately(message)) {
      this.progress.setDownloadedIconRes(R.drawable.baseline_hot_once_24);
      this.progress.setIgnorePlayPauseClicks(true);
      this.progress.setNoCloud();
    } else if (context.getChatId() == 0) { // Preview mode
      this.progress.setCurrentState(TdlibFilesManager.STATE_DOWNLOADED_OR_UPLOADED, false);
      this.progress.setDownloadedIconRes(R.drawable.baseline_pause_24);
    }
  }

  public void onContentOpened () {
    if (voice == null) {
      return;
    }
    if (unreadFactor == 1f) {
      if (!context.hasAnyTargetToInvalidate()) {
        unreadFactor = 0f;
      } else {
        FactorAnimator animator = new FactorAnimator(0, new FactorAnimator.Target() {
          @Override
          public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
            unreadFactor = factor;
            context.invalidate();
          }

          @Override
          public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {

          }
        }, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l, this.unreadFactor);
        animator.animateTo(0f);
      }
    }
    context.invalidate();
  }

  @Override
  public void onTrackStateChanged (Tdlib tdlib, long chatId, long messageId, int fileId, int state) {
    setPlaying(state == TGPlayerController.STATE_PLAYING || state == TGPlayerController.STATE_PAUSED);
  }

  @Override
  public void onTrackPlayProgress (Tdlib tdlib, long chatId, long messageId, int fileId, float progress, long position, long totalDuration, boolean isBuffering) {
    this.playPosition = position;
    this.playDuration = totalDuration;
    int newSeconds = position > 0 ? U.calculateRemainingSeconds(position) : 0;
    boolean secondsChanged = newSeconds != playSeconds;
    playSeconds = newSeconds;
    if (isPlaying) {
      if (secondsChanged) {
        setSubtitle(buildSubtitle());
      }
      context.invalidate();
    }
  }

  private void setPlaying (boolean isPlaying) {
    if (this.isPlaying != isPlaying) {
      this.isPlaying = isPlaying;
      if (!isPlaying) {
        dropSeek();
      }
      setSubtitle(buildSubtitle());
      context.invalidate();
    }
  }

  // COMMON

  public boolean isDocument () {
    return doc != null;
  }

  public boolean isAudio () {
    return audio != null;
  }

  public boolean isVoice () {
    return voice != null;
  }

  public FileProgressComponent getFileProgress () {
    return progress;
  }

  public void rebuildLayout () {
    if (lastMaxWidth != 0) {
      int width = lastMaxWidth;
      lastMaxWidth = 0;
      buildLayout(width);
    }
  }

  public void buildLayout (int maxWidth) {
    lastMaxWidth = maxWidth;
    if (title != null || subtitle != null) {
      buildTitles(maxWidth - (getPreviewSize() + getPreviewOffset()));
    }
    if (waveform != null) {
      waveform.layout(Math.min(Screen.dp(420f), Math.min(TGMessage.getEstimatedContentMaxWidth(), maxWidth) - Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS) * 2 - getPreviewOffset() - (int) sizeWidth - Screen.dp(12f) - getSttButtonSpace()));
    }
    buildSttText();
  }

  // Vibegram X: on-device transcription of voice messages (button next to the waveform, text under it)

  private static final float STT_BUTTON_WIDTH = 34f, STT_BUTTON_HEIGHT = 28f, STT_BUTTON_GAP = 8f, STT_TEXT_GAP = 6f;
  /**
   * While recognizing, a status row (ring with a stop square + "Transcribing… N%") goes under the text.
   * The text grows the bubble upwards (the chat is anchored at the bottom), so the button next to the waveform
   * keeps moving and a tap often misses it; the bottom of the bubble stays put, so the stop control there does not.
   */
  private static final float STT_STATUS_ROW = 24f;

  private @Nullable String sttKey;
  private @Nullable Text sttText;
  private @Nullable String sttTextSource;
  private boolean sttCaught;
  private final RectF sttButtonRect = new RectF();
  private @Nullable Text sttStatusText;
  private @Nullable String sttStatusSource;

  private int getSttButtonSpace () {
    return sttKey != null ? Screen.dp(STT_BUTTON_GAP + STT_BUTTON_WIDTH) : 0;
  }

  private @Nullable String getSttDisplayText () {
    if (sttKey == null) {
      return null;
    }
    SttManager.Transcription t = SttManager.instance().getTranscription(sttKey);
    if (!t.expanded) {
      return null;
    }
    switch (t.state) {
      case SttManager.STATE_RUNNING:
        // text recognized so far; the status goes into its own row
        return StringUtils.isEmpty(t.text) ? null : t.text;
      case SttManager.STATE_DONE:
        return StringUtils.isEmpty(t.text) ? Lang.getString(R.string.vg_SttEmpty) : t.text;
      case SttManager.STATE_FAILED:
        return Lang.getString(R.string.vg_SttFailed);
    }
    return null;
  }

  private @Nullable String getSttStatus () {
    if (sttKey == null) {
      return null;
    }
    SttManager.Transcription t = SttManager.instance().getTranscription(sttKey);
    if (!t.expanded || t.state != SttManager.STATE_RUNNING) {
      return null;
    }
    float progress = t.getDisplayProgress();
    if (progress < 0) {
      return Lang.getString(R.string.vg_SttQueued);
    }
    if (t.preparing) {
      return Lang.getString(R.string.vg_SttPreparing);
    }
    return Lang.getString(R.string.vg_SttProgress, (int) (progress * 100));
  }

  private void buildSttText () {
    int width = getWidth();
    String text = lastMaxWidth > 0 ? getSttDisplayText() : null;
    if (text == null) {
      sttText = null;
      sttTextSource = null;
    } else if (sttText == null || !text.equals(sttTextSource) || sttText.getMaxWidth() != width) {
      sttTextSource = text;
      sttText = new Text.Builder(text, width, TGMessage.getTextStyleProvider(), context.getTextColorSet()).build();
    }
    String status = lastMaxWidth > 0 ? getSttStatus() : null;
    int statusWidth = width;
    if (status == null || statusWidth <= 0) {
      sttStatusText = null;
      sttStatusSource = null;
    } else if (sttStatusText == null || !status.equals(sttStatusSource) || sttStatusText.getMaxWidth() != statusWidth) {
      sttStatusSource = status;
      sttStatusText = new Text.Builder(status, statusWidth, TGMessage.getTextStyleProvider(), context.getDecentColorSet()).singleLine().build();
    }
  }

  private int getSttTextHeight () {
    int height = 0;
    if (sttText != null) {
      height += Screen.dp(STT_TEXT_GAP) + sttText.getHeight();
    }
    if (sttStatusText != null) {
      height += Screen.dp(STT_TEXT_GAP + STT_STATUS_ROW);
    }
    return height;
  }

  @Override
  public void onTranscriptionStateChanged (String key) {
    if (sttKey == null || (key != null && !sttKey.equals(key))) {
      return;
    }
    int oldHeight = getHeight();
    buildSttText();
    if (getHeight() != oldHeight && context instanceof TGMessageFile) {
      ((TGMessageFile) context).onFileComponentHeightChanged();
    }
    // always redraw: the button changes from the progress ring to the arrow even when the height stays the same
    context.invalidate();
  }

  private long sttLastProgressUpdate;
  private boolean sttInvalidatePending;

  /** Refreshes the percentage under the waveform twice a second while the transcription is running. */
  private void updateSttProgressText () {
    long now = SystemClock.uptimeMillis();
    if (now - sttLastProgressUpdate < 500) {
      return;
    }
    sttLastProgressUpdate = now;
    String status = getSttStatus();
    if (status != null && !status.equals(sttStatusSource)) {
      UI.post(() -> onTranscriptionStateChanged(sttKey));
    }
  }

  private void onSttButtonClick () {
    if (sttKey == null) {
      return;
    }
    SttManager manager = SttManager.instance();
    SttManager.Transcription t = manager.getTranscription(sttKey);
    if (t.state == SttManager.STATE_RUNNING) {
      manager.cancelTranscription(sttKey);
      return;
    }
    if (t.state == SttManager.STATE_DONE || t.state == SttManager.STATE_FAILED) {
      if (t.state == SttManager.STATE_FAILED && !t.expanded) {
        startTranscription();
      } else {
        if (t.state == SttManager.STATE_DONE && !t.expanded) {
          markListened(context);
        }
        manager.setExpanded(sttKey, !t.expanded);
      }
      return;
    }
    startTranscription();
  }

  /**
   * Reading the transcription counts as listening: the voice message gets marked as listened,
   * for the sender too, the same as after playing it.
   */
  private static void markListened (TGMessage message) {
    if (!message.isOutgoing() && !message.isContentRead() && !TD.isSelfDestructTypeImmediately(message.getMessage())) {
      message.readContent();
    }
  }

  private void startTranscription () {
    final TdApi.VoiceNote voice = this.voice;
    if (voice == null || sttKey == null) {
      return;
    }
    final Tdlib tdlib = context.tdlib();
    // the message object outlives this component's binding, so the mark is applied even after scrolling away
    final TGMessage message = context;
    boolean started = SttManager.instance().transcribe(sttKey, () -> {
      TdApi.File file = voice.voice;
      if (file.local != null && file.local.isDownloadingCompleted && new File(file.local.path).exists()) {
        return file.local.path;
      }
      final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
      final String[] path = new String[1];
      tdlib.send(new TdApi.DownloadFile(file.id, 32, 0, 0, true), (result, error) -> {
        if (result != null && result.local != null && result.local.isDownloadingCompleted) {
          path[0] = result.local.path;
        }
        latch.countDown();
      });
      latch.await(2, java.util.concurrent.TimeUnit.MINUTES);
      return path[0];
    }, () -> markListened(message));
    if (!started) {
      UI.showToast(R.string.vg_SttNoModel, android.widget.Toast.LENGTH_SHORT);
      context.controller().navigateTo(new SettingsVoiceTranscriptionController(context.controller().context(), tdlib));
    }
  }

  private void drawSttButton (Canvas c, int x, int cy) {
    if (sttKey == null) {
      return;
    }
    boolean outgoing = context.isOutgoingBubble();
    int activeColor = Theme.getColor(outgoing ? ColorId.bubbleOut_waveformActive : ColorId.waveformActive);
    float width = Screen.dp(STT_BUTTON_WIDTH), height = Screen.dp(STT_BUTTON_HEIGHT);
    sttButtonRect.set(x, cy - height / 2f, x + width, cy + height / 2f);
    float radius = Screen.dp(8f);
    c.drawRoundRect(sttButtonRect, radius, radius, Paints.fillingPaint(ColorUtils.alphaColor(.16f, activeColor)));
    SttManager.Transcription t = SttManager.instance().getTranscription(sttKey);
    float cx = sttButtonRect.centerX();
    if (t.state == SttManager.STATE_RUNNING) {
      drawSttRing(c, t, cx, cy, activeColor);
      updateSttProgressText();
      if (!sttInvalidatePending) {
        // 30 frames per second are enough for the ring and leave the CPU to the recognition
        sttInvalidatePending = true;
        UI.post(() -> {
          sttInvalidatePending = false;
          context.invalidate();
        }, 33);
      }
    } else {
      boolean expanded = t.expanded && (t.state == SttManager.STATE_DONE || t.state == SttManager.STATE_FAILED);
      Drawable icon = Drawables.get(expanded ? R.drawable.baseline_keyboard_arrow_up_24 : R.drawable.vg_speech_to_text_24);
      c.save();
      float scale = expanded ? 1f : .8f;
      c.scale(scale, scale, cx, cy);
      Drawables.drawCentered(c, icon, cx, cy, Paints.getPorterDuffPaint(activeColor));
      c.restore();
    }
  }

  /** Progress ring with a stop square: a tap cancels. */
  private static void drawSttRing (Canvas c, SttManager.Transcription t, float cx, float cy, int activeColor) {
    float r = Screen.dp(8f);
    RectF arc = Paints.getRectF(cx - r, cy - r, cx + r, cy + r);
    float progress = t.getDisplayProgress();
    // the arc always turns, so the work is visibly going on even when the progress moves slowly
    float angle = (SystemClock.uptimeMillis() % 1500L) / 1500f * 360f;
    if (progress < 0) {
      // queued: indeterminate spinner
      c.drawArc(arc, angle, 270f, false, Paints.getProgressPaint(activeColor, Screen.dp(2f)));
    } else {
      c.drawArc(arc, 0, 360f, false, Paints.getProgressPaint(ColorUtils.alphaColor(.25f, activeColor), Screen.dp(2f)));
      c.drawArc(arc, angle - 90f, Math.max(36f, 360f * progress), false, Paints.getProgressPaint(activeColor, Screen.dp(2f)));
    }
    float half = Screen.dp(3f);
    c.drawRect(cx - half, cy - half, cx + half, cy + half, Paints.fillingPaint(activeColor));
  }

  private void drawSttStatus (Canvas c, int startX, int top, float alpha) {
    if (sttKey == null || sttStatusText == null) {
      return;
    }
    // Text only: progress and stop are already shown by the transcription button
    float cy = top + Screen.dp(STT_STATUS_ROW) / 2f;
    sttStatusText.draw(c, startX, startX + sttStatusText.getWidth(), 0, (int) (cy - sttStatusText.getHeight() / 2f), null, alpha);
  }

  private void buildTitles (int maxWidth) {
    trimmedTitle = title != null ? new Text.Builder(title, maxWidth, Paints.getTitleStyleProvider(), context.getTextColorSet()).textFlags(doc != null ? Text.FLAG_ELLIPSIZE_MIDDLE : 0).singleLine().allBold().highlight(context.getHighlightedText(Highlight.Pool.KEY_FILE_TITLE, title)).build() : null;

    float oldWidth = sizeWidth;
    trimSubtitle(maxWidth);
    if (sizeWidth != oldWidth && oldWidth != 0) {
      context.buildBubble(false);
    }
  }

  private @Nullable ViewProvider viewProvider;

  public void setViewProvider (@Nullable ViewProvider provider) {
    this.viewProvider = provider;
    progress.setViewProvider(provider);
  }

  private String buildProgressSubtitle (TdApi.File file, boolean forMeasure) {
    return buildProgressSubtitle(file, forMeasure, (progress != null && progress.isLoading()), context.disableBubble());
  }

  public static String buildProgressSubtitle (TdApi.File file, boolean isLoading, boolean needDownloading) {
    return buildProgressSubtitle(file, false, isLoading, needDownloading);
  }

  private static String buildProgressSubtitle (TdApi.File file, boolean forMeasure, boolean isLoading, boolean needDownloading) {
    if (file != null && file.local != null && file.remote != null && (forMeasure || (file.local.isDownloadingActive) || isLoading) && file.expectedSize != 0) {
      return Lang.getDownloadProgress(forMeasure ? file.expectedSize : file.remote.isUploadingActive ? file.remote.uploadedSize : file.local.downloadedSize, file.expectedSize, needDownloading);
    }
    return null;
  }

  private String buildSubtitle () {
    return buildSubtitle(null, false);
  }

  private void trimSubtitle (int maxWidth) {
    trimmedSubtitle = !StringUtils.isEmpty(subtitle) ? new Text.Builder(subtitle, maxWidth, Paints.getSubtitleStyleProvider(), context.getDecentColorSet()).singleLine().highlight(context.getHighlightedText(Highlight.Pool.KEY_FILE_SUBTITLE, subtitle)).build() : null;
    if (!StringUtils.isEmpty(subtitleMeasure)) {
      Text trimmedBigSubtitle = new Text.Builder(subtitleMeasure, maxWidth, Paints.getSubtitleStyleProvider(), context.getDecentColorSet()).singleLine().highlight(context.getHighlightedText(Highlight.Pool.KEY_FILE_SUBTITLE, subtitleMeasure)).build();
      sizeWidth = Math.max(Math.max(sizeWidth, getSubtitleWidth()), trimmedBigSubtitle.getWidth());
    } else {
      sizeWidth = Math.max(sizeWidth, getSubtitleWidth());
    }
  }

  private void initSubtitle () {
    this.subtitle = buildSubtitle(null, true);
    this.subtitleMeasure = buildSubtitle(subtitle, false);
  }

  private String buildSubtitle (String measure, boolean init) {
    if (measure != null) {
      String progress = buildProgressSubtitle(doc != null ? doc.document : audio != null ? audio.audio : null, true);
      return progress != null && !progress.equals(measure) ? progress : null;
    }
    if (doc != null) {
      final TdApi.File file = doc.document;
      if (file.remote.isUploadingActive && file.expectedSize == 0) {
        return Lang.getString(R.string.ProcessingFile);
      } else {
        String progress = buildProgressSubtitle(file, false);
        if (progress != null) {
          return progress;
        }
        String extension = null;
        if (!StringUtils.isEmpty(doc.fileName)) {
          extension = U.getExtension(doc.fileName);
        }
        if (StringUtils.isEmpty(extension) && !StringUtils.isEmpty(doc.mimeType)) {
          extension = TGMimeType.extensionForMimeType(doc.mimeType);
        }
        if (BuildConfig.THEME_FILE_EXTENSION.equalsIgnoreCase(extension)) {
          if (init && !StringUtils.isEmpty(title) && title.toLowerCase(Locale.ROOT).endsWith("." + BuildConfig.THEME_FILE_EXTENSION)) {
            title = title.substring(0, title.length() - 1 - BuildConfig.THEME_FILE_EXTENSION.length());
          }
          return Lang.getString(R.string.ThemeFile, Strings.buildSize(file.expectedSize));
        }
        if (!StringUtils.isEmpty(extension) && extension.length() <= 7) {
          extension = extension.toUpperCase(Locale.ROOT);
          if (init && !StringUtils.isEmpty(title) && title.toUpperCase(Locale.ROOT).endsWith("." + extension)) {
            title = title.substring(0, title.length() - 1 - extension.length());
          }
          return Lang.getString(R.string.format_fileSizeAndExtension, Strings.buildSize(file.expectedSize), extension);
        }
        return Strings.buildSize(file.expectedSize);
      }
    } else if (voice != null) {
      if (isPlaying) {
        return Strings.buildDuration(playSeconds);
      }
      return Strings.buildDuration(voice.duration);
    } else if (audio != null) {
      TdApi.File file = audio.audio;
      String progress = buildProgressSubtitle(file, false);
      return progress != null ? progress : TD.getSubtitle(audio);
    }
    return null;
  }

  private ImageFile createFullPreview () {
    if (fullPreview == null) {
      if (doc != null) {
        mayBeTransparent = TGMimeType.isTransparentImageMimeType(doc.mimeType);
        fullPreview = createFullPreview(context.tdlib(), doc);
      } else if (audio != null && TD.isFileLoaded(audio.audio) && (audio.albumCoverThumbnail == null || preview == null || Math.max(audio.albumCoverThumbnail.width, audio.albumCoverThumbnail.height) < 90)) {
        fullPreview = new ImageMp3File(audio.audio.local.path);
        fullPreview.setSize(Screen.dp(80f, 2f));
        fullPreview.setScaleType(ImageFile.CENTER_CROP);
      }
    }
    return fullPreview;
  }

  public boolean onLocaleChange () {
    return !progress.isLoading() && setSubtitle(buildSubtitle());
  }

  private int lastPreviewLeft, lastPreviewTop, lastPreviewRight, lastPreviewBottom;

  private int lastMaxWidth;

  private void layoutSize () {
    int maxWidth = lastMaxWidth - getPreviewOffset() - getPreviewSize();
    if (maxWidth <= 0) {
      return;
    }
    final float oldWidth = sizeWidth;
    trimSubtitle(maxWidth);
    if (sizeWidth != oldWidth && oldWidth != 0) {
      context.buildBubble(false);
    }
  }

  public void requestContent (ImageReceiver receiver) {
    if (hasPreview) {
      if ((doc != null && TD.isFileLoaded(doc.document) && TGMimeType.isImageMimeType(doc.mimeType)) ||
        (audio != null && TD.isFileLoaded(audio.audio))) {
        // receiver.setBounds(startX, startY, startX + getPreviewSize(), startY + getPreviewSize());
        receiver.requestFile(createFullPreview());
      } else {
        receiver.requestFile(null);
      }
    } else {
      receiver.requestFile(null);
    }
  }

  private int getTitleWidth () {
    return trimmedTitle != null ? trimmedTitle.getWidth() : 0;
  }

  private int getSubtitleWidth () {
    return trimmedSubtitle != null ? trimmedSubtitle.getWidth() : 0;
  }

  public int getWidth () {
    int contentWidth = getPreviewSize() + getPreviewOffset();
    if (waveform != null) {
      contentWidth += waveform.getWidth() + sizeWidth + Screen.dp(12f) + getSttButtonSpace();
    } else {
      contentWidth += Math.max(getTitleWidth(), sizeWidth) + Screen.dp(6f);
    }
    return contentWidth;
  }

  public int getHeight () {
    return getDocHeight() + getSttTextHeight();
  }

  private boolean loadCaught;
  private ViewParent seekCaught;
  private float seekStartX, desiredSeek;
  private long seekDesireTime;
  private boolean isSeeking;
  private boolean disallowBoundTouch;

  public void setDisallowBoundTouch () {
    disallowBoundTouch = true;
  }

  private void dropSeek () {
    if (seekCaught != null) {
      seekCaught.requestDisallowInterceptTouchEvent(false);
      seekCaught = null;
      isSeeking = false;
      context.invalidate();
    }
  }

  public boolean onTouchEvent (View view, MotionEvent event) {
    int startX = lastStartX;
    int startY = lastStartY;

    if (progress.onTouchEvent(view, event)) {
      return true;
    }
    if (disallowBoundTouch) {
      return false;
    }

    float x = event.getX();
    float y = event.getY();

    if (sttKey != null) {
      int action = event.getAction();
      if (action == MotionEvent.ACTION_DOWN) {
        float slop = Screen.dp(6f);
        sttCaught = x >= sttButtonRect.left - slop && x <= sttButtonRect.right + slop && y >= sttButtonRect.top - slop && y <= sttButtonRect.bottom + slop;
        if (sttCaught) {
          return true;
        }
      } else if (sttCaught) {
        if (action == MotionEvent.ACTION_UP) {
          sttCaught = false;
          onSttButtonClick();
          context.performClickSoundFeedback();
        } else if (action == MotionEvent.ACTION_CANCEL) {
          sttCaught = false;
        }
        return true;
      }
    }

    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        clearTouch();
        if (waveform != null && isPlaying && playDuration > 0 && playPosition >= 0) {
          int radius = Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS);
          int waveformLeft = startX + radius * 2 + getPreviewOffset();
          int cy = startY + radius;
          if (y >= cy - radius && y <= cy + radius && x >= waveformLeft && x <= waveformLeft + waveform.getWidth()) {
            seekStartX = startX;
            seekCaught = view.getParent();
            if (seekCaught != null) {
              seekCaught.requestDisallowInterceptTouchEvent(true);
              return true;
            }
            return false;
          }
        }
        if (title == null && subtitle == null) {
          return false;
        }
        float bound = progress.getRadius() * 1.6f;
        float cx = startX + progress.getRadius();
        float cy = startY + progress.getRadius();

        if (x >= cx - bound && x <= startX + getPreviewSize() + getPreviewOffset() + Math.max(getTitleWidth(), sizeWidth) + bound && y >= cy - bound && y <= cy + bound) {
          loadCaught = true;
        }
        return loadCaught;
      }
      case MotionEvent.ACTION_MOVE: {
        if (!(loadCaught || seekCaught != null))
          return false;
        if (waveform != null) {
          if (seekCaught != null && !isSeeking && Math.abs(seekStartX - x) >= Screen.getTouchSlop()) {
            isSeeking = true;
            seekStartX = x;
          }
          if (isSeeking) {
            int waveformLeft = startX + Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS) * 2 + getPreviewOffset();
            int waveformWidth = waveform.getWidth();
            float seek = MathUtils.clamp((x - waveformLeft) / (float) waveformWidth);
            boolean needInvalidate = desiredSeek == -1 || (int) ((float) waveformWidth * seek) != (int) ((float) waveformWidth * desiredSeek);
            desiredSeek = seek;
            if (needInvalidate) {
              context.invalidate();
            }
          }
          return seekCaught != null;
        }
        break;
      }
      case MotionEvent.ACTION_UP: {
        if (!(loadCaught || seekCaught != null))
          return false;
        if (isSeeking && desiredSeek != -1 && isPlaying && playDuration > 0) {
          seekDesireTime = SystemClock.uptimeMillis();
          TdlibManager.instance().audio().seekTo((long) ((double) playDuration * desiredSeek), playDuration);
        }
        dropSeek();
        if (loadCaught && progress.performClick(view)) {
          context.performClickSoundFeedback();
        }

        clearTouch();
        break;
      }
      case MotionEvent.ACTION_CANCEL: {
        if (!(loadCaught || seekCaught != null))
          return false;
        dropSeek();
        clearTouch();
        break;
      }
    }

    return loadCaught || seekCaught != null;
  }

  public void clearTouch () {
    loadCaught = false;
  }

  // private static final boolean USE_ROUND_SMOOTHING = false;

  public int getContentRadius (int defaultValue) {
    return hasPreview ? getPreviewSize() / 2 : defaultValue;
  }

  public void requestPreview (DoubleImageReceiver receiver) {
    if (hasPreview) {
      receiver.requestFile(miniThumbnail, preview);
    } else {
      receiver.clear();
    }
  }

  private int lastStartX, lastStartY;

  public MediaViewThumbLocation getMediaThumbLocation (View view, int viewTop, int viewBottom, int top) {
    if (hasPreview) {
      MediaViewThumbLocation thumbLocation = new MediaViewThumbLocation();
      thumbLocation.setNoBounce();

      final int previewSize = getPreviewSize();

      int actualTop = lastStartY + viewTop;
      int actualBottom = (view.getMeasuredHeight() - (lastStartY + previewSize)) + viewBottom;

      thumbLocation.set(lastStartX, lastStartY + top, lastStartX + previewSize, lastStartY + previewSize + top);
      thumbLocation.setClip(0, actualTop < 0 ? -actualTop : 0, 0, actualBottom < 0 ? -actualBottom : 0);
      thumbLocation.setRoundings(previewSize / 2);

      return thumbLocation;
    }
    return null;
  }

  public <T extends View & DrawableProvider> void draw (T view, Canvas c, int startX, int startY, Receiver preview, Receiver receiver, @ColorInt int backgroundColor, int contentReplaceColor, float alpha, float checkFactor) {
    this.lastStartX = startX;
    this.lastStartY = startY;

    final int previewSize = getPreviewSize();
    preview.setBounds(startX, startY, startX + previewSize, startY + previewSize);
    if (checkFactor != 0f) {
      c.save();
      float scale = 1f - .1f * checkFactor;
      c.scale(scale, scale, preview.centerX(), preview.centerY());
    }

    if (hasPreview) {
      /*int padding = USE_ROUND_SMOOTHING && path != null ? Screen.dp(1f) : 0;
      if (path != null) {
        layoutPath(startX - padding, startY - padding, startX + previewSize + padding, startY + previewSize + padding);
        ViewSupport.clipPath(c, path);
      }*/
      preview.setPaintAlpha(alpha * preview.getAlpha());
      receiver.setPaintAlpha(alpha * receiver.getAlpha());
      if (mayBeTransparent) {
        c.drawCircle(startX + previewSize / 2f, startY + previewSize / 2f, previewSize / 2f, Paints.fillingPaint(ColorUtils.alphaColor(alpha, Color.WHITE)));
      }
      DrawAlgorithms.drawReceiver(c, preview, receiver, true, true, startX, startY, startX + previewSize, startY + previewSize);
      receiver.restorePaintAlpha();
      preview.restorePaintAlpha();
      /*ViewSupport.restoreClipPath(c, path);
      if (USE_ROUND_SMOOTHING && path != null && backgroundColor != 0) {
        c.drawCircle(startX + previewSize / 2, startY + previewSize / 2, previewSize / 2 + padding, Paints.strokeBigPaint(backgroundColor));
      }*/
    }

    progress.setRequestedAlpha(alpha);
    progress.setBounds(startX, startY, startX + previewSize, startY + previewSize);
    progress.draw(view, c);

    if (checkFactor != 0f) {
      c.restore();
    }

    DrawAlgorithms.drawSimplestCheckBox(c, preview, checkFactor, contentReplaceColor);

    if (waveform == null) {
      int textLeft = startX + previewSize + getPreviewOffset();
      if (trimmedTitle != null) {
        trimmedTitle.draw(c, textLeft, textLeft + trimmedTitle.getWidth(), 0, startY + Screen.dp(8f), null, alpha);
      }
      if (trimmedSubtitle != null) {
        trimmedSubtitle.draw(c, textLeft, textLeft + trimmedSubtitle.getWidth(), 0, startY + Screen.dp(29f), null, alpha);
      }
    } else {
      // TODO alpha parameter support for voice messages
      float seek;
      if (context.getChatId() != 0) {
        if (isPlaying) {
          if (desiredSeek != -1 && (isSeeking || (seekDesireTime != 0 && SystemClock.uptimeMillis() - seekDesireTime < 100))) {
            seek = desiredSeek;
          } else if (playDuration <= 0 || playPosition <= 0) {
            seek = 0f;
          } else {
            seek = (float) ((double) playPosition / (double) playDuration);
          }
        } else {
          seek = context.isContentRead() ? 0f : 1f;
        }
      } else { // Chat Preview
        seek = .68f;
      }
      int waveformLeft = startX + Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS) * 2 + getPreviewOffset();
      int cy = startY + Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS);
      waveform.draw(c, seek, waveformLeft, cy, isPlaying && TD.isSelfDestructTypeImmediately(message));
      boolean align = context.isOutgoingBubble();
      if (unreadFactor != 0f) {
        int cx = startX + Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS);
        int fileRadius = Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS);
        float innerRadius = Screen.dp(3f);
        float outerRadius = innerRadius + Screen.dp(2f);
        double radians = Math.toRadians(45f);
        float x = cx + (float) ((double) fileRadius * Math.sin(radians)) + Screen.dp(22f);
        float y = cy + (float) ((double) fileRadius * Math.cos(radians));

        // c.drawCircle(x, y, outerRadius * unreadFactor, Paints.fillingPaint(context.getContentReplaceColor()));
        c.drawCircle(x, y, innerRadius * unreadFactor, Paints.fillingPaint(ColorUtils.alphaColor(unreadFactor, Theme.getColor(align ? ColorId.bubbleOut_waveformActive : ColorId.waveformActive))));
      }
      if (trimmedSubtitle != null) {
        int textX = startX + previewSize + getPreviewOffset() + waveform.getWidth() + Screen.dp(12f);
        trimmedSubtitle.draw(c, textX, textX + trimmedSubtitle.getWidth(), 0, startY + Screen.dp(18f), null, alpha);
      }
      if (sttKey != null) {
        int buttonX = startX + previewSize + getPreviewOffset() + waveform.getWidth() + Screen.dp(12f) + (int) sizeWidth + Screen.dp(STT_BUTTON_GAP);
        drawSttButton(c, buttonX, cy);
        int textTop = startY + getDocHeight();
        if (sttText != null) {
          sttText.draw(c, startX, startX + sttText.getWidth(), 0, textTop + Screen.dp(STT_TEXT_GAP), null, alpha);
          textTop += Screen.dp(STT_TEXT_GAP) + sttText.getHeight();
        }
        drawSttStatus(c, startX, textTop + Screen.dp(STT_TEXT_GAP), alpha);
      }
    }
  }

  private boolean setSubtitle (@Nullable String subtitle) {
    String measure = buildSubtitle(subtitle, false);
    if (!StringUtils.equalsOrBothEmpty(this.subtitle, subtitle) || !StringUtils.equalsOrBothEmpty(this.subtitleMeasure, measure)) {
      this.subtitle = subtitle;
      this.subtitleMeasure = measure;
      layoutSize();
      context.invalidate();
      return true;
    }
    return false;
  }

  public int getLastLineWidth () {
    if (waveform != null && sttStatusText != null) {
      return sttStatusText.getWidth();
    }
    if (waveform != null && sttText != null) {
      return sttText.getLastLineWidth();
    }
    return waveform != null ? TGMessage.BOTTOM_LINE_KEEP_WIDTH : (int) sizeWidth + getPreviewSize() + getPreviewOffset();
  }

  public void open () {
    if (doc != null) {
      progress.openFile(this.context.controller(), () ->
        UI.openFile(this.context.controller(), StringUtils.isEmpty(doc.fileName) ? null : doc.fileName, new File(doc.document.local.path), doc.mimeType, TD.getViewCount(this.context.getMessage().interactionInfo)));
      this.context.readContent();
    }
  }

  @Override
  public boolean onPlayPauseClick (FileProgressComponent context, View view, TdApi.File file, long messageId) {
    if (TD.isSelfDestructTypeImmediately(message)) {
      return DisposableMediaViewController.openMediaOrShowTooltip(view, this.context, (targetView, outRect) -> progress.toRect(outRect));
    }

    return false;
  }

  @Override
  public boolean onClick (FileProgressComponent context, View view, TdApi.File file, long messageId) {
    if (doc != null) {
      this.context.tdlib().ui().readCustomLanguage(this.context.controller(), doc, langPack ->
        this.context.controller().showOptions(Lang.getString(R.string.LanguageWarning), new int[] {R.id.btn_messageApplyLocalization, R.id.btn_open}, new String[] {Lang.getString(R.string.LanguageInstall), Lang.getString(R.string.Open)}, null, new int[] {R.drawable.baseline_language_24, R.drawable.baseline_open_in_browser_24}, (itemView, id) -> {
          if (id == R.id.btn_messageApplyLocalization) {
            this.context.tdlib().ui().showLanguageInstallPrompt(this.context.controller(), langPack, this.context.getMessage());
          } else if (id == R.id.btn_open) {
            open();
          }
          this.context.readContent();
          return true;
        }), this::open);
      return true;
    }
    return false;
  }

  @Override
  public void onPlayPause (int fileId, boolean isPlaying, boolean isUpdate) { }

  @Override
  public boolean needPlayProgress (int fileId) {
    return isVoice() || isAudio();
  }

  @Override
  public void onPlayProgress (int fileId, float progress, boolean isUpdate) {
    context.invalidate();
  }

  private boolean needProgress () {
    return waveform == null; // && (isAudio() || !context.useBubble());
  }

  @Override
  public void onStateChanged (TdApi.File file, @TdlibFilesManager.FileDownloadState int state) {
    if (needProgress()) {
      setSubtitle(buildSubtitle());
    }
  }

  @Override
  public void onProgress (TdApi.File file, float progress) {
    if (needProgress()) {
      setSubtitle(buildSubtitle());
    }
  }

  // STATIC

  private static int getDocHeight () {
    return Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS) * 2;
  }

  private static int getPreviewSize () {
    return Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS) * 2;
  }

  private static int getPreviewOffset () {
    return Screen.dp(12f);
  }

  public static ImageFile createFullPreview (Tdlib tdlib, TdApi.Document doc) {
    if (TGMimeType.isImageMimeType(doc.mimeType)) {
      ImageFile file = new ImageFile(tdlib, doc.document);
      file.setProbablyRotated();
      return createFullPreview(file, doc.mimeType);
    } else if (TGMimeType.isVideoMimeType(doc.mimeType)) {
      return createFullPreview(new ImageVideoThumbFile(tdlib, doc.document), doc.mimeType);
    }
    return null;
  }

  public static ImageFile createFullPreview (ImageFile preview, String mimeType) {
    preview.setSize(Screen.dp(80f, 3f));
    preview.setDecodeSquare(true);
    /*if (!"image/png".equals(mimeType)) {
      preview.setProbablyRotated();
    }*/
    preview.setNoBlur();
    preview.setScaleType(ImageFile.CENTER_CROP);
    return preview;
  }

  public static String getTitleFromMime (String mimeType) {
    String ext = TGMimeType.extensionForMimeType(mimeType);
    if (ext != null) {
      return ext.toUpperCase(Locale.ROOT) + " " + Lang.getString(R.string.File);
    }
    return mimeType;
  }

  @Nullable
  @Override
  public TdApi.File getFile () {
    return getFileProgress().getFile();
  }

  @Override
  public void performDestroy () {
    if (progress != null) {
      progress.performDestroy();
    }
    if (sttKey != null) {
      SttManager.instance().removeTranscriptionListener(this);
    }
  }
}
