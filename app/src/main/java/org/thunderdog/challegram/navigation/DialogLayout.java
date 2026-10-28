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
 */
package org.thunderdog.challegram.navigation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.text.TextEntity;
import org.thunderdog.challegram.widget.CustomTextView;
import org.thunderdog.challegram.widget.M3StateLayerDrawable;
import org.thunderdog.challegram.widget.NoScrollTextView;
import org.thunderdog.challegram.widget.RootFrameLayout;

import me.vkryl.android.ViewUtils;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.StringUtils;

/**
 * M3 basic dialog (m3.material.io/components/dialogs › Specs), shown centred by
 * {@link org.thunderdog.challegram.widget.PopupLayout#showFadingPopupView}: surfaceContainerHigh container,
 * 28dp corners, width 280..560dp, 24dp padding, headline HeadlineSmall (24sp) onSurface, 16dp headline → text,
 * supporting text BodyMedium (14sp), 24dp text → actions, primary text buttons aligned to the trailing edge with the
 * confirming action closest to the edge, 8dp between buttons, Level3 elevation. Headline and buttons stay pinned while
 * the supporting text / content scrolls.
 */
public class DialogLayout extends LinearLayout implements RootFrameLayout.MarginModifier {
  public static final int MIN_WIDTH = 280, MAX_WIDTH = 560;
  /** Window margin on narrow screens (312dp dialog on a 360dp phone). */
  public static final int WINDOW_MARGIN = 24;
  public static final int PADDING = 24;

  private final @Nullable ViewController<?> parent;
  private final @Nullable ThemeDelegate forcedTheme;
  private final TextView headlineView;
  private final ScrollView scrollView;
  /** Scrolling part between the headline and the buttons: {@link #scrollView}, or the list set by {@link #setListView}. */
  private View bodyView;
  private final LinearLayout contentLayout;
  private final CustomTextView textView;
  private final LinearLayout buttonsLayout;

