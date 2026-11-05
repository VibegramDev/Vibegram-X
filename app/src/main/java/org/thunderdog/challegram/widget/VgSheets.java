/*
 * This file is a part of Vibegram X, a fork of Telegram X
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
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.TogglerView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.DialogLayout;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.text.Text;

import me.vkryl.core.lambda.RunnableBool;

/**
 * Building blocks of the custom M3 bottom sheets and dialogs of Vibegram X (login requests, Telegram Stars):
 * the sheet container is the one of {@link ViewController#showOptions} ({@link org.thunderdog.challegram.navigation.OptionsLayout}),
 * so these sheets look, open, scroll and close like every other sheet of the app.
 *
 * Operations with Telegram Stars are confirmed twice (user request): the main sheet or dialog looks as in the official
 * app, and its action asks once more in a dialog whose button unlocks after {@link #CONFIRM_DELAY_SECONDS} seconds
 * ({@link #showLockedDialog}, used by StarsUi.confirmAgain).
 */
public final class VgSheets {
  private VgSheets () { }

  public static final int CONFIRM_DELAY_SECONDS = 3;
  public static final float PADDING = 24f;

  // Sheets

  public interface ContentBuilder {
    View build (PopupLayout popupLayout);
  }

  /** Shows the content in an M3 bottom sheet. */
  public static @Nullable PopupLayout showSheet (@NonNull ViewController<?> context, @NonNull ContentBuilder builder) {
    return context.showPopup(null, false, Text.LINE_COUNT_UNLIMITED, (popupLayout, optionsLayout) -> {
      View content = builder.build(popupLayout);
      content.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      optionsLayout.addView(content);
      int width = M3Sheets.width(Screen.currentWidth());
      content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      return content.getMeasuredHeight();
    }, null);
  }

  public static LinearLayout newContent (Context context) {
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(Screen.dp(PADDING), Screen.dp(8f), Screen.dp(PADDING), Screen.dp(16f));
    return layout;
  }

  // Texts

