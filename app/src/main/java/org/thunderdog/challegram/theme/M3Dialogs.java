/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.theme;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;

import me.vkryl.core.ColorUtils;

/**
 * M3 basic dialog on top of the platform {@link AlertDialog} (Material layout, see {@code M3DialogTheme}).
 *
 * <p>DialogTokens: container surfaceContainerHigh, CornerExtraLarge (28dp), Level3 elevation; headline
 * HeadlineSmall (24sp) onSurface; supporting text BodyMedium (14sp) onSurfaceVariant; actions LabelLarge (14sp)
 * primary text buttons. Spec: width 280..560dp, 24dp padding, 16dp title→body, 24dp body→actions, 8dp between
 * buttons, at most two actions — a third (neutral) action is moved into the content as an inline text button.
 */
public final class M3Dialogs {
  private M3Dialogs () { }

  public static final int MIN_WIDTH = 280, MAX_WIDTH = 560;
  /** Window margin on narrow screens (our choice: 312dp dialog on a 360dp phone). */
  public static final int WINDOW_MARGIN = 24;
  public static final int PADDING = 24;

  private static final class Colors {
    final @ColorInt int container, onSurface, onSurfaceVariant, primary;

    Colors (ThemeDelegate theme) {
      if (theme == null || theme == ThemeManager.instance().currentTheme(false)) {
        container = M3.surfaceContainerHigh();
        onSurface = M3.onSurface();
        onSurfaceVariant = M3.onSurfaceVariant();
        primary = M3.primary();
      } else {
        // Forced theme (e.g. dark dialogs over the camera): no role scheme, use its own colours.
        container = theme.getColor(ColorId.overlayFilling);
        onSurface = theme.getColor(ColorId.text);
        onSurfaceVariant = theme.getColor(ColorId.textLight);
        primary = theme.getColor(ColorId.textNeutral);
      }
    }
  }

