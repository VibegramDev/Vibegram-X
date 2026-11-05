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
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.widget.M3StateLayerDrawable;
import org.thunderdog.challegram.widget.VgSheets;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.core.ColorUtils;
import me.vkryl.core.CurrencyUtils;
import me.vkryl.core.StringUtils;

/**
 * «Buy Stars», as in the official app without Google Play billing: a sheet with the Star packages
 * (getStarPaymentOptions, «Choose package», «More Options» for the additional ones), and a package opens the
 * Premium Bot checkout ({@link StarsCheckoutController}) where it's paid by card.
 */
public final class StarsBuySheet {
  private StarsBuySheet () { }

  /** The «Buy Stars» sheet of the Stars screen. */
  public static void show (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @Nullable Runnable onPurchased) {
    load(context, tdlib, 0, null, false, onPurchased);
  }

  /** «N Stars Needed»: the balance is too low for a purchase or a paid message. */
  public static void showNeeded (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, long missingStars, @Nullable String purposeName, boolean forMessages, @Nullable Runnable onPurchased) {
    load(context, tdlib, missingStars, purposeName, forMessages, onPurchased);
  }

  private static void load (ViewController<?> context, Tdlib tdlib, long missingStars, @Nullable String purposeName, boolean forMessages, @Nullable Runnable onPurchased) {
    if (tdlib.options().starsPurchaseBlocked) {
      StarsUi.showUnavailable();
      return;
    }
    tdlib.send(new TdApi.GetStarPaymentOptions(), (result, error) -> UI.post(() -> {
      if (context.isDestroyed()) {
        return;
      }
      if (error != null) {
        UI.showError(error);
        return;
      }
      if (result.options.length == 0) {
        StarsUi.showUnavailable();
        return;
      }
      showSheet(context, tdlib, result.options, missingStars, purposeName, forMessages, onPurchased);
    }));
  }

  private static void showSheet (ViewController<?> context, Tdlib tdlib, TdApi.StarPaymentOption[] options, long missingStars, @Nullable String purposeName, boolean forMessages, @Nullable Runnable onPurchased) {
    VgSheets.showSheet(context, popupLayout -> {
      Context ctx = context.context();
      LinearLayout content = VgSheets.newContent(ctx);
      content.addView(StarsUi.newStarIcon(ctx, 72f), VgSheets.centered(Screen.dp(72f), 12f, 12f));

      CharSequence title = missingStars > 0 ?
        Stars.withIcons(Lang.plural(R.string.vg_StarsNeededTitle, missingStars)) :
        Lang.getString(R.string.vg_TelegramStars);
      content.addView(VgSheets.newTitle(ctx, title), VgSheets.wide(0, 8f));

      CharSequence text;
      if (missingStars > 0 && !StringUtils.isEmpty(purposeName)) {
        text = VgSheets.markdown(Lang.getString(forMessages ? R.string.vg_StarsNeededTextMessage : R.string.vg_StarsNeededText, purposeName));
      } else {
        text = StarsUi.linkified(context, Lang.getString(R.string.vg_TelegramStarsInfo), StarsUi.ABOUT_URL);
      }
      TextView textView = VgSheets.newBody(ctx, text, true);
      VgSheets.setLinksClickable(textView);
      content.addView(textView, VgSheets.wide(0, 4f));

      TextView balanceView = VgSheets.newCaption(ctx, Stars.withIcons(Lang.getString(R.string.vg_StarsYourBalance, Stars.STAR + " " + (tdlib.ownedStarCount() != null ? Stars.format(tdlib.ownedStarCount()) : "…"))), true);
      content.addView(balanceView, VgSheets.wide(0, 20f));

      TextView header = VgSheets.newCaption(ctx, Lang.getString(R.string.vg_TelegramStarsChoose), false);
      header.setPadding(Screen.dp(16f), 0, Screen.dp(16f), 0);
      content.addView(header, VgSheets.wide(0, 8f));

      // Packages: regular ones; the additional ones behind «More Options». When some Stars are missing, only the
      // packages that cover them.
      List<TdApi.StarPaymentOption> main = new ArrayList<>(), additional = new ArrayList<>();
      for (TdApi.StarPaymentOption option : options) {
        if (missingStars > 0 && option.starCount < missingStars) {
          continue;
        }
        (option.isAdditional && missingStars == 0 ? additional : main).add(option);
      }
      if (main.isEmpty() && additional.isEmpty()) {
        for (TdApi.StarPaymentOption option : options) {
          main.add(option);
        }
      }

      LinearLayout card = VgSheets.newCard(ctx);
      int index = 0;
      for (TdApi.StarPaymentOption option : main) {
        card.addView(newOptionRow(ctx, option, ++index, v -> {
          popupLayout.hideWindow(true);
          StarsCheckoutController.buyStars(context, tdlib, option, onPurchased);
        }));
      }
      if (!additional.isEmpty()) {
        LinearLayout more = newMoreRow(ctx);
        card.addView(more);
        final int startIndex = index;
        more.setOnClickListener(v -> {
          int at = card.indexOfChild(more);
          card.removeView(more);
          int i = startIndex;
          for (TdApi.StarPaymentOption option : additional) {
            card.addView(newOptionRow(ctx, option, ++i, v1 -> {
              popupLayout.hideWindow(true);
              StarsCheckoutController.buyStars(context, tdlib, option, onPurchased);
            }), at++);
          }
        });
      }
      content.addView(card, VgSheets.wide(0, 16f));

      TextView terms = VgSheets.newCaption(ctx, StarsUi.linkified(context, Lang.getString(R.string.vg_StarsTOS), StarsUi.TERMS_URL), true);
      VgSheets.setLinksClickable(terms);
      content.addView(terms, VgSheets.wide(0, 8f));
      return content;
    });
  }

