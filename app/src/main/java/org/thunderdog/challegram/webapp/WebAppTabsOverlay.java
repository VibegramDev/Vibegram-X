/*
 * This file is a part of Vibegram X, a fork of Telegram X
 * Based on BottomSheetTabsOverlay from Telegram for Android (https://github.com/DrKLO/Telegram), GPL-2.0-or-later.
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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.widget.PopupLayout;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

/**
 * All minimized mini apps of the account as a stack of tilted cards over a dim, shown for two or
 * more tabs.
 */
@SuppressLint("ViewConstructor")
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public final class WebAppTabsOverlay extends View implements
  PopupLayout.AnimatedPopupProvider,
  PopupLayout.DismissListener,
  FactorAnimator.Target,
  WebAppTabs.Listener {

  private static final int ANIMATOR_OPEN = 0;
  private static final int ANIMATOR_LAYOUT = 1;
  private static final int ANIMATOR_SCROLL = 2;

  private static final long OPEN_DURATION = 320L;
  private static final long LAYOUT_DURATION = 250L;

  private static final float HEADER_HEIGHT = 50f;
  private static final float SCROLL_STEP = 200f;
  private static final float DISMISS_DISTANCE = 300f;
  private static final float DISMISS_THRESHOLD = .4f;

  private static final int ID_CLOSE_ALL = Integer.MAX_VALUE;

  /** Shows the overlay over the activity of the strip, for the account the strip shows. */
  static void show (BaseActivity activity, WebAppTabsView strip) {
    List<WebAppTab> tabs = WebAppTabs.list(strip.getAccountId());
    if (tabs.size() < 2) {
      return;
    }
    WebAppTabsOverlay overlay = new WebAppTabsOverlay(activity, strip, tabs);
    PopupLayout popup = new PopupLayout(activity);
    overlay.popup = popup;
    popup.setIgnoreBottom(true);
    popup.setHideKeyboard();
    popup.setNeedRootInsets();
    popup.setOverlayStatusBar(true);
    popup.setHideBackground(true);
    popup.setDismissListener(overlay);
    popup.init(true);
    popup.showAnimatedPopupView(overlay, overlay);
  }

  private final class Card implements FactorAnimator.Target {
    final WebAppTab tab;
    final int tabColor;
    final @Nullable Bitmap preview;
    final FactorAnimator dismissAnimator;
    final RectF bounds = new RectF();
    @Nullable Text title;
    float fromIndex, toIndex;
    float dismiss, dismissFrom, dismissTo;
    boolean removed;

    Card (WebAppTab tab, int index) {
      this.tab = tab;
      this.tabColor = tab.getTabColor() | 0xff000000;
      this.preview = tab.getTabPreview();
      this.fromIndex = this.toIndex = index;
      this.dismissAnimator = new FactorAnimator(
        0,
        this,
        AnimatorUtils.DECELERATE_INTERPOLATOR,
        LAYOUT_DURATION
      );
    }

    float index (float factor) {
      return MathUtils.fromTo(fromIndex, toIndex, factor);
    }

    void animateDismiss (float toDismiss) {
      if (removed && toDismiss == 0f) {
        // Its app is already closed: the card leaves instead of coming back
        toDismiss = dismiss < 0f ? -1f : 1f;
      }
      dismissFrom = dismiss;
      dismissTo = toDismiss;
      dismissAnimator.forceFactor(0f);
      dismissAnimator.animateTo(1f);
    }

    @Override
    public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
      dismiss = MathUtils.fromTo(dismissFrom, dismissTo, factor);
      invalidate();
    }

    @Override
    public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
      if (finalFactor == 1f && Math.abs(dismissTo) == 1f) {
        onCardDismissed(this);
      }
    }
  }

  private final BaseActivity activity;
  private final WebAppTabsView strip;
  private final int accountId;
  private @Nullable PopupLayout popup;

  private final ArrayList<Card> cards;
  private final FactorAnimator openAnimator;
  private final FactorAnimator layoutAnimator;
  private final FactorAnimator scrollAnimator;
  private final OverScroller scroller;
  private final int touchSlop, minimumVelocity, maximumVelocity;

  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final TextPaint buttonPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final float buttonCenterOffset;
  private final String closeAllText;
  private final float closeAllTextWidth;
  private final WebAppTabsView.CardHeader header;
  private final AccessibilityHelper accessibilityHelper;

  private final Matrix matrix = new Matrix();
  private final float[] source = new float[8];
  private final float[] destination = new float[8];
  private final Path clipPath = new Path();
  private final RectF rect = new RectF();
  private final RectF stripRect = new RectF();
  private final RectF buttonRect = new RectF();
  private final int[] stripLocation = new int[2];
  private final int[] location = new int[2];
  private int stripOffsetX, stripOffsetY;

  private float fromCount, toCount;
  private float offset;
  private float scrollFrom, scrollTo;
  private boolean isClosing, fadeOnClose;

  WebAppTabsOverlay (BaseActivity activity, WebAppTabsView strip, List<WebAppTab> tabs) {
    super(activity);
    this.activity = activity;
    this.strip = strip;
    this.accountId = strip.getAccountId();
    this.cards = new ArrayList<>(tabs.size());
    for (int i = 0; i < tabs.size(); i++) {
      cards.add(new Card(tabs.get(i), i));
    }
    this.fromCount = this.toCount = cards.size();
    this.offset = getScrollMax(toCount);

    this.openAnimator = new FactorAnimator(
      ANIMATOR_OPEN,
      this,
      AnimatorUtils.DECELERATE_INTERPOLATOR,
      OPEN_DURATION
    );
    this.layoutAnimator = new FactorAnimator(
      ANIMATOR_LAYOUT,
      this,
      AnimatorUtils.DECELERATE_INTERPOLATOR,
      LAYOUT_DURATION,
      1f
    );
    this.scrollAnimator = new FactorAnimator(
      ANIMATOR_SCROLL,
      this,
      AnimatorUtils.DECELERATE_INTERPOLATOR,
      LAYOUT_DURATION
    );
    this.scroller = new OverScroller(activity);
    ViewConfiguration configuration = ViewConfiguration.get(activity);
    this.touchSlop = configuration.getScaledTouchSlop();
    this.minimumVelocity = configuration.getScaledMinimumFlingVelocity();
    this.maximumVelocity = configuration.getScaledMaximumFlingVelocity();

    fillPaint.setStyle(Paint.Style.FILL);
    buttonPaint.setTypeface(Fonts.getRobotoMedium());
    buttonPaint.setTextSize(Screen.sp(14f));
    Paint.FontMetrics metrics = buttonPaint.getFontMetrics();
    buttonCenterOffset = -(metrics.ascent + metrics.descent) / 2f;
    // The overlay is modal and short-lived: the language cannot change while it is shown
    closeAllText = Lang.getString(R.string.vg_BotCloseAllTabs);
    closeAllTextWidth = buttonPaint.measureText(closeAllText);

    header = new WebAppTabsView.CardHeader(this);
    accessibilityHelper = new AccessibilityHelper();
    ViewCompat.setAccessibilityDelegate(this, accessibilityHelper);

    setLayoutParams(FrameLayoutFix.newParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT
    ));
  }

  // Popup

  @Override
  public void prepareShowAnimation () {
    openAnimator.forceFactor(0f);
  }

  @Override
  public void launchShowAnimation (PopupLayout popup) {
    strip.getLocationInWindow(stripLocation);
    getLocationInWindow(location);
    stripOffsetX = stripLocation[0] - location[0];
    stripOffsetY = stripLocation[1] - location[1];
    strip.setCardsHidden(true);
    WebAppTabs.addListener(this);
    openAnimator.animateTo(1f);
  }

  @Override
  public boolean launchHideAnimation (PopupLayout popup, FactorAnimator originalAnimator) {
    isClosing = true;
    stopScrolling();
    openAnimator.animateTo(0f);
    return true;
  }

  @Override
  public void onPopupDismiss (PopupLayout popup) {
    WebAppTabs.removeListener(this);
    strip.setCardsHidden(false);
  }

  private void close (boolean fade) {
    if (popup != null && !isClosing) {
      fadeOnClose = fade;
      popup.hideWindow(true);
    }
  }

  // Model

  @Override
  public void onTabsChanged (int accountId) {
    if (this.accountId != accountId) {
      return;
    }
    int remaining = 0;
    for (int i = 0; i < cards.size(); i++) {
      Card card = cards.get(i);
      if (!card.removed && !WebAppTabs.contains(card.tab)) {
        card.removed = true;
        if (Math.abs(card.dismissTo) != 1f || !card.dismissAnimator.isAnimating()) {
          card.animateDismiss(card.dismiss < 0f ? -1f : 1f);
        }
      }
      if (!card.removed) {
        remaining++;
      }
    }
    if (remaining == 0) {
      close(true);
    }
    accessibilityHelper.invalidateRoot();
  }

  private void onCardDismissed (Card card) {
    if (!cards.contains(card)) {
      return;
    }
    if (!card.removed && WebAppTabs.contains(card.tab)) {
      // The app asked for a confirmation of closing: the card comes back until it is confirmed
      card.animateDismiss(0f);
      return;
    }
    card.removed = true;
    float factor = layoutAnimator.getFactor();
    float count = MathUtils.fromTo(fromCount, toCount, factor);
    for (int i = 0; i < cards.size(); i++) {
      Card other = cards.get(i);
      other.fromIndex = other.index(factor);
    }
    cards.remove(card);
    for (int i = 0; i < cards.size(); i++) {
      cards.get(i).toIndex = i;
    }
    fromCount = count;
    toCount = cards.size();
    layoutAnimator.forceFactor(0f);
    layoutAnimator.animateTo(1f);
    accessibilityHelper.invalidateRoot();
    if (cards.isEmpty()) {
      close(true);
    } else {
      animateScroll(MathUtils.clamp(offset, getScrollMin(toCount), getScrollMax(toCount)));
    }
  }

  private void closeAll () {
    List<WebAppTab> tabs = new ArrayList<>(WebAppTabs.list(accountId));
    close(true);
    for (int i = 0; i < tabs.size(); i++) {
      tabs.get(i).closeFromTab(true);
    }
  }

  private void restore (Card card) {
    close(true);
    card.tab.restoreFromTab();
  }

  // Scrolling, in cards

  private static float getScrollWindow (float count) {
    return Math.min(3f, count);
  }

  private static float getScrollMin (float count) {
    return -getScrollWindow(count) / 3f * MathUtils.clamp(count);
  }

  private static float getScrollMax (float count) {
    float window = getScrollWindow(count);
    return count - window - window / 3f * MathUtils.clamp(4f - count, 0f, .5f);
  }

  private void animateScroll (float toOffset) {
    scroller.forceFinished(true);
    scrollFrom = offset;
    scrollTo = toOffset;
    scrollAnimator.forceFactor(0f);
    scrollAnimator.animateTo(1f);
  }

  private void stopScrolling () {
    scroller.forceFinished(true);
    scrollAnimator.cancel();
  }

  @Override
  public void computeScroll () {
    if (scroller.computeScrollOffset()) {
      offset = scroller.getCurrY() / (float) Screen.dp(SCROLL_STEP);
      postInvalidateOnAnimation();
    }
  }

  // Animation

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    if (id == ANIMATOR_SCROLL) {
      offset = MathUtils.fromTo(scrollFrom, scrollTo, factor);
    }
    invalidate();
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    accessibilityHelper.invalidateRoot();
    if (id != ANIMATOR_OPEN || popup == null) {
      return;
    }
    if (finalFactor == 1f && !isClosing) {
      popup.onCustomShowComplete();
    } else if (finalFactor == 0f && isClosing) {
      popup.onCustomHideAnimationComplete();
    }
  }

  // Layout

  private float getPaddingTopOffset () {
    return HeaderView.getTopOffset() + Screen.dp(95f);
  }

  private float getPaddingBottomOffset () {
    return Screen.dp(WebAppTabs.STRIP_HEIGHT_STACK) + activity.getRootView().getSystemInsetsWithoutIme().bottom;
  }

  private float getCardWidth () {
    return Math.min(Screen.dp(340f), getWidth() * .95f);
  }

  @Override
  protected void onSizeChanged (int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    if (width != oldWidth) {
      float cardWidth = getCardWidth();
      for (int i = 0; i < cards.size(); i++) {
        Card card = cards.get(i);
        card.title = header.buildTitle(card.tab.getTabTitle().toString(), cardWidth);
      }
    }
  }

  // Drawing

  @Override
  protected void onDraw (Canvas c) {
    final float open = openAnimator.getFactor();
    if (open <= 0f) {
      return;
    }
    final float width = getWidth();
    final float height = getHeight();
    c.drawColor(ColorUtils.alphaColor(.6f * open, Color.BLACK));

    final float layoutFactor = layoutAnimator.getFactor();
    final float count = MathUtils.fromTo(fromCount, toCount, layoutFactor);
    final float window = getScrollWindow(count);
    final float scrollMin = getScrollMin(count);
    final float paddingTop = getPaddingTopOffset();
    final float cardWidth = getCardWidth();
    final float cardHeight = height * .75f;
    final float centerX = width / 2f;
    final float stackBottom = height - getPaddingBottomOffset() - cardHeight * .26f;
    final boolean fade = isClosing && fadeOnClose;
    // Pulling the stack down past its start folds the cards back towards the strip
    final float pull = window > 0f ?
      MathUtils.clamp((scrollMin - offset) / (window * .15f) * .2f) :
      0f;
    final float expand = fade ? 1f : open * (1f - pull);
    final float alpha = fade ? open : 1f;
    final int stripCardColor = WebAppTabsView.getCardColor(WebAppTabsView.getStripColor());

    for (int i = cards.size() - 1; i >= 0; i--) {
      Card card = cards.get(i);
      card.bounds.setEmpty();
      if (window <= 0f) {
        continue;
      }
      float index = card.index(layoutFactor);
      float position = count - 1f - index;
      float scroll = (position - Math.max(scrollMin, offset)) / window;
      float top = paddingTop + Screen.dp(6f) * Math.min(5f, position);
      float y = top + (stackBottom - top) * scroll;
      rect.set(centerX - cardWidth / 2f, y, centerX + cardWidth / 2f, y + cardHeight);
      if (expand < 1f) {
        strip.getCardBounds(stripRect, Math.round(index));
        stripRect.offset(stripOffsetX, stripOffsetY);
        rect.set(
          MathUtils.fromTo(stripRect.left, rect.left, expand),
          MathUtils.fromTo(stripRect.top, rect.top, expand),
          MathUtils.fromTo(stripRect.right, rect.right, expand),
          MathUtils.fromTo(stripRect.bottom, rect.bottom, expand)
        );
      }
      if (rect.top > height || rect.bottom < 0f) {
        continue;
      }
      card.bounds.set(rect);
      float dismissAlpha = MathUtils.clamp(1f - (Math.abs(card.dismiss) - .3f) / .7f);
      float cardAlpha = alpha * dismissAlpha;
      if (cardAlpha <= 0f) {
        continue;
      }
      drawCard(c, card, expand, cardAlpha, stripCardColor);
    }

    drawCloseAllButton(c, width, paddingTop, alpha * open);
  }

  private void drawCard (Canvas c, Card card, float expand, float alpha, int stripCardColor) {
    final float radius = Screen.dp(WebAppTabsView.CARD_RADIUS);
    final float headerHeight = Screen.dp(HEADER_HEIGHT);
    final int saveCount = c.save();

    // Perspective of cards lying on a table: the bottom edge is narrower and higher
    final float bottomScale = MathUtils.fromTo(1f, .83f, expand);
    final float heightScale = MathUtils.fromTo(1f, .6f, expand);
    source[0] = rect.left;
    source[1] = rect.top;
    source[2] = rect.right;
    source[3] = rect.top;
    source[4] = rect.right;
    source[5] = rect.bottom;
    source[6] = rect.left;
    source[7] = rect.bottom;
    destination[0] = rect.left;
    destination[1] = rect.top;
    destination[2] = rect.right;
    destination[3] = rect.top;
    destination[4] = rect.centerX() + rect.width() / 2f * bottomScale;
    destination[5] = rect.top + rect.height() * heightScale;
    destination[6] = rect.centerX() - rect.width() / 2f * bottomScale;
    destination[7] = destination[5];
    matrix.setPolyToPoly(source, 0, destination, 0, 4);
    c.concat(matrix);
    c.rotate(
      card.dismiss * 20f,
      rect.centerX() + Screen.dp(50f) * card.dismiss,
      rect.bottom + Screen.dp(350f)
    );

    clipPath.rewind();
    clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
    final int clipSaveCount = ViewSupport.clipPath(c, clipPath);

    // The header is stretched as much as the perspective squeezes it
    final float headerScale = MathUtils.fromTo(1f, 1.3f, expand);
    fillPaint.setColor(ColorUtils.alphaColor(alpha, Theme.fillingColor() | 0xff000000));
    c.drawRect(rect, fillPaint);
    if (card.preview != null && !card.preview.isRecycled() && card.preview.getWidth() > 0) {
      int previewSaveCount = c.save();
      c.translate(rect.left, rect.top + headerHeight * headerScale);
      float scale = rect.width() / card.preview.getWidth();
      c.scale(scale, scale * MathUtils.fromTo(1f, 1.25f, expand));
      bitmapPaint.setAlpha((int) (255f * alpha * expand));
      c.drawBitmap(card.preview, 0, 0, bitmapPaint);
      c.restoreToCount(previewSaveCount);
    }

    c.scale(1f, headerScale, rect.centerX(), rect.top);
    final int headerColor = ColorUtils.fromToArgb(stripCardColor, card.tabColor, expand);
    fillPaint.setColor(ColorUtils.alphaColor(alpha, headerColor));
    c.drawRect(rect.left, rect.top, rect.right, rect.top + headerHeight, fillPaint);

    final float centerY = rect.top + headerHeight / 2f;
    header.draw(c, rect, centerY, card.title, M3.contrast(headerColor), alpha, alpha * (1f - expand));
    ViewSupport.restoreClipPath(c, clipSaveCount);
    c.restoreToCount(saveCount);
  }

  private void drawCloseAllButton (Canvas c, float width, float paddingTop, float alpha) {
    if (alpha <= 0f) {
      return;
    }
    float buttonWidth = closeAllTextWidth + Screen.dp(24f);
    float centerY = paddingTop - Screen.dp(47.5f);
    buttonRect.set(
      (width - buttonWidth) / 2f,
      centerY - Screen.dp(14f),
      (width + buttonWidth) / 2f,
      centerY + Screen.dp(14f)
    );
    // The dim makes the background dark in both themes
    fillPaint.setColor(ColorUtils.alphaColor(alpha, pressedCloseAll ? 0x33ffffff : 0x20ffffff));
    c.drawRoundRect(buttonRect, Screen.dp(14f), Screen.dp(14f), fillPaint);
    buttonPaint.setColor(ColorUtils.alphaColor(alpha, Color.WHITE));
    c.drawText(
      closeAllText,
      buttonRect.left + Screen.dp(12f),
      centerY + buttonCenterOffset,
      buttonPaint
    );
  }

  // Touches

  private @Nullable VelocityTracker velocityTracker;
  private @Nullable Card pressedCard;
  private boolean pressedClose, pressedCloseAll;
  private boolean isScrolling, isSwiping, movedTooFar;
  private float startX, startY, lastY;
  private long startTime;

  private @Nullable Card findCardAt (float x, float y) {
    if (openAnimator.getFactor() < 1f || isClosing) {
      return null;
    }
    for (int i = 0; i < cards.size(); i++) {
      Card card = cards.get(i);
      if (!card.removed &&
        Math.abs(card.dismiss) < DISMISS_THRESHOLD &&
        card.bounds.contains(x, y)) {
        return card;
      }
    }
    return null;
  }

  private boolean isInsideClose (Card card, float x, float y) {
    return WebAppTabsView.CardHeader.isInsideClose(card.bounds, x) &&
      y - card.bounds.top < Screen.dp(HEADER_HEIGHT) * 1.3f;
  }

  private void recycleVelocityTracker () {
    if (velocityTracker != null) {
      velocityTracker.recycle();
      velocityTracker = null;
    }
  }

  // Taps act on the cards drawn by this view, the view itself has no click action
  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent (MotionEvent e) {
    if (isClosing) {
      return true;
    }
    final float x = e.getX();
    final float y = e.getY();
    if (velocityTracker != null) {
      velocityTracker.addMovement(e);
    }
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN: {
        recycleVelocityTracker();
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(e);
        startX = x;
        startY = lastY = y;
        startTime = e.getEventTime();
        isScrolling = isSwiping = movedTooFar = false;
        pressedCard = findCardAt(x, y);
        pressedClose = pressedCard != null && isInsideClose(pressedCard, x, y);
        pressedCloseAll = pressedCard == null && buttonRect.contains(x, y);
        if (pressedCard != null) {
          pressedCard.dismissAnimator.cancel();
        }
        stopScrolling();
        invalidate();
        return true;
      }
      case MotionEvent.ACTION_MOVE: {
        onTouchMove(x, y);
        lastY = y;
        return true;
      }
      case MotionEvent.ACTION_UP: {
        onTouchUp(x, y, e.getEventTime());
        resetTouch();
        return true;
      }
      case MotionEvent.ACTION_CANCEL: {
        if (pressedCard != null && pressedCard.dismiss != 0f) {
          pressedCard.animateDismiss(0f);
        }
        if (offset < getScrollMin(toCount)) {
          animateScroll(getScrollMin(toCount));
        }
        resetTouch();
        return true;
      }
    }
    return true;
  }

  private void resetTouch () {
    recycleVelocityTracker();
    pressedCard = null;
    pressedClose = false;
    if (pressedCloseAll) {
      pressedCloseAll = false;
      invalidate();
    }
  }

  private void onTouchMove (float x, float y) {
    final float dx = x - startX;
    final float dy = y - startY;
    if (!isScrolling && !isSwiping) {
      if (pressedCard != null && !pressedClose && Math.abs(dx) > touchSlop &&
        Math.abs(dx) > Math.abs(dy)) {
        isSwiping = true;
      } else if (Math.abs(dy) > touchSlop) {
        isScrolling = true;
      } else if (Math.abs(dx) > touchSlop) {
        movedTooFar = true;
      }
    }
    if (pressedClose && pressedCard != null && !isInsideClose(pressedCard, x, y)) {
      pressedClose = false;
    }
    if (pressedCloseAll && !buttonRect.contains(x, y)) {
      pressedCloseAll = false;
      invalidate();
    }
    if (isSwiping && pressedCard != null) {
      pressedCard.dismiss = dx / Screen.dp(DISMISS_DISTANCE);
      invalidate();
    } else if (isScrolling) {
      float deltaY = y - lastY;
      float scrollMin = getScrollMin(toCount);
      if (offset < scrollMin) {
        deltaY *= 1f - .5f * MathUtils.clamp(scrollMin - offset);
      }
      offset = MathUtils.clamp(
        offset - deltaY / Screen.dp(SCROLL_STEP),
        scrollMin - 1.4f,
        Math.max(scrollMin, getScrollMax(toCount))
      );
      invalidate();
    }
  }

  private void onTouchUp (float x, float y, long eventTime) {
    final Card card = pressedCard;
    if (isSwiping && card != null) {
      if (Math.abs(card.dismiss) > DISMISS_THRESHOLD) {
        card.animateDismiss(card.dismiss < 0f ? -1f : 1f);
        card.tab.closeFromTab(false);
      } else {
        card.animateDismiss(0f);
      }
      return;
    }
    if (isScrolling) {
      onScrollReleased();
      return;
    }
    if (movedTooFar) {
      return;
    }
    if (card != null) {
      if (pressedClose && isInsideClose(card, x, y)) {
        card.tab.closeFromTab(false);
      } else if (!pressedClose) {
        restore(card);
      }
    } else if (pressedCloseAll && buttonRect.contains(x, y)) {
      closeAll();
    } else if (eventTime - startTime <= ViewConfiguration.getTapTimeout() * 1.2f) {
      close(false);
    }
  }

  private void onScrollReleased () {
    final float window = getScrollWindow(toCount);
    final float scrollMin = getScrollMin(toCount);
    final float scrollMax = Math.max(scrollMin, getScrollMax(toCount));
    if (offset < scrollMin - window * .15f) {
      close(false);
    } else if (offset < scrollMin) {
      animateScroll(scrollMin);
    } else if (velocityTracker != null) {
      velocityTracker.computeCurrentVelocity(1000, maximumVelocity);
      float velocityY = velocityTracker.getYVelocity();
      if (Math.abs(velocityY) > minimumVelocity) {
        int step = Screen.dp(SCROLL_STEP);
        scroller.fling(
          0,
          (int) (offset * step),
          0,
          (int) -velocityY,
          0,
          0,
          (int) (scrollMin * step),
          (int) (scrollMax * step),
          0,
          (int) (.1f * step)
        );
        postInvalidateOnAnimation();
      }
    }
  }

  // Accessibility: every visible card and the button that closes all of them are virtual views

  @Override
  protected boolean dispatchHoverEvent (MotionEvent e) {
    return accessibilityHelper.dispatchHoverEvent(e) || super.dispatchHoverEvent(e);
  }

  private boolean isAccessible (Card card) {
    return !card.removed && !card.bounds.isEmpty() && Math.abs(card.dismiss) < DISMISS_THRESHOLD;
  }

  private final class AccessibilityHelper extends ExploreByTouchHelper {
    private final Rect bounds = new Rect();

    AccessibilityHelper () {
      super(WebAppTabsOverlay.this);
    }

    private @Nullable Card cardFor (int virtualViewId) {
      if (virtualViewId >= 0 && virtualViewId < cards.size()) {
        Card card = cards.get(virtualViewId);
        return isAccessible(card) ? card : null;
      }
      return null;
    }

    private boolean isReady () {
      return openAnimator.getFactor() == 1f && !isClosing;
    }

    @Override
    protected int getVirtualViewAt (float x, float y) {
      if (!isReady()) {
        return INVALID_ID;
      }
      if (buttonRect.contains(x, y)) {
        return ID_CLOSE_ALL;
      }
      Card card = findCardAt(x, y);
      return card != null ? cards.indexOf(card) : INVALID_ID;
    }

    @Override
    protected void getVisibleVirtualViews (List<Integer> virtualViewIds) {
      if (!isReady()) {
        return;
      }
      // Front card first, the order of the strip
      for (int i = 0; i < cards.size(); i++) {
        if (isAccessible(cards.get(i))) {
          virtualViewIds.add(i);
        }
      }
      virtualViewIds.add(ID_CLOSE_ALL);
    }

    @Override
    protected void onPopulateNodeForVirtualView (int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
      node.setClassName(android.widget.Button.class.getName());
      Card card = virtualViewId != ID_CLOSE_ALL ? cardFor(virtualViewId) : null;
      if (card == null) {
        node.setContentDescription(closeAllText);
        buttonRect.roundOut(bounds);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
      } else {
        node.setContentDescription(card.tab.getTabTitle());
        // The visible part of a tilted card
        card.bounds.roundOut(bounds);
        bounds.bottom = bounds.top + (int) (card.bounds.height() * .6f);
        node.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
          AccessibilityNodeInfoCompat.ACTION_CLICK, Lang.getString(R.string.Open)
        ));
        node.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
          AccessibilityNodeInfoCompat.ACTION_DISMISS, Lang.getString(R.string.vg_Close)
        ));
        node.setDismissable(true);
      }
      if (bounds.isEmpty()) {
        bounds.set(0, 0, 1, 1);
      }
      node.setBoundsInParent(bounds);
    }

    @Override
    protected boolean onPerformActionForVirtualView (int virtualViewId, int action, @Nullable Bundle arguments) {
      if (!isReady()) {
        return false;
      }
      if (virtualViewId == ID_CLOSE_ALL) {
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
          closeAll();
          return true;
        }
        return false;
      }
      Card card = cardFor(virtualViewId);
      if (card == null) {
        return false;
      }
      switch (action) {
        case AccessibilityNodeInfoCompat.ACTION_CLICK:
          restore(card);
          return true;
        case AccessibilityNodeInfoCompat.ACTION_DISMISS:
          card.tab.closeFromTab(false);
          return true;
      }
      return false;
    }
  }
}
