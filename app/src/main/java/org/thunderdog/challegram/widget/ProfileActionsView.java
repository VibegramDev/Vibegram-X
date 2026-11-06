/*
 * This file is a part of Vibegram X
 * Copyright © 2026 (Vibegram X)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.ViewUtils;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.StringUtils;

/**
 * Row of profile actions under the name and the status of the expanded profile app bar (the buttons of the official
 * app's profile: chat, sound, call...), as an M3 standard button group of tonal square buttons of equal width:
 * secondaryContainer container, onSecondaryContainer 24dp icon above a label medium label, 64dp tall, 16dp corners,
 * 8dp between the buttons ("Standard button group inner padding: M: 8dp") and 16dp side margins, aligned with the
 * info cards; pressed state layer onSecondaryContainer 10%.
 *
 * The view is the profile list row right after the empty offset under the expanded header (TYPE_EMPTY_OFFSET): the
 * list's item decoration pulls it up by {@link #getHeaderOverlap()} (the offset's gap under the header), so the buttons
 * start right at the bottom of the expanded header and stay glued to it while the header collapses; they fade and
 * shrink towards the header as it collapses into the small app bar ({@link #setExpandFactor}).
 */
public class ProfileActionsView extends ViewGroup {
  public static final int MAX_ACTIONS = 4;
  private static final float BUTTON_HEIGHT = 64f, SIDE_MARGIN = 16f, BUTTON_GAP = 8f, BOTTOM_MARGIN = 16f;
  /** Gap of TYPE_EMPTY_OFFSET under the expanded header (SettingHolder: 12dp + header size difference). */
  private static final float HEADER_OVERLAP = 12f;

  public static final class Action {
    public final int id;
    public final @DrawableRes int icon;
    public final String label;

    public Action (int id, @DrawableRes int icon, String label) {
      this.id = id;
      this.icon = icon;
      this.label = label != null ? label : "";
    }

    boolean sameAs (Action other) {
      return other != null && other.id == id && other.icon == icon && other.label.equals(label);
    }
  }

  public interface Callback {
    void onProfileActionPressed (View view, Action action);
  }

  private final ArrayList<Action> actions = new ArrayList<>(MAX_ACTIONS);
  private final @Nullable ViewController<?> themeProvider;
  private @Nullable Callback callback;
  private float expandFactor = 1f;

