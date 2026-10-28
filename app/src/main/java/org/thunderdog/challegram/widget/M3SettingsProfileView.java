/*
 * This file is a part of Vibegram X
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
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.telegram.TGLegacyManager;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.EmojiStatusHelper;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.Destroyable;

/**
 * Profile block at the top of the main Settings page: round 96dp avatar, a 40dp filled icon button (primary /
 * onPrimary, 3dp ring of the page color) on its bottom-end edge to change the photo, the name (headline small,
 * emphasized) with the emoji status and a "phone • @username" line (body medium, onSurfaceVariant), centered.
 * The block sits on the page color (no container); both the avatar and the button open the existing profile photo menu.
 */
public class M3SettingsProfileView extends FrameLayoutFix implements AttachDelegate, Destroyable, TGLegacyManager.EmojiLoadListener {
  public static final float AVATAR_SIZE = 96f;
  private static final float PADDING_TOP = 8f, NAME_TOP = PADDING_TOP + AVATAR_SIZE + 16f, NAME_HEIGHT = 32f,
    SUBTITLE_HEIGHT = 20f, PADDING_BOTTOM = 16f, BUTTON_SIZE = 40f, BUTTON_RING = 3f, TEXT_PADDING = 24f;

  public static int getDefaultHeight () {
    return Screen.dp(NAME_TOP + NAME_HEIGHT + SUBTITLE_HEIGHT + PADDING_BOTTOM);
  }

  private final Tdlib tdlib;
  private final AvatarView avatarView;
  private final CameraButton cameraButton;
  private final EmojiStatusHelper emojiStatusHelper;

  private @Nullable String name, subtitle;
  private @Nullable Text trimmedName, trimmedSubtitle;
  private int lastWidth;

