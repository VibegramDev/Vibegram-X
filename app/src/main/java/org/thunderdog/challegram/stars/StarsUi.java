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

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.emoji.Emoji;
import org.thunderdog.challegram.tool.Strings;

import me.vkryl.core.lambda.RunnableBool;

/** Common parts of the Telegram Stars screens and sheets. */
public final class StarsUi {
  private StarsUi () { }

  public static final String TERMS_URL = "https://telegram.org/tos/stars";
  public static final String ABOUT_URL = "https://telegram.org/blog/telegram-stars";
  public static final String WITHDRAW_INFO_URL = "https://telegram.org/tos/stars";

  private static Bitmap goldStarIcon;

  /**
   * Icon of the «Telegram Stars» settings row: the star glyph of the official app filled with its gold gradient
   * (PremiumGradient.goldenStarMenuDrawable: starsGradient1 → starsGradient2 with the same matrix). Not tinted.
   */
  public static Bitmap goldStarIcon (@NonNull Resources res) {
    if (goldStarIcon == null) {
      Drawable glyph = Drawables.get(res, R.drawable.vg_stars_24);
      int width = glyph.getMinimumWidth(), height = glyph.getMinimumHeight();
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      Canvas c = new Canvas(bitmap);
      glyph.setBounds(0, 0, width, height);
      glyph.draw(c);
      LinearGradient shader = new LinearGradient(0, 100, 150, 0, 0xFFFEC846, 0xFFEC920A, Shader.TileMode.CLAMP);
      Matrix matrix = new Matrix();
      matrix.postScale(width / 100f, height * 2 / 100f, 75, 50);
      matrix.postTranslate(-width, -height * 2);
      shader.setLocalMatrix(matrix);
      Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      paint.setShader(shader);
      paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
      c.drawRect(0, 0, width, height, paint);
      goldStarIcon = bitmap;
    }
    return goldStarIcon;
  }

  /** Seconds the confirming button of an operation with Stars stays locked. */
  public static final int CONFIRM_DELAY_SECONDS = 3;

  /**
   * Second confirmation of every operation with Stars (Vibegram X): after the main confirmation, which looks as in
   * the official app, a dialog names the amount and the recipient once more, and its button unlocks after
   * {@link #CONFIRM_DELAY_SECONDS} seconds.
   */
  public static void confirmAgain (@NonNull ViewController<?> context, @NonNull CharSequence message, @NonNull CharSequence action, @NonNull RunnableBool onResult) {
    confirm(context, Lang.getString(R.string.vg_StarsConfirmAgainTitle), message, action, CONFIRM_DELAY_SECONDS, false, onResult);
  }

  /**
   * Telegram X dialog ({@link AlertPopup}) for operations with Stars: **bold** markdown and Stars in the message,
   * the action button optionally locked for lockSeconds and red when it deletes something; onResult runs once.
   */
  public static void confirm (@NonNull ViewController<?> context, @NonNull CharSequence title, @NonNull CharSequence message, @NonNull CharSequence action, int lockSeconds, boolean isDestructive, @NonNull RunnableBool onResult) {
    final boolean[] done = new boolean[1];
    RunnableBool result = isConfirmed -> {
      if (!done[0]) {
        done[0] = true;
        onResult.runWithBool(isConfirmed);
      }
    };
    AlertPopup.Builder b = new AlertPopup.Builder(context.context())
      .setController(context)
      .setTitle(title)
      .setMessage(markdownSpans(context, message))
      .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> result.runWithBool(false))
      .setPositiveButton(action, (dialog, which) -> result.runWithBool(true))
      .setOnCancelListener(dialog -> result.runWithBool(false))
      .setOnDismissListener(dialog -> result.runWithBool(false));
    if (lockSeconds > 0) {
      b.setPositiveButtonDelay(lockSeconds);
    }
    if (isDestructive) {
      b.setPositiveButtonDestructive();
    }
    b.show();
  }

  /** **bold** parts of a string as entities (Telegram X markdown), for the {@link org.thunderdog.challegram.util.text.Text} engine. */
  public static TdApi.FormattedText markdownText (@NonNull CharSequence text) {
    TdApi.FormattedText formattedText = TD.toFormattedText(text, false);
    TD.parseMarkdownWithEntities(formattedText);
    return formattedText;
  }

  /** Text whose **bold** part opens the url (official: links in captions). */
  public static TdApi.FormattedText linkText (@NonNull CharSequence markdownText, @NonNull String url) {
    TdApi.FormattedText formattedText = markdownText(markdownText);
    if (formattedText.entities != null) {
      for (TdApi.TextEntity entity : formattedText.entities) {
        if (entity.type.getConstructor() == TdApi.TextEntityTypeBold.CONSTRUCTOR) {
          entity.type = new TdApi.TextEntityTypeTextUrl(url);
        }
      }
    }
    return formattedText;
  }

  /** **bold** parts and emoji of Telegram X for a TextView (dialog messages). */
  public static CharSequence markdownSpans (@NonNull ViewController<?> context, @NonNull CharSequence text) {
    return Emoji.instance().replaceEmoji(Strings.buildMarkdown(context, text));
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
    // **part** → link; the asterisks are removed.
    String str = markdownText.toString();
    SpannableStringBuilder b = new SpannableStringBuilder();
    int from = 0;
    while (true) {
      int open = str.indexOf("**", from);
      int close = open != -1 ? str.indexOf("**", open + 2) : -1;
      if (close == -1) {
        b.append(str, from, str.length());
        break;
      }
      b.append(str, from, open);
      int start = b.length();
      b.append(str, open + 2, close);
      int end = b.length();
      from = close + 2;
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

  /** «⭐ 1 234» for the balance of the current user, «…» while unknown. */
  public static CharSequence ownBalance (@NonNull Tdlib tdlib) {
    TdApi.StarAmount amount = tdlib.ownedStarCount();
    return amount != null ? Stars.amount(amount) : Stars.STAR + " …";
  }

  public static void showUnavailable () {
    UI.showToast(R.string.vg_StarsPurchaseUnavailable, android.widget.Toast.LENGTH_SHORT);
  }
}