  public ProfileActionsView (Context context, @Nullable ViewController<?> themeProvider) {
    super(context);
    this.themeProvider = themeProvider;
    setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, getMeasuredRowHeight()));
  }

  public void setCallback (@Nullable Callback callback) {
    this.callback = callback;
  }

  /** Negative top offset of the row (item decoration): it is drawn over the empty offset's gap under the header. */
  public static int getHeaderOverlap () {
    return Screen.dp(HEADER_OVERLAP);
  }

  private static int getMeasuredRowHeight () {
    return Screen.dp(BUTTON_HEIGHT) + Screen.dp(BOTTOM_MARGIN);
  }

  /** Space the row takes in the list: the buttons and the gap to the first card, minus {@link #getHeaderOverlap()}. */
  public static int getViewHeight () {
    return getMeasuredRowHeight() - getHeaderOverlap();
  }

  public void setActions (List<Action> newActions) {
    int count = Math.min(MAX_ACTIONS, newActions.size());
    boolean changed = count != actions.size();
    for (int i = 0; i < count && !changed; i++) {
      changed = !actions.get(i).sameAs(newActions.get(i));
    }
    if (!changed) {
      return;
    }
    actions.clear();
    actions.addAll(newActions.subList(0, count));
    while (getChildCount() > count) {
      removeViewAt(getChildCount() - 1);
    }
    for (int i = 0; i < count; i++) {
      ActionButton button;
      if (i < getChildCount()) {
        button = (ActionButton) getChildAt(i);
      } else {
        button = new ActionButton(getContext());
        button.setOnClickListener(v -> {
          Action action = ((ActionButton) v).action;
          if (callback != null && action != null) {
            callback.onProfileActionPressed(v, action);
          }
        });
        if (themeProvider != null) {
          themeProvider.addThemeInvalidateListener(button);
        }
        addView(button);
      }
      button.setAction(actions.get(i));
    }
    applyExpandFactor();
    requestLayout();
  }

  /** Expand factor of the header: 1 = expanded, 0 = small app bar. */
  public void setExpandFactor (float expandFactor) {
    if (this.expandFactor != expandFactor) {
      this.expandFactor = expandFactor;
      applyExpandFactor();
    }
  }

  private void applyExpandFactor () {
    // Gone before the header reaches the small app bar, as the expanded name and status.
    float alpha = MathUtils.clamp((expandFactor - .4f) / .6f);
    float scale = MathUtils.fromTo(.85f, 1f, alpha);
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      child.setAlpha(alpha);
      child.setScaleX(scale);
      child.setScaleY(scale);
      int visibility = alpha > 0f ? View.VISIBLE : View.INVISIBLE;
      if (child.getVisibility() != visibility) {
        child.setVisibility(visibility);
      }
    }
  }

  private int getButtonWidth (int width, int count) {
    if (count == 0) {
      return 0;
    }
    return Math.max(0, (width - Screen.dp(SIDE_MARGIN) * 2 - Screen.dp(BUTTON_GAP) * (count - 1)) / count);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int count = getChildCount();
    int buttonWidth = getButtonWidth(width, count);
    for (int i = 0; i < count; i++) {
      getChildAt(i).measure(MeasureSpec.makeMeasureSpec(buttonWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Screen.dp(BUTTON_HEIGHT), MeasureSpec.EXACTLY));
    }
    setMeasuredDimension(width, getMeasuredRowHeight());
  }

  @Override
  protected void onLayout (boolean changed, int l, int t, int r, int b) {
    int count = getChildCount();
    int buttonWidth = getButtonWidth(r - l, count);
    int buttonHeight = Screen.dp(BUTTON_HEIGHT);
    boolean rtl = Lang.rtl();
    for (int i = 0; i < count; i++) {
      View child = getChildAt(i);
      int index = rtl ? count - 1 - i : i;
      int left = Screen.dp(SIDE_MARGIN) + index * (buttonWidth + Screen.dp(BUTTON_GAP));
      child.layout(left, 0, left + buttonWidth, buttonHeight);
      // Shrinks towards the header.
      child.setPivotX(buttonWidth / 2f);
      child.setPivotY(0f);
    }
  }

  // Button

  private static TextPaint labelPaint;

  private static TextPaint labelPaint () {
    if (labelPaint == null) {
      // Label medium: 12/16, weight 500, tracking 0.5.
      labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      labelPaint.setTypeface(Fonts.getRobotoMedium());
      labelPaint.setTextSize(Screen.dp(12f));
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        labelPaint.setLetterSpacing(.5f / 12f);
      }
    }
    return labelPaint;
  }

  private static final class ActionButton extends View {
    private static final float ICON_TOP = 10f, ICON_SIZE = 24f, LABEL_TOP = ICON_TOP + ICON_SIZE + 4f, LABEL_HEIGHT = 16f, LABEL_PADDING = 4f;

    private @Nullable Action action;
    private @Nullable Drawable icon;
    private @Nullable String trimmedLabel;
    private int trimmedLabelWidth, trimmedForWidth = -1;
    private final M3StateLayerDrawable stateLayer;

    ActionButton (Context context) {
      super(context);
      float radius = Screen.dp(M3.CORNER_LARGE);
      M3.RoleShape container = new M3.RoleShape(M3.SECONDARY_CONTAINER);
      container.setRadii(radius, radius);
      M3.RoleShape mask = new M3.RoleShape(M3.SECONDARY_CONTAINER);
      mask.setRadii(radius, radius);
      stateLayer = new M3StateLayerDrawable(container, mask, pressedColor());
      ViewUtils.setBackground(this, stateLayer);
      Views.setClickable(this);
    }

    private static int pressedColor () {
      return ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSecondaryContainer());
    }

    void setAction (Action action) {
      boolean iconChanged = this.action == null || this.action.icon != action.icon;
      this.action = action;
      if (iconChanged) {
        icon = action.icon != 0 ? Drawables.get(getResources(), action.icon) : null;
      }
      trimmedForWidth = -1;
      setContentDescription(action.label);
      invalidate();
    }

    private void trimLabel (int availWidth) {
      if (trimmedForWidth == availWidth) {
        return;
      }
      trimmedForWidth = availWidth;
      String label = action != null ? action.label : "";
      TextPaint paint = labelPaint();
      trimmedLabel = availWidth > 0 ? TextUtils.ellipsize(label, paint, availWidth, TextUtils.TruncateAt.END).toString() : "";
      trimmedLabelWidth = (int) Math.ceil(paint.measureText(trimmedLabel));
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      // Theme may have changed since the last press.
      stateLayer.setColor(pressedColor());
      int width = getMeasuredWidth();
      int color = M3.onSecondaryContainer();
      if (icon != null) {
        Drawables.drawCentered(c, icon, width / 2f, Screen.dp(ICON_TOP + ICON_SIZE / 2f), Paints.getPorterDuffPaint(color));
      }
      trimLabel(width - Screen.dp(LABEL_PADDING) * 2);
      if (!StringUtils.isEmpty(trimmedLabel)) {
        TextPaint paint = labelPaint();
        paint.setColor(color);
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        float baseline = Screen.dp(LABEL_TOP) + (Screen.dp(LABEL_HEIGHT) - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        c.drawText(trimmedLabel, (width - trimmedLabelWidth) / 2f, baseline, paint);
      }
    }
  }
}
