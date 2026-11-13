/*
 * This file is a part of Vibegram X, a fork of Telegram X
 * Based on BotButtons from Telegram for Android (https://github.com/DrKLO/Telegram), GPL-2.0-or-later.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.webapp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.ThemeChangeListener;
import org.thunderdog.challegram.theme.ThemeManager;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.TextEntity;
import org.thunderdog.challegram.util.text.TextEntityMessage;
import org.thunderdog.challegram.util.text.TextStyleProvider;
import org.thunderdog.challegram.widget.ProgressComponent;

import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.StringUtils;

import java.util.List;

/**
 * Main and secondary buttons of a mini app: one view that lays out, animates and draws both, so
 * their slots, colors and the bar height change in one transition. Accessibility services see them
 * as two virtual buttons.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
final class WebAppButtonsView extends View implements FactorAnimator.Target, ThemeChangeListener {
  interface Callback {
    void onButtonPressed (boolean isMain);
    void onVisibleHeightChanged (int targetHeight);
  }

  static final String POSITION_LEFT = "left", POSITION_RIGHT = "right";
  static final String POSITION_TOP = "top", POSITION_BOTTOM = "bottom";

  private static final float BUTTON_HEIGHT = 44f, PADDING = 7f, SIDE_MARGIN = 8f, GAP = 10f;
  private static final float TEXT_PADDING = 16f, ICON_GAP = 6f, PROGRESS_SHIFT = 10f;
  private static final long DURATION = 320L, SHINE_DURATION = 2000L;
  private static final String ICON_PLACEHOLDER = "\u2B50";
  private static final int VIRTUAL_ID_MAIN = 0, VIRTUAL_ID_SECONDARY = 1;

  private static TextStyleProvider iconStyleProvider;

  private final Tdlib tdlib;
  private final Callback callback;
  private final Button mainButton, secondaryButton;
  private final FactorAnimator animator;
  private final ComplexReceiver iconReceiver;
  private final AccessibilityHelper accessibilityHelper;
  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final float textBaselineOffset;

  private String secondaryPosition = POSITION_LEFT;
  private @ColorInt int barColor;
  private float fromHeight, toHeight, visibleHeight;
  private @Nullable Button pressedButton;

  WebAppButtonsView (Context context, Tdlib tdlib, Callback callback) {
    super(context);
    this.tdlib = tdlib;
    this.callback = callback;
    this.mainButton = new Button(true);
    this.secondaryButton = new Button(false);
    this.animator = new FactorAnimator(0, this, M3.EMPHASIZED, DURATION);
    this.iconReceiver = new ComplexReceiver(this);
    this.accessibilityHelper = new AccessibilityHelper();
    ViewCompat.setAccessibilityDelegate(this, accessibilityHelper);
    textPaint.setTypeface(Fonts.getRobotoMedium());
    textPaint.setTextSize(Screen.sp(14f));
    Paint.FontMetricsInt metrics = textPaint.getFontMetricsInt();
    textBaselineOffset = -(metrics.ascent + metrics.descent) / 2f;
  }

  static int getMaxHeight () {
    return Screen.dp(PADDING * 3 + BUTTON_HEIGHT * 2) + 1;
  }

  /** Height of the bar once the running transition ends. */
  int getTargetHeight () {
    return (int) toHeight;
  }

  float getVisibleHeight () {
    return visibleHeight;
  }

  void setBarColor (@ColorInt int color) {
    if (barColor != color) {
      barColor = color;
      invalidate();
    }
  }

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    iconReceiver.attach();
    ThemeManager.instance().addThemeListener(this);
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    iconReceiver.detach();
    ThemeManager.instance().removeThemeListener(this);
  }

  @Override
  public boolean needsTempUpdates () {
    return true;
  }

  @Override
  public void onThemeColorsChanged (boolean areTemp, @Nullable ColorState state) {
    invalidate();
  }

  void performDestroy () {
    animator.cancel();
    mainButton.destroyIcon();
    secondaryButton.destroyIcon();
    iconReceiver.performDestroy();
    mainButton.progress.performDestroy();
    secondaryButton.progress.performDestroy();
  }

  /**
   * Applies {@code web_app_setup_main_button} or {@code web_app_setup_secondary_button}. Values
   * missing in the event keep the last ones.
   */
  void setup (
    boolean isMain,
    boolean isVisible,
    boolean isActive,
    @Nullable String text,
    @ColorInt int color,
    @ColorInt int textColor,
    boolean isProgressVisible,
    boolean hasShineEffect,
    long iconCustomEmojiId,
    @Nullable String position
  ) {
    Button button = isMain ? mainButton : secondaryButton;
    if (text != null) {
      button.text = text.trim();
    }
    if (color != 0) {
      button.color = color;
    }
    if (textColor != 0) {
      button.textColor = textColor;
    }
    button.isVisible = isVisible && !StringUtils.isEmpty(button.text);
    button.isActive = isActive;
    button.isProgressVisible = button.isVisible && isProgressVisible;
    button.hasShine = button.isVisible && hasShineEffect;
    button.setIcon(iconCustomEmojiId);
    if (!isMain && !StringUtils.isEmpty(position)) {
      secondaryPosition = position;
    }
    relayout(true);
    accessibilityHelper.invalidateRoot();
  }

  boolean isVisible (boolean isMain) {
    return (isMain ? mainButton : secondaryButton).isVisible;
  }

  @Override
  protected void onSizeChanged (int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    if (width != oldWidth) {
      relayout(false);
    }
  }

  private void relayout (boolean animated) {
    final int width = getMeasuredWidth();
    if (width == 0) {
      return;
    }
    final boolean both = mainButton.isVisible && secondaryButton.isVisible;
    final boolean twoRows = both &&
      (POSITION_TOP.equals(secondaryPosition) || POSITION_BOTTOM.equals(secondaryPosition));
    final float rowHeight = Screen.dp(PADDING + BUTTON_HEIGHT);
    final float margin = Screen.dp(SIDE_MARGIN);
    final float firstTop = Screen.dp(PADDING);
    final float secondTop = firstTop + rowHeight;
    final float buttonHeight = Screen.dp(BUTTON_HEIGHT);
    final float fullWidth = width - margin * 2;

    final int rowCount = twoRows ? 2 : mainButton.isVisible || secondaryButton.isVisible ? 1 : 0;
    final float newHeight = rowCount == 0 ? 0 : Screen.dp(PADDING) + rowHeight * rowCount;

    if (both && !twoRows) {
      float halfWidth = (width - Screen.dp(SIDE_MARGIN * 2 + GAP)) / 2f;
      boolean secondaryLeft = !POSITION_RIGHT.equals(secondaryPosition);
      Button left = secondaryLeft ? secondaryButton : mainButton;
      Button right = secondaryLeft ? mainButton : secondaryButton;
      left.setTarget(margin, firstTop, margin + halfWidth, firstTop + buttonHeight);
      right.setTarget(width - margin - halfWidth, firstTop, width - margin,
        firstTop + buttonHeight);
    } else if (twoRows) {
      boolean secondaryTop = POSITION_TOP.equals(secondaryPosition);
      Button top = secondaryTop ? secondaryButton : mainButton;
      Button bottom = secondaryTop ? mainButton : secondaryButton;
      top.setTarget(margin, firstTop, margin + fullWidth, firstTop + buttonHeight);
      bottom.setTarget(margin, secondTop, margin + fullWidth, secondTop + buttonHeight);
    } else {
      Button single = secondaryButton.isVisible ? secondaryButton : mainButton;
      single.setTarget(margin, firstTop, margin + fullWidth, firstTop + buttonHeight);
      Button hidden = single == mainButton ? secondaryButton : mainButton;
      if (hidden.toAlpha == 0f && hidden.alpha == 0f) {
        hidden.setTarget(margin, firstTop, margin + fullWidth, firstTop + buttonHeight);
      } else {
        hidden.keepTarget();
      }
    }
    mainButton.prepareTransition();
    secondaryButton.prepareTransition();
    fromHeight = visibleHeight;
    final boolean heightChanged = toHeight != newHeight;
    toHeight = newHeight;
    animator.forceFactor(0f);
    if (animated && isShown()) {
      animator.animateTo(1f);
    } else {
      applyTransition(1f);
    }
    if (heightChanged) {
      callback.onVisibleHeightChanged((int) newHeight);
    }
  }

  private void applyTransition (float factor) {
    mainButton.apply(factor);
    secondaryButton.apply(factor);
    visibleHeight = MathUtils.fromTo(fromHeight, toHeight, factor);
    invalidate();
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    applyTransition(factor);
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    accessibilityHelper.invalidateRoot();
  }

  @Override
  protected void onDraw (@NonNull Canvas c) {
    if (visibleHeight <= 0f) {
      return;
    }
    final int width = getMeasuredWidth();
    final float top = getMeasuredHeight() - visibleHeight;
    fillPaint.setColor(barColor);
    c.drawRect(0, top, width, getMeasuredHeight(), fillPaint);
    c.drawRect(0, top, width, top + 1, Paints.fillingPaint(M3.outlineVariant()));
    c.save();
    c.clipRect(0, top, width, getMeasuredHeight());
    c.translate(0, top);
    final long now = SystemClock.uptimeMillis();
    // The button that fades out is drawn first, so the one that appears covers it while they cross.
    if (mainButton.toAlpha < secondaryButton.toAlpha) {
      mainButton.draw(c, now);
      secondaryButton.draw(c, now);
    } else {
      secondaryButton.draw(c, now);
      mainButton.draw(c, now);
    }
    c.restore();
    if ((mainButton.hasShine && mainButton.alpha > 0f) ||
      (secondaryButton.hasShine && secondaryButton.alpha > 0f)) {
      postInvalidateOnAnimation();
    }
  }

  private @Nullable Button findButton (float x, float y, boolean needActive) {
    final float buttonY = y - (getMeasuredHeight() - visibleHeight);
    if (mainButton.contains(x, buttonY, needActive)) {
      return mainButton;
    }
    if (secondaryButton.contains(x, buttonY, needActive)) {
      return secondaryButton;
    }
    return null;
  }

  private boolean performButtonClick (Button button) {
    if (!button.isVisible || !button.isActive) {
      return false;
    }
    callback.onButtonPressed(button.isMain);
    accessibilityHelper.sendEventForVirtualView(button.virtualViewId,
      AccessibilityEvent.TYPE_VIEW_CLICKED);
    return true;
  }

  @Override
  protected boolean dispatchHoverEvent (MotionEvent e) {
    return accessibilityHelper.dispatchHoverEvent(e) || super.dispatchHoverEvent(e);
  }

  // Accessibility services click the buttons through accessibilityHelper, not performClick.
  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent (MotionEvent e) {
    final float top = getMeasuredHeight() - visibleHeight;
    final float x = e.getX(), y = e.getY() - top;
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        if (e.getY() < top) {
          pressedButton = null;
          return false;
        }
        pressedButton = findButton(e.getX(), e.getY(), true);
        if (pressedButton != null) {
          pressedButton.isPressed = true;
          invalidate();
        }
        return true;
      }
      case MotionEvent.ACTION_MOVE: {
        if (pressedButton != null && !pressedButton.current.contains(x, y)) {
          clearPressed();
        }
        return true;
      }
      case MotionEvent.ACTION_UP: {
        Button button = pressedButton;
        clearPressed();
        if (button != null && button.contains(x, y, true)) {
          performButtonClick(button);
        }
        return true;
      }
      case MotionEvent.ACTION_CANCEL: {
        clearPressed();
        return true;
      }
    }
    return true;
  }

  private void clearPressed () {
    if (pressedButton != null) {
      pressedButton.isPressed = false;
      pressedButton = null;
      invalidate();
    }
  }

  private static TextStyleProvider iconStyleProvider () {
    if (iconStyleProvider == null) {
      iconStyleProvider = new TextStyleProvider(Fonts.newRobotoStorage())
        .setTextSize(14f * 1.4f)
        .setAllowSp(false);
    }
    return iconStyleProvider;
  }

  private final class Button {
    final boolean isMain;
    final int virtualViewId;
    final RectF from = new RectF(), to = new RectF(), current = new RectF();
    final ProgressComponent progress;
    final Matrix shineMatrix = new Matrix();
    final TextColorSet iconColorSet = () -> Button.this.currentTextColor();

    String text = "";
    /** Colors set by the bot; 0 until then, so the theme colors apply. */
    @ColorInt int color, textColor;
    boolean isVisible, isActive = true, isProgressVisible, hasShine, isPressed;

    float fromAlpha, toAlpha, alpha;
    @ColorInt int fromColor, fromTextColor;
    float colorFactor = 1f;
    float fromProgress, toProgress, progressFactor;
    float fromShine, toShine, shineFactor;

    long iconCustomEmojiId;
    @Nullable Text icon;
    @Nullable String layoutText;
    float layoutTextWidth;
    int layoutWidth = -1;
    @Nullable LinearGradient shineGradient;
    int shineWidth;

    Button (boolean isMain) {
      this.isMain = isMain;
      this.virtualViewId = isMain ? VIRTUAL_ID_MAIN : VIRTUAL_ID_SECONDARY;
      this.progress = ProgressComponent.simpleInstance(WebAppButtonsView.this, 9f, 0, 0, 0, 0);
    }

    @ColorInt int containerColor () {
      return color != 0 ? color : M3.primary();
    }

    @ColorInt int labelColor () {
      return textColor != 0 ? textColor : M3.onPrimary();
    }

    @ColorInt int currentColor () {
      return ColorUtils.fromToArgb(fromColor, containerColor(), colorFactor);
    }

    @ColorInt int currentTextColor () {
      return ColorUtils.fromToArgb(fromTextColor, labelColor(), colorFactor);
    }

    void setTarget (float left, float top, float right, float bottom) {
      to.set(left, top, right, bottom);
      if (alpha == 0f) {
        // An invisible button appears in its new slot instead of sliding there from the old one.
        current.set(to);
      }
    }

    void keepTarget () {
      to.set(current);
    }

    void prepareTransition () {
      from.set(current);
      fromAlpha = alpha;
      toAlpha = isVisible ? 1f : 0f;
      fromColor = currentColor();
      fromTextColor = currentTextColor();
      fromProgress = progressFactor;
      toProgress = isProgressVisible ? 1f : 0f;
      fromShine = shineFactor;
      toShine = hasShine ? 1f : 0f;
      buildLayout((int) to.width());
    }

    void apply (float factor) {
      current.set(
        MathUtils.fromTo(from.left, to.left, factor),
        MathUtils.fromTo(from.top, to.top, factor),
        MathUtils.fromTo(from.right, to.right, factor),
        MathUtils.fromTo(from.bottom, to.bottom, factor)
      );
      alpha = MathUtils.fromTo(fromAlpha, toAlpha, factor);
      colorFactor = factor;
      progressFactor = MathUtils.fromTo(fromProgress, toProgress, factor);
      shineFactor = MathUtils.fromTo(fromShine, toShine, factor);
    }

    boolean contains (float x, float y, boolean needActive) {
      return isVisible && (isActive || !needActive) && alpha > .5f && current.contains(x, y);
    }

    void setIcon (long customEmojiId) {
      if (iconCustomEmojiId == customEmojiId && (customEmojiId == 0 || icon != null)) {
        return;
      }
      destroyIcon();
      iconCustomEmojiId = customEmojiId;
      layoutWidth = -1;
      if (customEmojiId == 0) {
        return;
      }
      TdApi.TextEntity[] rawEntities = {
        new TdApi.TextEntity(0, ICON_PLACEHOLDER.length(),
          new TdApi.TextEntityTypeCustomEmoji(customEmojiId))
      };
      TextEntity[] entities = TextEntity.valueOf(tdlib, ICON_PLACEHOLDER, rawEntities, null);
      if (entities != null &&
        Settings.instance().getNewSetting(Settings.SETTING_FLAG_NO_ANIMATED_EMOJI)) {
        for (TextEntity entity : entities) {
          if (entity instanceof TextEntityMessage) {
            ((TextEntityMessage) entity).setForceDisableAnimations(true);
          }
        }
      }
      icon = new Text.Builder(ICON_PLACEHOLDER, Screen.dp(100f), iconStyleProvider(), iconColorSet)
        .entities(entities, (text, specificMedia) -> requestIcons())
        .singleLine()
        .noClickable()
        .build();
      requestIcons();
    }

    void destroyIcon () {
      if (icon != null) {
        icon.performDestroy();
        icon = null;
      }
    }

    private void buildLayout (int width) {
      if (width == layoutWidth && StringUtils.equalsOrBothEmpty(text, layoutText)) {
        return;
      }
      layoutWidth = width;
      int iconSpace = icon != null ? icon.getWidth() + Screen.dp(ICON_GAP) : 0;
      float available = Math.max(0, width - Screen.dp(TEXT_PADDING) * 2 - iconSpace);
      CharSequence ellipsized = TextUtils.ellipsize(text, textPaint, available,
        TextUtils.TruncateAt.END);
      layoutText = ellipsized.toString();
      layoutTextWidth = textPaint.measureText(layoutText);
      if (shineWidth != width && width > 0) {
        shineWidth = width;
        shineGradient = new LinearGradient(0, 0, width / 2f, 0,
          new int[] {0x00ffffff, 0x40ffffff, 0x00ffffff}, null, Shader.TileMode.CLAMP);
      }
    }

    void draw (Canvas c, long now) {
      if (alpha <= 0f) {
        return;
      }
      final float radius = Screen.dp(M3.CORNER_MEDIUM);
      final float scale = MathUtils.fromTo(.7f, 1f, alpha);
      final float centerX = current.centerX(), centerY = current.centerY();
      final int currentColor = currentColor(), currentTextColor = currentTextColor();
      final int saveCount = c.save();
      c.scale(scale, scale, centerX, centerY);

      fillPaint.setColor(ColorUtils.alphaColor(alpha, currentColor));
      c.drawRoundRect(current, radius, radius, fillPaint);
      if (isPressed) {
        fillPaint.setColor(ColorUtils.alphaColor(alpha * M3.STATE_PRESSED, currentTextColor));
        c.drawRoundRect(current, radius, radius, fillPaint);
      }
      if (shineFactor > 0f && shineGradient != null) {
        float progress = (now % SHINE_DURATION) / (float) SHINE_DURATION;
        shineMatrix.setTranslate(
          current.left + MathUtils.fromTo(-shineWidth / 2f, shineWidth, progress), 0);
        shineGradient.setLocalMatrix(shineMatrix);
        fillPaint.setColor(0xffffffff);
        fillPaint.setAlpha((int) (255 * alpha * shineFactor));
        fillPaint.setShader(shineGradient);
        c.drawRoundRect(current, radius, radius, fillPaint);
        fillPaint.setShader(null);
      }

      c.clipRect(current);
      final float textAlpha = alpha * (1f - progressFactor);
      if (textAlpha > 0f && layoutText != null) {
        float textScale = MathUtils.fromTo(1f, .75f, progressFactor);
        float shift = -Screen.dp(PROGRESS_SHIFT) * progressFactor;
        c.save();
        c.scale(textScale, textScale, centerX, centerY + shift);
        int iconSpace = icon != null ? icon.getWidth() + Screen.dp(ICON_GAP) : 0;
        float groupLeft = centerX - (iconSpace + layoutTextWidth) / 2f;
        if (icon != null) {
          icon.draw(c, (int) groupLeft, (int) (centerY + shift - icon.getHeight() / 2f), null,
            textAlpha, iconReceiver);
        }
        textPaint.setColor(ColorUtils.alphaColor(textAlpha, currentTextColor));
        c.drawText(layoutText, groupLeft + iconSpace, centerY + shift + textBaselineOffset,
          textPaint);
        c.restore();
      }
      if (progressFactor > 0f) {
        float shift = Screen.dp(PROGRESS_SHIFT) * (1f - progressFactor);
        float progressScale = MathUtils.fromTo(.75f, 1f, progressFactor);
        int size = Screen.dp(18f);
        c.save();
        c.scale(progressScale, progressScale, centerX, centerY + shift);
        progress.setBounds((int) centerX - size / 2, (int) (centerY + shift) - size / 2,
          (int) centerX + size / 2, (int) (centerY + shift) + size / 2);
        progress.forceColor(currentTextColor);
        progress.setAlpha(alpha * progressFactor);
        progress.draw(c);
        c.restore();
      }
      c.restoreToCount(saveCount);
    }
  }

  private final class AccessibilityHelper extends ExploreByTouchHelper {
    private final Rect bounds = new Rect();

    AccessibilityHelper () {
      super(WebAppButtonsView.this);
    }

    private Button buttonFor (int virtualViewId) {
      return virtualViewId == VIRTUAL_ID_MAIN ? mainButton : secondaryButton;
    }

    @Override
    protected int getVirtualViewAt (float x, float y) {
      Button button = findButton(x, y, false);
      return button != null ? button.virtualViewId : INVALID_ID;
    }

    @Override
    protected void getVisibleVirtualViews (List<Integer> virtualViewIds) {
      if (mainButton.isVisible) {
        virtualViewIds.add(VIRTUAL_ID_MAIN);
      }
      if (secondaryButton.isVisible) {
        virtualViewIds.add(VIRTUAL_ID_SECONDARY);
      }
    }

    @Override
    protected void onPopulateNodeForVirtualView (int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
      Button button = buttonFor(virtualViewId);
      node.setClassName(android.widget.Button.class.getName());
      node.setText(button.text);
      node.setEnabled(button.isActive);
      button.to.roundOut(bounds);
      bounds.offset(0, getMeasuredHeight() - (int) toHeight);
      node.setBoundsInParent(bounds);
      if (button.isActive) {
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
      }
    }

    @Override
    protected boolean onPerformActionForVirtualView (int virtualViewId, int action, @Nullable Bundle arguments) {
      return action == AccessibilityNodeInfoCompat.ACTION_CLICK && performButtonClick(buttonFor(virtualViewId));
    }
  }

  private void requestIcons () {
    if (mainButton.icon != null) {
      mainButton.icon.requestMedia(iconReceiver, 0, 1);
    }
    if (secondaryButton.icon != null) {
      secondaryButton.icon.requestMedia(iconReceiver, 1, 1);
    }
    if (mainButton.icon == null && secondaryButton.icon == null) {
      iconReceiver.clear();
    }
  }
}
