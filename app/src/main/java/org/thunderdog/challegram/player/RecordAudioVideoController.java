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
 * File created on 10/12/2017
 */
package org.thunderdog.challegram.player;

import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.N;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.chat.VoiceVideoButtonView;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.data.TGRecord;
import org.thunderdog.challegram.filegen.VideoGen;
import org.thunderdog.challegram.filegen.VideoGenerationInfo;
import org.thunderdog.challegram.helper.Recorder;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibFilesManager;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.stt.DictationRecorder;
import org.thunderdog.challegram.stt.SttManager;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeChangeListener;
import org.thunderdog.challegram.theme.ThemeManager;
import org.thunderdog.challegram.tool.DrawAlgorithms;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.widget.MainNavigationBar;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.MessagesController;
import org.thunderdog.challegram.ui.SettingsVoiceTranscriptionController;
import org.thunderdog.challegram.ui.camera.CameraControlButton;
import org.thunderdog.challegram.ui.camera.CameraController;
import org.thunderdog.challegram.ui.camera.legacy.CameraManagerLegacy;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.HapticMenuHelper;
import org.thunderdog.challegram.util.StuckWatch;
import org.thunderdog.challegram.widget.CircleFrameLayout;
import org.thunderdog.challegram.widget.NoScrollTextView;
import org.thunderdog.challegram.widget.SendButton;
import org.thunderdog.challegram.widget.ShadowView;
import org.thunderdog.challegram.widget.SimpleVideoPlayer;
import org.thunderdog.challegram.widget.VideoTimelineView;

import java.io.File;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.ViewUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.FileUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.StringUtils;
import me.vkryl.core.reference.ReferenceUtils;
import tgx.td.ChatId;
import tgx.td.Td;

