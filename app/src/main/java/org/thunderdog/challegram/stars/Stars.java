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

import android.text.SpannableStringBuilder;
import android.text.Spanned;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.core.Lang;

import java.text.NumberFormat;
import java.util.Currency;

/** Formatting of Telegram Star amounts. */
public final class Stars {
  private Stars () { }

  /** Currency code of invoices paid in Telegram Stars. */
  public static final String CURRENCY = "XTR";
  /** The star in string resources; {@link #withIcons} draws it as the gold star in TextViews. */
  public static final String STAR = "⭐";

  public static boolean isStarCurrency (@Nullable String currency) {
    return CURRENCY.equals(currency);
  }

  public static String format (long count) {
    return Lang.formatNumber(count);
  }

  /** 12, 1 234, 0.5: a fractional part only when there are nanostars. */
  public static String format (@Nullable TdApi.StarAmount amount) {
    if (amount == null) {
      return format(0);
    }
    if (amount.nanostarCount == 0) {
      return format(amount.starCount);
    }
    // As formatStarsAmount of the official app: whole Stars with grouping, then up to three digits of the fraction
    // without trailing zeros (23,418.1). Truncated, so that a balance is never shown higher than it is.
    TdApi.StarAmount abs = abs(amount);
    StringBuilder b = new StringBuilder();
    if (isNegative(amount)) {
      b.append('-');
    }
    b.append(format(abs.starCount));
    String fraction = String.format(java.util.Locale.US, "%03d", Math.abs(abs.nanostarCount) / 1_000_000);
    int end = fraction.length();
    while (end > 0 && fraction.charAt(end - 1) == '0') {
      end--;
    }
    if (end > 0) {
      b.append(Lang.getDecimalSeparator()).append(fraction, 0, end);
    }
    return b.toString();
  }

  private static final String[] SHORT_SUFFIXES = {"", "K", "M", "G", "T", "P"};

  /**
   * Short amount of the official app (formatStarsAmountShort): up to 1000 in full, then 1.2K, 23.4K, 1.5M (whole
   * tenths, never rounded up).
   */
  public static String formatShort (@Nullable TdApi.StarAmount amount) {
    if (amount == null) {
      return format(0);
    }
    TdApi.StarAmount abs = abs(amount);
    if (abs.starCount <= 1000) {
      return (isNegative(amount) ? "-" : "") + format(abs);
    }
    long count = abs.starCount;
    long divider = 1;
    int suffix = 0;
    while (count / divider >= 1000 && suffix < SHORT_SUFFIXES.length - 1) {
      divider *= 1000;
      suffix++;
    }
    long tenths = count * 10 / divider;
    String value = tenths % 10 == 0 ? Long.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
    return (isNegative(amount) ? "-" : "") + value + SHORT_SUFFIXES[suffix];
  }

  public static boolean isPositive (@Nullable TdApi.StarAmount amount) {
    return amount != null && (amount.starCount > 0 || (amount.starCount == 0 && amount.nanostarCount > 0));
  }

  public static boolean isNegative (@Nullable TdApi.StarAmount amount) {
    return amount != null && (amount.starCount < 0 || (amount.starCount == 0 && amount.nanostarCount < 0));
  }

  public static TdApi.StarAmount abs (TdApi.StarAmount amount) {
    return isNegative(amount) ? new TdApi.StarAmount(-amount.starCount, -amount.nanostarCount) : amount;
  }

  /**
   * «$27,418.30» («27 418,30 $» in Russian): the currency format of the app language with cents, as
   * BillingController.formatCurrency of the official app. Not CurrencyUtils: its buildAmount drops the leading zero of
   * cents ($1.05 → $1.5).
   */
  public static String usd (long cents) {
    NumberFormat format = NumberFormat.getCurrencyInstance(Lang.locale());
    format.setCurrency(Currency.getInstance("USD"));
    format.setMinimumFractionDigits(2);
    format.setMaximumFractionDigits(2);
    return format.format(cents / 100.0);
  }

  /** «⭐ 1 234» */
  public static String amount (long count) {
    return STAR + " " + format(count);
  }

  public static String amount (TdApi.StarAmount amount) {
    return STAR + " " + format(amount);
  }

  /** Signed amount of a transaction: «+⭐ 50» / «−⭐ 50». */
  public static String signedAmount (TdApi.StarAmount amount) {
    boolean negative = isNegative(amount);
    return (negative ? "−" : "+") + format(abs(amount)) + " " + STAR;
  }

  /** Replaces every {@link #STAR} (with or without the emoji variation selector) by the gold star span. */
  public static CharSequence withIcons (@Nullable CharSequence text) {
    return withIcons(text, 1.2f);
  }

  public static CharSequence withIcons (@Nullable CharSequence text, float scale) {
    if (text == null) {
      return null;
    }
    SpannableStringBuilder b = null;
    String str = text.toString();
    int index = str.indexOf(STAR);
    while (index != -1) {
      int end = index + STAR.length();
      if (end < str.length() && str.charAt(end) == '\uFE0F') {
        end++;
      }
      if (b == null) {
        b = text instanceof SpannableStringBuilder ? (SpannableStringBuilder) text : new SpannableStringBuilder(text);
      }
      b.setSpan(new StarSpan(scale), index, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      index = str.indexOf(STAR, end);
    }
    return b != null ? b : text;
  }
}