  public static void apply (Context context, AlertDialog dialog, @Nullable ThemeDelegate theme) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || dialog == null) {
      return;
    }
    Window window = dialog.getWindow();
    if (window == null) {
      return;
    }
    Colors colors = new Colors(theme);

    // Container
    GradientDrawable background = new GradientDrawable();
    background.setColor(colors.container);
    background.setCornerRadius(Screen.dp(M3.CORNER_EXTRA_LARGE));
    window.setBackgroundDrawable(background);
    window.setElevation(Screen.dp(M3.ELEVATION_LEVEL3));
    updateWidth(context, window);
    if (context instanceof android.app.Activity) {
      // Fold/unfold or a window resize keeps the dialog: follow the new activity width while the dialog is shown.
      View activityRoot = ((android.app.Activity) context).getWindow().getDecorView();
      View.OnLayoutChangeListener layoutListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (right - left != oldRight - oldLeft) {
          v.post(() -> updateWidth(context, window));
        }
      };
      activityRoot.addOnLayoutChangeListener(layoutListener);
      window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow (View v) { }

        @Override
        public void onViewDetachedFromWindow (View v) {
          activityRoot.removeOnLayoutChangeListener(layoutListener);
          v.removeOnAttachStateChangeListener(this);
        }
      });
    }

    int padding = Screen.dp(PADDING);

    // Headline
    View titleTemplate = Views.tryFindAndroidView(context, dialog, "title_template");
    TextView title = findText(context, dialog, "alertTitle", android.R.id.title);
    boolean hasTitle = title != null && title.getVisibility() == View.VISIBLE && title.getText() != null && title.getText().length() > 0;
    if (title != null) {
      title.getPaint().setFakeBoldText(false);
      title.setTypeface(Fonts.getRobotoRegular());
      title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f);
      title.setTextColor(colors.onSurface);
      title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    }
    if (titleTemplate != null) {
      titleTemplate.setPadding(padding, padding, padding, 0);
      titleTemplate.setMinimumHeight(0);
    }

    // Supporting text
    View message = dialog.findViewById(android.R.id.message);
    if (message instanceof TextView) {
      TextView text = (TextView) message;
      text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
      text.setTextColor(colors.onSurfaceVariant);
      text.setLineSpacing(Screen.dp(20f) - text.getPaint().getFontMetricsInt(null), 1f);
      text.setPadding(padding, hasTitle ? Screen.dp(16f) : padding, padding, 0);
    }
    for (String spacer : new String[] {"textSpacerNoTitle", "textSpacerNoButtons", "titleDividerNoCustom"}) {
      View view = Views.tryFindAndroidView(context, dialog, spacer);
      if (view != null) {
        view.setVisibility(View.GONE);
      }
    }

    // Actions
    Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
    Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
    Button neutral = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
    int buttonCount = 0;
    for (Button button : new Button[] {positive, negative, neutral}) {
      if (button != null && button.getVisibility() == View.VISIBLE) {
        styleButton(button, colors);
        buttonCount++;
      }
    }
    View anyButton = positive != null ? positive : negative != null ? negative : neutral;
    if (anyButton != null && anyButton.getParent() instanceof ViewGroup) {
      ViewGroup bar = (ViewGroup) anyButton.getParent();
      bar.setPadding(padding, buttonCount > 0 ? padding : 0, padding, buttonCount > 0 ? padding : 0);
      if (bar.getParent() instanceof View) {
        ((View) bar.getParent()).setPadding(0, 0, 0, 0);
      }
      for (Button button : new Button[] {positive, negative}) {
        if (button != null && button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
          ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) button.getLayoutParams();
          params.height = Screen.dp(40f);
          params.topMargin = params.bottomMargin = 0;
          params.setMarginStart(Screen.dp(8f));
          params.setMarginEnd(0);
          button.setLayoutParams(params);
        }
      }
    }
    if (neutral != null && neutral.getVisibility() == View.VISIBLE &&
      ((positive != null && positive.getVisibility() == View.VISIBLE) || (negative != null && negative.getVisibility() == View.VISIBLE))) {
      moveNeutralInline(context, dialog, neutral, colors);
    }
  }

  private static void updateWidth (Context context, Window window) {
    int windowWidth = context.getResources().getDisplayMetrics().widthPixels;
    if (context instanceof android.app.Activity) {
      View activityRoot = ((android.app.Activity) context).getWindow().getDecorView();
      if (activityRoot.getWidth() > 0) {
        windowWidth = activityRoot.getWidth();
      }
    }
    int width = Math.max(Screen.dp(MIN_WIDTH), Math.min(Screen.dp(MAX_WIDTH), windowWidth - Screen.dp(WINDOW_MARGIN) * 2));
    width = Math.min(width, windowWidth);
    if (window.getAttributes().width != width) {
      window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
  }

  private static @Nullable TextView findText (Context context, AlertDialog dialog, String name, int fallbackId) {
    View view = Views.tryFindAndroidView(context, dialog, name);
    if (!(view instanceof TextView)) {
      view = dialog.findViewById(fallbackId);
    }
    return view instanceof TextView ? (TextView) view : null;
  }

  private static void styleButton (TextView button, Colors colors) {
    button.getPaint().setFakeBoldText(false);
    button.setAllCaps(false);
    button.setTypeface(Fonts.getRobotoMedium());
    button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
    button.setTextColor(colors.primary);
    button.setMinHeight(Screen.dp(40f));
    button.setMinimumHeight(Screen.dp(40f));
    button.setMinWidth(Screen.dp(48f));
    button.setMinimumWidth(Screen.dp(48f));
    button.setPadding(Screen.dp(12f), 0, Screen.dp(12f), 0);
    button.setGravity(Gravity.CENTER);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      button.setStateListAnimator(null);
      button.setElevation(0);
      GradientDrawable mask = new GradientDrawable();
      mask.setColor(0xffffffff);
      mask.setCornerRadius(Screen.dp(20f));
      button.setBackground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.alphaColor(M3.STATE_PRESSED, colors.primary)), null, mask));
    }
  }

  /** M3: dialogs have at most two actions. The third one becomes a text button under the content. */
  private static void moveNeutralInline (Context context, AlertDialog dialog, Button neutral, Colors colors) {
    View parentPanel = Views.tryFindAndroidView(context, dialog, "parentPanel");
    View buttonPanel = Views.tryFindAndroidView(context, dialog, "buttonPanel");
    if (!(parentPanel instanceof LinearLayout) || buttonPanel == null || buttonPanel.getParent() != parentPanel) {
      return;
    }
    LinearLayout panel = (LinearLayout) parentPanel;
    TextView inline = new TextView(context);
    inline.setText(neutral.getText());
    styleButton(inline, colors);
    inline.setOnClickListener(v -> neutral.performClick());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(40f));
    params.gravity = Gravity.START;
    // Label aligned with the 24dp content edge (button has 12dp inner padding).
    params.setMarginStart(Screen.dp(PADDING - 12));
    params.topMargin = Screen.dp(8f);
    panel.addView(inline, panel.indexOfChild(buttonPanel), params);
    neutral.setVisibility(View.GONE);
  }
}
