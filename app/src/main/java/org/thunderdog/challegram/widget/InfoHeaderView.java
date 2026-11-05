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
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.util.text.TextStyleProvider;

import me.vkryl.core.lambda.Destroyable;
import tgx.td.Td;

/**
 * Centered header of an info sheet or screen, drawn by the TGX text engine like {@link GiftHeaderView}: an icon
 * (drawable, avatar of a sender, photo or a symbol in a tonal circle), a title, an optional amount and caption and a
 * text with links. Colours are read from the theme when drawing, so the header follows theme changes once it is
 * invalidated (register it with {@code addThemeInvalidateListener}).
 *
 * Type scale (M3): title — title large emphasized (22sp medium), amount — headline medium emphasized (28sp medium),
 * caption and text — body medium (14sp) onSurfaceVariant.
 */
public class InfoHeaderView extends View implements Destroyable {
  private static final float PADDING_HORIZONTAL = 24f;

  private final AvatarReceiver avatarReceiver;
  private final ImageReceiver photoReceiver;
  private @Nullable Tdlib tdlib;

  private static final int ICON_NONE = 0, ICON_DRAWABLE = 1, ICON_SYMBOL = 2, ICON_AVATAR = 3, ICON_PHOTO = 4;
  private int iconType = ICON_NONE;
  private int iconSize;
  private @Nullable Drawable iconDrawable;

  private @Nullable CharSequence title, amount, caption;
  private @Nullable TdApi.FormattedText text;
  private @Nullable TextColorSet amountColorSet;
  private float amountSize = 28f;

  private @Nullable Text titleText, amountText, captionText, bodyText;
  private int builtWidth;

  public InfoHeaderView (Context context) {
    super(context);
    avatarReceiver = new AvatarReceiver(this);
    photoReceiver = new ImageReceiver(this, 0);
  }

  // Icon

  /** Drawable drawn as is (the gold star of Telegram Stars). */
  public void setIcon (@NonNull Drawable drawable, float sizeDp) {
    clearIcon();
    iconType = ICON_DRAWABLE;
    iconDrawable = drawable;
    iconSize = Screen.dp(sizeDp);
    requestLayout();
  }

  /** Symbol in a secondaryContainer circle, as the icon placeholders of M3 lists. */
  public void setIconSymbol (@DrawableRes int iconRes, float sizeDp) {
    clearIcon();
    iconType = ICON_SYMBOL;
    iconDrawable = Drawables.get(getResources(), iconRes);
    iconSize = Screen.dp(sizeDp);
    requestLayout();
  }

  public void setAvatar (@NonNull Tdlib tdlib, @NonNull TdApi.MessageSender sender, float sizeDp) {
    clearIcon();
    iconType = ICON_AVATAR;
    iconSize = Screen.dp(sizeDp);
    avatarReceiver.requestMessageSender(tdlib, sender, AvatarReceiver.Options.NONE);
    requestLayout();
  }

  /** Photo of a product with rounded corners; false when there is no photo. */
  public boolean setPhoto (@NonNull Tdlib tdlib, @Nullable TdApi.Photo photo, float sizeDp, float radiusDp) {
    TdApi.PhotoSize size = photo != null ? Td.findBiggest(photo.sizes) : null;
    if (size == null) {
      return false;
    }
    clearIcon();
    iconType = ICON_PHOTO;
    iconSize = Screen.dp(sizeDp);
    photoReceiver.setRadius(Screen.dp(radiusDp));
    ImageFile imageFile = new ImageFile(tdlib, size.photo);
    imageFile.setSize(iconSize);
    imageFile.setScaleType(ImageFile.CENTER_CROP);
    photoReceiver.requestFile(imageFile);
    requestLayout();
    return true;
  }

  private void clearIcon () {
    if (iconType == ICON_AVATAR) {
      avatarReceiver.clear();
    } else if (iconType == ICON_PHOTO) {
      photoReceiver.clear();
    }
    iconType = ICON_NONE;
    iconDrawable = null;
  }

  // Texts

  public void setTexts (@NonNull Tdlib tdlib, @Nullable CharSequence title, @Nullable TdApi.FormattedText text) {
    this.tdlib = tdlib;
    this.title = title;
    this.text = text;
    invalidateTexts();
  }

  /** Amount under the title («+⭐ 50»); colours null — onSurface. */
  public void setAmount (@Nullable CharSequence amount, @Nullable TextColorSet colorSet, float sizeSp) {
    this.amount = amount;
    this.amountColorSet = colorSet;
    this.amountSize = sizeSp;
    invalidateTexts();
  }

  public void setCaption (@Nullable CharSequence caption) {
    this.caption = caption;
    invalidateTexts();
  }

  private void invalidateTexts () {
    builtWidth = 0;
    requestLayout();
    invalidate();
  }

  private static TextStyleProvider titleStyle, bodyStyle;
  private static final TextStyleProvider[] amountStyles = new TextStyleProvider[4];

  private static TextStyleProvider titleStyle () {
    if (titleStyle == null) {
      titleStyle = Paints.robotoStyleProvider(22f);
    }
    return titleStyle;
  }

  private static TextStyleProvider bodyStyle () {
    if (bodyStyle == null) {
      bodyStyle = Paints.robotoStyleProvider(14f);
    }
    return bodyStyle;
  }