  private static LinearLayout newRowLayout (Context context) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(Screen.dp(56f));
    row.setPadding(Screen.dp(16f), 0, Screen.dp(16f), 0);
    row.setBackground(new M3StateLayerDrawable(null, VgSheets.roundRect(0xffffffff, M3.CORNER_LARGE), ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface())));
    Views.setClickable(row);
    return row;
  }

  private static View newOptionRow (Context context, TdApi.StarPaymentOption option, int position, View.OnClickListener onClick) {
    LinearLayout row = newRowLayout(context);
    ImageView icon = new ImageView(context);
    icon.setScaleType(ImageView.ScaleType.FIT_START);
    int stackCount = Math.min(position, 6);
    StarStackDrawable stack = new StarStackDrawable(stackCount);
    icon.setImageDrawable(stack);
    row.addView(icon, new LinearLayout.LayoutParams(stack.getIntrinsicWidth(), Screen.dp(24f)));

    TextView title = VgSheets.newBody(context, Lang.plural(R.string.vg_StarsCount, option.starCount), false);
    title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
    title.setTypeface(org.thunderdog.challegram.tool.Fonts.getRobotoMedium());
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    titleParams.leftMargin = Screen.dp(12f);
    row.addView(title, titleParams);

    TextView price = VgSheets.newBody(context, CurrencyUtils.buildAmount(option.currency, option.amount), false);
    price.setTextColor(M3.onSurfaceVariant());
    row.addView(price, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    row.setOnClickListener(onClick);
    return row;
  }

  private static LinearLayout newMoreRow (Context context) {
    LinearLayout row = newRowLayout(context);
    TextView title = VgSheets.newBody(context, Lang.getString(R.string.vg_StarsMoreOptions), false);
    title.setTextColor(M3.primary());
    row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    ImageView arrow = new ImageView(context);
    arrow.setScaleType(ImageView.ScaleType.CENTER);
    arrow.setImageDrawable(Drawables.get(context.getResources(), R.drawable.baseline_keyboard_arrow_down_24));
    arrow.setColorFilter(M3.primary());
    row.addView(arrow, new LinearLayout.LayoutParams(Screen.dp(24f), Screen.dp(24f)));
    return row;
  }

  /** 1…6 overlapping stars: bigger packages show more of them, as in the official app. */
  static final class StarStackDrawable extends Drawable {
    private final int count;
    private final StarDrawable star = new StarDrawable();
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);

    StarStackDrawable (int count) {
      this.count = Math.max(1, count);
      outline.setStyle(Paint.Style.FILL);
    }

    private int size () {
      return Screen.dp(22f);
    }

    private int step () {
      return Screen.dp(5f);
    }

    @Override
    public int getIntrinsicWidth () {
      return size() + step() * (count - 1);
    }

    @Override
    public int getIntrinsicHeight () {
      return Screen.dp(24f);
    }

    @Override
    public void draw (@NonNull Canvas c) {
      Rect bounds = getBounds();
      int size = size();
      int top = bounds.top + (bounds.height() - size) / 2;
      // The back stars first, the front (leftmost) star last.
      for (int i = count - 1; i >= 0; i--) {
        int left = bounds.left + step() * i;
        star.setAlpha(i == 0 ? 255 : Math.max(110, 255 - i * 28));
        star.setBounds(left, top, left + size, top + size);
        star.draw(c);
      }
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