  public DialogLayout (Context context, @Nullable ViewController<?> parent, @Nullable ThemeDelegate forcedTheme) {
    super(context);
    this.parent = parent;
    this.forcedTheme = forcedTheme;

    setOrientation(VERTICAL);
    setPadding(0, Screen.dp(PADDING), 0, Screen.dp(PADDING));
    // Vertical centring between the insets is done in onMeasure: FrameLayout's CENTER_VERTICAL shifts the child by the
    // whole difference of the top and bottom margins, so a keyboard inset would push the dialog off the top.
    setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

    ViewUtils.setBackground(this, new ContainerDrawable(this));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Screen.dp(M3.CORNER_EXTRA_LARGE));
        }
      });
      setElevation(Screen.dp(M3.ELEVATION_LEVEL3));
    }

    headlineView = new NoScrollTextView(context);
    headlineView.setTypeface(Fonts.getRobotoRegular());
    headlineView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f);
    headlineView.setLineSpacing(Screen.dp(32f) - headlineView.getPaint().getFontMetricsInt(null), 1f);
    headlineView.setTextColor(role(M3.ON_SURFACE));
    headlineView.setGravity(Lang.gravity());
    headlineView.setPadding(Screen.dp(PADDING), 0, Screen.dp(PADDING), Screen.dp(16f));
    headlineView.setVisibility(View.GONE);
    addView(headlineView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    contentLayout = new LinearLayout(context);
    contentLayout.setOrientation(VERTICAL);
    scrollView = new ScrollView(context);
    scrollView.setVerticalScrollBarEnabled(false);
    scrollView.addView(contentLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    bodyView = scrollView;

    textView = new CustomTextView(context, parent != null ? parent.tdlib() : null);
    textView.setTextSize(14f);
    textView.setTextColorId(ColorId.textLight);
    textView.setPadding(Screen.dp(PADDING), 0, Screen.dp(PADDING), 0);
    textView.setVisibility(View.GONE);
    contentLayout.addView(textView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    buttonsLayout = new LinearLayout(context);
    buttonsLayout.setOrientation(HORIZONTAL);
    buttonsLayout.setGravity(Gravity.CENTER_VERTICAL | (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT));
    buttonsLayout.setPadding(Screen.dp(PADDING), Screen.dp(PADDING), Screen.dp(PADDING), 0);
    buttonsLayout.setVisibility(View.GONE); // Until the first button: a list dialog may have no actions.
    addView(buttonsLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    if (forcedTheme != null) {
      textView.setForcedTheme(forcedTheme);
    } else if (parent != null) {
      parent.addThemeInvalidateListener(this);
      parent.addThemeInvalidateListener(textView);
    }
  }

  private @ColorInt int role (int role) {
    return M3.get(role, forcedTheme);
  }

  public void setHeadline (@Nullable CharSequence headline) {
    headlineView.setText(headline);
    headlineView.setVisibility(StringUtils.isEmpty(headline) ? View.GONE : View.VISIBLE);
  }

  /** Supporting text with entities (links close the popup when their handler does so). */
  public void setSupportingText (ViewController<?> context, Tdlib tdlib, @Nullable CharSequence text, int maxLineCount) {
    if (StringUtils.isEmpty(text)) {
      textView.setVisibility(View.GONE);
      return;
    }
    TextEntity[] entities = TD.collectAllEntities(context, tdlib, text, false, null);
    textView.setMaxLineCount(maxLineCount);
    textView.setText(text.toString(), entities, false);
    textView.setVisibility(View.VISIBLE);
  }

  /** Custom content under the supporting text (scrolls with it). */
  public void addContentView (View view) {
    contentLayout.addView(view);
  }

  /**
   * Supporting text in a plain TextView (BodyMedium, onSurfaceVariant): spans of an alert message work as in the
   * platform alert (clickable spans need {@link android.text.method.LinkMovementMethod}). Scrolls like the content.
   */
  public TextView addMessageView (CharSequence text) {
    TextView view = new NoScrollTextView(getContext());
    view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
    view.setLineSpacing(Screen.dp(20f) - view.getPaint().getFontMetricsInt(null), 1f);
    view.setTextColor(role(M3.ON_SURFACE_VARIANT));
    view.setLinkTextColor(role(M3.PRIMARY));
    view.setGravity(Lang.gravity());
    view.setPadding(Screen.dp(PADDING), 0, Screen.dp(PADDING), 0);
    view.setText(text);
    contentLayout.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return view;
  }

  /** Content that does not scroll (a text field, a wheel picker): between the supporting text and the actions. */
  public void addFixedView (View view) {
    addView(view, indexOfChild(buttonsLayout), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
  }

  /**
   * Text button under the content at its leading edge, for a third action: «Dialogs should contain a maximum of two
   * actions» (m3.material.io/components/dialogs › Guidelines), so it does not go into the actions row.
   */
  public TextView addContentButton (int id, CharSequence text, View.OnClickListener onClickListener) {
    TextView button = newButton(id, text, onClickListener);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(40f));
    params.gravity = Lang.rtl() ? Gravity.RIGHT : Gravity.LEFT;
    // Label aligned with the 24dp content edge (the button has 12dp inner padding).
    params.leftMargin = params.rightMargin = Screen.dp(PADDING - 12);
    params.topMargin = Screen.dp(8f);
    addView(button, indexOfChild(buttonsLayout), params);
    return button;
  }

  /**
   * Replaces the supporting text and content views with a view that scrolls by itself (a list of options). It is
   * shrunk like the supporting text when the dialog does not fit, the headline and buttons stay pinned.
   */
  public void setListView (View listView) {
    int index = indexOfChild(bodyView);
    removeViewAt(index);
    addView(listView, index, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    bodyView = listView;
  }

  /**
   * Adds a text button. Call for the dismissive action first and the confirming one last: the last button ends up
   * closest to the trailing edge.
   */
  public TextView addButton (int id, CharSequence text, View.OnClickListener onClickListener) {
    TextView button = newButton(id, text, onClickListener);
    buttons.add(button);
    buttonsLayout.setVisibility(View.VISIBLE);
    buttonsLayout.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(40f)));
    stackedButtons = null;
    return button;
  }

  private TextView newButton (int id, CharSequence text, View.OnClickListener onClickListener) {
    TextView button = new NoScrollTextView(getContext());
    button.setId(id);
    button.setTypeface(Fonts.getRobotoMedium());
    button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
    button.setTextColor(role(M3.PRIMARY));
    button.setText(text);
    button.setSingleLine(true);
    button.setEllipsize(TextUtils.TruncateAt.END);
    button.setGravity(Gravity.CENTER);
    button.setMinWidth(Screen.dp(48f));
    button.setMinimumWidth(Screen.dp(48f));
    button.setPadding(Screen.dp(12f), 0, Screen.dp(12f), 0);
    button.setOnClickListener(onClickListener);
    Views.setClickable(button);
    // Pressed state layer: primary 10% in a full-rounded (20dp) button shape.
    ViewUtils.setBackground(button, new M3StateLayerDrawable(null, new RoundMask(Screen.dp(20f)), ColorUtils.alphaColor(M3.STATE_PRESSED, role(M3.PRIMARY))));
    return button;
  }

  private final java.util.List<TextView> buttons = new java.util.ArrayList<>();
  private @Nullable Boolean stackedButtons;

  /**
   * Buttons side by side, the confirming one closest to the trailing edge, 8dp apart; when their labels do not fit,
   * stacked and end-aligned with the confirming one on top («Stacked buttons accommodate longer button text…
   * Confirming actions appear above dismissive actions»).
   */
  private void layoutButtons (int contentWidth) {
    int total = 0;
    for (TextView button : buttons) {
      button.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(Screen.dp(40f), MeasureSpec.EXACTLY));
      total += button.getMeasuredWidth();
    }
    total += Screen.dp(8f) * Math.max(0, buttons.size() - 1);
    boolean stacked = buttons.size() > 1 && total > contentWidth;
    if (stackedButtons != null && stackedButtons == stacked) {
      return;
    }
    stackedButtons = stacked;
    buttonsLayout.removeAllViews();
    buttonsLayout.setOrientation(stacked ? VERTICAL : HORIZONTAL);
    buttonsLayout.setGravity(stacked ? (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT) : Gravity.CENTER_VERTICAL | (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT));
    for (int i = 0; i < buttons.size(); i++) {
      // Side by side: dismissive first (reversed in RTL). Stacked: confirming first.
      TextView button = buttons.get(stacked ? buttons.size() - 1 - i : Lang.rtl() ? buttons.size() - 1 - i : i);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(40f));
      if (i > 0) {
        if (stacked) {
          params.topMargin = Screen.dp(8f);
        } else if (Lang.rtl()) {
          params.rightMargin = Screen.dp(8f);
        } else {
          params.leftMargin = Screen.dp(8f);
        }
      }
      buttonsLayout.addView(button, params);
    }
  }

  // Layout

  @Override
  public void onApplyMarginInsets (View child, FrameLayout.LayoutParams params, Rect legacyInsets, Rect insets, Rect insetsWithoutIme) {
    Views.setMargins(params, insets.left, insets.top, insets.right, insets.bottom);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
    int width = Math.max(Screen.dp(MIN_WIDTH), Math.min(Screen.dp(MAX_WIDTH), availableWidth - Screen.dp(WINDOW_MARGIN) * 2));
    if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
      width = Math.min(width, availableWidth);
    }
    layoutButtons(width - Screen.dp(PADDING) * 2);
    int widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
    int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
    ViewGroup.LayoutParams scrollParams = bodyView.getLayoutParams();
    scrollParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
    super.onMeasure(widthSpec, heightSpec);
    if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
      int availableHeight = MeasureSpec.getSize(heightMeasureSpec) - Screen.dp(WINDOW_MARGIN) * 2;
      int overflow = getMeasuredHeight() - availableHeight;
      if (overflow > 0) {
        // Headline and buttons stay, the supporting text scrolls.
        scrollParams.height = Math.max(0, bodyView.getMeasuredHeight() - overflow);
        super.onMeasure(widthSpec, heightSpec);
      }
      // Centred in the space left by the insets (incl. the keyboard).
      setTranslationY(Math.max(0, (MeasureSpec.getSize(heightMeasureSpec) - getMeasuredHeight()) / 2));
    }
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    super.onTouchEvent(e);
    return true; // Touches on the container must not reach the scrim (tap outside = dismiss).
  }

  // Drawing

  private static final class ContainerDrawable extends Drawable {
    private final DialogLayout dialog;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    ContainerDrawable (DialogLayout dialog) {
      this.dialog = dialog;
    }

    @Override
    public void draw (@NonNull Canvas c) {
      rect.set(getBounds());
      float radius = Screen.dp(M3.CORNER_EXTRA_LARGE);
      paint.setColor(dialog.role(M3.SURFACE_CONTAINER_HIGH));
      c.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    public void setAlpha (int alpha) { }

    @Override
    public void setColorFilter (@Nullable ColorFilter colorFilter) { }

    @Override
    public int getOpacity () {
      return PixelFormat.TRANSLUCENT;
    }
  }

  /** Mask of a state layer: rounded rect with the given radius (RectF overload, API 1). */
  private static final class RoundMask extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;

    RoundMask (float radius) {
      this.radius = radius;
      paint.setColor(0xffffffff);
    }

    @Override
    public void draw (@NonNull Canvas c) {
      rect.set(getBounds());
      c.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    public void setAlpha (int alpha) { }

    @Override
    public void setColorFilter (@Nullable ColorFilter colorFilter) { }

    @Override
    public int getOpacity () {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