public class RecordAudioVideoController implements
  Settings.VideoModePreferenceListener, FactorAnimator.Target,
  Recorder.Listener,
  CameraController.ReadyListener,
  RoundVideoRecorder.Delegate,
  TdlibFilesManager.SimpleListener,
  BaseActivity.ActivityListener,
  ThemeChangeListener {
  private static final int MAX_HQ_ROUND_RESOLUTION = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP ? 360 : 480;
  private static final int MAX_ROUND_RESOLUTION = 280;
  private static final int MAX_ROUND_DURATION_MS = 59500;

  private final BaseActivity context;

  private FrameLayoutFix rootLayout;
  private RecordBackgroundView videoBackgroundView;
  private ShadowView videoTopShadowView, videoBottomShadowView;
  private RecordButton recordBackground;
  private VoiceVideoButtonView voiceVideoButtonView;
  private RecordLockView lockView;
  private CameraControlButton switchCameraButton;
  private RecordControllerButton switchCameraButtonWrap;
  private CameraControlButton torchButton;
  private RecordControllerButton torchButtonWrap;
  private RoundZoomBar zoomBar;
  private RecordDisposableSwitchButton disposableSwitchButton;
  private RecordDurationView durationView;
  private FrameLayoutFix inputOverlayView;
  private TextView slideHintView;
  private TextView cancelView;
  private View cornerView;
  private CircleFrameLayout videoLayout;
  private View videoPlaceholderView;
  private RoundProgressView3 progressView;
  private SendButton sendButton;
  private ImageView deleteButton;
  private HapticMenuHelper sendHelper;
  private VideoTimelineView videoTimelineView;
  private SimpleVideoPlayer videoPreviewView;
  private VoiceWaveformView audioPreviewView;
  private ImageView muteIcon;

  private boolean preferVideoMode;

  public RecordAudioVideoController (BaseActivity context) {
    this.context = context;
    context.addActivityListener(this);

    this.preferVideoMode = Settings.instance().preferVideoMode();
    this.preferDictation = Settings.instance().getPreferredRecordMode() == Settings.RECORD_MODE_DICTATION;
    Settings.instance().addVideoPreferenceChangeListener(this);

    Recorder.instance();

    ThemeManager.instance().addThemeListener(this);
  }

  private Tdlib tdlib;

  public void setTdlib (Tdlib tdlib) {
    this.tdlib = tdlib;
  }

  // Theme

  @Override
  public boolean needsTempUpdates () {
    return Math.max(editFactor, recordFactor) > 0f;
  }

  @Override
  public void onThemeColorsChanged (boolean areTemp, ColorState state) {
    updateColors();
  }

  private void updateColors () {
    if (rootLayout == null) {
      return;
    }

    applyOverlayBackground();
    this.slideHintView.setTextColor(Theme.textDecentColor());
    this.cancelView.setTextColor(Theme.getColor(ColorId.textNeutral));
    this.videoPlaceholderView.setBackgroundColor(Theme.fillingColor());
    this.deleteButton.setColorFilter(Theme.iconColor());
    this.videoBackgroundView.setBackgroundColor(screenLightOn ? 0xffffffff : Theme.getColor(ColorId.previewBackground));

    this.cornerView.invalidate();
    this.switchCameraButton.invalidate();
    this.switchCameraButtonWrap.invalidate();
    this.torchButton.invalidate();
    this.torchButtonWrap.invalidate();
    this.zoomBar.invalidate();
    this.disposableSwitchButton.invalidate();
    this.videoTimelineView.invalidate();
    this.durationView.invalidate();
    this.lockView.invalidate();
    this.videoBottomShadowView.invalidate();
    this.videoTopShadowView.invalidate();
  }

  // Implementation

  public void onBackPressed () {
    if (recordMode == RECORD_MODE_VIDEO_EDIT) {
      ViewController<?> c = UI.getCurrentStackItem(context);
      if (c != null) {
        c.openAlert(R.string.DiscardVideoMessageTitle, R.string.DiscardVideoMessageDescription, Lang.getString(R.string.Discard), (dialog, which) -> closeVideoEditMode(null));
      }
    } else if (recordMode == RECORD_MODE_AUDIO_EDIT) {
      ViewController<?> c = UI.getCurrentStackItem(context);
      if (c != null) {
        c.openAlert(R.string.DiscardAudioMessageTitle, R.string.DiscardAudioMessageDescription, Lang.getString(R.string.Discard), (dialog, which) -> closeAudioEditMode(null));
      }
    } else {
      finishRecording(true);
    }
  }

  @Override
  public void onActivityPause () {
    videoPreviewView.setActivityPaused(true);
    if (isOpen() && !inRaiseMode) {
      finishRecording(true);
    }
  }

  @Override
  public void onActivityResume () {
    videoPreviewView.setActivityPaused(false);
  }

  @Override
  public void onActivityDestroy () {
    videoPreviewView.setActivityPaused(true);
    if (isOpen()) {
      if (recordMode == RECORD_MODE_VIDEO_EDIT) {
        closeVideoEditMode(null);
      } else if (recordMode == RECORD_MODE_AUDIO_EDIT) {
        closeAudioEditMode(null);
      } else {
        stopRecording(CLOSE_MODE_CANCEL, false);
      }
    }
  }

  @Override
  public void onActivityPermissionResult (int code, boolean granted) {

  }

  @Override
  public void onPreferVideoModeChanged (boolean preferVideoMode) {
    this.preferVideoMode = preferVideoMode;
  }

  @Override
  public void onPreferRecordModeChanged (int recordMode) {
    this.preferVideoMode = recordMode == Settings.RECORD_MODE_ROUND;
    this.preferDictation = recordMode == Settings.RECORD_MODE_DICTATION;
  }

  // Vibegram: dictation, the third mode of the record button. Recording looks like a voice message (same bar,
  // lock and slide-to-cancel), but finishing it recognizes the speech with the selected transcription model
  // and inserts the text into the composer instead of sending anything.

  private boolean preferDictation;
  /** The current (or last) recording is a dictation. Set when a recording starts. */
  private boolean dictating;

  private final DictationRecorder.Listener dictationListener = new DictationRecorder.Listener() {
    @Override
    public void onAmplitude (float amplitude) {
      RecordAudioVideoController.this.onAmplitude(amplitude);
    }

    @Override
    public void onFail () {
      RecordAudioVideoController.this.onFail();
    }

    @Override
    public void onMaxDurationReached () {
      // 10 minutes recorded: finish as if the user released the button, nothing more is captured anyway
      if (dictating && currentRecording == RECORD_MODE_AUDIO) {
        finishRecording(false);
      }
    }
  };

  private void finishDictation (boolean cancel) {
    if (cancel || !hasValidOutputTarget()) {
      DictationRecorder.instance().stop(true, null);
      return;
    }
    final MessagesController target = targetController;
    final long chatId = targetChatId;
    final TdApi.MessageTopic topicId = targetMessageTopicId;
    target.setDictationInProgress(true);
    // The microphone thread finishes on its own; the samples come back without blocking the UI thread.
    DictationRecorder.instance().stop(false, samples -> {
      if (samples == null) {
        if (!target.isDestroyed() && target.compareChat(chatId, topicId)) {
          target.setDictationInProgress(false);
        }
        return;
      }
      recognizeDictation(samples, target, chatId, topicId);
    });
  }

  /**
   * Dictation only (owner): a chat message does not end with a period, so one final «.» the model put after
   * the last sentence is dropped. «...», «?», «!» stay; voice/round/video transcription is not affected.
   */
  private static String withoutFinalPeriod (String text) {
    if (text == null) {
      return null;
    }
    int end = text.length();
    while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
      end--;
    }
    if (end > 0 && text.charAt(end - 1) == '.' && !(end > 1 && text.charAt(end - 2) == '.')) {
      end--;
      while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
        end--;
      }
    }
    return text.substring(0, end);
  }

  private static void recognizeDictation (float[] samples, MessagesController target, long chatId, TdApi.MessageTopic topicId) {
    boolean started = SttManager.instance().transcribeSamples(samples, recognized -> {
      String text = withoutFinalPeriod(recognized);
      boolean sameChat = !target.isDestroyed() && target.compareChat(chatId, topicId);
      if (sameChat) {
        target.setDictationInProgress(false);
      }
      if (text == null) {
        UI.showToast(R.string.vg_DictationFailed, Toast.LENGTH_SHORT);
      } else if (text.isEmpty()) {
        UI.showToast(R.string.vg_DictationEmpty, Toast.LENGTH_SHORT);
      } else if (sameChat) {
        target.insertDictatedText(text);
      } else {
        // The chat was closed while recognizing: keep the text rather than put it into another chat.
        UI.copyText(text, R.string.vg_DictationCopied);
      }
    });
    if (!started) {
      if (!target.isDestroyed() && target.compareChat(chatId, topicId)) {
        target.setDictationInProgress(false);
      }
      UI.showToast(R.string.vg_DictationFailed, Toast.LENGTH_SHORT);
    }
  }

  // Layout

  public View prepareViews () {
    if (rootLayout == null) {
      this.rootLayout = new FrameLayoutFix(context) {
        @Override
        public boolean onInterceptTouchEvent (MotionEvent ev) {
          return Math.max(recordFactor, editFactor) == 0f;
        }

        @Override
        public boolean onTouchEvent (MotionEvent event) {
          return Math.max(recordFactor, editFactor) != 0f && super.onTouchEvent(event);
        }
      };
      this.rootLayout.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

      this.videoBackgroundView = new RecordBackgroundView(context);
      this.videoBackgroundView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      this.rootLayout.addView(videoBackgroundView);

      this.inputOverlayView = new FrameLayoutFix(context) {
        @Override
        public boolean onTouchEvent (MotionEvent event) {
          return Views.isValid(this);
        }
      };
      this.inputOverlayView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(49f), Gravity.BOTTOM));
      this.rootLayout.addView(inputOverlayView);

      this.slideHintView = new NoScrollTextView(context) {
        private int prevWidth;
        @Override
        protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
          super.onMeasure(widthMeasureSpec, heightMeasureSpec);
          int newWidth = getMeasuredWidth();
          if (prevWidth != newWidth) {
            prevWidth = newWidth;
            updateTranslations();
          }
        }
      };
      this.slideHintView.setText(Lang.getString(R.string.slideToCancel));
      this.slideHintView.setTypeface(Fonts.getRobotoRegular());
      this.slideHintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
      this.slideHintView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
      this.inputOverlayView.addView(slideHintView);

      this.cornerView = new View(context) {
        @Override
        protected void onDraw (Canvas c) {
          // Telegram's "slide to cancel" arrow (ChatActivityEnterView.SlideTextView): arms 4dp back, 5dp to the side, 1.6dp
          DrawAlgorithms.drawDirection(c, 0, getMeasuredHeight() / 2, Theme.textDecentColor(), Gravity.LEFT, Screen.dpf(4f), Screen.dpf(5f), Screen.dpf(1.6f));
        }
      };
      this.cornerView.setLayoutParams(FrameLayoutFix.newParams(Screen.dp(12f), Screen.dp(16f), Gravity.CENTER));
      this.inputOverlayView.addView(cornerView);

      this.cancelView = new NoScrollTextView(context) {
        @Override
        public boolean onTouchEvent (MotionEvent e) {
          return Views.onTouchEvent(this, e) && super.onTouchEvent(e);
        }
      };
      this.cancelView.setGravity(Gravity.CENTER);
      this.cancelView.setText(Lang.getString(R.string.Cancel));
      this.cancelView.setPadding(Screen.dp(12f), 0, Screen.dp(12f), 0);
      this.cancelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
      this.cancelView.setTypeface(Fonts.getRobotoMedium());
      this.cancelView.setAlpha(0f);
      this.cancelView.setOnClickListener(v -> {
        if (isReleased) {
          stopRecording(CLOSE_MODE_CANCEL, false);
        }
      });
      this.cancelView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
      this.inputOverlayView.addView(cancelView);

      this.videoTopShadowView = new ShadowView(context);
      this.videoTopShadowView.setSimpleTopShadow(true);
      this.videoTopShadowView.setLayoutParams(Views.newLayoutParams(videoTopShadowView, Gravity.BOTTOM));
      this.rootLayout.addView(videoTopShadowView);

      this.videoBottomShadowView = new ShadowView(context);
      this.videoBottomShadowView.setSimpleBottomTransparentShadow(true);
      this.videoBottomShadowView.setLayoutParams(Views.newLayoutParams(videoBottomShadowView, Gravity.BOTTOM));
      this.rootLayout.addView(videoBottomShadowView);

      // Duration
      this.durationView = new RecordDurationView(context);
      this.durationView.setTimerCallback(() -> {
        float progress = recordingVideo ? getRecordProgress() : 0f;
        progressView.setVisualProgress(MathUtils.clamp(progress));
        if (progress >= 1f) {
          finishRecording(true);
        }
      });
      this.durationView.setLayoutParams(Views.newLayoutParams(durationView, Gravity.LEFT | Gravity.BOTTOM));
      this.rootLayout.addView(durationView);

      this.recordBackground = new RecordButton(context);
      this.recordBackground.setOnClickListener(v -> {
        if (isOpen() && isReleased) {
          finishRecording(false);
        }
      });
      this.rootLayout.addView(recordBackground);

      this.switchCameraButton = new CameraControlButton(context) {
        @Override
        public boolean onTouchEvent (MotionEvent event) {
          return Views.isValid(this) && super.onTouchEvent(event);
        }
      };
      this.switchCameraButton.setCameraIconRes(!Settings.instance().startRoundWithRear());
      /*this.switchCameraButton.setSpinCallback(new CameraControlButton.SpinCallback() {
        @Override
        public void onSpin (CameraControlButton v, float rotate, float scaleFactor) {
          float scale = 1f + .1f * scaleFactor;
          videoLayout.setRotationY(rotate);
          videoLayout.setScaleX(scale);
          videoLayout.setScaleY(scale);
        }
      });*/
      this.switchCameraButton.setIsSmall();
      this.switchCameraButtonWrap = new RecordControllerButton(context);
      this.switchCameraButtonWrap.init(null);
      this.switchCameraButtonWrap.setOnClickListener(v -> {
        if (ownedCamera != null) {
          ownedCamera.switchCamera();
        }
      });
      this.switchCameraButtonWrap.addView(switchCameraButton);
      this.rootLayout.addView(switchCameraButtonWrap);

      this.torchButton = new CameraControlButton(context) {
        @Override
        public boolean onTouchEvent (MotionEvent event) {
          return Views.isValid(this) && super.onTouchEvent(event);
        }
      };
      this.torchButton.setIconRes(R.drawable.baseline_flash_off_24);
      this.torchButton.setIsSmall();
      this.torchButtonWrap = new RecordControllerButton(context);
      this.torchButtonWrap.init(null);
      this.torchButtonWrap.setOnClickListener(v -> setTorchEnabled(!torchEnabled));
      this.torchButtonWrap.addView(torchButton);
      this.rootLayout.addView(torchButtonWrap);

      // Vibegram: zoom buttons + ruler under the round preview (positioned in updateVideoY)
      this.zoomBar = new RoundZoomBar(context);
      this.zoomBar.setDelegate((ratio, target) -> {
        if (ownedCamera != null) {
          ownedCamera.getLegacyManager().requestZoomRatio(ratio, target);
        }
      });
      this.zoomBar.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));
      this.zoomBar.setOnSizeChangedListener(this::updateVideoY);
      this.rootLayout.addView(zoomBar);

      this.disposableSwitchButton = new RecordDisposableSwitchButton(context);
      this.disposableSwitchButton.init(null);
      this.disposableSwitchButton.setOnClickListener(v -> disposableSwitchButton.toggleActive(true));
      this.rootLayout.addView(disposableSwitchButton);

      this.lockView = new RecordLockView(context);
      Views.setSimpleStateListAnimator(lockView);
      rootLayout.addView(lockView);
      this.lockView.setOnClickListener(v -> {
        if (recordMode == RECORD_MODE_AUDIO_EDIT) {
          resumeRecordingImpl(RECORD_MODE_AUDIO);
        } else if (recordMode == RECORD_MODE_VIDEO_EDIT) {
          resumeRecordingImpl(RECORD_MODE_VIDEO);
        } else if (isReleased && !dictating) {
          finishRecording(true);
        }
      });

      this.voiceVideoButtonView = new VoiceVideoButtonView(context) {
        @Override
        protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
          super.onLayout(changed, left, top, right, bottom);
          updateTranslations();
          updatePositions();
        }
      };
      this.voiceVideoButtonView.setLayoutParams(FrameLayoutFix.newParams(Screen.dp(50f), Screen.dp(49f), Gravity.RIGHT | Gravity.BOTTOM));
      ViewSupport.setHigherElevation(voiceVideoButtonView, recordBackground, true);
      rootLayout.addView(voiceVideoButtonView);

      this.videoLayout = new CircleFrameLayout(context) {
        @Override
        public boolean onTouchEvent (MotionEvent event) {
          return Views.isValid(this) && super.onTouchEvent(event);
        }

        @Override
        protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
          final int width = MeasureSpec.getSize(widthMeasureSpec);
          final int height = MeasureSpec.getSize(heightMeasureSpec);

          final int size = Math.min(Math.min(width - Screen.dp(80), height - Screen.dp(80)), Screen.dp(640));
          final int sizeSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);

          super.onMeasure(sizeSpec, sizeSpec);
        }
      };
      this.videoLayout.setOnClickListener(v -> {
        if (recordMode == RECORD_MODE_VIDEO_EDIT) {
          videoPreviewView.toggleMuted();
        }
      });
      this.videoLayout.setTransparentOutline(false);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        this.videoLayout.setTranslationZ(Screen.dp(1.5f));
        this.videoLayout.setElevation(Screen.dp(1f));
      }
      this.videoLayout.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
      this.rootLayout.addView(videoLayout);

      this.videoPlaceholderView = new View(context);
      this.videoPlaceholderView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      this.videoLayout.addView(videoPlaceholderView);

      this.progressView = new RoundProgressView3(context) {
        @Override
        protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
          final int width = MeasureSpec.getSize(widthMeasureSpec);
          final int height = MeasureSpec.getSize(heightMeasureSpec);

          final int size = Math.min(Math.min(width - Screen.dp(80), height - Screen.dp(80)), Screen.dp(640)) + Screen.dp(RoundProgressView3.PADDING * 2 + 20);
          final int sizeSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);

          super.onMeasure(sizeSpec, sizeSpec);
        }
      };
      this.progressView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
      this.progressView.setDelegate(new RoundProgressView3.Delegate() {

        @Override
        public void onTrimSliderDown (RoundProgressView3 view, float start, float end, boolean isEnd) {
          videoTimelineView.performSliderDown(isEnd);
        }

        @Override
        public void onTrimSliderMove (RoundProgressView3 view, float start, float end, boolean isEnd) {
          videoTimelineView.performSliderMove(isEnd ? end : start, isEnd);
        }

        @Override
        public void onTrimSliderUp (RoundProgressView3 view, float start, float end, boolean isEnd) {
          videoTimelineView.performSliderUp(isEnd);
        }
      });
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        this.progressView.setTranslationZ(Screen.dp(2f));
        this.progressView.setElevation(Screen.dp(1.5f));
      }
      this.rootLayout.addView(progressView);

      this.deleteButton = new ImageView(context) {
        @Override
        public boolean onTouchEvent (MotionEvent event) {
          return editFactor > 0f && Views.isValid(this) && super.onTouchEvent(event);
        }
      };
      this.deleteButton.setScaleType(ImageView.ScaleType.CENTER);
      this.deleteButton.setImageResource(R.drawable.baseline_delete_24);
      Views.setClickable(deleteButton);
      this.deleteButton.setOnClickListener(v -> {
        if (recordMode == RECORD_MODE_VIDEO_EDIT) {
          closeVideoEditMode(null);
        } else if (recordMode == RECORD_MODE_AUDIO_EDIT) {
          closeAudioEditMode(null);
        }
      });
      this.deleteButton.setLayoutParams(FrameLayoutFix.newParams(Screen.dp(56f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));
      this.inputOverlayView.addView(deleteButton);

      this.sendButton = new SendButton(context, R.drawable.deproko_baseline_send_24) {
        @Override
        public boolean onTouchEvent (MotionEvent event) {
          return editFactor > 0f && Views.isValid(this) && super.onTouchEvent(event);
        }
      };
      Views.setClickable(sendButton);
      this.sendButton.setOnClickListener(v -> {
        if (!targetController.showSlowModeRestriction(v, null)) {
          if (recordMode == RECORD_MODE_VIDEO_EDIT) {
            sendVideo(Td.newSendOptions());
          } else if (recordMode == RECORD_MODE_AUDIO_EDIT) {
            sendAudio(Td.newSendOptions());
          }
        }
      });
      this.sendButton.setLayoutParams(FrameLayoutFix.newParams(Screen.dp(55f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT));
      this.inputOverlayView.addView(sendButton);

      this.videoTimelineView = new VideoTimelineView(context) {
        @Override
        public boolean onTouchEvent (MotionEvent event) {
          return editFactor > 0f && Views.isValid(this) && super.onTouchEvent(event);
        }
      };
      this.videoTimelineView.setDelegate(new VideoTimelineView.TimelineDelegate() {
        @Override
        public boolean canTrimTimeline (VideoTimelineView v) {
          return videoPreviewView != null && videoPreviewView.canTrimRegion();
        }

        @Override
        public void onTrimStartEnd (VideoTimelineView v, boolean isStarted) {
          videoPreviewView.setPlaying(!isStarted);
        }

        @Override
        public void onTimelineTrimChanged (VideoTimelineView v, double totalDuration, double startTimeSeconds, double endTimeSeconds) {
          videoPreviewView.setTrimRegion(totalDuration, startTimeSeconds, endTimeSeconds);
        }

        @Override
        public void onTimelineVisualTrimChanged (VideoTimelineView v, double totalDuration, double startTimeSeconds, double endTimeSeconds) {
          if (totalDuration == 0.0) {
            progressView.setTrimFactors(0f, 1f);
          } else {
            progressView.setTrimFactors((float) (startTimeSeconds / totalDuration), (float) (endTimeSeconds / totalDuration));
          }
        }

        @Override
        public void onSeekTo (VideoTimelineView v, float progress) {
          videoPreviewView.seekTo(progress);
        }
      });
      videoTimelineView.setPadding(Screen.dp(14f), Screen.dp(5f), Screen.dp(14f), Screen.dp(5f));
      FrameLayoutFix.LayoutParams params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
      params.leftMargin = params.rightMargin = Screen.dp(56f) - Screen.dp(14f);
      this.videoTimelineView.setLayoutParams(params);
      this.inputOverlayView.addView(videoTimelineView);

      params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
      params.leftMargin = params.rightMargin = Screen.dp(56f);
      this.audioPreviewView = new VoiceWaveformView(context);
      this.audioPreviewView.setLayoutParams(params);
      this.inputOverlayView.addView(audioPreviewView);

      this.videoPreviewView = new SimpleVideoPlayer(context);
      this.videoPreviewView.setMuted(true);
      this.videoPreviewView.setPlaying(true);
      this.videoPreviewView.setLooping(true);
      this.videoPreviewView.setDelegate(new SimpleVideoPlayer.Delegate() {
        @Override
        public void onVideoRenderStateChanged (boolean hasFrame) {
          renderAnimator.setValue(hasFrame, hasFrame);
        }

        @Override
        public void onVideoMuteStateChanged (boolean isMuted) {
          muteAnimator.setValue(isMuted, editFactor > 0f);
        }
      });
      this.videoPreviewView.setAlpha(0f);
      this.videoPreviewView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      this.videoLayout.addView(videoPreviewView);

      this.muteIcon = new ImageView(context) {
        @Override
        protected void onDraw (Canvas c) {
          c.drawCircle(getMeasuredWidth() / 2, getMeasuredHeight() / 2, Screen.dp(12f), Paints.fillingPaint(0x40000000));
          super.onDraw(c);
        }
      };
      this.muteIcon.setScaleType(ImageView.ScaleType.CENTER);
      this.muteIcon.setImageResource(R.drawable.deproko_baseline_sound_muted_24);
      this.muteIcon.setColorFilter(0xffffffff);
      params = FrameLayoutFix.newParams(Screen.dp(12f) * 2 + Screen.dp(2f), Screen.dp(12f) * 2 + Screen.dp(2), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
      params.bottomMargin = Screen.dp(8f);
      this.muteIcon.setLayoutParams(params);
      this.videoLayout.addView(muteIcon);

      updateColors();

      setRecordFactor(0f);
    }
    return rootLayout;
  }

  private float getRecordProgress () {
    return startTime != 0 ? (float) ((double) (SystemClock.uptimeMillis() - startTime + lastDuration) / (double) MAX_ROUND_DURATION_MS) : 0f;
  }

  @Override
  public void onCameraCompletelyReady (CameraController camera) {
    if (recordMode == RECORD_MODE_VIDEO) {
      isCameraReady = true;
      // Vibegram: the camera a recording actually starts with counts as "last used" too
      Settings.instance().setRoundLastCameraRear(!roundFrontFace);
      checkActualRecording(CLOSE_MODE_CANCEL);
    }
  }

  // private int[] inputPos = new int[2];

  private static final float SLIDE_DISTANCE = 50f;

  private void updateMiddle () {
    float actualX = Math.max(-Screen.dp(SLIDE_DISTANCE), getActualTranslateX());
    float closeFactor = MathUtils.clamp(-actualX / (float) Screen.dp(SLIDE_DISTANCE));

    float slideAlpha = (1f - releaseFactor) * (1f - closeFactor);
    slideHintView.setAlpha(slideAlpha);
    cornerView.setAlpha(slideAlpha);

    float editFactor = this.editFactor * (1f - recordFactor);

    cancelView.setAlpha(releaseFactor * (1f - editFactor));

    float dy = inputOverlayView.getMeasuredHeight() / 2;
    float addDy = editFactor * dy;
    cancelView.setTranslationY(-dy * (1f - releaseFactor) + addDy);
    slideHintView.setTranslationY(dy * releaseFactor);
    cornerView.setTranslationY(dy * releaseFactor);

    float editDy = -dy + addDy;
    sendButton.setAlpha(this.editFactor);
    deleteButton.setAlpha(editFactor);
    deleteButton.setTranslationY(editDy);
    videoTimelineView.setAlpha(editFactor);
    videoTimelineView.setTranslationY(editDy);
    audioPreviewView.setAlpha(editFactor);
    audioPreviewView.setTranslationY(editDy);
  }

  private void updateTranslations () {
    float actualY = getActualTranslateY();
    int threshold = -Screen.dp(33f) * 2;
    if (!isReleased) {
      float collapseFactor = actualY <= threshold ? 1f : actualY / threshold;
      lockView.setCollapseFactor(collapseFactor);

      if (collapseFactor == 1f) {
        setReleased(true, true);
      }
    }
    float actualX = Math.max(-Screen.dp(SLIDE_DISTANCE), getActualTranslateX());
    float closeFactor = MathUtils.clamp(-actualX / (float) Screen.dp(SLIDE_DISTANCE));

    float cornerX = -slideHintView.getMeasuredWidth() / 2 + actualX - cornerView.getMeasuredWidth();
    slideHintView.setTranslationX(actualX);
    cornerView.setTranslationX(cornerX);
    updateMiddle();
    cornerX += cornerView.getLeft();

    durationView.setTranslationX(Math.min(0, cornerX - durationView.getMeasuredWidth()));

    updateVideoY();
    updateDuration();

    inputOverlayView.setTranslationY(overallTranslation + overlayExtraY);
    videoTopShadowView.setTranslationY(overallTranslation - inputOverlayView.getMeasuredHeight());
    videoBottomShadowView.setTranslationY(overallTranslation + videoBottomShadowView.getMeasuredHeight());

    float y = overallTranslation + buttonExtraY + actualY;
    voiceVideoButtonView.setTranslationY(y);

    // Relative to voiceVideoButtonView

    float top = voiceVideoButtonView.getTop() + voiceVideoButtonView.getTranslationY();

    float cx = voiceVideoButtonView.getLeft() + voiceVideoButtonView.getTranslationX() + voiceVideoButtonView.getMeasuredWidth() / 2;
    float cy = top + voiceVideoButtonView.getMeasuredHeight() / 2;

    videoBackgroundView.setPivot((int) cx, (int) cy);

    recordBackground.setTranslationX(cx - recordBackground.getMeasuredWidth() / 2);
    recordBackground.setTranslationY(cy - recordBackground.getMeasuredHeight() / 2);

    lockView.setTranslationX(cx - lockView.getMeasuredWidth() / 2);
    switchCameraButtonWrap.setTranslationX(cx - switchCameraButtonWrap.getMeasuredWidth() / 2f);
    torchButtonWrap.setTranslationX(cx - torchButtonWrap.getMeasuredWidth() / 2f);
    disposableSwitchButton.setTranslationX(cx - disposableSwitchButton.getMeasuredWidth() / 2f);
    updateLockY();

    if (closeFactor * recordFactor == 1f) {
      stopRecording(CLOSE_MODE_CANCEL, false);
    }
  }

  private void updateButtons () {
    float editFactor = editAnimator.getValue() ? 1f : (recordAnimator.getValue() ? 1f : this.editFactor);
    float factor = Math.max(recordFactor, editFactor);
    float scale = .6f + .4f * factor;

    lockView.setScaleX(scale);
    lockView.setScaleY(scale);
    switchCameraButtonWrap.setScaleX(scale);
    switchCameraButtonWrap.setScaleY(scale);
    torchButtonWrap.setScaleX(scale);
    torchButtonWrap.setScaleY(scale);
    zoomBar.setScaleX(scale);
    zoomBar.setScaleY(scale);
    disposableSwitchButton.setScaleX(scale);
    disposableSwitchButton.setScaleY(scale);

    recordBackground.setExpand(recordFactor);
  }

  private void updateVideoY () {
    float editFactor = recordMode != RECORD_MODE_NONE && editAnimator.getFloatValue() > 0f ? 1f : this.editFactor;

    final int bottom = inputOverlayView.getTop() + overallTranslation;
    final float y = bottom / 2f - videoLayout.getMeasuredHeight() / 2f + (bottom / 3f * (1f - Math.max(recordFactor, editFactor)));

    videoLayout.setTranslationY(y);
    progressView.setTranslationY(y - Screen.dp(RoundProgressView3.PADDING + 10));
    updateZoomBarPosition(bottom, y);
  }

  // Vibegram: the zoom bar sits under the circle (outside the progress ring, above the input bar).
  // When there is no room below (landscape phones, tablets), it goes to the left of the circle, vertically centered.
  // The column of switch camera / torch buttons is on the right edge, so the centered bar never reaches it.
  private void updateZoomBarPosition (int bottom, float videoY) {
    final int width = rootLayout.getMeasuredWidth();
    final int size = videoLayout.getMeasuredHeight();
    if (width == 0 || size == 0) {
      return;
    }
    final int barHeight = RoundZoomBar.getDesiredHeight();
    final int ringMargin = Screen.dp(12f); // progress ring: 10dp away from the circle + half of its 4dp stroke
    final float scale = videoLayout.getScaleY();
    final float centerY = videoY + size / 2f;
    final float finalCircleBottom = bottom / 2f + size / 2f;
    final boolean fitsBelow = bottom - finalCircleBottom >= ringMargin + Screen.dp(6f) + barHeight + Screen.dp(4f);
    final float circleLeft = (width - size) / 2f - ringMargin;
    final int sideZone = (int) (circleLeft - Screen.dp(16f));
    if (!fitsBelow && sideZone >= Screen.dp(120f)) {
      zoomBar.setMaxWidth(sideZone);
      int barWidth = Math.min(zoomBar.getMeasuredWidth() > 0 ? zoomBar.getMeasuredWidth() : sideZone, sideZone);
      zoomBar.setTranslationX(Screen.dp(8f) + (sideZone - barWidth) / 2f);
      zoomBar.setTranslationY(centerY - barHeight / 2f);
    } else {
      zoomBar.setMaxWidth(0);
      zoomBar.setTranslationX((width - zoomBar.getMeasuredWidth()) / 2f);
      float y = centerY + size * scale / 2f + ringMargin * scale + Screen.dp(6f);
      zoomBar.setTranslationY(Math.min(y, bottom - barHeight - Screen.dp(2f)));
    }
  }

  private void updateLockY () {
    float cy = voiceVideoButtonView.getTop() + voiceVideoButtonView.getTranslationY() + voiceVideoButtonView.getMeasuredHeight() / 2;
    float y = cy - lockView.getMeasuredHeight() - Screen.dp(11f) - Screen.dp(41f) + Screen.dp(RecordLockView.BUTTON_EXPANDED) * releaseFactor + Screen.dp(24f) * (1f - MathUtils.clamp(recordFactor));
    y -= Screen.dp(12) * editFactor;

    lockView.setTranslationY(y);
    y -= Screen.dp(5) + Screen.dp(50) * (recordingVideo ? lockView.getAlpha() : 1f);
    y += Screen.dp(24f) * (1f - MathUtils.clamp(recordFactor)) * (1f - releaseFactor);

    switchCameraButtonWrap.setTranslationY(y);
    if (recordMode == RECORD_MODE_VIDEO_EDIT) {
      y -= switchCameraButtonWrap.getMeasuredHeight() * switchCameraButtonWrap.getAlpha();
    } else {
      if (Views.isValid(switchCameraButtonWrap)) {
        y -= switchCameraButtonWrap.getMeasuredHeight();
      }
    }

    torchButtonWrap.setTranslationY(y);
    if (recordMode == RECORD_MODE_VIDEO_EDIT) {
      y -= torchButtonWrap.getMeasuredHeight() * torchButtonWrap.getAlpha();
    } else {
      if (Views.isValid(torchButtonWrap)) {
        y -= torchButtonWrap.getMeasuredHeight();
      }
    }

    disposableSwitchButton.setTranslationY(y);
    if (Views.isValid(disposableSwitchButton)) {
      y -= disposableSwitchButton.getMeasuredHeight();
    }
  }

  private boolean isReleased;
  private static final int ANIMATOR_RELEASE = 2;
  private BoolAnimator releaseAnimator = new BoolAnimator(ANIMATOR_RELEASE, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);
  private float releaseFactor;

  private void setReleased (boolean isReleased, boolean animated) {
    if (this.isReleased != isReleased) {
      this.isReleased = isReleased;
      releaseAnimator.setValue(isReleased, animated);
    }
  }

  private void setReleaseFactor (float factor) {
    if (this.releaseFactor != factor) {
      this.releaseFactor = factor;
      voiceVideoButtonView.setSendFactor(factor);
      lockView.setSendFactor(factor);
      if (dictating) {
        updateMainAlphas();
      }
      updateTranslations();
    }
  }

  public void updatePositions () {
    ViewController<?> c = UI.getCurrentStackItem(context);
    if (c instanceof MessagesController) {
      View view = ((MessagesController) c).getBottomWrap();
      // Views.getLocationInWindow returns one shared array: copy the values before the next call.
      int[] location = Views.getLocationInWindow(rootLayout);
      int rootX = location[0], rootY = location[1];
      int wrapTop = Views.getLocationInWindow(view)[1] - rootY;
      updateComposerGeometry((MessagesController) c, rootX, rootY, wrapTop);
      setOverallTranslation(wrapTop - voiceVideoButtonView.getTop());
    }
  }

  // Vibegram: the floating composer (MD3 pill with side and bottom margins). The recording bar takes the pill's place:
  // same bounds and corner radius, the duration starts inside it and the record circle grows from the composer's own
  // record button. Without a floating composer (chat preview) the old full-width 49dp bar stays.

  private boolean composerFloating;
  private final RectF composerPill = new RectF();
  private final PointF composerRecordCenter = new PointF();
  /** Offsets from the old layout (49dp bar at the top of the bottom wrap, record button at the right edge). */
  private int overlayExtraY, durationExtraY, buttonExtraX, buttonExtraY;
  private M3.RoleShape overlayBackground;

  private void updateComposerGeometry (MessagesController c, int rootX, int rootY, int wrapTop) {
    boolean floating = c.getComposerRecordGeometry(composerPill, composerRecordCenter) && rootLayout.getWidth() > 0;
    int left = 0, width = ViewGroup.LayoutParams.MATCH_PARENT, height = Screen.dp(49f);
    if (floating) {
      composerPill.offset(-rootX, -rootY);
      composerRecordCenter.offset(-rootX, -rootY);
      left = Math.round(composerPill.left);
      width = Math.round(composerPill.width());
      height = Math.round(composerPill.height());
      int pillTop = Math.round(composerPill.top) - wrapTop;
      overlayExtraY = pillTop + height - Screen.dp(49f);
      durationExtraY = pillTop + (height - Screen.dp(49f)) / 2;
      buttonExtraX = Math.round(composerRecordCenter.x - (rootLayout.getWidth() - Screen.dp(50f) / 2f));
      buttonExtraY = Math.round(composerRecordCenter.y - wrapTop - Screen.dp(49f) / 2f);
    } else {
      overlayExtraY = durationExtraY = buttonExtraX = buttonExtraY = 0;
    }
    FrameLayoutFix.LayoutParams params = (FrameLayoutFix.LayoutParams) inputOverlayView.getLayoutParams();
    if (params.width != width || params.height != height || params.leftMargin != left) {
      params.width = width;
      params.height = height;
      params.leftMargin = left;
      params.gravity = Gravity.LEFT | Gravity.BOTTOM;
      inputOverlayView.setLayoutParams(params);
    }
    // The duration text starts inside the pill, clear of its rounded corner.
    int durationLeft = floating ? left + Screen.dp(12f) : 0;
    ViewGroup.MarginLayoutParams durationParams = (ViewGroup.MarginLayoutParams) durationView.getLayoutParams();
    if (durationParams.leftMargin != durationLeft) {
      durationParams.leftMargin = durationLeft;
      durationView.setLayoutParams(durationParams);
    }
    voiceVideoButtonView.setTranslationX(buttonExtraX);
    if (this.composerFloating != floating) {
      this.composerFloating = floating;
      // The shadows edged the old full-width bar; a floating pill has none.
      videoTopShadowView.setVisibility(floating ? View.GONE : View.VISIBLE);
      videoBottomShadowView.setVisibility(floating ? View.GONE : View.VISIBLE);
      applyOverlayBackground();
    }
  }

  private void applyOverlayBackground () {
    if (!composerFloating) {
      inputOverlayView.setBackgroundColor(Theme.fillingColor());
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        inputOverlayView.setClipToOutline(false);
      }
      return;
    }
    if (overlayBackground == null) {
      // Same surface role and radius as the composer field.
      overlayBackground = new M3.RoleShape(M3.SURFACE_CONTAINER);
    }
    float radius = MainNavigationBar.getBarRadius();
    overlayBackground.setRadii(radius, radius);
    ViewUtils.setBackground(inputOverlayView, overlayBackground);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // Children (delete/send ripples, waveform) stay inside the rounded pill.
      inputOverlayView.setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Math.min(MainNavigationBar.getBarRadius(), view.getHeight() / 2f));
        }
      });
      inputOverlayView.setClipToOutline(true);
    }
    inputOverlayView.invalidate();
  }

  private int overallTranslation;

  private void setOverallTranslation (int translation) {
    if (this.overallTranslation != translation) {
      this.overallTranslation = translation;
      updateTranslations();
    }
  }

  private void resetViews () {
    lastDuration = 0;
    setTranslations(0f, 0f);
    switchCameraButton.setCameraIconRes(!Settings.instance().startRoundWithRear());
    roundFrontFace = !Settings.instance().startRoundWithRear();
    setTorchEnabled(false);
    progressView.reset();
    durationView.reset();
    lockView.setCollapseFactor(0f);
    disposableSwitchButton.setActive(false, false);
    disposableSwitchButton.setVisibility(canSendSelfDestructMessages() ? View.VISIBLE : View.GONE);
    recordBackground.setVolume(0f, false);
    editAnimator.setValue(false, false);
    videoPreviewView.performDestroy();
    videoTimelineView.performDestroy();
    videoPreviewView.setMuted(true);
    videoPreviewView.setPlaying(true);
    sendButton.destroySlowModeCounterController();
    audioPreviewView.clearData();
    setReleased(false, false);
    resetState();
  }

  @Override
  public void onCameraSwitched (boolean isForward, boolean toFrontFace) {
    switchCameraButton.spinAround(isForward, toFrontFace);
    roundFrontFace = toFrontFace;
    // Vibegram: remember the camera for "last used" (Settings → Video messages)
    Settings.instance().setRoundLastCameraRear(!toFrontFace);
    if (torchEnabled) {
      applyTorch();
    }
  }

  // Vibegram: zoom of the round video camera. Capabilities arrive on the camera thread
  // (camera opened, switched front/rear, closed); a session id drops callbacks of an old camera.

  private int zoomSessionId;

  private void attachZoomBar () {
    final int sessionId = ++zoomSessionId;
    // Every recording starts at 1× (the camera resets its ratio when it opens)
    zoomBar.reset();
    if (ownedCamera == null) {
      return;
    }
    ownedCamera.getLegacyManager().setZoomRatioListener(new CameraManagerLegacy.ZoomRatioListener() {
      @Override
      public void onZoomCapabilitiesChanged (float minRatio, float maxRatio, float[] stops, float currentRatio) {
        final float[] stopsCopy = stops != null ? stops.clone() : null;
        UI.post(() -> {
          if (sessionId == zoomSessionId) {
            zoomBar.setCapabilities(minRatio, maxRatio, stopsCopy, currentRatio, true);
          }
        });
      }

      // Called for every camera frame while zooming: one reusable Runnable carries the latest ratio,
      // posted again only after the previous post has run.
      private volatile float latestRatio;
      private final java.util.concurrent.atomic.AtomicBoolean ratioPosted = new java.util.concurrent.atomic.AtomicBoolean();
      private final Runnable applyRatio = () -> {
        ratioPosted.set(false);
        if (sessionId == zoomSessionId) {
          zoomBar.setCurrentRatio(latestRatio);
        }
      };

      @Override
      public void onZoomRatioChanged (float ratio) {
        latestRatio = ratio;
        if (ratioPosted.compareAndSet(false, true)) {
          UI.post(applyRatio);
        }
      }
    });
  }

  private void detachZoomBar () {
    zoomSessionId++;
    if (ownedCamera != null) {
      ownedCamera.getLegacyManager().setZoomRatioListener(null);
    }
    zoomBar.setTouchEnabled(false);
    zoomBar.reset();
  }

  // Light for round videos: the torch of a rear camera, a white screen around the circle for the front one

  private boolean torchEnabled, roundFrontFace = true;
  private boolean screenLightOn;

  private void setTorchEnabled (boolean enabled) {
    if (this.torchEnabled != enabled) {
      this.torchEnabled = enabled;
      torchButton.changeIcon(enabled ? R.drawable.baseline_flash_on_24 : R.drawable.baseline_flash_off_24);
    }
    applyTorch();
  }

  private void applyTorch () {
    boolean rearTorch = torchEnabled && !roundFrontFace;
    if (ownedCamera != null) {
      ownedCamera.getLegacyManager().setTorchEnabled(rearTorch);
    }
    setScreenLight(torchEnabled && roundFrontFace);
  }

  private void setScreenLight (boolean on) {
    if (this.screenLightOn == on) {
      return;
    }
    this.screenLightOn = on;
    videoBackgroundView.setBackgroundColor(on ? 0xffffffff : Theme.getColor(ColorId.previewBackground));
    android.view.Window window = context.getWindow();
    if (window != null) {
      android.view.WindowManager.LayoutParams params = window.getAttributes();
      params.screenBrightness = on ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
      window.setAttributes(params);
    }
  }

  private float translateX, translateY;

  private float getActualTranslateX () {
    return translateX * (1f - verticalFactor);
  }

  private float getActualTranslateY () {
    return translateY * verticalFactor * (1f - releaseFactor);
  }

  public boolean setTranslations (float x, float y) {
    float prevX = getActualTranslateX();
    float prevY = getActualTranslateY();

    this.translateX = x;
    this.translateY = y;

    if (prevX != getActualTranslateX() || prevY != getActualTranslateY()) {
      updateTranslations();
    }

    return !isReleased && isOpen();
  }

  private boolean applyVerticalDrag;

  public void setApplyVerticalDrag (boolean applyVerticalDrag) {
    if (this.applyVerticalDrag != applyVerticalDrag) {
      this.applyVerticalDrag = applyVerticalDrag;
      checkAxis();
    }
  }

  private boolean currentAxis;
  private static final int ANIMATOR_AXIS = 1;
  private BoolAnimator axisAnimator = new BoolAnimator(ANIMATOR_AXIS, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);

  private void checkAxis () {
    boolean axis = this.applyVerticalDrag;
    if (this.currentAxis != axis) {
      this.currentAxis = axis;
      axisAnimator.setValue(axis, Math.max(Math.abs(getActualTranslateX()), Math.abs(getActualTranslateY())) > 0);
    }
  }

  // Duration

  private long startTime;
  private long lastStartDuration;
  private long lastDuration;

  private void startTimers (long ms) {
    startTime = ms;
    lastStartDuration = SystemClock.uptimeMillis();
    durationView.start(startTime, lastDuration);
  }

  private void stopTimers () {
    lastDuration += SystemClock.uptimeMillis() - lastStartDuration;
    startTime = 0;
    durationView.stop();
  }

  // State

  /** Diagnostics for {@link StuckWatch}. */
  public String stuckState () {
    return "open=" + isOpen() + ", mode=" + recordMode + ", recordFactor=" + recordFactor + ", editFactor=" + editFactor + ", recordAnimator=" + recordAnimator.getValue() + "/" + recordAnimator.getFloatValue() + ", editAnimator=" + editAnimator.getValue() + "/" + editAnimator.getFloatValue() + ", currentRecording=" + currentRecording + ", raise=" + inRaiseMode;
  }

  public boolean isOpen () {
    return recordMode != RECORD_MODE_NONE || Math.max(recordFactor, editFactor) > 0f;
  }

  // Permissions check

  private boolean requestPermissions (boolean video, boolean allowRequest) {
    if (allowRequest) {
      if (video) {
        return context.permissions().requestRecordVideoPermissions(null);
      } else {
        return context.permissions().requestRecordAudioPermissions(null);
      }
    } else {
      if (video) {
        return context.permissions().canRecordVideo();
      } else {
        return context.permissions().canRecordAudio();
      }
    }
  }

  // Entry points

  public interface RecordStateListeners {
    void onRecordStateChanged (boolean isRecording);
  }

  private final List<Reference<RecordStateListeners>> recordListeners = new ArrayList<>();

  public void addRecordStateListener (RecordStateListeners listener) {
    ReferenceUtils.addReference(recordListeners, listener);
  }

  public void removeRecordStateListener (RecordStateListeners listener) {
    ReferenceUtils.removeReference(recordListeners, listener);
  }

  private static volatile boolean isRecordingAny;

  /** Voice or round video is being recorded right now (notifications must be silent then). */
  public static boolean isRecordingVoiceOrVideo () {
    return isRecordingAny;
  }

  private void notifyRecordStateChanged (boolean isRecording) {
    isRecordingAny = isRecording;
    context.navigation().getStack().setIsLocked(isRecording);

    if (isRecording) {
      UI.forceVibrate(voiceVideoButtonView, true, true);
      tdlib.context().player().pauseWithReason(TGPlayerController.PAUSE_REASON_RECORD_AUDIO_VIDEO);
    }

    final int size = recordListeners.size();
    for (int i = size - 1; i >= 0; i--) {
      RecordStateListeners listener = recordListeners.get(i).get();
      if (listener != null) {
        listener.onRecordStateChanged(isRecording);
      } else {
        recordListeners.remove(i);
      }
    }
  }

  private static final int RECORD_MODE_NONE = 0;
  private static final int RECORD_MODE_AUDIO = 1;
  private static final int RECORD_MODE_VIDEO = 2;
  private static final int RECORD_MODE_VIDEO_EDIT = 3;
  private static final int RECORD_MODE_AUDIO_EDIT = 4;

  private static final long EXPAND_DURATION = 160l;
  private static final long COLLAPSE_DURATION = 140l;

  private static final int ANIMATOR_RECORDING = 0;
  private int recordMode;
  private final BoolAnimator recordAnimator = new BoolAnimator(ANIMATOR_RECORDING, this, AnimatorUtils.DECELERATE_INTERPOLATOR, EXPAND_DURATION);
  private boolean recordingVideo;

  private static final int ANIMATOR_EDIT = 4;
  private float editFactor;
  private final BoolAnimator editAnimator = new BoolAnimator(ANIMATOR_EDIT, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);

  private static final int ANIMATOR_RENDER = 5;
  private float renderFactor;
  private final BoolAnimator renderAnimator = new BoolAnimator(ANIMATOR_RENDER, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l);

  private static final int ANIMATOR_MUTE = 6;
  private float muteFactor = 1f;
  private final BoolAnimator muteAnimator = new BoolAnimator(ANIMATOR_MUTE, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l, true);

  private void updateMuteAlpha () {
    float factor = editFactor * (1f - recordFactor) * muteFactor;
    muteIcon.setAlpha(factor);
    float scale = .6f + .4f * factor;
    muteIcon.setScaleX(scale);
    muteIcon.setScaleY(scale);
  }

  private void setEditFactor (float factor) {
    if (this.editFactor != factor) {
      boolean wasOpen = isOpen();
      this.editFactor = factor;
      lockView.setEditFactor(factor);
      updateMainAlphas();
      updateButtons();
      updateMiddle();
      updateLockY();
      if (isOpen() != wasOpen) {
        context.notifyBackPressAvailabilityChanged();
      }
    }
  }

  private void setMuteFactor (float factor) {
    if (this.muteFactor != factor) {
      this.muteFactor = factor;
      updateMuteAlpha();
    }
  }

  private void setRenderFactor (float factor) {
    if (this.renderFactor != factor) {
      this.renderFactor = factor;
      videoPreviewView.setAlpha(factor);
    }
  }

  private static boolean isVideoMode (int mode) {
    return mode == RECORD_MODE_VIDEO || mode == RECORD_MODE_VIDEO_EDIT;
  }

  private static boolean isInRecording (int mode) {
    return mode != RECORD_MODE_NONE && mode != RECORD_MODE_VIDEO_EDIT && mode != RECORD_MODE_AUDIO_EDIT;
  }

  private void setRecordModeImpl (int mode) {
    StuckWatch.event("recorder mode " + this.recordMode + " -> " + mode);
    this.recordMode = mode;
    context.notifyBackPressAvailabilityChanged();
  }

  private void setRecordMode (int mode, boolean animated) {
    boolean wasRecording = isInRecording(this.recordMode);
    boolean isRecording = isInRecording(mode);
    this.recordingVideo = isVideoMode(this.recordMode) || isVideoMode(mode);
    setRecordModeImpl(mode);
    if (targetController != null) {
      if (!dictating) {
        // Dictation does not tell the chat that a voice message is being recorded.
        targetController.setChatAction(recordingVideo ? TdApi.ChatActionRecordingVideoNote.CONSTRUCTOR : TdApi.ChatActionRecordingVoiceNote.CONSTRUCTOR, mode != RECORD_MODE_NONE, false);
      }
      targetController.hideBottomHint();
    }
    recordAnimator.setDuration(isRecording ? EXPAND_DURATION : COLLAPSE_DURATION);
    if (isRecording) {
      updatePositions();
      updateTranslations();
      recordAnimator.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
    } else {
      recordAnimator.setInterpolator(AnimatorUtils.ACCELERATE_INTERPOLATOR);
    }
    this.recordAnimator.setValue(isRecording, animated);
    if (isRecording) {
      voiceVideoButtonView.setRecordMode(dictating ? Settings.RECORD_MODE_DICTATION : mode == RECORD_MODE_VIDEO ? Settings.RECORD_MODE_ROUND : Settings.RECORD_MODE_VOICE, recordFactor > 0f);
      voiceVideoButtonView.setDoneInsteadOfSend(dictating);
    }

    videoTimelineView.setVisibility(mode == RECORD_MODE_VIDEO_EDIT ? View.VISIBLE : View.GONE);
    audioPreviewView.setVisibility(mode == RECORD_MODE_AUDIO_EDIT ? View.VISIBLE : View.GONE);

    if (wasRecording != isRecording) {
      notifyRecordStateChanged(isRecording);
      if (!isRecording) {
        onRecordBlur();
      }
    }
  }

  private CameraController ownedCamera;
  private boolean isCameraReady;
  private boolean inRaiseMode;

  private long targetChatId;
  private @Nullable TdApi.MessageTopic targetMessageTopicId;
  private MessagesController targetController;

  public boolean startRecording (View view, boolean inRaiseMode) {
    if (this.recordMode != RECORD_MODE_NONE) {
      return false;
    }

    if (tdlib.context().calls().promptActiveCall()) {
      return false;
    }

    final boolean dictation = !inRaiseMode && preferDictation;
    final boolean needVideo = !inRaiseMode && preferVideoMode && !dictation;
    if (dictation && !SttManager.instance().canDictate()) {
      // No model yet: open the models page right away.
      MessagesController c = findMessagesController();
      if (c != null && !c.isDestroyed()) {
        UI.showToast(R.string.vg_DictationNoModel, Toast.LENGTH_SHORT);
        c.navigateTo(new SettingsVoiceTranscriptionController(context, c.tdlib()));
      }
      return false;
    }
    if (requestPermissions(needVideo, !inRaiseMode)) {
      return false;
    }

    this.targetController = findMessagesController();
    if (targetController == null) {
      return false;
    }

    this.savedRoundDurationSeconds = 0;
    this.prevVideoPath = null;
    this.targetChatId = targetController.getChatId();
    this.targetMessageTopicId = targetController.getMessageTopicId();
    if (needVideo && !tdlib.chatSupportsRoundVideos(targetChatId)) {
      TdApi.User user = tdlib.chatUser(targetChatId);
      String name = user != null ? user.firstName : tdlib.chatTitle(targetChatId);
      UI.showToast(Lang.getString(R.string.SecretChatFeatureUnsupported, name), Toast.LENGTH_SHORT);
      return false;
    }

    // Dictation only types text: voice message restrictions of the chat do not apply.
    CharSequence restrictionText = dictation ? null : tdlib.getVoiceVideoRestrictionText(tdlib.chat(targetChatId), needVideo);
    if (restrictionText != null) {
      if (view != null) {
        context.tooltipManager().builder(view).controller(targetController).icon(R.drawable.baseline_warning_24).show(tdlib, restrictionText).hideDelayed();
      } else {
        UI.showToast(restrictionText, Toast.LENGTH_SHORT);
      }
      return false;
    }

    if (awaitingRoundResult()) {
      return false;
    }

    if (recordAnimator != null && recordAnimator.isAnimating()) {
      return false;
    }

    if (UI.getContext(context).isActivityBusyWithSomething()) {
      return false;
    }

    UI.getContext(context).closeAllMedia(true);

    if (needVideo) {
      if (ownedCamera != null) {
        throw new IllegalStateException();
      }
      isCameraReady = false;
      ownedCamera = context.takeCameraOwnership(new ViewController.CameraOpenOptions()
        .mode(CameraController.MODE_ROUND_VIDEO)
        .readyListener(this)
      );
      if (ownedCamera == null) {
        return false;
      }
      prepareVideoRecording();
      attachZoomBar();
    }

    if (sendHelper != null)
      sendHelper.detachFromView(sendButton);
    sendHelper = dictation ? null : tdlib.ui()
      .createSimpleHapticMenu(targetController, targetChatId, () -> editFactor == 1f, null, null, null, (sendOptions, disableMarkdown) -> {
        if (recordMode == RECORD_MODE_AUDIO_EDIT) {
          sendAudio(sendOptions);
        } else {
          sendVideo(sendOptions);
        }
      }, null)
      .attachToView(sendButton);
    this.dictating = dictation;
    if (dictation) {
      SttManager.instance().prepareDictation();
    }
    if (!inRaiseMode) {
      context.setScreenFlagEnabled(BaseActivity.SCREEN_FLAG_RECORDING, true);
    }
    context.setOrientationLockFlagEnabled(BaseActivity.ORIENTATION_FLAG_RECORDING, true);

    resetViews();
    this.inRaiseMode = inRaiseMode;
    setReleased(inRaiseMode, false);
    if (inRaiseMode) {
      lockView.setCollapseFactor(1f);
    }
    lockView.setMode(needVideo ? RecordLockView.MODE_VIDEO : RecordLockView.MODE_AUDIO);
    setRecordMode(needVideo ? RECORD_MODE_VIDEO : RECORD_MODE_AUDIO, !inRaiseMode);

    // checkActualRecording(CLOSE_MODE_CANCEL);

    return true;
  }

  private static final int CLOSE_MODE_CANCEL = 0;
  private static final int CLOSE_MODE_SEND = 1;
  private static final int CLOSE_MODE_PREVIEW = 2;
  private static final int CLOSE_MODE_PREVIEW_SCHEDULE = 3;

  private static final long MINIMUM_VIDEO_RECORDING_DURATION = 1000l;
  private static final long MINIMUM_AUDIO_RECORDING_DURATION = 500l;

  private boolean canSendRecording () {
    return totalDuration() >= (recordingVideo ? MINIMUM_VIDEO_RECORDING_DURATION : MINIMUM_AUDIO_RECORDING_DURATION);
  }

  private long totalDuration () {
    return (startTime != 0 ? SystemClock.uptimeMillis() - startTime : 0) + lastDuration;
  }

  public boolean finishRecording (boolean needPreview) {
    boolean forcePreview = tdlib.getSlowModeRestrictionText(targetChatId) != null;
    return stopRecording(canSendRecording() ? (needPreview || forcePreview ? CLOSE_MODE_PREVIEW : (hasValidOutputTarget() && targetController.areScheduledOnly()) ? CLOSE_MODE_PREVIEW_SCHEDULE : CLOSE_MODE_SEND) : CLOSE_MODE_CANCEL, true);
  }

  private boolean stopRecording (int closeMode, boolean showPrompt) {
    if (recordMode == RECORD_MODE_NONE || recordMode == RECORD_MODE_VIDEO_EDIT || recordMode == RECORD_MODE_AUDIO_EDIT) {
      return false;
    }
    if (dictating && closeMode != CLOSE_MODE_CANCEL) {
      // No preview for dictation: stop and the lock's stop button both finish it.
      closeMode = CLOSE_MODE_SEND;
    }

    if (closeMode == CLOSE_MODE_CANCEL) {
      UI.forceVibrate(voiceVideoButtonView, true, true);
      if (showPrompt) {
        Settings.instance().notifyRecordAudioVideoError();
      }
    }

    int mode = RECORD_MODE_NONE;

    boolean async = (closeMode == CLOSE_MODE_PREVIEW || closeMode == CLOSE_MODE_PREVIEW_SCHEDULE);
    if (async) {
      mode = recordingVideo ? RECORD_MODE_VIDEO_EDIT : RECORD_MODE_AUDIO_EDIT;
      editAnimator.setValue(true, true);
      if (sendButton != null) {
        sendButton.getSlowModeCounterController(tdlib).setCurrentChat(targetChatId);
      }
    }

    if (recordingVideo && (closeMode == CLOSE_MODE_PREVIEW || closeMode == CLOSE_MODE_PREVIEW_SCHEDULE)) {
      if (ownedCamera != null) {
        ownedCamera.getLegacyManager().setNoPreviewBlur(true);
      } else {
        Log.w(Log.TAG_ROUND, "ownedCamera.setNoPreviewBlur() failed: null");
      }
    }
    setRecordMode(mode, true);
    if (recordingVideo) {
      destroyVideoRecording(closeMode == CLOSE_MODE_CANCEL);
    }
    checkActualRecording(closeMode);

    return true;
  }

  // Backend

  private boolean isVideoRecordingReady () {
    return ownedCamera != null && isCameraReady && isRoundVideoFileReady();
  }

  private boolean isAudioRecordingReady () {
    return !Recorder.instance().isRecording() && !DictationRecorder.instance().isRecording();
  }

  private boolean hasValidOutputTarget () {
    return targetController != null && !targetController.isDestroyed() && targetController.compareChat(targetChatId, targetMessageTopicId) && targetChatId != 0 && tdlib != null;
  }

  private void checkActualRecording (int closeMode) {
    boolean isRecording = this.recordMode != RECORD_MODE_NONE && this.recordMode != RECORD_MODE_VIDEO_EDIT && this.recordMode != RECORD_MODE_AUDIO_EDIT && gotFocus;
    boolean actuallyRecording = this.currentRecording != RECORD_MODE_NONE;
    if (!actuallyRecording && isRecording) {
      switch (recordMode) {
        case RECORD_MODE_AUDIO:
          isRecording = isAudioRecordingReady();
          break;
        case RECORD_MODE_VIDEO:
          isRecording = isVideoRecordingReady();
          break;
      }
    }
    if (actuallyRecording != isRecording) {
      if (isRecording) {
        startRecordingImpl(this.recordMode);
      } else {
        stopRecordingImpl(hasValidOutputTarget() ? closeMode : CLOSE_MODE_CANCEL);
      }
    }
  }

  private boolean awaitingRoundResult () {
    return roundCloseMode != CLOSE_MODE_CANCEL;
  }

  private boolean awaitingVoiceResult () {
    return voiceCloseMode != CLOSE_MODE_CANCEL;
  }

  private Throwable releasedTrace;
  private boolean cleanupVideoPending;

  private void cleanupVideoRecording () {
    if (recordingVideo && Math.max(recordFactor, editFactor) * (1f - renderFactor) == 0f && ownedCamera != null && !awaitingRoundResult()) {
      if (recordingRoundVideo) {
        cleanupVideoPending = true;
        return;
      }
      setTorchEnabled(false);
      detachZoomBar();
      ownedCamera.onCleanAfterHide();
      ownedCamera.releaseCameraLayout();

      setupCamera(false);
      context.releaseCameraOwnership();
      releasedTrace = Log.generateException();
      ownedCamera = null;

      resetRoundState();
    }
  }

  // Raise to Speak

  public boolean enterRaiseRecordMode () {
    return startRecording(null, true);
  }

  public boolean leaveRaiseRecordMode () {
    return finishRecording(true);
  }

  // Animations

  private float recordFactor = -1f;
  private float verticalFactor = 0f;

  private void setVerticalFactor (float factor) {
    if (this.verticalFactor != factor) {
      this.verticalFactor = factor;
      updateTranslations();
    }
  }

  private void updateDuration () {
    float range = MathUtils.clamp(recordFactor);
    float editDyTotal = inputOverlayView.getMeasuredHeight() / 2;
    float editDy = editFactor * (1f - recordFactor) * editDyTotal;
    durationView.setAlpha(range);
    durationView.setTranslationY(overallTranslation + durationExtraY + editDy);
  }

  private void updateMainAlphas () {
    float editFactor = editAnimator.getValue() ? 1f : (recordAnimator.getValue() ? 1f : this.editFactor);

    float range = MathUtils.clamp(recordFactor);
    float editRange = Math.max(range, editFactor);
    voiceVideoButtonView.setAlpha(range);
    inputOverlayView.setAlpha(editRange);
    // Dictation has no pause/preview: once locked, only the check mark (finish) and "Cancel" stay.
    lockView.setAlpha(dictating ? editRange * (1f - releaseFactor) : editRange);

    float videoRange = recordingVideo ? editRange : 0f;
    videoBackgroundView.setFactor(videoRange);
    videoTopShadowView.setAlpha(videoRange);
    videoBottomShadowView.setAlpha(videoRange);
    videoLayout.setAlpha(videoRange);
    progressView.setAlpha(videoRange * Math.max(recordFactor, editFactor));
    progressView.setEditFactor(editAnimator.getFloatValue());

    float videoScale = .4f + .6f * videoRange;
    videoLayout.setScaleX(videoScale);
    videoLayout.setScaleY(videoScale);
    progressView.setScaleX(videoScale);
    progressView.setScaleY(videoScale);
    switchCameraButtonWrap.setAlpha(recordingVideo ? editRange : 0);
    torchButtonWrap.setAlpha(recordingVideo && recordMode != RECORD_MODE_VIDEO_EDIT ? editRange : 0);
    zoomBar.setAlpha(recordingVideo && recordMode != RECORD_MODE_VIDEO_EDIT ? editRange : 0);
    zoomBar.setTouchEnabled(recordMode == RECORD_MODE_VIDEO && ownedCamera != null);
    disposableSwitchButton.setAlpha(editRange);

    updateMuteAlpha();

    updateVideoY();
  }

  private void setRecordFactor (float factor) {
    if (this.recordFactor != factor) {
      boolean wasOpen = isOpen();
      this.recordFactor = factor;

      updateMainAlphas();
      updateButtons();
      updateLockY();
      updateDuration();
      updateMiddle();

      if (wasOpen != isOpen()) {
        context.notifyBackPressAvailabilityChanged();
      }
    }
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    switch (id) {
      case ANIMATOR_RECORDING: {
        setRecordFactor(factor);
        break;
      }
      case ANIMATOR_AXIS: {
        setVerticalFactor(factor);
        break;
      }
      case ANIMATOR_RELEASE: {
        setReleaseFactor(factor);
        break;
      }
      case ANIMATOR_EDIT: {
        setEditFactor(factor);
        break;
      }
      case ANIMATOR_RENDER: {
        setRenderFactor(factor);
        break;
      }
      case ANIMATOR_MUTE: {
        setMuteFactor(factor);
        break;
      }
    }
  }

  private boolean gotFocus;

  private void onRecordFocus () {
    if (gotFocus) {
      return;
    }
    gotFocus = true;
    if (recordingVideo) {
      if (ownedCamera != null) {
        ownedCamera.onFocus();
      } else {
        Log.w(Log.TAG_ROUND, "ownedCamera.onFocus() failed: null");
      }
    }
    checkActualRecording(CLOSE_MODE_CANCEL);
  }

  private void onRecordRemoved () {
    // note: when animations disabled happens before finishVideoRecording
    context.setScreenFlagEnabled(BaseActivity.SCREEN_FLAG_RECORDING, false);
    context.setOrientationLockFlagEnabled(BaseActivity.ORIENTATION_FLAG_RECORDING, false);
    cleanupVideoRecording();
  }

  private void onRecordBlur () {
    if (!gotFocus) {
      return;
    }
    if (recordingVideo && ownedCamera != null) {
      ownedCamera.onBlur();
    }
    gotFocus = false;
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    switch (id) {
      case ANIMATOR_RECORDING: {
        if (finalFactor == 1f) {
          onRecordFocus();
        } else if (finalFactor == 0f) {
          onRecordRemoved();
        }
        break;
      }
      case ANIMATOR_RELEASE: {
        if (finalFactor == 1f) {
          videoPreviewView.preparePlayer();
        }
        break;
      }
      case ANIMATOR_EDIT: {
        if (finalFactor == 0f) {
          cleanupVideoRecording();
          deleteVoiceRecord();
        } else if (finalFactor == 1f && roundCloseMode == CLOSE_MODE_PREVIEW_SCHEDULE) {
          sendVideo(Td.newSendOptions());
        } else if (finalFactor == 1f && voiceCloseMode == CLOSE_MODE_PREVIEW_SCHEDULE) {
          sendAudio(Td.newSendOptions());
        }
        break;
      }
      case ANIMATOR_RENDER: {
        if (finalFactor == 1f) {
          cleanupVideoRecording();
        }
        break;
      }
    }
  }

  // Record  impl

  private int currentRecording;
  private int roundCloseMode;
  private int voiceCloseMode;

  private void startRecordingImpl (int mode) {
    this.currentRecording = mode;
    switch (mode) {
      case RECORD_MODE_AUDIO:
        if (dictating) {
          DictationRecorder.instance().start(dictationListener);
        } else if (tdlib != null) {
          Recorder.instance().record(tdlib, ChatId.isSecret(targetChatId), this);
        }
        break;
      case RECORD_MODE_VIDEO:
        startVideoRecording();
        break;
    }
  }

  private void resumeRecordingImpl (int mode) {
    editAnimator.setValue(false, true);
    if (mode == RECORD_MODE_AUDIO) {
      currentRecording = mode;
      if (tdlib != null) {
        Recorder.instance().resume(tdlib, ChatId.isSecret(targetChatId), this);
      }
    } else if (mode == RECORD_MODE_VIDEO) {
      if (recordingRoundVideo) {
        cleanupVideoPending = true;
        return;
      }

      ownedCamera.getLegacyManager().setUseRoundRender(false);

      setRecordModeImpl(RECORD_MODE_NONE);
      resetRoundState();
      // editFactor = 0f;

      prevVideoHasTrim = videoPreviewView.hasTrim();
      prevVideoTrimStart = videoPreviewView.getStartTime();
      prevVideoTrimEnd = videoPreviewView.getEndTime();

      videoPreviewView.performDestroy();
      videoPreviewView.setMuted(true);
      videoPreviewView.setPlaying(true);
      resetRoundState();
      isCameraReady = false;

      prepareVideoRecording();
      attachZoomBar();
    }
    setRecordMode(mode, true);
  }

  private MessagesController findMessagesController () {
    ViewController<?> c = UI.getCurrentStackItem(context);
    return c instanceof MessagesController ? (MessagesController) c : null;
  }

  private void stopRecordingImpl (int closeMode) {
    final boolean needResult = closeMode != CLOSE_MODE_CANCEL && hasValidOutputTarget();
    switch (currentRecording) {
      case RECORD_MODE_AUDIO:
        if (dictating) {
          finishDictation(!needResult);
          break;
        }
        voiceCloseMode = closeMode;
        if (needResult) {
          Recorder.instance().save(closeMode == CLOSE_MODE_PREVIEW || closeMode == CLOSE_MODE_PREVIEW_SCHEDULE);
        } else {
          Recorder.instance().cancel();
        }
        break;
      case RECORD_MODE_VIDEO:
        finishVideoRecording(closeMode);
        break;
    }
    this.currentRecording = RECORD_MODE_NONE;
    stopTimers();
  }

  // Audio record impl

  private void resetState () {
    checkActualRecording(RECORD_MODE_NONE);
  }

  @Override
  public void onAmplitude (float amplitude) {
    if (isOpen() && currentRecording != RECORD_MODE_NONE) {
      if (startTime != 0) {
        recordBackground.setVolume(amplitude, true);
      } else {
        startTimers(SystemClock.uptimeMillis());
      }
    }
  }

  @Override
  public void onFail () {
    if (isOpen()) {
      UI.showToast(R.string.Error, Toast.LENGTH_SHORT);
      stopRecording(CLOSE_MODE_CANCEL, false);
    }
  }

  private TGRecord voiceRecord;

  private void deleteVoiceRecord () {
    if (voiceRecord != null && !awaitingVoiceResult()) {
      voiceRecord = null;
    }
  }

  @Override
  public void onSave (final Tdlib.Generation generation, final int duration, final byte[] waveform) {
    UI.post(() -> {
      final TGRecord record = new TGRecord(tdlib, generation, duration, waveform);
      if (awaitingVoiceResult()) {
        if (voiceCloseMode == CLOSE_MODE_SEND) {
          sendAudioNote(new TdApi.InputMessageVoiceNote(new TdApi.InputVoiceNote(record.toInputFile(), record.getDuration(), record.getWaveform()), null, obtainSelfDestructType()), Td.newSendOptions());
        } else {
          audioPreviewView.processRecord(voiceRecord = record);
        }
      }
    });
  }

  private TdApi.MessageSelfDestructType obtainSelfDestructType () {
    return canSendSelfDestructMessages() && disposableSwitchButton != null && disposableSwitchButton.isActive() ?
      new TdApi.MessageSelfDestructTypeImmediately() : null;
  }

  private boolean canSendSelfDestructMessages () {
    return tdlib.selfChatId() != targetChatId && ChatId.isUserChat(targetChatId);
  }

  // Video record impl

  private void setupCamera (boolean isOwned) {
    ownedCamera.getManager().setPreferFrontFacingCamera(!Settings.instance().startRoundWithRear() && isOwned);
    // Vibegram: the round quality (settings, 0 = max) decides which camera stream is needed.
    int roundMaxResolution = 0;
    if (isOwned) {
      RoundVideoQuality roundQuality = RoundVideoQuality.prepareCapture(ownedCamera.getLegacyManager());
      // Fallback for backends that only pick the preview closest by area to maxResolution²: 4/3 of the short side hits 1280×720 / 640×480.
      roundMaxResolution = roundQuality.getCaptureMinSide() * 4 / 3;
    }
    ownedCamera.getManager().setMaxResolution(roundMaxResolution);
    ownedCamera.getLegacyManager().setNoPreviewBlur(false);
    ownedCamera.getLegacyManager().setUseRoundRender(isOwned);
    ownedCamera.getLegacyManager().getView().setIgnoreAspectRatio(isOwned);
    ownedCamera.getCameraLayout().setDisallowRatioChanges(isOwned);
    ownedCamera.setUseFastInitialization(isOwned);
  }

  private String roundKey;
  private long roundGenerationId;
  private String roundOutputPath;
  private TdApi.File roundFile;

  private void prepareVideoRecording () {
    if (!StringUtils.isEmpty(roundKey)) {
      throw new IllegalStateException();
    }
    if (ownedCamera == null) {
      throw new IllegalStateException();
    }
    roundKey = "round" + SystemClock.uptimeMillis() + "_" + System.currentTimeMillis() + "_" + Math.random();
    // Vibegram: a bitrate that does not fit a full minute into 10 MB may produce a file that is re-encoded
    // before sending, so it is not uploaded while recording (the upload would be wasted or fail past 10 MB).
    roundUploadWhileRecording = RoundVideoQuality.resolve(Settings.instance()).fitsFullLength();
    // A recording that may not fit is generated as a plain video file: TDLib refuses to register a generated
    // video note over its size limit (option video_note_size_max, 12 MB), the generation then fails and is
    // requested again on sending, after the recording is gone. It is sent via its path, re-encoded if needed.
    TdApi.FileType roundFileType = ChatId.isSecret(targetChatId) ? new TdApi.FileTypeSecret() :
      roundUploadWhileRecording ? new TdApi.FileTypeVideoNote() : new TdApi.FileTypeVideo();
    tdlib.client().send(new TdApi.PreliminaryUploadFile(new TdApi.InputFileGenerated(null, roundKey, 0), roundFileType, 1), object -> {
      if (object.getConstructor() == TdApi.File.CONSTRUCTOR) {
        setRoundGenerationFile((TdApi.File) object);
      } else {
        UI.showError(object);
      }
    });

    setupCamera(true);
    ownedCamera.setInEarlyInitialization();
    ownedCamera.setOutputController(context.navigation().getCurrentStackItem());
    ownedCamera.onPrepareToShow();
    ownedCamera.takeCameraLayout(videoLayout, 1);
  }

  private void setRoundGeneration (long generationId, String outputPath) {
    this.roundGenerationId = generationId;
    this.roundOutputPath = outputPath;
    Log.v("roundOutputPath = %s", Log.generateException(), outputPath);
    checkActualRecording(CLOSE_MODE_CANCEL);
  }

  private void setRoundFile (TdApi.File file) {
    this.roundFile = file;
    if (!roundUploadWhileRecording) {
      detachRoundUpload();
    }
    checkActualRecording(CLOSE_MODE_CANCEL);
  }

  private boolean isRoundVideoFileReady () {
    return !StringUtils.isEmpty(roundKey) && !StringUtils.isEmpty(roundOutputPath) && roundFile != null;
  }

  private void destroyVideoRecording (boolean deleteFile) {
    if (StringUtils.isEmpty(roundKey)) {
      return;
    }

    if (deleteFile) {
      if (!StringUtils.isEmpty(roundOutputPath)) {
        // Vibegram: a described error instead of an empty one ("Empty error 400"), to tell where a failed round came from
        tdlib.client().send(new TdApi.FinishFileGeneration(roundGenerationId, new TdApi.Error(400,
          "VG-R1: round video recording cancelled, mode " + roundCloseMode + ", detached " + roundUploadDetached +
          ", file " + (roundFile != null ? roundFile.id + "/" + roundFile.size : "null"))), tdlib.silentHandler());
      }
      if (roundFile != null) {
        tdlib.client().send(new TdApi.CancelPreliminaryUploadFile(roundFile.id), tdlib.silentHandler());
        if (roundUploadDetached) {
          tdlib.client().send(new TdApi.CancelDownloadFile(roundFile.id, false), tdlib.silentHandler());
        }
      }
      resetRoundState();
    }
  }

  private void resetRoundState () {
    roundUploadDetached = false;
    if (roundFile != null) {
      tdlib.files().unsubscribe(roundFile.id, this);
      roundFile = null;
    }
    Log.v("roundOutputPath -> null", Log.generateException());
    roundOutputPath = null;
    roundGenerationId = 0;
    roundKey = null;
    roundGenerationFinished = false;
  }

  @AnyThread
  public void setRoundGeneration (final String key, final long generationId, final String outputPath) {
    UI.post(() -> {
      if (StringUtils.equalsOrBothEmpty(key, roundKey)) {
        setRoundGeneration(generationId, outputPath);
      } else {
        tdlib.client().send(new TdApi.FinishFileGeneration(generationId, new TdApi.Error(400,
          "VG-R2: round video generation requested again" + (StringUtils.isEmpty(roundKey) ? " after the recording was closed" : " for another recording") +
          ", detached " + roundUploadDetached + ", upload while recording " + roundUploadWhileRecording)), tdlib.silentHandler());
      }
    });
  }

  @AnyThread
  private void setRoundGenerationFile (final TdApi.File file) {
    UI.post(() -> {
      if (!StringUtils.isEmpty(roundKey) && StringUtils.equalsOrBothEmpty(file.local.path, roundOutputPath)) {
        setRoundFile(file);
      } else {
        tdlib.client().send(new TdApi.CancelPreliminaryUploadFile(file.id), tdlib.silentHandler());
      }
    });
  }

  private boolean recordingRoundVideo;
  private int savedRoundDurationSeconds;

  private void startVideoRecording () {
    if (this.recordingRoundVideo)
      throw new IllegalStateException();
    this.recordingRoundVideo = true;
    ownedCamera.getLegacyManager().requestRoundVideoCapture(roundKey, this, roundOutputPath);
  }

  private void finishVideoRecording (int closeMode) {
    // note: when animations disabled, happens after cleanupVideoRecording is called
    if (!this.recordingRoundVideo)
      throw new IllegalStateException();
    this.recordingRoundVideo = false;
    this.roundCloseMode = closeMode;
    final boolean needResult = closeMode != CLOSE_MODE_CANCEL;
    if (ownedCamera == null) {
      throw new RuntimeException(releasedTrace);
    }
    ownedCamera.getLegacyManager().finishOrCancelRoundVideoCapture(roundKey, needResult);
    // The light is needed only while recording
    setTorchEnabled(false);
    if (cleanupVideoPending) {
      cleanupVideoPending = false;
      cleanupVideoRecording();
    }
  }

  @Override
  public void onVideoRecordingStarted (String key, long startTimeMs) {
    if (StringUtils.equalsOrBothEmpty(roundKey, key) && recordingRoundVideo) {
      startTimers(startTimeMs);
    }
  }

  @Override
  public void onVideoRecordProgress (String key, long readyBytesCount) {
    if (StringUtils.equalsOrBothEmpty(roundKey, key) && prevVideoPath == null) {
      if (readyBytesCount > ROUND_UPLOAD_DETACH_BYTES) {
        detachRoundUpload();
      }
      tdlib.client().send(new TdApi.SetFileGenerationProgress(roundGenerationId, 0, readyBytesCount), tdlib.silentHandler());
    }
  }

  // Vibegram: rounds are recorded at a high bitrate, a longer one does not fit Telegram's 10 MB
  // (TDLib uploads video notes only as small files) and is re-encoded to fit when sent.
  // Its upload during recording would fail past 10 MB, so it stops before that.

  /** Recorded bytes after which the upload during recording is stopped. */
  private static final long ROUND_UPLOAD_DETACH_BYTES = 9L << 20;
  /** Files up to this size are sent as recorded. */
  private static final long ROUND_SEND_AS_IS_BYTES = RoundVideoQuality.MAX_FILE_SIZE - (64L << 10);

  private volatile boolean roundUploadDetached;
  private boolean roundUploadWhileRecording = true;

  @AnyThread
  private void detachRoundUpload () {
    TdApi.File file = roundFile;
    if (roundUploadDetached || file == null) {
      return;
    }
    roundUploadDetached = true;
    Log.i(Log.TAG_ROUND, "Stopping the round video upload during recording, size limit: %b", roundUploadWhileRecording);
    // TDLib stops generating a file nobody downloads or uploads: keep the generation alive with a download,
    // and cancel the upload only once the download is registered (TDLib checks the partial file first, asynchronously).
    tdlib.client().send(new TdApi.DownloadFile(file.id, 1, 0, 0, false), result -> {
      if (result.getConstructor() == TdApi.File.CONSTRUCTOR) {
        tdlib.client().send(new TdApi.CancelPreliminaryUploadFile(file.id), tdlib.silentHandler());
      } else {
        Log.w(Log.TAG_ROUND, "Cannot keep the round video generation without upload: %s", TD.toErrorString(result));
      }
    });
  }

  private static long getVideoDurationMs (String path, long fallbackMs) {
    android.media.MediaMetadataRetriever retriever;
    try {
      retriever = U.openRetriever(path);
    } catch (Throwable t) {
      retriever = null;
    }
    if (retriever != null) {
      try {
        long durationMs = StringUtils.parseLong(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
        if (durationMs > 0) {
          return durationMs;
        }
      } catch (Throwable ignored) {
      } finally {
        U.closeRetriever(retriever);
      }
    }
    return fallbackMs;
  }

  /** Sends the whole recorded round: as is when it fits Telegram's 10 MB, re-encoded to fit otherwise. */
  private void sendRecordedRound (TdApi.MessageSendOptions initialSendOptions) {
    String path = !StringUtils.isEmpty(roundFile.local.path) ? roundFile.local.path : roundOutputPath;
    long size = !StringUtils.isEmpty(path) ? new File(path).length() : 0;
    if (size <= 0) {
      size = Math.max(roundFile.size, roundFile.local.downloadedSize);
    }
    if (size <= ROUND_SEND_AS_IS_BYTES) {
      // Uploaded during recording: send that file. Otherwise it is not a video note file in TDLib, send it by path.
      TdApi.InputFile inputFile = roundUploadWhileRecording || StringUtils.isEmpty(path) ? new TdApi.InputFileId(roundFile.id) : new TdApi.InputFileLocal(path);
      sendVideoNote(new TdApi.InputMessageVideoNote(new TdApi.InputVideoNote(inputFile, null, savedRoundDurationSeconds, getVideoNoteLength()), obtainSelfDestructType()), initialSendOptions, roundFile);
      return;
    }
    tdlib.send(new TdApi.CancelPreliminaryUploadFile(roundFile.id), tdlib.typedOkHandler());
    long durationMs = getVideoDurationMs(path, (savedRoundDurationSeconds + 1) * 1000L);
    String conversion = VideoGenerationInfo.makeRoundConversion(roundFile.id, -1, -1, durationMs, RoundVideoQuality.getRecorded(roundKey));
    Log.i(Log.TAG_ROUND, "Round video is %d bytes, %d ms: re-encoding to fit, %s", size, durationMs, conversion);
    TdApi.InputFileGenerated fittedFile = new TdApi.InputFileGenerated(path, conversion, 0);
    sendVideoNote(new TdApi.InputMessageVideoNote(new TdApi.InputVideoNote(fittedFile, null, savedRoundDurationSeconds, getVideoNoteLength()), obtainSelfDestructType()), initialSendOptions, null);
  }

  private void sendVideoNote (TdApi.InputMessageVideoNote videoNote, TdApi.MessageSendOptions initialSendOptions, TdApi.File helperFile) {
    if (hasValidOutputTarget()) {
      boolean isSecretChat = ChatId.isSecret(targetChatId);
      targetController.pickDateOrProceed(initialSendOptions, (modifiedSendOptions, disableMarkdown) -> {
        TdApi.InputMessageVideoNote newVideoNote = tdlib.filegen().createThumbnail(videoNote, isSecretChat, helperFile);
        long chatId = targetController.getChatId();
        MessagesController.ReplyInfo replyInfo = targetController.obtainReplyTo();
        TdApi.InputMessageReplyTo replyTo = replyInfo != null ? replyInfo.toInputMessageReply() : null;
        TdApi.MessageTopic topicId = targetController.getMessageTopicId(replyInfo);
        TdApi.MessageSendOptions sendOptions = Td.newSendOptions(
          modifiedSendOptions,
          targetController.getInputSuggestedPostInfo(replyInfo),
          targetController.obtainSilentMode()
        );
        if (newVideoNote.videoNote.thumbnail == null && helperFile != null) {
          tdlib.client().send(new TdApi.DownloadFile(helperFile.id, TdlibFilesManager.PRIORITY_FILE_GENERATION, 0, 0, true), result -> {
            tdlib.sendMessage(chatId, topicId, replyTo, sendOptions, result.getConstructor() == TdApi.File.CONSTRUCTOR ? tdlib.filegen().createThumbnail(videoNote, isSecretChat, (TdApi.File) result) : newVideoNote, null);
          });
        } else {
          tdlib.sendMessage(chatId, topicId, replyTo, sendOptions, newVideoNote, null);
        }
      });
    }
    roundCloseMode = CLOSE_MODE_CANCEL;
    cleanupVideoRecording();
  }

  private void sendAudioNote (TdApi.InputMessageVoiceNote voiceNote, TdApi.MessageSendOptions initialSendOptions) {
    if (hasValidOutputTarget()) {
      targetController.pickDateOrProceed(initialSendOptions, (modifiedSendOptions, disableMarkdown) -> {
        long chatId = targetController.getChatId();
        MessagesController.ReplyInfo replyInfo = targetController.obtainReplyTo();
        TdApi.InputMessageReplyTo replyTo = replyInfo != null ? replyInfo.toInputMessageReply() : null;
        TdApi.MessageTopic topicId = targetController.getMessageTopicId(replyInfo);
        TdApi.MessageSendOptions sendOptions = Td.newSendOptions(
          modifiedSendOptions,
          targetController.getInputSuggestedPostInfo(replyInfo),
          targetController.obtainSilentMode()
        );
        tdlib.sendMessage(chatId, topicId, replyTo, sendOptions, voiceNote, null);
      });
    }

    voiceCloseMode = CLOSE_MODE_CANCEL;
    deleteVoiceRecord();
  }

  private void finishFileGeneration (long resultFileSize, @Nullable Runnable after) {
    if (prevVideoPath != null) {
      Background.instance().post(() -> {
        if (StringUtils.isEmpty(prevVideoPath) || StringUtils.isEmpty(roundOutputPath))
          throw new IllegalStateException();
        boolean merged = false;
        String destinationPath = roundOutputPath + ".merge";
        File outputFile = new File(roundOutputPath);
        if (outputFile.exists()) {
          try {
            VideoGen.appendTwoVideos(prevVideoPath, roundOutputPath, destinationPath, prevVideoHasTrim, prevVideoTrimStart, prevVideoTrimEnd);
            merged = true;
          } catch (Exception e) {
            Log.i("Unable to merge two video messages", e);
          }
        }
        File mergedFile = new File(destinationPath);
        if (merged) {
          U.moveFile(mergedFile, outputFile);
        } else {
          FileUtils.deleteFile(mergedFile);
          U.moveFile(new File(prevVideoPath), outputFile);
        }
        if (outputFile.length() > ROUND_UPLOAD_DETACH_BYTES) {
          detachRoundUpload();
        }
        tdlib.client().send(new TdApi.FinishFileGeneration(roundGenerationId, null), tdlib.silentHandler(after));
      });
      return;
    }
    if (resultFileSize > ROUND_UPLOAD_DETACH_BYTES) {
      detachRoundUpload();
    }
    tdlib.client().send(new TdApi.SetFileGenerationProgress(roundGenerationId, resultFileSize, resultFileSize), tdlib.silentHandler());
    tdlib.client().send(new TdApi.FinishFileGeneration(roundGenerationId, null), tdlib.silentHandler(after));
  }

  // Vibegram: the real side of the recorded file (TDLib allows up to 640) instead of a fixed 360.
  private int getVideoNoteLength () {
    return RoundVideoQuality.getVideoNoteLength(roundKey);
  }

  @Override
  public void onVideoRecordingFinished (String key, long resultFileSize, long resultFileDuration, TimeUnit resultFileDurationUnit) {
    if (StringUtils.equalsOrBothEmpty(roundKey, key)) {
      boolean success = resultFileSize > 0;
      if (awaitingRoundResult()) {
        if (success) {
          this.savedRoundDurationSeconds += (int) resultFileDurationUnit.toSeconds(resultFileDuration);
          if (roundCloseMode == CLOSE_MODE_PREVIEW || roundCloseMode == CLOSE_MODE_PREVIEW_SCHEDULE) {
            awaitRoundVideo();
            finishFileGeneration(resultFileSize, null);
          } else {
            // The generated file is moved once finished: take its final location before choosing how to send it.
            finishFileGeneration(resultFileSize, () -> {
              TdApi.File file = roundFile;
              if (file == null) {
                return;
              }
              tdlib.client().send(new TdApi.GetFile(file.id), result -> tdlib.ui().post(() -> {
                if (roundFile == null || roundFile.id != file.id) {
                  return;
                }
                if (result.getConstructor() == TdApi.File.CONSTRUCTOR) {
                  roundFile = (TdApi.File) result;
                }
                sendRecordedRound(Td.newSendOptions());
              }));
            });
          }
        } else {
          finishFileGeneration(-1, this::cancelAwaitRoundRecord);
        }
      } else if (!success) {
        stopRecording(CLOSE_MODE_CANCEL, false);
      }
    }
  }

  /*@Override
  public void onUpdateFileGenerationFailure (final int fileId) {
    TGDataManager.runOnUiThread(new Runnable() {
      @Override
      public void run () {
        if (roundFile.id == fileId) {
          cancelAwaitRoundRecord();
        }
      }
    });
  }*/

  private void cancelAwaitRoundRecord () {
    if (recordMode == RECORD_MODE_VIDEO_EDIT) {
      roundCloseMode = CLOSE_MODE_CANCEL;
      setRecordMode(RECORD_MODE_NONE, true);
      cleanupVideoRecording();
    }
  }

  // Generation progress

  @Override
  public void onUpdateFile (final TdApi.File file) {
    if (!StringUtils.isEmpty(file.local.path) && file.size != 0 && file.local.isDownloadingCompleted) {
      tdlib.ui().post(() -> {
        if (roundFile != null && roundFile.id == file.id) {
          roundFile = file;
          roundGenerationFinished = true;
          onRoundVideoReady();
        }
      });
    }
  }

  /*@Override
  public void onUpdateFileGenerationFinish (final TdApi.File file) {
    TGDataManager.runOnUiThread(new Runnable() {
      @Override
      public void run () {
        if (roundFile != null && roundFile.id == file.id) {
          roundFile = file;
          roundGenerationFinished = true;
          onRoundVideoReady();
        }
      }
    });
  }*/

  // Video Trim/Preview

  private boolean roundGenerationFinished;

  private void awaitRoundVideo () {
    tdlib.files().subscribe(roundFile.id, this);
  }

  private boolean roundFileReceived () {
    return roundGenerationFinished && TD.isFileLoaded(roundFile);
  }

  private String prevVideoPath;
  private double prevVideoTrimStart;
  private double prevVideoTrimEnd;
  private boolean prevVideoHasTrim;

  private void onRoundVideoReady () {
    if (!roundFileReceived()) {
      return;
    }

    tdlib.files().unsubscribe(roundFile.id, this);
    videoTimelineView.setVideoPath(roundFile.local.path);
    videoPreviewView.setVideo(roundFile.local.path);
    prevVideoPath = roundFile.local.path;

    if (scheduledEditClose) {
      scheduledEditClose = false;
      TdApi.MessageSendOptions options = scheduledSendOptions;
      scheduledSendOptions = null;
      closeVideoEditMode(options);
    }
  }

  private boolean scheduledEditClose;
  private TdApi.MessageSendOptions scheduledSendOptions;

  private void sendVideo (@NonNull TdApi.MessageSendOptions initialSendOptions) {
    if (recordMode == RECORD_MODE_VIDEO_EDIT) {
      closeVideoEditMode(initialSendOptions);
    }
  }

  private void closeVideoEditMode (@NonNull TdApi.MessageSendOptions initialSendOptions) {
    if (recordMode != RECORD_MODE_VIDEO_EDIT) {
      return;
    }

    if (!roundFileReceived()) {
      scheduledEditClose = true;
      scheduledSendOptions = initialSendOptions;
      return;
    }

    if (initialSendOptions != null && initialSendOptions.schedulingState == null && targetController != null && targetController.areScheduledOnly()) {
      targetController.pickDateOrProceed(initialSendOptions,
        (modifiedSendOptions, disableMarkdown) ->
          closeVideoEditMode(modifiedSendOptions)
      );
      return;
    }

    setRecordModeImpl(RECORD_MODE_NONE);

    if (initialSendOptions != null) {
      if (videoPreviewView.hasTrim()) {
        tdlib.send(new TdApi.CancelPreliminaryUploadFile(roundFile.id), tdlib.typedOkHandler());
        double startTimeSeconds = videoPreviewView.getStartTime();
        double endTimeSeconds = videoPreviewView.getEndTime();
        // Vibegram: trim with the round's own size/fps/bitrate, not the general video quality setting.
        String conversion = VideoGenerationInfo.makeRoundConversion(roundFile.id,
          (long) (startTimeSeconds * 1_000_000), (long) (endTimeSeconds * 1_000_000),
          (long) ((endTimeSeconds - startTimeSeconds) * 1000), RoundVideoQuality.getRecorded(roundKey)
        );
        TdApi.InputFileGenerated trimmedFile = new TdApi.InputFileGenerated(roundFile.local.path, conversion, 0);
        sendVideoNote(new TdApi.InputMessageVideoNote(new TdApi.InputVideoNote(trimmedFile, null, (int) Math.round(endTimeSeconds - startTimeSeconds), getVideoNoteLength()), obtainSelfDestructType()), initialSendOptions, null);
      } else {
        sendRecordedRound(initialSendOptions);
      }
    } else {
      tdlib.client().send(new TdApi.DeleteFile(roundFile.id), tdlib.silentHandler());
      roundCloseMode = CLOSE_MODE_CANCEL;
    }
    videoPreviewView.setPlaying(false);
    editAnimator.setValue(false, true);
    sendButton.destroySlowModeCounterController();
  }

  // Audio Preview

  private void sendAudio (@NonNull TdApi.MessageSendOptions initialSendOptions) {
    if (recordMode == RECORD_MODE_AUDIO_EDIT) {
      closeAudioEditMode(initialSendOptions);
    }
  }

  private void closeAudioEditMode (TdApi.MessageSendOptions initialSendOptions) {
    if (recordMode != RECORD_MODE_AUDIO_EDIT) {
      return;
    }

    setRecordModeImpl(RECORD_MODE_NONE);

    final var record = voiceRecord;
    if (initialSendOptions != null && record != null) {
      if (record.getWaveform() == null) {
        Background.instance().post(() -> {
          byte[] waveform = N.getWaveform(record.getPath());
          sendAudioNote(new TdApi.InputMessageVoiceNote(new TdApi.InputVoiceNote(record.toInputFile(), record.getDuration(), waveform), null, obtainSelfDestructType()), initialSendOptions);
        });
      } else {
        sendAudioNote(new TdApi.InputMessageVoiceNote(new TdApi.InputVoiceNote(record.toInputFile(), record.getDuration(), record.getWaveform()), null, obtainSelfDestructType()), initialSendOptions);
      }
      Recorder.instance().finish(false);
    } else {
      if (record != null) {
        tdlib.client().send(new TdApi.DeleteFile(record.getFileId()), tdlib.silentHandler());
      }
      voiceCloseMode = CLOSE_MODE_CANCEL;
      Recorder.instance().finish(true);
    }
    editAnimator.setValue(false, true);
    sendButton.destroySlowModeCounterController();
  }
}