  public M3SettingsProfileView (Context context, Tdlib tdlib) {
    super(context);
    this.tdlib = tdlib;
    setWillNotDraw(false);
    setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, getDefaultHeight()));

    avatarView = new AvatarView(context);
    FrameLayoutFix.LayoutParams params = FrameLayoutFix.newParams(Screen.dp(AVATAR_SIZE), Screen.dp(AVATAR_SIZE), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    params.topMargin = Screen.dp(PADDING_TOP);
    avatarView.setLayoutParams(params);
    avatarView.setContentDescription(Lang.getString(R.string.ProfilePhoto));
    addView(avatarView);

    cameraButton = new CameraButton(context);
    int buttonFullSize = Screen.dp(BUTTON_SIZE + BUTTON_RING * 2);
    params = FrameLayoutFix.newParams(buttonFullSize, buttonFullSize, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    // Button centre on the avatar circle at 45° (bottom-end): r * sin(45°) from the avatar centre.
    float offset = AVATAR_SIZE / 2f * 0.7071f;
    params.topMargin = Screen.dp(PADDING_TOP + AVATAR_SIZE / 2f + offset - BUTTON_SIZE / 2f - BUTTON_RING);
    if (Lang.rtl()) {
      params.rightMargin = Screen.dp(offset);
    } else {
      params.leftMargin = Screen.dp(offset);
    }
    cameraButton.setLayoutParams(params);
    cameraButton.setContentDescription(Lang.getString(R.string.SetProfilePhoto));
    addView(cameraButton);

    emojiStatusHelper = new EmojiStatusHelper(tdlib, this, "settings_profile");
    TGLegacyManager.instance().addEmojiListener(this);
  }

  public void setOnPhotoClickListener (View.OnClickListener listener) {
    avatarView.setOnClickListener(listener);
    cameraButton.setOnClickListener(listener);
  }

  public void setOnEmojiStatusClickListener (View.OnClickListener listener) {
    emojiStatusHelper.setClickListener(listener);
  }

  public View getPhotoAnchorView () {
    return cameraButton;
  }

  public int getEmojiStatusLastDrawX () {
    return emojiStatusHelper.getLastDrawX();
  }

  public int getEmojiStatusLastDrawY () {
    return emojiStatusHelper.getLastDrawY();
  }

  public void setIgnoreDrawEmojiStatus (boolean ignore) {
    emojiStatusHelper.setIgnoreDraw(ignore);
    invalidate();
  }

  public void setUser (@Nullable TdApi.User user, @Nullable String name, @Nullable String subtitle) {
    if (user != null) {
      avatarView.receiver.requestUser(tdlib, user.id, AvatarReceiver.Options.FULL_SIZE);
    } else {
      avatarView.receiver.clear();
    }
    emojiStatusHelper.updateEmoji(tdlib, user, TextColorSets.Regular.NORMAL, R.drawable.baseline_premium_star_24, 24);
    this.name = name;
    this.subtitle = subtitle;
    buildTexts(true);
    invalidate();
  }

  @Override
  public void onEmojiUpdated (boolean isPackSwitch) {
    invalidate();
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    buildTexts(false);
  }

  private void buildTexts (boolean force) {
    int width = getMeasuredWidth();
    if (width <= 0 || (!force && width == lastWidth)) {
      return;
    }
    lastWidth = width;
    int availWidth = width - Screen.dp(TEXT_PADDING) * 2;
    int nameWidth = availWidth;
    if (emojiStatusHelper.needDrawEmojiStatus()) {
      nameWidth -= emojiStatusHelper.getWidth() + Screen.dp(6f);
    }
    trimmedName = !StringUtils.isEmpty(name) && nameWidth > 0 ?
      new Text.Builder(name, nameWidth, Paints.robotoStyleProvider(24), TextColorSets.Regular.NORMAL).singleLine().allBold().build() : null;
    trimmedSubtitle = !StringUtils.isEmpty(subtitle) && availWidth > 0 ?
      new Text.Builder(subtitle, availWidth, Paints.robotoStyleProvider(14), TextColorSets.Regular.LIGHT).singleLine().build() : null;
    emojiStatusHelper.invalidateEmojiStatusReceiver(trimmedName, null);
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    return emojiStatusHelper.onTouchEvent(this, e) || super.onTouchEvent(e);
  }

  @Override
  protected void onDraw (Canvas c) {
    super.onDraw(c);
    final int width = getMeasuredWidth();
    if (trimmedName != null) {
      int nameWidth = trimmedName.getWidth();
      boolean hasEmoji = emojiStatusHelper.needDrawEmojiStatus();
      int emojiWidth = hasEmoji ? emojiStatusHelper.getWidth() + Screen.dp(6f) : 0;
      int left = (width - nameWidth - emojiWidth) / 2;
      int top = Screen.dp(NAME_TOP) + (Screen.dp(NAME_HEIGHT) - trimmedName.getHeight()) / 2;
      boolean rtl = Lang.rtl();
      int textLeft = rtl ? left + emojiWidth : left;
      trimmedName.draw(c, textLeft, textLeft + nameWidth, 0, top);
      if (hasEmoji) {
        emojiStatusHelper.draw(c, rtl ? left : left + nameWidth + Screen.dp(6f), top);
      }
    }
    if (trimmedSubtitle != null) {
      int subtitleWidth = trimmedSubtitle.getWidth();
      int left = (width - subtitleWidth) / 2;
      int top = Screen.dp(NAME_TOP + NAME_HEIGHT) + (Screen.dp(SUBTITLE_HEIGHT) - trimmedSubtitle.getHeight()) / 2;
      trimmedSubtitle.draw(c, left, left + subtitleWidth, 0, top);
    }
  }

  @Override
  public void attach () {
    avatarView.receiver.attach();
    emojiStatusHelper.attach();
  }

  @Override
  public void detach () {
    avatarView.receiver.detach();
    emojiStatusHelper.detach();
  }

  @Override
  public void performDestroy () {
    avatarView.receiver.destroy();
    emojiStatusHelper.performDestroy();
  }

  private static M3StateLayerDrawable newOvalStateLayer (int inset) {
    GradientDrawable oval = new GradientDrawable();
    oval.setShape(GradientDrawable.OVAL);
    oval.setColor(0xFFFFFFFF);
    Drawable mask = inset != 0 ? new InsetDrawable(oval, inset) : oval;
    return new M3StateLayerDrawable(null, mask, 0);
  }

  private static final class AvatarView extends FrameLayoutFix {
    private final AvatarReceiver receiver;
    private final M3StateLayerDrawable stateLayer;

    AvatarView (Context context) {
      super(context);
      setWillNotDraw(false);
      receiver = new AvatarReceiver(this);
      stateLayer = newOvalStateLayer(0);
      ForegroundCompat.set(this, stateLayer);
    }

    @Override
    protected void onDraw (Canvas c) {
      stateLayer.setColor(ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface()));
      receiver.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
      if (receiver.needPlaceholder()) {
        receiver.drawPlaceholder(c);
      }
      receiver.draw(c);
    }
  }

  private static final class CameraButton extends FrameLayoutFix {
    private final Drawable icon;
    private final M3StateLayerDrawable stateLayer;

    CameraButton (Context context) {
      super(context);
      setWillNotDraw(false);
      icon = Drawables.get(getResources(), R.drawable.baseline_camera_alt_24);
      stateLayer = newOvalStateLayer(Screen.dp(BUTTON_RING));
      ForegroundCompat.set(this, stateLayer);
    }

    @Override
    protected void onDraw (Canvas c) {
      float cx = getMeasuredWidth() / 2f, cy = getMeasuredHeight() / 2f;
      // Ring of the page color separates the button from the photo.
      c.drawCircle(cx, cy, Math.min(cx, cy), Paints.fillingPaint(Theme.backgroundColor()));
      c.drawCircle(cx, cy, Screen.dp(BUTTON_SIZE / 2f), Paints.fillingPaint(M3.primary()));
      stateLayer.setColor(ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onPrimary()));
      if (icon != null) {
        Drawables.draw(c, icon, cx - icon.getMinimumWidth() / 2f, cy - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(M3.onPrimary()));
      }
    }
  }
}
