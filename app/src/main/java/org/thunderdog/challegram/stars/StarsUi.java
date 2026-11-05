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
package org.thunderdog.challegram.stars;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.widget.ImageReceiverView;
import org.thunderdog.challegram.widget.VgSheets;

import me.vkryl.core.lambda.RunnableBool;
import tgx.td.Td;

/** Common parts of the Telegram Stars screens and sheets. */
public final class StarsUi {
  private StarsUi () { }

  public static final String TERMS_URL = "https://telegram.org/tos/stars";
  public static final String ABOUT_URL = "https://telegram.org/blog/telegram-stars";
  public static final String WITHDRAW_INFO_URL = "https://telegram.org/tos/stars";

  /**
   * Second confirmation of every operation with Stars (Vibegram X): after the main confirmation, which looks as in
   * the official app, a dialog names the amount and the recipient once more, and its button unlocks after
   * {@link VgSheets#CONFIRM_DELAY_SECONDS} seconds.
   */
  public static void confirmAgain (@NonNull ViewController<?> context, @NonNull CharSequence message, @NonNull CharSequence action, @NonNull RunnableBool onResult) {
    VgSheets.showLockedDialog(context, Lang.getString(R.string.vg_StarsConfirmAgainTitle), Stars.withIcons(VgSheets.markdown(message)), action, null, null, VgSheets.CONFIRM_DELAY_SECONDS, onResult);
  }

  /**
   * Checks that the current user owns enough Stars. If not, offers to buy them (the «N Stars Needed» sheet) and
   * returns false. When the balance isn't known yet, lets TDLib check it.
   */
  public static boolean checkBalance (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, long neededStars, @Nullable String purposeName, boolean forMessages) {
    TdApi.StarAmount balance = tdlib.ownedStarCount();
    if (balance == null || balance.starCount >= neededStars) {
      return true;
    }
    StarsBuySheet.showNeeded(context, tdlib, neededStars - balance.starCount, purposeName, forMessages, null);
    return false;
  }

  public static void openUrl (@NonNull ViewController<?> context, @NonNull String url) {
    context.tdlib().ui().openUrl(context, url, null);
  }

  /** Text with **bold** parts, where the bold part opens the url (official: links in captions). */
  public static CharSequence linkified (@NonNull ViewController<?> context, @NonNull CharSequence markdownText, @NonNull String url) {
    CharSequence text = VgSheets.markdown(markdownText);
    SpannableStringBuilder b = text instanceof SpannableStringBuilder ? (SpannableStringBuilder) text : new SpannableStringBuilder(text);
    StyleSpan[] spans = b.getSpans(0, b.length(), StyleSpan.class);
    for (StyleSpan span : spans) {
      int start = b.getSpanStart(span), end = b.getSpanEnd(span);
      b.removeSpan(span);
      b.setSpan(new ClickableSpan() {
        @Override
        public void onClick (@NonNull View widget) {
          openUrl(context, url);
        }

        @Override
        public void updateDrawState (@NonNull TextPaint ds) {
          ds.setColor(M3.primary());
          ds.setUnderlineText(false);
        }
      }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return b;
  }

  /** Big gold star at the top of the Stars sheets and screens. */
  public static ImageView newStarIcon (Context context, float sizeDp) {
    ImageView view = new ImageView(context);
    view.setScaleType(ImageView.ScaleType.FIT_CENTER);
    view.setImageDrawable(new StarDrawable(Screen.dp(sizeDp)));
    return view;
  }

  /** Photo of a product (invoice) with rounded corners; null when there's no photo. */
  public static @Nullable View newPhotoView (Context context, Tdlib tdlib, @Nullable TdApi.Photo photo, float sizeDp, float radiusDp) {
    TdApi.PhotoSize size = photo != null ? Td.findBiggest(photo.sizes) : null;
    if (size == null) {
      return null;
    }
    ImageReceiverView view = new ImageReceiverView(context) {
      @Override
      protected void onAttachedToWindow () {
        super.onAttachedToWindow();
        attach();
      }

      @Override
      protected void onDetachedFromWindow () {
        super.onDetachedFromWindow();
        detach();
      }
    };
    view.getReceiver().setRadius(Screen.dp(radiusDp));
    ImageFile imageFile = new ImageFile(tdlib, size.photo);
    imageFile.setSize(Screen.dp(sizeDp));
    imageFile.setScaleType(ImageFile.CENTER_CROP);
    view.getReceiver().requestFile(imageFile);
    return view;
  }

  /** «⭐ 1 234» for the balance of the current user, «…» while unknown. */
  public static CharSequence ownBalance (@NonNull Tdlib tdlib) {
    TdApi.StarAmount amount = tdlib.ownedStarCount();
    return Stars.withIcons(amount != null ? Stars.amount(amount) : Stars.STAR + " …");
  }

  public static void showUnavailable () {
    UI.showToast(R.string.vg_StarsPurchaseUnavailable, android.widget.Toast.LENGTH_SHORT);
  }
}
