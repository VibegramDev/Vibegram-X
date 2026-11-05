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

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.widget.InfoHeaderView;
import org.thunderdog.challegram.widget.M3Sheets;
import org.thunderdog.challegram.widget.PopupLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
      Collections.addAll(main, options);
    }

    final PopupLayout[] popup = new PopupLayout[1];
    final SettingsAdapter[] adapterRef = new SettingsAdapter[1];
    SettingsAdapter adapter = new SettingsAdapter(context, v -> {
      ListItem item = (ListItem) v.getTag();
      if (item == null) {
        return;
      }
      if (v.getId() == R.id.btn_vgStarsMoreOptions) {
        // Unfold the additional packages in place of the row.
        SettingsAdapter list = adapterRef[0];
        int at = list.indexOfViewById(R.id.btn_vgStarsMoreOptions);
        if (at == -1) {
          return;
        }
        int position = main.size();
        List<ListItem> rows = new ArrayList<>();
        for (TdApi.StarPaymentOption option : additional) {
          rows.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
          rows.add(newOptionItem(option, ++position));
        }
        list.removeItem(at);
        list.removeItem(at - 1);
        list.addItems(at - 1, rows.toArray(new ListItem[0]));
      } else if (item.getData() instanceof TdApi.StarPaymentOption) {
        if (popup[0] != null) {
          popup[0].hideWindow(true);
        }
        StarsCheckoutController.buyStars(context, tdlib, (TdApi.StarPaymentOption) item.getData(), onPurchased);
      }
    }, context) {
      @Override
      protected void setInfoHeader (ListItem item, InfoHeaderView view, boolean isUpdate) {
        view.setIcon(Drawables.get(R.drawable.vg_star_96), 72f);
        CharSequence title = missingStars > 0 ? Lang.plural(R.string.vg_StarsNeededTitle, missingStars) : Lang.getString(R.string.vg_TelegramStars);
        TdApi.FormattedText text;
        if (missingStars > 0 && !StringUtils.isEmpty(purposeName)) {
          text = StarsUi.markdownText(Lang.getString(forMessages ? R.string.vg_StarsNeededTextMessage : R.string.vg_StarsNeededText, purposeName));
        } else {
          text = StarsUi.linkText(Lang.getString(R.string.vg_TelegramStarsInfo), StarsUi.ABOUT_URL);
        }
        view.setTexts(tdlib, title, text);
        view.setCaption(Lang.getString(R.string.vg_StarsYourBalance, Stars.STAR + " " + (tdlib.ownedStarCount() != null ? Stars.format(tdlib.ownedStarCount()) : "…")));
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getData() instanceof TdApi.StarPaymentOption) {
          TdApi.StarPaymentOption option = (TdApi.StarPaymentOption) item.getData();
          view.setIconDrawable(StarStackDrawable.get(item.getIntValue()));
          view.setData(CurrencyUtils.buildAmount(option.currency, option.amount));
        }
      }
    };
    adapterRef[0] = adapter;
    adapter.setSegmented();

    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_INFO_HEADER));
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.vg_TelegramStarsChoose));
    int position = 0;
    for (TdApi.StarPaymentOption option : main) {
      if (position > 0) {
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      }
      items.add(newOptionItem(option, ++position));
    }
    if (!additional.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_vgStarsMoreOptions, R.drawable.baseline_keyboard_arrow_down_24, R.string.vg_StarsMoreOptions));
    }
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, StarsUi.linkified(context, Lang.getString(R.string.vg_StarsTOS), StarsUi.TERMS_URL), false));
    adapter.setItems(items, false);
    popup[0] = M3Sheets.showList(context, adapter);
  }

  private static ListItem newOptionItem (TdApi.StarPaymentOption option, int position) {
    return new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgStarsOption, 0, Lang.plural(R.string.vg_StarsCount, option.starCount), false)
      .setData(option)
      .setIntValue(position);
  }

  /** 1…6 overlapping stars: bigger packages show more of them, as in the official app. */
  static final class StarStackDrawable extends Drawable {
    private static final int MAX_COUNT = 6;
    // Stateless apart from the bounds, which every draw sets: shared by all rows (drawn on the main thread only).
    private static final StarStackDrawable[] cache = new StarStackDrawable[MAX_COUNT];
    private static Drawable star;

    /** The stack of 1…6 stars: shared, rows are bound without allocations. */
    static StarStackDrawable get (int count) {
      int index = Math.max(1, Math.min(count, MAX_COUNT)) - 1;
      if (cache[index] == null) {
        cache[index] = new StarStackDrawable(index + 1);
      }
      return cache[index];
    }

    private final int count;

    private StarStackDrawable (int count) {
      this.count = count;
      if (star == null) {
        star = Drawables.get(R.drawable.vg_star_24);
      }
    }

    private int size () {
      return Screen.dp(22f);
    }

    private int step () {
      return Screen.dp(5f);
    }

    /** Width of a list icon slot: the stack grows towards the text, which starts 72dp from the edge. */
    @Override
    public int getIntrinsicWidth () {
      return Screen.dp(24f);
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
      boolean rtl = Lang.rtl();
      for (int i = count - 1; i >= 0; i--) {
        int left = rtl ? bounds.right - size - step() * i : bounds.left + step() * i;
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