  public static android.widget.TextView newTitle (Context context, CharSequence text) {
    android.widget.TextView view = new NoScrollTextView(context);
    view.setTypeface(Fonts.getRobotoMedium());
    view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f);
    view.setTextColor(M3.onSurface());
    view.setGravity(Gravity.CENTER);
    view.setText(text);
    return view;
  }

  public static android.widget.TextView newBody (Context context, CharSequence text, boolean centered) {
    android.widget.TextView view = new NoScrollTextView(context);
    view.setTypeface(Fonts.getRobotoRegular());
    view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
    view.setLineSpacing(Screen.dp(2f), 1f);
    view.setTextColor(M3.onSurface());
    view.setLinkTextColor(M3.primary());
    view.setGravity(centered ? Gravity.CENTER : Lang.gravity());
    view.setText(text);
    return view;
  }

  public static android.widget.TextView newCaption (Context context, CharSequence text, boolean centered) {
    android.widget.TextView view = newBody(context, text, centered);
    view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
    view.setTextColor(M3.onSurfaceVariant());
    return view;
  }

  /** Text with the **bold** parts of a string resource (spans for TextViews). */
  public static CharSequence markdown (CharSequence text) {
    String str = text.toString();
    android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
    int start = 0;
    boolean isBold = false;
    while (true) {
      int index = str.indexOf("**", start);
      if (index == -1) {
        b.append(text, start, str.length());
        break;
      }
      int spanStart = b.length();
      b.append(text, start, index);
      if (isBold) {
        b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), spanStart, b.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      isBold = !isBold;
      start = index + 2;
    }
    return b;
  }

  /** Makes {@link android.text.style.ClickableSpan}s of the text work. */
  public static void setLinksClickable (android.widget.TextView view) {
    view.setMovementMethod(LinkMovementMethod.getInstance());
    view.setHighlightColor(0);
  }

  public static LinearLayout.LayoutParams params (int width, int height, float topMargin, float bottomMargin) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
    params.topMargin = Screen.dp(topMargin);
    params.bottomMargin = Screen.dp(bottomMargin);
    return params;
  }

  public static LinearLayout.LayoutParams wide (float topMargin, float bottomMargin) {
    return params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, topMargin, bottomMargin);
  }

  public static LinearLayout.LayoutParams centered (int size, float topMargin, float bottomMargin) {
    LinearLayout.LayoutParams params = params(size, size, topMargin, bottomMargin);
    params.gravity = Gravity.CENTER_HORIZONTAL;
    return params;
  }

  // Cards

  public static GradientDrawable roundRect (@ColorInt int color, float radiusDp) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setShape(GradientDrawable.RECTANGLE);
    drawable.setCornerRadius(Screen.dp(radiusDp));
    drawable.setColor(color);
    return drawable;
  }

  /** Card of rows inside a sheet (16dp corners, a container a step above the sheet). */
  public static LinearLayout newCard (Context context) {
    LinearLayout card = new LinearLayout(context);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(roundRect(M3.surfaceContainerHigh(), M3.CORNER_LARGE));
    return card;
  }

  /** Row of a card: 24dp icon and one or two lines of text. */
  public static LinearLayout newCardRow (Context context, @DrawableRes int iconRes, CharSequence title, @Nullable CharSequence subtitle) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(Screen.dp(56f));
    row.setPadding(Screen.dp(16f), Screen.dp(10f), Screen.dp(16f), Screen.dp(10f));
    if (iconRes != 0) {
      ImageView icon = new ImageView(context);
      icon.setScaleType(ImageView.ScaleType.CENTER);
      icon.setImageDrawable(Drawables.get(context.getResources(), iconRes));
      icon.setColorFilter(M3.onSurfaceVariant());
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Screen.dp(24f), Screen.dp(24f));
      if (Lang.rtl()) {
        params.leftMargin = Screen.dp(16f);
      } else {
        params.rightMargin = Screen.dp(16f);
      }
      row.addView(icon, params);
    }
    LinearLayout texts = new LinearLayout(context);
    texts.setOrientation(LinearLayout.VERTICAL);
    android.widget.TextView titleView = newBody(context, title, false);
    titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
    texts.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    if (!TextUtils.isEmpty(subtitle)) {
      android.widget.TextView subtitleView = newCaption(context, subtitle, false);
      subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
      texts.addView(subtitleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    return row;
  }

  /** Card with a switch («Allow messages»); the whole card toggles it. */
  public static FrameLayout newSwitchCard (Context context, CharSequence title, boolean isEnabled, RunnableBool onChanged) {
    FrameLayout card = new FrameLayout(context);
    GradientDrawable mask = roundRect(0xffffffff, M3.CORNER_LARGE);
    card.setBackground(new M3StateLayerDrawable(roundRect(M3.surfaceContainerHigh(), M3.CORNER_LARGE), mask, me.vkryl.core.ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface())));
    card.setMinimumHeight(Screen.dp(56f));
    android.widget.TextView titleView = newBody(context, title, false);
    titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
    FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
    textParams.leftMargin = Lang.rtl() ? Screen.dp(72f) : Screen.dp(16f);
    textParams.rightMargin = Lang.rtl() ? Screen.dp(16f) : Screen.dp(72f);
    card.addView(titleView, textParams);
    TogglerView toggler = new TogglerView(context);
    toggler.init(isEnabled);
    card.addView(toggler, new FrameLayout.LayoutParams(Screen.dp(72f), Screen.dp(48f), (Lang.rtl() ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));
    card.setOnClickListener(v -> onChanged.runWithBool(toggler.toggle(true)));
    Views.setClickable(card);
    return card;
  }

  // Buttons

  private static android.widget.TextView newButton (Context context, CharSequence text, @ColorInt int textColor, @Nullable Drawable content, @ColorInt int stateColor, View.OnClickListener onClickListener) {
    android.widget.TextView button = new NoScrollTextView(context);
    button.setTypeface(Fonts.getRobotoMedium());
    button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
    button.setTextColor(textColor);
    button.setGravity(Gravity.CENTER);
    button.setSingleLine(true);
    button.setEllipsize(TextUtils.TruncateAt.END);
    button.setPadding(Screen.dp(16f), 0, Screen.dp(16f), 0);
    button.setText(text);
    button.setOnClickListener(onClickListener);
    button.setBackground(new M3StateLayerDrawable(content, roundRect(0xffffffff, 24f), me.vkryl.core.ColorUtils.alphaColor(M3.STATE_PRESSED, stateColor)));
    Views.setClickable(button);
    return button;
  }

  /** M3 filled button (primary container, 48dp, full corners). */
  public static android.widget.TextView newFilledButton (Context context, CharSequence text, View.OnClickListener onClickListener) {
    return newButton(context, text, M3.onPrimary(), roundRect(M3.primary(), 24f), M3.onPrimary(), onClickListener);
  }

  /** M3 filled tonal button. */
  public static android.widget.TextView newTonalButton (Context context, CharSequence text, View.OnClickListener onClickListener) {
    return newButton(context, text, M3.onSecondaryContainer(), roundRect(M3.secondaryContainer(), 24f), M3.onSecondaryContainer(), onClickListener);
  }

  /** Filled button in the error colour (Decline). */
  public static android.widget.TextView newDestructiveButton (Context context, CharSequence text, View.OnClickListener onClickListener) {
    return newButton(context, text, M3.error(), roundRect(me.vkryl.core.ColorUtils.alphaColor(.12f, M3.error()), 24f), M3.error(), onClickListener);
  }

  public static LinearLayout.LayoutParams buttonParams (float topMargin) {
    return params(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(48f), topMargin, 0);
  }

  /** Two buttons side by side, 8dp apart. */
  public static LinearLayout newButtonRow (Context context, View start, View end) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, Screen.dp(48f), 1f);
    LinearLayout.LayoutParams endParams = new LinearLayout.LayoutParams(0, Screen.dp(48f), 1f);
    if (Lang.rtl()) {
      startParams.leftMargin = Screen.dp(4f);
      endParams.rightMargin = Screen.dp(4f);
    } else {
      startParams.rightMargin = Screen.dp(4f);
      endParams.leftMargin = Screen.dp(4f);
    }
    row.addView(start, startParams);
    row.addView(end, endParams);
    return row;
  }

  public static void setButtonEnabled (View button, boolean isEnabled) {
    button.setEnabled(isEnabled);
    button.setAlpha(isEnabled ? 1f : .5f);
  }

  /**
   * Keeps the button disabled for {@link #CONFIRM_DELAY_SECONDS} seconds with a countdown in its label, so that a
   * stray tap right after the sheet opens can't confirm an operation with Stars.
   */
  public static void lockButton (android.widget.TextView button, CharSequence label) {
    lockButton(button, label, CONFIRM_DELAY_SECONDS);
  }

  public static void lockButton (android.widget.TextView button, CharSequence label, int seconds) {
    if (seconds <= 0) {
      button.setText(label);
      setButtonEnabled(button, true);
      return;
    }
    final int[] secondsLeft = {seconds};
    setButtonEnabled(button, false);
    Runnable tick = new Runnable() {
      @Override
      public void run () {
        if (secondsLeft[0] <= 0) {
          button.setText(label);
          setButtonEnabled(button, true);
          return;
        }
        button.setText(TextUtils.concat(label, " · ", Integer.toString(secondsLeft[0])));
        secondsLeft[0]--;
        button.postDelayed(this, 1000L);
      }
    };
    tick.run();
  }

  // Dialogs

  public static PopupLayout showDialog (ViewController<?> context, DialogLayout dialog) {
    PopupLayout popupLayout = new PopupLayout(context.context());
    popupLayout.setTag(context);
    popupLayout.init(true);
    popupLayout.setNeedRootInsets();
    popupLayout.setNeedFullScreen(true);
    // A confirmation over a sheet (Pay → «Are you sure?») keeps the sheet below it.
    popupLayout.setDismissOtherPopUps(false);
    popupLayout.addThemeListeners(context);
    popupLayout.showFadingPopupView(dialog);
    return popupLayout;
  }

  /**
   * Dialog with a confirming action that unlocks after {@link #CONFIRM_DELAY_SECONDS} seconds.
   *
   * @param onResult true when confirmed; false when cancelled or dismissed.
   */
  public static PopupLayout showLockedDialog (ViewController<?> context, CharSequence headline, CharSequence message, CharSequence action, @Nullable CharSequence cancel, @Nullable View content, RunnableBool onResult) {
    return showLockedDialog(context, headline, message, action, cancel, content, CONFIRM_DELAY_SECONDS, onResult);
  }

  public static PopupLayout showLockedDialog (ViewController<?> context, CharSequence headline, CharSequence message, CharSequence action, @Nullable CharSequence cancel, @Nullable View content, int lockSeconds, RunnableBool onResult) {
    return showLockedDialog(context, headline, message, action, cancel, content, lockSeconds, false, onResult);
  }

  /** @param isDestructive the action deletes something: its button is red, as in the confirmations of Telegram X. */
  public static PopupLayout showLockedDialog (ViewController<?> context, CharSequence headline, CharSequence message, CharSequence action, @Nullable CharSequence cancel, @Nullable View content, int lockSeconds, boolean isDestructive, RunnableBool onResult) {
    DialogLayout dialog = new DialogLayout(context.context(), context, null);
    dialog.setHeadline(headline);
    if (!TextUtils.isEmpty(message)) {
      android.widget.TextView messageView = dialog.addMessageView(message);
      setLinksClickable(messageView);
    }
    if (content != null) {
      dialog.addContentView(content);
    }
    final boolean[] done = new boolean[1];
    final PopupLayout[] popupLayout = new PopupLayout[1];
    dialog.addButton(R.id.btn_cancel, cancel != null ? cancel : Lang.getString(R.string.Cancel), v -> {
      if (!done[0]) {
        done[0] = true;
        onResult.runWithBool(false);
      }
      popupLayout[0].hideWindow(true);
    });
    android.widget.TextView actionButton = dialog.addButton(R.id.btn_done, action, v -> {
      if (!done[0]) {
        done[0] = true;
        popupLayout[0].hideWindow(true);
        onResult.runWithBool(true);
      }
    });
    if (isDestructive) {
      dialog.setButtonDestructive(actionButton);
    }
    lockButton(actionButton, action, lockSeconds);
    popupLayout[0] = showDialog(context, dialog);
    popupLayout[0].setDismissListener(new PopupLayout.DismissListener() {
      @Override
      public void onPopupDismissPrepare (PopupLayout popup) {
        if (!done[0]) {
          done[0] = true;
          onResult.runWithBool(false);
        }
      }

      @Override
      public void onPopupDismiss (PopupLayout popup) { }
    });
    return popupLayout[0];
  }
}
