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
import android.content.DialogInterface;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.widget.PopupLayout;

import me.vkryl.core.StringUtils;

/**
 * Alert of the app: {@link DialogLayout} (M3 basic dialog) in a {@link PopupLayout}, built with the API of the
 * platform {@code AlertDialog.Builder} that the alerts used.
 *
 * <p>Semantics follow the platform dialog: a button calls its {@link DialogInterface.OnClickListener} with
 * {@link #BUTTON_POSITIVE}, {@link #BUTTON_NEGATIVE} or {@link #BUTTON_NEUTRAL} and then dismisses the dialog (replace
 * the click listener of {@link #getButton} to keep it open); Back and a tap outside cancel it unless
 * {@link Builder#setCancelable} is {@code false}; the cancel listener runs before the dismiss listener.
 *
 * <p>The popup is added to the activity's root view: a text field in the dialog gets the keyboard, and the system
 * insets including the keyboard become the dialog's margins ({@link DialogLayout#onApplyMarginInsets}), so the dialog
 * moves above the keyboard.
 */
public final class AlertPopup implements DialogInterface {
  public static class Builder {
    private final Context context;
    private @Nullable ViewController<?> controller;
    private CharSequence title, message;
    private @Nullable View view;
    private CharSequence positiveText, negativeText, neutralText;
    private @Nullable OnClickListener positiveListener, negativeListener, neutralListener;
    private boolean cancelable = true;
    private @Nullable OnCancelListener onCancelListener;
    private @Nullable OnDismissListener onDismissListener;

    public Builder (Context context) {
      this.context = context;
    }

    /** Screen whose theme changes recolour the dialog (default: the current screen). */
    Builder setController (@Nullable ViewController<?> controller) {
      this.controller = controller;
      return this;
    }

    public Builder setTitle (CharSequence title) {
      this.title = title;
      return this;
    }

    public Builder setMessage (CharSequence message) {
      this.message = message;
      return this;
    }

    /** Content under the message that does not scroll (a text field, a wheel picker). */
    public Builder setView (View view) {
      this.view = view;
      return this;
    }

    public Builder setPositiveButton (CharSequence text, @Nullable OnClickListener listener) {
      this.positiveText = text;
      this.positiveListener = listener;
      return this;
    }

    public Builder setNegativeButton (CharSequence text, @Nullable OnClickListener listener) {
      this.negativeText = text;
      this.negativeListener = listener;
      return this;
    }

    public Builder setNeutralButton (CharSequence text, @Nullable OnClickListener listener) {
      this.neutralText = text;
      this.neutralListener = listener;
      return this;
    }

    public Builder setCancelable (boolean cancelable) {
      this.cancelable = cancelable;
      return this;
    }

    public Builder setOnCancelListener (@Nullable OnCancelListener listener) {
      this.onCancelListener = listener;
      return this;
    }

    public Builder setOnDismissListener (@Nullable OnDismissListener listener) {
      this.onDismissListener = listener;
      return this;
    }

    public @Nullable AlertPopup show () {
      return show(null);
    }

    /** @param forcedTheme theme whose colour roles the dialog uses (e.g. dark over the media viewer), or null. */
    public @Nullable AlertPopup show (@Nullable ThemeDelegate forcedTheme) {
      BaseActivity activity = UI.getContext(context);
      if (activity == null) {
        activity = UI.getUiContext();
      }
      return activity != null ? activity.showAlert(this, forcedTheme) : null;
    }

    /** Use {@link #show} or {@link BaseActivity#showAlert}. */
    public AlertPopup create (BaseActivity activity, @Nullable ThemeDelegate forcedTheme) {
      return new AlertPopup(activity, this, forcedTheme);
    }
  }

  private final BaseActivity context;
  private final PopupLayout popupLayout;
  private final @Nullable TextView messageView;
  private @Nullable TextView positiveButton, negativeButton, neutralButton;
  private boolean isClosing;

