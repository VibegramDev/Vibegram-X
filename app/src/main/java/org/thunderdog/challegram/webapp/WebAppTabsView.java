/*
 * This file is a part of Vibegram X, a fork of Telegram X
 * Based on BottomSheetTabs from Telegram for Android (https://github.com/DrKLO/Telegram), GPL-2.0-or-later.
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.DrawerController;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.telegram.GlobalAccountListener;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeChangeListener;
import org.thunderdog.challegram.theme.ThemeManager;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.widget.RootFrameLayout;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

/**
 * Minimized mini apps of the current account: a strip at the very bottom of the app, above the
 * system navigation bar and under every screen. The screens end above it through
 * {@link NavigationController#setExtraBottomInset}.
 */
@SuppressLint("ViewConstructor")
public final class WebAppTabsView extends View implements
  RootFrameLayout.MarginModifier,
  FactorAnimator.Target,
  WebAppTabs.Listener,
  GlobalAccountListener,
  ThemeChangeListener,
  Lang.Listener,
  ViewTreeObserver.OnPreDrawListener {

  static final float CARD_RADIUS = 18f;
  static final float CLOSE_SIZE = 50f;

  private static final float CARD_HEIGHT = 50f;
  private static final float CARD_MARGIN = 4f;
  private static final float STACK_SHIFT = 8f;
  private static final float CORNER_RADIUS = 10f;
  private static final int MAX_CARDS = 4;
  private static final long ANIMATION_DURATION = 320L;

  private static final int[] STATE_PRESSED = {
    android.R.attr.state_pressed,
    android.R.attr.state_enabled
  };
  private static final int[] STATE_DEFAULT = {};

  private static final class Card {
    final WebAppTab tab;
    float fromPosition, toPosition;
    float fromAlpha, toAlpha;
    @Nullable String titleSource;
    @Nullable Text title;
    int titleWidth = -1;

    Card (WebAppTab tab) {
      this.tab = tab;
    }

    float position (float factor) {
      return MathUtils.fromTo(fromPosition, toPosition, factor);
    }

    float alpha (float factor) {
      return MathUtils.fromTo(fromAlpha, toAlpha, factor) * positionAlpha(position(factor));
    }
  }

  /** Close icon, bot name and expand icon of a tab card; the overlay of all tabs draws the same header. */
  static final class CardHeader {
    private static final float CLOSE_ICON_X = 24f;
    private static final float EXPAND_ICON_X = 28f;
    private static final float TITLE_X = 60f;
    /** Room for the icons on both sides of the title. */
    private static final float TITLE_PADDING = 100f;
    private static final float TITLE_TEXT_SIZE = 17f;

    private static final TextColorSet WHITE = () -> Color.WHITE;
    private static final TextColorSet BLACK = () -> Color.BLACK;

    private final View view;
    private final Drawable closeIcon, expandIcon;
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int iconColor;

    CardHeader (View view) {
      this.view = view;
      this.closeIcon = Drawables.get(view.getResources(), R.drawable.baseline_close_24);
      this.expandIcon = Drawables.get(view.getResources(), R.drawable.baseline_keyboard_arrow_up_24);
    }

    @Nullable Text buildTitle (@Nullable String title, float cardWidth) {
      int maxWidth = (int) cardWidth - Screen.dp(TITLE_PADDING);
      if (TextUtils.isEmpty(title) || maxWidth <= 0) {
        return null;
      }
      return new Text.Builder(title, maxWidth, Paints.robotoStyleProvider(TITLE_TEXT_SIZE), WHITE)
        .view(view)
        .allBold()
        .singleLine()
        .build();
    }

    static boolean isInsideClose (RectF card, float x) {
      int size = Screen.dp(CLOSE_SIZE);
      return Lang.rtl() ? x > card.right - size : x < card.left + size;
    }

    static void getCloseBounds (RectF card, float centerY, Rect out) {
      int size = Screen.dp(CLOSE_SIZE);
      int left = Lang.rtl() ? (int) card.right - size : (int) card.left;
      out.set(left, (int) (centerY - size / 2f), left + size, (int) (centerY + size / 2f));
    }

    void draw (Canvas c, RectF card, float centerY, @Nullable Text title, int contentColor, float alpha, float expandAlpha) {
      final boolean rtl = Lang.rtl();
      if (iconColor != contentColor || iconPaint.getColorFilter() == null) {
        iconColor = contentColor;
        iconPaint.setColorFilter(Paints.getColorFilter(contentColor));
      }
      iconPaint.setAlpha((int) (255f * alpha));
      float closeX = rtl ? card.right - Screen.dp(CLOSE_ICON_X) : card.left + Screen.dp(CLOSE_ICON_X);
      Drawables.drawCentered(c, closeIcon, closeX, centerY, iconPaint);
      if (expandAlpha > 0f) {
        iconPaint.setAlpha((int) (255f * expandAlpha));
        float expandX = rtl ? card.left + Screen.dp(EXPAND_ICON_X) : card.right - Screen.dp(EXPAND_ICON_X);
        Drawables.drawCentered(c, expandIcon, expandX, centerY, iconPaint);
      }
      if (title != null) {
        int x = rtl ?
          (int) (card.right - Screen.dp(TITLE_X)) - title.getWidth() :
          (int) (card.left + Screen.dp(TITLE_X));
        int y = (int) (centerY - title.getHeight() / 2f);
        title.draw(c, x, y, contentColor == Color.WHITE ? WHITE : BLACK, alpha);
      }
    }
  }

  private final BaseActivity activity;
  private final FactorAnimator positionAnimator;
  private final CardHeader header;

  private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path cornerPath = new Path();
  private final RectF rect = new RectF();
  private final Rect closeBounds = new Rect();
  private final Drawable closeRipple;

  private int accountId = -1;
  private ArrayList<Card> cards = new ArrayList<>(MAX_CARDS);
  private float fromVisibility, toVisibility;
  private float fromBand, toBand;
  private int navigationInset;
  private int appliedExtraInset;
  private boolean hasTabs, isContentCovered;
  private boolean cardsHidden;
  private boolean pressedClose, pressedCard;

  WebAppTabsView (BaseActivity activity) {
    super(activity);
    this.activity = activity;
    this.positionAnimator = new FactorAnimator(
      0,
      this,
      AnimatorUtils.DECELERATE_INTERPOLATOR,
      ANIMATION_DURATION,
      1f
    );
    this.header = new CardHeader(this);
    backgroundPaint.setStyle(Paint.Style.FILL);
    cardPaint.setStyle(Paint.Style.FILL);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      // Hardware rendering draws shadows of shapes only since Android 9
      cardPaint.setShadowLayer(Screen.dp(2.33f), 0, Screen.dp(1f), 0x10000000);
    }

    // Inverse rounded corner at the left top edge of the strip: the content above it looks
    // clipped with rounded bottom corners
    float radius = Screen.dp(CORNER_RADIUS);
    cornerPath.moveTo(0, -radius);
    cornerPath.lineTo(0, 0);
    cornerPath.lineTo(radius, 0);
    rect.set(0, -radius * 2f, radius * 2f, 0);
    cornerPath.arcTo(rect, 90f, 90f);
    cornerPath.close();

    closeRipple = Theme.transparentRoundSelector(Screen.dp(CLOSE_SIZE / 2f));
    closeRipple.setCallback(this);

    updateAccessibilityActions();
    setVisibility(View.GONE);
    setLayoutParams(FrameLayoutFix.newParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      getLayoutHeight(),
      Gravity.BOTTOM
    ));
  }

  private int getLayoutHeight () {
    return Screen.dp(CORNER_RADIUS) + Screen.dp(WebAppTabs.STRIP_HEIGHT_STACK) + navigationInset;
  }

  // Lifecycle

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    WebAppTabs.addListener(this);
    TdlibManager.instance().global().addAccountListener(this);
    ThemeManager.instance().addThemeListener(this);
    Lang.addLanguageListener(this);
    getViewTreeObserver().addOnPreDrawListener(this);
    setAccountId(activity.hasTdlib() ? activity.currentTdlib().id() : -1);
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    WebAppTabs.removeListener(this);
    TdlibManager.instance().global().removeAccountListener(this);
    ThemeManager.instance().removeThemeListener(this);
    Lang.removeLanguageListener(this);
    getViewTreeObserver().removeOnPreDrawListener(this);
  }

  @Override
  public void onAccountSwitched (
    TdlibAccount newAccount,
    TdApi.User profile,
    int reason,
    TdlibAccount oldAccount
  ) {
    setAccountId(newAccount.id);
  }

  @Override
  public boolean needsTempUpdates () {
    return true;
  }

  @Override
  public void onThemeColorsChanged (boolean areTemp, @Nullable ColorState state) {
    invalidate();
  }

  @Override
  public void onTabsChanged (int accountId) {
    if (this.accountId == accountId) {
      updateTabs(true);
    }
  }

  @Override
  public void onLanguagePackEvent (int event, int arg1) {
    if (event != Lang.EVENT_DATE_FORMAT_CHANGED) {
      updateTitles(WebAppTabs.list(accountId));
      updateAccessibilityActions();
      invalidate();
    }
  }

  @Override
  public boolean onPreDraw () {
    setContentCovered(checkContentCovered());
    return true;
  }

  /**
   * The strip belongs to the content, but lies above it in the root view: it must not stay on top
   * of the drawer and the recorder of voice and video messages, which are inside the content, nor
   * of the camera, which slides the content away and then removes it.
   */
  private boolean checkContentCovered () {
    DrawerController drawer = activity.getDrawer();
    View contentView = activity.getContentView();
    return (drawer != null && drawer.getShowFactor() > 0f) ||
      activity.getRecordAudioVideoController().isOpen() ||
      contentView.getParent() == null ||
      contentView.getTranslationY() != 0f;
  }

  private void setContentCovered (boolean isCovered) {
    if (this.isContentCovered != isCovered) {
      this.isContentCovered = isCovered;
      resetTouch();
      updateVisibility();
    }
  }

  private void updateVisibility () {
    setVisibility(hasTabs && !isContentCovered ? View.VISIBLE : View.GONE);
  }

  int getAccountId () {
    return accountId;
  }

  private void setAccountId (int accountId) {
    if (this.accountId != accountId) {
      this.accountId = accountId;
      cards.clear();
      updateTabs(false);
    }
  }

  // Insets

  @Override
  public void onApplyMarginInsets (
    View child,
    FrameLayout.LayoutParams params,
    Rect legacyInsets,
    Rect insets,
    Rect insetsWithoutIme
  ) {
    // The keyboard covers the strip
    boolean edgeToEdge = Settings.instance().useEdgeToEdge();
    int navigationInset = edgeToEdge ? insetsWithoutIme.bottom : 0;
    Views.setMargins(
      params,
      legacyInsets.left,
      0,
      legacyInsets.right,
      edgeToEdge ? 0 : legacyInsets.bottom
    );
    if (this.navigationInset != navigationInset) {
      this.navigationInset = navigationInset;
      params.height = getLayoutHeight();
      updateTranslation();
      invalidate();
    }
  }

  private void applyExtraInset () {
    int extraInset = WebAppTabs.getStripHeight(accountId);
    if (appliedExtraInset != extraInset) {
      appliedExtraInset = extraInset;
      NavigationController navigation = activity.navigation();
      if (navigation != null) {
        navigation.setExtraBottomInset(extraInset);
      }
    }
  }

  // State

  private void updateTabs (boolean animated) {
    List<WebAppTab> tabs = WebAppTabs.list(accountId);
    int count = tabs.size();
    float factor = animated ? positionAnimator.getFactor() : 1f;

    // The current frame becomes the start of the new transition
    float visibility = MathUtils.fromTo(fromVisibility, toVisibility, factor);
    float band = MathUtils.fromTo(fromBand, toBand, factor);
    boolean wasHidden = !animated || visibility == 0f;
    for (int i = 0; i < cards.size(); i++) {
      Card card = cards.get(i);
      card.fromAlpha = MathUtils.fromTo(card.fromAlpha, card.toAlpha, factor);
      card.fromPosition = card.position(factor);
    }

    ArrayList<Card> nextCards = new ArrayList<>(MAX_CARDS);
    int frontCount = Math.min(count, 2);
    for (int index = 0; index < frontCount; index++) {
      WebAppTab tab = tabs.get(index);
      Card card = findCard(tab);
      if (card == null) {
        card = new Card(tab);
        card.fromPosition = wasHidden ? index : -1f;
        card.fromAlpha = 1f;
      }
      card.toPosition = index;
      card.toAlpha = 1f;
      nextCards.add(card);
    }
    for (int i = 0; i < cards.size() && nextCards.size() < MAX_CARDS; i++) {
      Card card = cards.get(i);
      if (nextCards.contains(card)) {
        continue;
      }
      int index = tabs.indexOf(card.tab);
      if (index != -1) {
        card.toPosition = Math.min(index, 2);
        card.toAlpha = 1f;
      } else {
        card.toPosition = card.fromPosition;
        card.toAlpha = 0f;
      }
      if (card.fromAlpha * positionAlpha(card.fromPosition) > 0f) {
        nextCards.add(card);
      }
    }
    // Back cards first; a closed front card fades out on top of the one replacing it
    for (int i = 1; i < nextCards.size(); i++) {
      Card card = nextCards.get(i);
      int j = i - 1;
      while (j >= 0 && drawsAfter(nextCards.get(j), card)) {
        nextCards.set(j + 1, nextCards.get(j));
        j--;
      }
      nextCards.set(j + 1, card);
    }
    cards = nextCards;
    updateTitles(tabs);

    fromVisibility = visibility;
    fromBand = band;
    toVisibility = count > 0 ? 1f : 0f;
    toBand = count > 0 ? WebAppTabs.getStripHeight(accountId) : band;
    if (wasHidden) {
      fromBand = toBand;
    }
    if (count > 0) {
      hasTabs = true;
      updateVisibility();
    }
    applyExtraInset();

    if (animated) {
      positionAnimator.forceFactor(0f);
      positionAnimator.animateTo(1f);
    } else {
      positionAnimator.forceFactor(1f);
      onPositionsChanged();
      onPositionsSettled();
    }
  }

  private void updateTitles (List<WebAppTab> tabs) {
    for (int i = 0; i < cards.size(); i++) {
      Card card = cards.get(i);
      if (card.toAlpha != 0f) {
        setCardTitle(card, card.toPosition == 0f ? getFrontTitle(tabs) : card.tab.getTabTitle().toString());
      }
    }
    setContentDescription(tabs.isEmpty() ? null : getFrontTitle(tabs));
  }

  private static String getFrontTitle (List<WebAppTab> tabs) {
    String title = tabs.get(0).getTabTitle().toString();
    return tabs.size() > 1 ? Lang.plural(R.string.vg_BotMoreTabs, tabs.size() - 1, title) : title;
  }

  private @Nullable Card findCard (WebAppTab tab) {
    for (int i = 0; i < cards.size(); i++) {
      Card card = cards.get(i);
      if (card.tab == tab) {
        return card;
      }
    }
    return null;
  }

  private static boolean drawsAfter (Card card, Card other) {
    if (card.toPosition != other.toPosition) {
      return card.toPosition < other.toPosition;
    }
    return card.toAlpha == 0f && other.toAlpha != 0f;
  }

  private static float positionAlpha (float position) {
    if (position < 0f) {
      return Math.max(0f, 1f + position);
    }
    if (position < 1f) {
      return MathUtils.fromTo(1f, .87f, position);
    }
    return .87f * (1f - Math.min(1f, position - 1f));
  }

  private float getCardWidth () {
    return getWidth() - Screen.dp(CARD_MARGIN) * 2;
  }

  private void setCardTitle (Card card, String title) {
    int width = (int) getCardWidth();
    if (width == card.titleWidth && title.equals(card.titleSource)) {
      return;
    }
    card.titleSource = title;
    card.titleWidth = width;
    card.title = header.buildTitle(title, width);
  }

  @Override
  protected void onSizeChanged (int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    if (width != oldWidth) {
      for (int i = 0; i < cards.size(); i++) {
        Card card = cards.get(i);
        if (card.titleSource != null) {
          setCardTitle(card, card.titleSource);
        }
      }
    }
  }

  // Animation

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    onPositionsChanged();
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    if (finalFactor == 1f) {
      onPositionsSettled();
    }
  }

  private void onPositionsChanged () {
    updateTranslation();
    invalidate();
  }

  private void onPositionsSettled () {
    for (int i = cards.size() - 1; i >= 0; i--) {
      if (cards.get(i).alpha(1f) <= 0f) {
        cards.remove(i);
      }
    }
    if (toVisibility == 0f) {
      hasTabs = false;
      updateVisibility();
    }
  }

  private void updateTranslation () {
    float factor = positionAnimator.getFactor();
    float visibility = MathUtils.fromTo(fromVisibility, toVisibility, factor);
    float band = MathUtils.fromTo(fromBand, toBand, factor);
    setTranslationY((1f - visibility) * (band + Screen.dp(CORNER_RADIUS) + navigationInset));
  }

  /** The overlay of all tabs replaces the cards while it is shown. */
  void setCardsHidden (boolean hidden) {
    if (this.cardsHidden != hidden) {
      this.cardsHidden = hidden;
      resetTouch();
      invalidate();
    }
  }

  /**
   * Bounds of the card at {@code stackIndex} (0 is the newest tab) in coordinates of this view,
   * for the overlay of all tabs that grows its cards out of the strip.
   */
  void getCardBounds (RectF out, int stackIndex) {
    getCardBounds(out, Math.min(stackIndex, 1), getHeight() - navigationInset);
  }

  private void getCardBounds (RectF out, float position, float bottom) {
    float width = getCardWidth();
    float height = Screen.dp(CARD_HEIGHT);
    float scale = MathUtils.fromTo(1f, .95f, Math.abs(position));
    float centerX = getWidth() / 2f;
    float centerY = bottom - Screen.dp(CARD_MARGIN) - height / 2f
      - position * Screen.dp(STACK_SHIFT);
    out.set(
      centerX - width / 2f * scale,
      centerY - height / 2f * scale,
      centerX + width / 2f * scale,
      centerY + height / 2f * scale
    );
  }

  static int getCardColor (int backgroundColor) {
    boolean isDark = M3.contrast(backgroundColor) == Color.WHITE;
    int overlay = ColorUtils.alphaColor(isDark ? .08f : .75f, Color.WHITE);
    return ColorUtils.compositeColor(backgroundColor, overlay);
  }

  static int getStripColor () {
    // The color the activity gives to the system navigation bar
    return Theme.backgroundColor() | 0xff000000;
  }

  // Drawing

  @Override
  protected void onDraw (Canvas c) {
    final int width = getWidth();
    final int height = getHeight();
    final float factor = positionAnimator.getFactor();
    final float band = MathUtils.fromTo(fromBand, toBand, factor);
    final float cardsBottom = height - navigationInset;
    final float stripTop = cardsBottom - band;
    final int backgroundColor = getStripColor();

    backgroundPaint.setColor(backgroundColor);
    c.drawRect(0, stripTop, width, height, backgroundPaint);
    int saveCount = c.save();
    c.translate(0, stripTop);
    c.drawPath(cornerPath, backgroundPaint);
    c.translate(width, 0);
    c.scale(-1f, 1f);
    c.drawPath(cornerPath, backgroundPaint);
    c.restoreToCount(saveCount);

    if (cardsHidden) {
      return;
    }

    final int cardColor = getCardColor(backgroundColor);
    final int contentColor = M3.contrast(cardColor);
    final float radius = Screen.dp(CARD_RADIUS);
    for (int i = 0; i < cards.size(); i++) {
      Card card = cards.get(i);
      float position = card.position(factor);
      float alpha = card.alpha(factor);
      if (alpha <= 0f || position > 1.99f) {
        continue;
      }
      getCardBounds(rect, position, cardsBottom);
      cardPaint.setColor(ColorUtils.alphaColor(alpha, cardColor));
      c.drawRoundRect(rect, radius, radius, cardPaint);

      float contentAlpha = alpha * MathUtils.clamp(1f - Math.abs(position));
      if (contentAlpha <= 0f) {
        continue;
      }
      float centerY = rect.centerY();
      if (card.toPosition == 0f && card.toAlpha == 1f) {
        CardHeader.getCloseBounds(rect, centerY, closeBounds);
        closeRipple.setBounds(closeBounds);
        closeRipple.draw(c);
      }
      header.draw(c, rect, centerY, card.title, contentColor, contentAlpha, contentAlpha);
    }
  }

  @Override
  protected boolean verifyDrawable (@NonNull Drawable who) {
    return who == closeRipple || super.verifyDrawable(who);
  }

  // Touches

  private @Nullable Card findFrontCard () {
    for (int i = cards.size() - 1; i >= 0; i--) {
      Card card = cards.get(i);
      if (card.toPosition == 0f && card.toAlpha == 1f) {
        return card;
      }
    }
    return null;
  }

  private boolean findFrontBounds () {
    Card card = cards.isEmpty() || cardsHidden ? null : findFrontCard();
    if (card == null || toVisibility == 0f) {
      return false;
    }
    getCardBounds(rect, card.position(positionAnimator.getFactor()), getHeight() - navigationInset);
    return true;
  }

  private void setClosePressed (boolean pressed, float x, float y) {
    pressedClose = pressed;
    if (pressed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      closeRipple.setHotspot(x, y);
    }
    closeRipple.setState(pressed ? STATE_PRESSED : STATE_DEFAULT);
  }

  private void resetTouch () {
    if (pressedClose) {
      setClosePressed(false, 0, 0);
    }
    pressedCard = false;
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    final float x = e.getX();
    final float y = e.getY();
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN: {
        resetTouch();
        if (!findFrontBounds() || !rect.contains(x, y)) {
          return false;
        }
        if (CardHeader.isInsideClose(rect, x)) {
          setClosePressed(true, x, y);
        } else {
          pressedCard = true;
        }
        return true;
      }
      case MotionEvent.ACTION_MOVE: {
        if ((pressedClose || pressedCard) && (!findFrontBounds() || !rect.contains(x, y))) {
          resetTouch();
        }
        return true;
      }
      case MotionEvent.ACTION_UP: {
        boolean close = pressedClose;
        boolean click = pressedCard;
        resetTouch();
        if (close) {
          closeFrontTab();
        } else if (click) {
          performClick();
        }
        return true;
      }
      case MotionEvent.ACTION_CANCEL: {
        resetTouch();
        return true;
      }
    }
    return pressedClose || pressedCard;
  }

  private boolean closeFrontTab () {
    List<WebAppTab> tabs = WebAppTabs.list(accountId);
    if (tabs.isEmpty()) {
      return false;
    }
    tabs.get(0).closeFromTab(false);
    return true;
  }

  @Override
  public boolean performClick () {
    super.performClick();
    List<WebAppTab> tabs = WebAppTabs.list(accountId);
    if (tabs.size() == 1) {
      tabs.get(0).restoreFromTab();
    } else if (tabs.size() > 1 && WebApp.isSupported()) {
      WebAppTabsOverlay.show(activity, this);
    }
    return true;
  }

  // Accessibility: the strip is one node, its click opens the front tab and dismiss closes it

  private void updateAccessibilityActions () {
    ViewCompat.replaceAccessibilityAction(
      this,
      AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
      Lang.getString(R.string.Open),
      (view, arguments) -> performClick()
    );
    ViewCompat.replaceAccessibilityAction(
      this,
      AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_DISMISS,
      Lang.getString(R.string.vg_Close),
      (view, arguments) -> closeFrontTab()
    );
  }
}