  private static TextStyleProvider amountStyle (float size) {
    int index = size >= 28f ? 0 : size >= 24f ? 1 : size >= 22f ? 2 : 3;
    if (amountStyles[index] == null) {
      amountStyles[index] = Paints.robotoStyleProvider(index == 0 ? 28f : index == 1 ? 24f : index == 2 ? 22f : 16f);
    }
    return amountStyles[index];
  }

  private final TextColorSet amountColors = () -> amountColorSet != null ? amountColorSet.defaultTextColor() : M3.onSurface();

  private void buildTexts (int width) {
    if (builtWidth == width) {
      return;
    }
    builtWidth = width;
    int textWidth = Math.max(0, width - Screen.dp(PADDING_HORIZONTAL) * 2);
    titleText = title != null && title.length() > 0 ?
      new Text.Builder(tdlib, title, null, textWidth, titleStyle(), TextColorSets.Regular.NORMAL, null).allBold().textFlags(Text.FLAG_ALIGN_CENTER).build() : null;
    amountText = amount != null && amount.length() > 0 ?
      new Text.Builder(tdlib, amount, null, textWidth, amountStyle(amountSize), amountColors, null).allBold().singleLine().textFlags(Text.FLAG_ALIGN_CENTER).build() : null;
    captionText = caption != null && caption.length() > 0 ?
      new Text.Builder(tdlib, caption, null, textWidth, bodyStyle(), TextColorSets.Regular.LIGHT, null).textFlags(Text.FLAG_ALIGN_CENTER).build() : null;
    bodyText = text != null && !Td.isEmpty(text) && tdlib != null ?
      new Text.Builder(tdlib, text, new TdlibUi.UrlOpenParameters().disableInstantView(), textWidth, bodyStyle(), TextColorSets.Regular.LIGHT, null).textFlags(Text.FLAG_ALIGN_CENTER).build() : null;
  }

  // Layout: 8dp, icon, 16dp, title, 4dp, amount, 2dp, caption, 8dp, text, 20dp

  private int iconTop () {
    return Screen.dp(8f);
  }

  private int contentHeight () {
    int height = iconTop();
    if (iconType != ICON_NONE) {
      height += iconSize + Screen.dp(16f);
    }
    if (titleText != null) {
      height += titleText.getHeight();
    }
    if (amountText != null) {
      height += Screen.dp(4f) + amountText.getHeight();
    }
    if (captionText != null) {
      height += Screen.dp(2f) + captionText.getHeight();
    }
    if (bodyText != null) {
      height += Screen.dp(8f) + bodyText.getHeight();
    }
    return height + Screen.dp(20f);
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    buildTexts(width);
    setMeasuredDimension(width, contentHeight());
  }

  @Override
  protected void onDraw (@NonNull Canvas c) {
    int width = getMeasuredWidth();
    buildTexts(width);
    int y = iconTop();
    if (iconType != ICON_NONE) {
      int left = (width - iconSize) / 2;
      switch (iconType) {
        case ICON_DRAWABLE: {
          if (iconDrawable != null) {
            iconDrawable.setBounds(left, y, left + iconSize, y + iconSize);
            iconDrawable.draw(c);
          }
          break;
        }
        case ICON_SYMBOL: {
          float radius = iconSize / 2f;
          c.drawCircle(left + radius, y + radius, radius, Paints.fillingPaint(M3.secondaryContainer()));
          if (iconDrawable != null) {
            Drawables.drawCentered(c, iconDrawable, left + radius, y + radius, Paints.getPorterDuffPaint(M3.onSecondaryContainer()));
          }
          break;
        }
        case ICON_AVATAR: {
          avatarReceiver.setBounds(left, y, left + iconSize, y + iconSize);
          if (avatarReceiver.needPlaceholder()) {
            avatarReceiver.drawPlaceholder(c);
          }
          avatarReceiver.draw(c);
          break;
        }
        case ICON_PHOTO: {
          photoReceiver.setBounds(left, y, left + iconSize, y + iconSize);
          if (photoReceiver.needPlaceholder()) {
            photoReceiver.drawPlaceholder(c);
          }
          photoReceiver.draw(c);
          break;
        }
      }
      y += iconSize + Screen.dp(16f);
    }
    int x = Screen.dp(PADDING_HORIZONTAL);
    if (titleText != null) {
      titleText.draw(c, x, y);
      y += titleText.getHeight();
    }
    if (amountText != null) {
      y += Screen.dp(4f);
      amountText.draw(c, x, y);
      y += amountText.getHeight();
    }
    if (captionText != null) {
      y += Screen.dp(2f);
      captionText.draw(c, x, y);
      y += captionText.getHeight();
    }
    if (bodyText != null) {
      y += Screen.dp(8f);
      bodyText.draw(c, x, y);
    }
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    return (bodyText != null && bodyText.onTouchEvent(this, e)) || super.onTouchEvent(e);
  }

  // Receivers

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    avatarReceiver.attach();
    photoReceiver.attach();
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    avatarReceiver.detach();
    photoReceiver.detach();
  }

  @Override
  public void performDestroy () {
    avatarReceiver.destroy();
    photoReceiver.destroy();
  }
}