  private AlertPopup (BaseActivity context, Builder b, @Nullable ThemeDelegate forcedTheme) {
    this.context = context;
    DialogLayout dialog = new DialogLayout(context, b.controller != null ? b.controller : context.navigation().getCurrentStackItem(), forcedTheme);
    dialog.setHeadline(b.title);
    if (!StringUtils.isEmpty(b.message)) {
      messageView = dialog.addMessageView(b.message);
      if (b.message instanceof Spanned && ((Spanned) b.message).getSpans(0, b.message.length(), ClickableSpan.class).length > 0) {
        messageView.setMovementMethod(LinkMovementMethod.getInstance());
      }
    } else {
      messageView = null;
    }
    if (b.view != null) {
      dialog.addFixedView(b.view);
    }
    if (b.neutralText != null) {
      View.OnClickListener listener = newClickListener(BUTTON_NEUTRAL, b.neutralListener);
      // «Dialogs should contain a maximum of two actions»: a third one goes under the content.
      neutralButton = b.positiveText != null && b.negativeText != null ?
        dialog.addContentButton(View.NO_ID, b.neutralText, listener) :
        dialog.addButton(View.NO_ID, b.neutralText, listener);
    }
    // Dismissive action first, the confirming one closest to the trailing edge.
    if (b.negativeText != null) {
      negativeButton = dialog.addButton(View.NO_ID, b.negativeText, newClickListener(BUTTON_NEGATIVE, b.negativeListener));
    }
    if (b.positiveText != null) {
      positiveButton = dialog.addButton(View.NO_ID, b.positiveText, newClickListener(BUTTON_POSITIVE, b.positiveListener));
    }

    final boolean cancelable = b.cancelable;
    final OnCancelListener onCancelListener = b.onCancelListener;
    final OnDismissListener onDismissListener = b.onDismissListener;
    popupLayout = new PopupLayout(context);
    popupLayout.init(true);
    popupLayout.setNeedRootInsets();
    popupLayout.setOverlayStatusBar(true);
    popupLayout.setDismissOtherPopUps(false);
    // Modal: touches never reach the screen below, also while the scrim fades in and out.
    popupLayout.setTouchProvider((x, y) -> false);
    popupLayout.setDisableCancelOnTouchDown(!cancelable);
    popupLayout.setBackListener((fromTop, commit) -> !cancelable);
    popupLayout.setDismissListener(new PopupLayout.DismissListener() {
      @Override
      public void onPopupDismissPrepare (PopupLayout popup) {
        if (!isClosing) {
          // Back, a tap outside or cancel().
          isClosing = true;
          if (onCancelListener != null) {
            onCancelListener.onCancel(AlertPopup.this);
          }
        }
      }

      @Override
      public void onPopupDismiss (PopupLayout popup) {
        isClosing = true;
        if (onDismissListener != null) {
          onDismissListener.onDismiss(AlertPopup.this);
        }
      }
    });
    popupLayout.showFadingPopupView(dialog);
  }

  private View.OnClickListener newClickListener (int which, @Nullable OnClickListener listener) {
    return v -> {
      if (isClosing) {
        return;
      }
      if (listener != null) {
        listener.onClick(this, which);
      }
      dismiss();
    };
  }

  public BaseActivity getContext () {
    return context;
  }

  /** @return button of {@link #BUTTON_POSITIVE}, {@link #BUTTON_NEGATIVE} or {@link #BUTTON_NEUTRAL}, if it was set. */
  public @Nullable TextView getButton (int which) {
    switch (which) {
      case BUTTON_POSITIVE:
        return positiveButton;
      case BUTTON_NEGATIVE:
        return negativeButton;
      case BUTTON_NEUTRAL:
        return neutralButton;
    }
    return null;
  }

  public @Nullable TextView getMessageView () {
    return messageView;
  }

  public boolean isShowing () {
    return !isClosing && !popupLayout.isWindowHidden();
  }

  @Override
  public void cancel () {
    if (!popupLayout.isWindowHidden()) {
      popupLayout.hideWindow(true);
    }
  }

  @Override
  public void dismiss () {
    if (!popupLayout.isWindowHidden()) {
      isClosing = true;
      popupLayout.hideWindow(true);
    }
  }
}
