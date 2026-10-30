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
 *
 * File created on 01/03/2017
 */
package org.thunderdog.challegram.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.config.Device;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.AvatarPlaceholder;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.data.ThreadInfo;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.navigation.ComplexHeaderView;
import org.thunderdog.challegram.navigation.DoubleHeaderView;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.ChatListener;
import org.thunderdog.challegram.telegram.DisplayInformation;
import org.thunderdog.challegram.telegram.MessageThreadListener;
import org.thunderdog.challegram.telegram.NotificationSettingsListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.telegram.TdlibCache;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeChangeListener;
import org.thunderdog.challegram.theme.ThemeListenerList;
import org.thunderdog.challegram.theme.ThemeManager;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.SensitiveContentContainer;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.ViewUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.lambda.Destroyable;
import tgx.td.ChatId;
import tgx.td.Td;

public class ForceTouchView extends FrameLayoutFix implements
  PopupLayout.AnimatedPopupProvider, FactorAnimator.Target,
  ChatListener, MessageThreadListener, NotificationSettingsListener, TdlibCache.UserDataChangeListener, TdlibCache.SupergroupDataChangeListener, TdlibCache.BasicGroupDataChangeListener, ThemeChangeListener, TdlibCache.UserStatusChangeListener, SensitiveContentContainer, RootFrameLayout.MarginModifier {
  private ForceTouchContext forceTouchContext;
  private final RelativeLayout contentWrap;
  private final View backgroundView;
  private final ComplexReceiver complexAvatarReceiver;

  public interface StateListener {
    void onPrepareToExitForceTouch (ForceTouchContext context);
    void onPrepareToEnterForceTouch (ForceTouchContext context);
    void onCompletelyShownForceTouch (ForceTouchContext context);
    void onDestroyForceTouch (ForceTouchContext context);
  }

  public interface ActionListener {
    void onForceTouchAction (ForceTouchContext context, int actionId, Object arg);
    default void onAfterForceTouchAction (ForceTouchContext context, int actionId, Object arg) { }
  }

  public interface MaximizeListener {
    boolean onPerformMaximize (FactorAnimator target, float animateToWhenReady, Object arg);
  }

  public interface PreviewDelegate {
    void onPrepareForceTouchContext (ForceTouchContext context);
  }

  // 56dp: room for the 40dp «Open» button of a chat preview (M3 button height) with 8dp around it.
  public static final float FOOTER_HEIGHT = 56f;
  public static final float HEADER_HEIGHT = 56f;
  // TEMPORARY: two shapes of the preview card for the owner to choose; delete the losing one.
  // 1 = card: CornerMedium 12dp, elevation Level1 (1dp); 2 = dialog-like: CornerExtraLarge 28dp, elevation Level3 (6dp).
  private static final int PREVIEW_SHAPE_VARIANT = 1;
  private static final float RADIUS = PREVIEW_SHAPE_VARIANT == 1 ? 12f : 28f;
  private static final float ELEVATION = PREVIEW_SHAPE_VARIANT == 1 ? 1f : 6f;

  /** Elevated card container is surfaceContainerLow, dialog container surfaceContainerHigh (M3 tokens). */
  private static int containerColor () {
    return PREVIEW_SHAPE_VARIANT == 1 ? M3.surfaceContainerLow() : M3.surfaceContainerHigh();
  }

  // private static TextPaint hintTextPaint;

  private final ThemeListenerList themeListenerList;

  private final RectF drawingRect = new RectF();
  private final RectF targetRect = new RectF();
  private final RectF sourceRect = new RectF();
  private final @Nullable Path path = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ? new Path() : null;

  public ForceTouchView (Context context) {
    super(context);

    this.themeListenerList = new ThemeListenerList();

    // Base

    FrameLayoutFix.LayoutParams params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    /*if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      params.topMargin = Screen.getStatusBarHeight();
    }*/
    setLayoutParams(params);

    // Background

    backgroundView = new View(context);
    addView(backgroundView);

    // Content

    params = FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);

    contentWrap = new RelativeLayout(context) {

      @Override
      protected void onSizeChanged (int width, int height, int oldWidth, int oldHeight) {
        if (width != oldWidth || height != oldHeight) {
          targetRect.set(0, 0, width, height);
          if (forceTouchContext.animationType == ForceTouchContext.ANIMATION_TYPE_EXPAND_VERTICALLY) {
            if (sourceRect.isEmpty() && height != 0) {
              sourceRect.set(0, 0, width, Math.min(Screen.dp(200f), height));

              int[] location = Views.getLocationOnScreen(this);
              float y = forceTouchContext.sourcePoint != null ? forceTouchContext.sourcePoint.y - location[1] : targetRect.centerY();
              float dy = y - y / height * sourceRect.height();
              sourceRect.offset(0, dy);

              drawingRect.set(sourceRect);
              if (path != null) {
                path.reset();
                path.addRoundRect(drawingRect, Screen.dp(RADIUS), Screen.dp(RADIUS), Path.Direction.CW);
              }
            }
          } else {
            drawingRect.set(targetRect);
            if (path != null) {
              path.reset();
              path.addRoundRect(drawingRect, Screen.dp(RADIUS), Screen.dp(RADIUS), Path.Direction.CW);
            }
          }
        }
      }

      @Override
      public void draw (Canvas c) {
        final boolean needClip = !forceTouchContext.needHeader || forceTouchContext.animationType == ForceTouchContext.ANIMATION_TYPE_EXPAND_VERTICALLY;
        final int saveCount = needClip ? ViewSupport.clipPath(c, path) : Integer.MIN_VALUE;
        super.draw(c);
        if (needClip) {
          ViewSupport.restoreClipPath(c, saveCount);
        }
        if (path == null) {
          c.drawRect(drawingRect, Paints.strokeBigPaint(ColorUtils.alphaColor(.2f, Theme.textAccentColor())));
        }
      }
    };
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      contentWrap.setOutlineProvider(new android.view.ViewOutlineProvider() {
        @Override
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        public void getOutline (View view, android.graphics.Outline outline) {
          outline.setRoundRect(Math.round(drawingRect.left), Math.round(drawingRect.top), Math.round(drawingRect.right), Math.round(drawingRect.bottom), Screen.dp(RADIUS));
        }
      });
      contentWrap.setElevation(Screen.dp(ELEVATION));
    }
    ViewUtils.setBackground(contentWrap, new Drawable() {
      @Override
      public void draw (@NonNull Canvas c) {
        c.drawRoundRect(drawingRect, Screen.dp(RADIUS), Screen.dp(RADIUS), Paints.fillingPaint(containerColor()));
      }

      @Override
      public void setAlpha (int alpha) {

      }

      @Override
      public void setColorFilter (ColorFilter colorFilter) {

      }

      @Override
      @SuppressWarnings("deprecation")
      public int getOpacity () {
        return PixelFormat.UNKNOWN;
      }
    });
    contentWrap.setLayoutParams(params);
    addView(contentWrap);

    themeListenerList.addThemeInvalidateListener(contentWrap);
    complexAvatarReceiver = new ComplexReceiver(this);
  }

  @Override
  public void onApplyMarginInsets (View child, LayoutParams params, Rect legacyInsets, Rect insets, Rect insetsWithoutIme) {
    // Views.setBottomMargin(contentWrap, insets.bottom / 2);
  }

  @Override
  public boolean shouldDisallowScreenshots () {
    if (forceTouchContext != null) {
      if (forceTouchContext.boundController != null) {
        return forceTouchContext.boundController.shouldDisallowScreenshots();
      }
      if (forceTouchContext.contentView instanceof SensitiveContentContainer) {
        return ((SensitiveContentContainer) forceTouchContext.contentView).shouldDisallowScreenshots();
      }
    }
    return false;
  }

  @Override
  public boolean needsTempUpdates () {
    return true;
  }

  @Override
  public void onThemeColorsChanged (boolean areTemp, ColorState state) {
    themeListenerList.onThemeColorsChanged(areTemp);
  }

  private @Nullable StateListener listener;
  private ComplexHeaderView headerView;
  private @Nullable View targetHeaderView;
  private @Nullable ShadowView headerShadowView;
  private @Nullable ShadowView footerShadowView;
  private @Nullable PopupWrapView popupWrapView;

  private LinearLayout buttonsList;
  private @Nullable OpenButtonView openButton;

  /** M3 filled button (FilledButtonTokens): 40dp, full corners, primary / onPrimary, label large, 24dp padding. */
  private static class OpenButtonView extends View {
    private final String text;
    private final RectF rect = new RectF();

    public OpenButtonView (Context context, String text) {
      super(context);
      this.text = text;
      setClickable(true);
      setFocusable(true);
      setContentDescription(text);
    }

    private TextPaint textPaint () {
      return Paints.getMediumTextPaint(14f, M3.onPrimary(), false);
    }

    @Override
    protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
      int width = (int) Math.ceil(U.measureText(text, textPaint())) + Screen.dp(24f) * 2;
      setMeasuredDimension(Math.max(width, Screen.dp(48f)), Screen.dp(40f));
    }

    @Override
    protected void drawableStateChanged () {
      super.drawableStateChanged();
      invalidate();
    }

    @Override
    protected void onDraw (Canvas c) {
      rect.set(0, 0, getWidth(), getHeight());
      float radius = getHeight() / 2f;
      c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(M3.primary()));
      if (isPressed()) {
        // M3 pressed state layer: onPrimary at 10%.
        c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(ColorUtils.alphaColor(.1f, M3.onPrimary())));
      }
      TextPaint paint = textPaint();
      Paint.FontMetrics metrics = paint.getFontMetrics();
      c.drawText(text, (getWidth() - U.measureText(text, paint)) / 2f, getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f, paint);
    }
  }
  private Tdlib tdlib;

  public void initWithContext (ForceTouchContext context) {
    this.tdlib = context.tdlib;
    this.forceTouchContext = context;
    this.listener = context.stateListener;

    if (context.backgroundColor != 0) {
      backgroundView.setBackgroundColor(context.backgroundColor);
    } else {
      // M3 scrim (styles/elevation): «Scrims use the scrim color role at an opacity of 32%»; the scrim role is black.
      backgroundView.setBackgroundColor(0x52000000);
    }

    FrameLayoutFix.LayoutParams baseParams = (FrameLayoutFix.LayoutParams) contentWrap.getLayoutParams();
    if (context.isMatchParent) {
      baseParams.leftMargin = baseParams.rightMargin = getMatchParentHorizontalMargin();
      baseParams.topMargin = getMatchParentTopMargin();
      baseParams.bottomMargin = getMatchParentBottomMargin();
    } else {
      baseParams.leftMargin = baseParams.rightMargin = Screen.dp(16f); // = params.topMargin = params.rightMargin = params.bottomMargin = Screen.dp(8f);
      baseParams.topMargin = baseParams.bottomMargin = HeaderView.getTopOffset() + Screen.dp(12f);
    }

    RelativeLayout.LayoutParams contentParams;
    contentParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    contentParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
    // contentParams.addRule(RelativeLayout.CENTER_IN_PARENT);

    context.contentView.setId(R.id.forceTouch_content);
    context.contentView.setLayoutParams(contentParams);
    contentWrap.addView(context.contentView);

    if (context.hasHeader()) {
      RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(HEADER_HEIGHT));
      params.addRule(RelativeLayout.ALIGN_LEFT, R.id.forceTouch_content);
      params.addRule(RelativeLayout.ALIGN_RIGHT, R.id.forceTouch_content);
      contentParams.addRule(RelativeLayout.BELOW, R.id.forceTouch_header);

      if (context.needHeaderAvatar) {
        headerView = new ComplexHeaderView(getContext(), tdlib, null);
        headerView.setId(R.id.forceTouch_header);
        headerView.setIgnoreCustomHeight();
        headerView.setInnerMargins(Screen.dp(8f), Screen.dp(8f));
        headerView.setTextColors(Theme.textAccentColor(), Theme.textDecentColor());
        if (context.boundDataType != 0 && (context.boundDataId != 0 || context.boundDataType == DataType.ACCOUNT)) {
          switch (context.boundDataType) {
            case DataType.CHAT:
              setupChat(context.boundDataId, (ThreadInfo) context.boundArg1, headerView);
              break;
            case DataType.USER:
              setupUser(context.boundDataId, headerView);
              break;
            case DataType.ACCOUNT:
              setupAccount((int) context.boundDataId, headerView);
              break;
            default:
              throw new UnsupportedOperationException();
          }
        } else {
          if (context.avatarSender != null) {
            headerView.getAvatarReceiver().requestMessageSender(tdlib, context.avatarSender, AvatarReceiver.Options.NONE);
          } else if (context.avatarPlaceholder != null) {
            headerView.getAvatarReceiver().requestPlaceholder(tdlib, context.avatarPlaceholder, AvatarReceiver.Options.NONE);
          } else {
            headerView.getAvatarReceiver().clear();
          }
          headerView.setText(context.title, context.subtitle);
        }
        headerView.setLayoutParams(params);
        contentWrap.addView(targetHeaderView = headerView);
      } else {
        DoubleHeaderView headerView = new DoubleHeaderView(getContext());
        headerView.setId(R.id.forceTouch_header);
        headerView.setTitle(context.title);
        headerView.setSubtitle(context.subtitle);
        headerView.setTextColors(Theme.textAccentColor(), Theme.textDecentColor());
        headerView.setLayoutParams(params);
        contentWrap.addView(targetHeaderView = headerView);
      }
      ViewUtils.setBackground(targetHeaderView, new Drawable() {
        @Override
        public void draw (@NonNull Canvas canvas) {
          Rect bounds = getBounds();
          int radius = Screen.dp(RADIUS);
          Path path = Paints.getPath();
          path.reset();
          path.moveTo(bounds.right, bounds.top + radius);
          path.rQuadTo(0, -radius, -radius, -radius);
          path.rLineTo(-(bounds.width() - radius * 2), 0);
          path.rQuadTo(-radius, 0, -radius, radius);
          path.rLineTo(0, bounds.height() - radius);
          path.rLineTo(bounds.width(), 0);
          path.rLineTo(0, -(bounds.height() - radius));
          path.close();
          canvas.drawPath(path, Paints.fillingPaint(containerColor()));
        }

        @Override
        public void setAlpha (int alpha) {

        }

        @Override
        public void setColorFilter (@Nullable ColorFilter colorFilter) {

        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity () {
          return PixelFormat.UNKNOWN;
        }
      });
      themeListenerList.addThemeDoubleTextColorListener(targetHeaderView, ColorId.text, ColorId.textLight);

      params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(7f));
      params.addRule(RelativeLayout.ALIGN_LEFT, R.id.forceTouch_content);
      params.addRule(RelativeLayout.ALIGN_RIGHT, R.id.forceTouch_content);
      params.addRule(RelativeLayout.ALIGN_TOP, R.id.forceTouch_content);

      headerShadowView = new ShadowView(getContext());
      headerShadowView.setSimpleBottomTransparentShadow(true);
      headerShadowView.setLayoutParams(params);
      contentWrap.addView(headerShadowView);
      themeListenerList.addThemeInvalidateListener(headerShadowView);
    }

    if (context.hasFooter() || context.hasOpenButton()) {
      RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(0, /* contentParams.bottomMargin =*/ Screen.dp(FOOTER_HEIGHT));
      params.addRule(RelativeLayout.ALIGN_LEFT, R.id.forceTouch_content);
      params.addRule(RelativeLayout.ALIGN_RIGHT, R.id.forceTouch_content);
      params.addRule(RelativeLayout.BELOW, R.id.forceTouch_content);
      params.addRule(RelativeLayout.CENTER_HORIZONTAL);
      if (context.isMatchParent) {
        contentParams.bottomMargin = params.height;
        params.topMargin = -contentParams.bottomMargin;
      }

      buttonsList = new LinearLayout(getContext());
      buttonsList.setId(R.id.forceTouch_footer);
      buttonsList.setOrientation(LinearLayout.HORIZONTAL);
      buttonsList.setGravity(Gravity.CENTER_HORIZONTAL);
      buttonsList.setLayoutParams(params);

      ViewUtils.setBackground(buttonsList, new Drawable() {
        @Override
        public void draw (@NonNull Canvas canvas) {
          Rect bounds = getBounds();
          int radius = Screen.dp(RADIUS);
          Path path = Paints.getPath();
          path.reset();
          path.moveTo(bounds.right, bounds.top);
          path.rLineTo(-bounds.width(), 0);
          path.rLineTo(0, bounds.height() - radius);
          path.rQuadTo(0, radius, radius, radius);
          path.rLineTo(bounds.width() - radius * 2, 0);
          path.rQuadTo(radius, 0, radius, -radius);
          path.rLineTo(0, -(bounds.height() - radius));
          path.close();
          canvas.drawPath(path, Paints.fillingPaint(containerColor()));
        }

        @Override
        public void setAlpha (int alpha) {

        }

        @Override
        public void setColorFilter (@Nullable ColorFilter colorFilter) {

        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity () {
          return PixelFormat.UNKNOWN;
        }
      });

      if (context.hasOpenButton()) {
        // M3 filled button «Open», centered (owner's choice).
        buttonsList.setGravity(Gravity.CENTER);
        buttonsList.setPadding(Screen.dp(16f), 0, Screen.dp(16f), 0);
        openButton = new OpenButtonView(getContext(), context.openButtonText);
        openButton.setOnClickListener(v -> {
          if (forceTouchContext != null && forceTouchContext.maximizeListener != null) {
            maximize();
          }
          UI.getContext(getContext()).closeForceTouch();
        });
        themeListenerList.addThemeInvalidateListener(openButton);
        buttonsList.addView(openButton);
        contentWrap.addView(buttonsList);
      } else {
        View offsetView;

        final int offsetWeight = context.shrunkenFooter ? 4 : 1;

        if (context.actionItems.size() > 1) {
          offsetView = new View(getContext());
          offsetView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, offsetWeight));
          buttonsList.addView(offsetView);
        }

        params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(FOOTER_HEIGHT));
        params.addRule(RelativeLayout.ABOVE, R.id.forceTouch_footer);
        params.addRule(RelativeLayout.ALIGN_LEFT, R.id.forceTouch_footer);
        params.addRule(RelativeLayout.ALIGN_RIGHT, R.id.forceTouch_footer);

        if (context.isMatchParent) {
          params.bottomMargin = contentParams.bottomMargin;
        }

        popupWrapView = new PopupWrapView(getContext());
        popupWrapView.setLayoutParams(params);
        // popupWrapView.setBackgroundColor(0x1cff0000);

        PopupContext[] popupContexts = new PopupContext[context.actionItems.size()];
        boolean rtl = Lang.rtl();
        int remaining = context.actionItems.size();
        while (remaining > 0) {
          int i = rtl ? remaining - 1 : context.actionItems.size() - remaining;
          final BaseView.ActionItem actionItem = context.actionItems.get(i);
          int buttonId = actionItem.id;
          ImageView view;
          if (actionItem.iconRes != 0 && Drawables.needMirror(actionItem.iconRes)) {
            view = new ImageView(getContext()) {
              @Override
              protected void onDraw (Canvas c) {
                final int restoreToCount = Views.save(c);
                c.scale(-1f, 1f, getMeasuredWidth() / 2f, getMeasuredHeight() / 2f);
                super.onDraw(c);
                Views.restore(c, restoreToCount);
              }
            };
          } else if (actionItem.messageSender != null && actionItem.iconRes == 0) {
            AvatarReceiver receiver = complexAvatarReceiver.getAvatarReceiver(Td.getSenderId(actionItem.messageSender));
            receiver.requestMessageSender(tdlib, actionItem.messageSender, AvatarReceiver.Options.NONE);
            receiver.setBounds(0, 0, Screen.dp(24), Screen.dp(24));
            receiver.setRadius(Screen.dp(12));
            view = new ImageView(getContext()) {
              @Override
              protected void onDraw (Canvas c) {
                super.onDraw(c);
                final int restoreToCount = Views.save(c);
                c.translate((getMeasuredWidth() - receiver.getWidth()) / 2f, (getMeasuredHeight() - receiver.getHeight()) / 2f);
                receiver.draw(c);
                Views.restore(c, restoreToCount);
              }
            };
            receiver.setUpdateListener(r -> view.invalidate());
          } else {
            view = new ImageView(getContext());
          }
          view.setId(buttonId);
          PopupContext popupContext = new PopupContext(popupWrapView, view, actionItem.title);
          view.setTag(popupContexts[i] = popupContext);
          view.setScaleType(ImageView.ScaleType.CENTER);
          themeListenerList.addThemeFilterListener(view, ColorId.icon);
          if (actionItem.iconRes != 0) {
            view.setImageResource(actionItem.iconRes);
            view.setColorFilter(Theme.iconColor());
          }
          view.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f));

          buttonsList.addView(view);
          remaining--;
        }
        popupWrapView.setItems(popupContexts);

        if (context.actionItems.size() > 1) {
          offsetView = new View(getContext());
          offsetView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, offsetWeight));
          buttonsList.addView(offsetView);
        }

        contentWrap.addView(buttonsList);
        contentWrap.addView(popupWrapView);

        params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(6f));
        params.addRule(RelativeLayout.ALIGN_LEFT, R.id.forceTouch_content);
        params.addRule(RelativeLayout.ALIGN_RIGHT, R.id.forceTouch_content);
        params.addRule(RelativeLayout.ALIGN_BOTTOM, R.id.forceTouch_content);

        footerShadowView = new ShadowView(getContext());
        footerShadowView.setSimpleTopShadow(true);
        footerShadowView.setLayoutParams(params);
        contentWrap.addView(footerShadowView);
        themeListenerList.addThemeInvalidateListener(footerShadowView);
      }
    }

    revealFactor = 1f;
    setRevealFactor(0f);

    if (context.boundController != null) {
      context.boundController.setBoundForceTouchView(this);
    }

    ThemeManager.instance().addThemeListener(this);
  }

  // A sticky preview (opened from the menu, or left open when the holding finger is lifted): the chat scrolls,
  // «Open» opens it, a tap outside closes. Messages themselves take no touches, as in a held preview.

  /** The held preview stays open after the finger is lifted. */
  public void setSticky () {
    if (forceTouchContext != null) {
      forceTouchContext.setSticky(true);
      setActionView(null);
    }
  }

  private static final int STICKY_TOUCH_NONE = 0;
  private static final int STICKY_TOUCH_SCROLL = 1;
  private static final int STICKY_TOUCH_ACTION = 2;
  private static final int STICKY_TOUCH_OUTSIDE = 3;
  private static final int STICKY_TOUCH_OPEN = 4;
  private int stickyTouchMode;

  private boolean isInside (@Nullable View view, float x, float y) {
    if (view == null || view.getVisibility() != View.VISIBLE) {
      return false;
    }
    // Views.getLocationOnScreen returns a shared array: read each result before the next call.
    int[] location = Views.getLocationOnScreen(this);
    int selfX = location[0], selfY = location[1];
    location = Views.getLocationOnScreen(view);
    float left = location[0] - selfX, top = location[1] - selfY;
    return x >= left && x < left + view.getWidth() && y >= top && y < top + view.getHeight();
  }

  /** Feeds the touch to the messages list only (scroll and fling), so that no message reacts to it. */
  private void dispatchStickyScroll (MotionEvent e) {
    View scrollView = forceTouchContext.scrollableView;
    int[] location = Views.getLocationOnScreen(this);
    int selfX = location[0], selfY = location[1];
    location = Views.getLocationOnScreen(scrollView);
    MotionEvent event = MotionEvent.obtain(e);
    event.offsetLocation(selfX - location[0], selfY - location[1]);
    scrollView.onTouchEvent(event);
    event.recycle();
  }

  @Override
  public boolean onInterceptTouchEvent (MotionEvent e) {
    if (forceTouchContext == null || !forceTouchContext.isSticky) {
      return super.onInterceptTouchEvent(e);
    }
    return true; // all touches of a sticky preview are routed by onTouchEvent
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    if (forceTouchContext == null || !forceTouchContext.isSticky) {
      return super.onTouchEvent(e);
    }
    final int x = Math.round(e.getX()), y = Math.round(e.getY());
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN: {
        ImageView actionView = forceTouchContext.hasFooter() ? findActionViewByPosition(x, y) : null;
        if (isInside(openButton, x, y)) {
          stickyTouchMode = STICKY_TOUCH_OPEN;
          openButton.setPressed(true);
        } else if (actionView != null) {
          stickyTouchMode = STICKY_TOUCH_ACTION;
          setActionView(actionView);
        } else if (forceTouchContext.scrollableView != null && isInside(contentWrap, x, y) && isInside(forceTouchContext.contentView, x, y)) {
          stickyTouchMode = STICKY_TOUCH_SCROLL;
          dispatchStickyScroll(e);
        } else {
          stickyTouchMode = isInside(contentWrap, x, y) ? STICKY_TOUCH_NONE : STICKY_TOUCH_OUTSIDE;
        }
        break;
      }
      case MotionEvent.ACTION_MOVE: {
        if (stickyTouchMode == STICKY_TOUCH_SCROLL) {
          dispatchStickyScroll(e);
        } else if (stickyTouchMode == STICKY_TOUCH_ACTION) {
          setActionView(findActionViewByPosition(x, y));
        } else if (stickyTouchMode == STICKY_TOUCH_OPEN) {
          openButton.setPressed(isInside(openButton, x, y));
        }
        break;
      }
      case MotionEvent.ACTION_UP: {
        int mode = stickyTouchMode;
        stickyTouchMode = STICKY_TOUCH_NONE;
        if (mode == STICKY_TOUCH_OPEN) {
          openButton.setPressed(false);
          if (isInside(openButton, x, y)) {
            openButton.performClick();
          }
        } else if (mode == STICKY_TOUCH_SCROLL) {
          dispatchStickyScroll(e);
        } else if (mode == STICKY_TOUCH_ACTION) {
          setActionView(findActionViewByPosition(x, y));
          if (lastActionId != 0) {
            UI.getContext(getContext()).closeForceTouch();
          }
        } else if (mode == STICKY_TOUCH_OUTSIDE && !isInside(contentWrap, x, y)) {
          UI.getContext(getContext()).closeForceTouch();
        }
        break;
      }
      case MotionEvent.ACTION_CANCEL: {
        if (stickyTouchMode == STICKY_TOUCH_SCROLL) {
          dispatchStickyScroll(e);
        } else if (stickyTouchMode == STICKY_TOUCH_OPEN) {
          openButton.setPressed(false);
        }
        stickyTouchMode = STICKY_TOUCH_NONE;
        setActionView(null);
        break;
      }
    }
    return true;
  }

  // Move events (for actions)

  public void processMoveEvent (float x, float y, float startX, float startY) {
    if (forceTouchContext == null) {
      return;
    }

    if (forceTouchContext.maximizeListener != null && forceTouchContext.boundController != null) {
      forceTouchContext.boundController.maximizeFromPreviewIfNeeded(y, startY);
    }

    if (!forceTouchContext.hasFooter()) {
      return;
    }

    float innerX = x + forceTouchContext.getAddX(this);
    float innerY = y + forceTouchContext.getAddY(this);

    setPointerXY((int) innerX, (int) innerY);
  }

  private int lastX = -1, lastY = -1;
  private boolean hadExperience;

  public int getDistanceToButtonsList (float y) {
    if (forceTouchContext == null || !forceTouchContext.hasFooter() || buttonsList == null) {
      return 0;
    }
    float innerY = y + forceTouchContext.getAddY(this);
    final int contentTop = contentWrap.getBottom() - Screen.dp(FOOTER_HEIGHT) - Screen.dp(48f) /*padding*/;

    return innerY > contentTop ? (int) (innerY - contentTop) : 0;
  }

  private void setPointerXY (int x, int y) {
    if (lastX == -1 && lastY == -1) {
      lastX = x;
      lastY = y;
      return;
    }
    if (!hadExperience && Math.max(Math.abs(x - lastX), Math.abs(y - lastY)) < Screen.getTouchSlop()) {
      return;
    }

    hadExperience = true;

    this.lastX = x;
    this.lastY = y;

    ImageView foundView = findActionViewByPosition(x, y);
    setActionView(foundView);
  }

  private ImageView lastActionView;
  private int lastActionId;

  private void setActionView (ImageView imageView) {
    if (lastActionView == imageView) {
      return;
    }

    if (lastActionView != null) {
      ((PopupContext) lastActionView.getTag()).setIsVisible(false);
    }

    lastActionView = imageView;
    lastActionId = imageView != null ? imageView.getId() : 0;

    if (imageView != null) {
      ((PopupContext) imageView.getTag()).setIsVisible(true);
    }
  }

  private ImageView findActionViewByPosition (int x, int y) {
    final int contentBottom = contentWrap.getBottom();

    if (y > contentBottom || y < contentBottom - Screen.dp(FOOTER_HEIGHT) || buttonsList == null) {
      return null;
    }

    x -= contentWrap.getLeft();
    if (forceTouchContext.hasFooter()) {
      x -= buttonsList.getLeft();
    }

    final int childCount = buttonsList.getChildCount();
    final int offsetCount = forceTouchContext.actionItems.size() == 1 ? 0 : 1;

    for (int i = offsetCount; i < childCount - offsetCount; i++) {
      View view = buttonsList.getChildAt(i);
      if (view != null && x >= view.getLeft() && x <= view.getRight()) {
        return (ImageView) view;
      }
    }

    return null;
  }

  // Reveal animation

  private static final float REVEAL_FACTOR = .7f;
  private float revealFactor;

  public void setBeforeMaximizeFactor (float factor) {
    if (revealFactor >= 1f) {
      setRevealFactor(1f + .1f * factor);
      if (revealAnimator != null) {
        revealAnimator.forceFactor(revealFactor);
      }
    }
  }

  private void setRevealFactor (float factor) {
    if (this.revealFactor != factor) {
      this.revealFactor = factor;

      if (factor < 1f && path != null && forceTouchContext.animationType == ForceTouchContext.ANIMATION_TYPE_EXPAND_VERTICALLY) {
        drawingRect.left = MathUtils.fromTo(sourceRect.left, targetRect.left, factor);
        drawingRect.top = MathUtils.fromTo(sourceRect.top, targetRect.top, factor);
        drawingRect.right = MathUtils.fromTo(sourceRect.right, targetRect.right, factor);
        drawingRect.bottom = MathUtils.fromTo(sourceRect.bottom, targetRect.bottom, factor);
        contentWrap.setScaleX(1f);
        contentWrap.setScaleY(1f);
      } else {
        drawingRect.set(targetRect);
        final float scale = REVEAL_FACTOR + (1f - REVEAL_FACTOR) * factor;
        contentWrap.setScaleX(scale);
        contentWrap.setScaleY(scale);
      }

      if (forceTouchContext.animationType == ForceTouchContext.ANIMATION_TYPE_EXPAND_VERTICALLY) {
        if (path != null) {
          path.reset();
          path.addRoundRect(drawingRect, Screen.dp(RADIUS), Screen.dp(RADIUS), Path.Direction.CW);
        }
        if (forceTouchContext.contentView != null) {
          forceTouchContext.contentView.setTranslationY(drawingRect.centerY() - targetRect.centerY());
        }
        if (targetHeaderView != null) {
          targetHeaderView.setTranslationY(drawingRect.top);
        }
        if (headerShadowView != null) {
          headerShadowView.setTranslationY(drawingRect.top);
        }
        if (buttonsList != null) {
          buttonsList.setTranslationY(drawingRect.bottom - targetRect.bottom);
        }
        if (popupWrapView != null) {
          popupWrapView.setTranslationY(drawingRect.bottom - targetRect.bottom);
        }
        if (footerShadowView != null) {
          footerShadowView.setTranslationY(drawingRect.bottom - targetRect.bottom);
        }
        contentWrap.invalidate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          contentWrap.invalidateOutline();
        }
      }

      if (isMaximizing) {
        float progressFactor = MathUtils.clamp((factor - maximizingFromFactor) / (MAXIMIZE_FACTOR - maximizingFromFactor));
        float alpha = MathUtils.clamp(maximizingFromFactor * (1f - progressFactor));
        contentWrap.setAlpha(alpha);
        backgroundView.setAlpha(alpha);
      } else {
        float alpha = MathUtils.clamp(factor);
        contentWrap.setAlpha(alpha);
        backgroundView.setAlpha(alpha);
      }

    }
  }

  // Animator

  private static final int REVEAL_ANIMATOR = 0;

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    switch (id) {
      case REVEAL_ANIMATOR: {
        setRevealFactor(factor);
        break;
      }
    }
  }

  private boolean isDestroyed;

  private void destroy () {
    isDestroyed = true;
    if (forceTouchContext.contentView instanceof Destroyable) {
      ((Destroyable) forceTouchContext.contentView).performDestroy();
    }
    if (headerView != null) {
      headerView.performDestroy();
    }
    if (listener != null) {
      listener.onDestroyForceTouch(forceTouchContext);
    }
    removeListeners();
    ThemeManager.instance().removeThemeListener(this);
  }

  private void performAction (boolean after) {
    if (lastActionId != 0 && forceTouchContext.actionListener != null) {
      if (after) {
        forceTouchContext.actionListener.onAfterForceTouchAction(forceTouchContext, lastActionId, forceTouchContext.listenerArg);
      } else {
        forceTouchContext.actionListener.onForceTouchAction(forceTouchContext, lastActionId, forceTouchContext.listenerArg);
      }
    }
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    switch (id) {
      case REVEAL_ANIMATOR: {
        if (finalFactor == 0f || finalFactor == MAXIMIZE_FACTOR) {
          destroy();
          if (pendingCustomPopup != null) {
            pendingCustomPopup.onCustomHideAnimationComplete();
          }
          performAction(true);
          // UI.getContext(getContext()).setLowProfile(false, false);
        } else if (finalFactor == 1f) {
          if (listener != null) {
            listener.onCompletelyShownForceTouch(forceTouchContext);
          }
          if (pendingPopup != null) {
            pendingPopup.onCustomShowComplete();
          }
        }
        break;
      }
    }
  }

  // Popups

  private static class PopupContext implements FactorAnimator.Target {
    private final PopupWrapView parent;
    private final View target;

    private final String text;
    private final int width;

    private float factor;

    public PopupContext (PopupWrapView parent, View target, String text) {
      this.parent = parent;
      this.target = target;
      this.text = text;
      this.width = (int) U.measureText(text, Paints.getRegularTextPaint(12f));
    }

    public void draw (Canvas c) {
      if (factor != 0f) {
        int centerX = (target.getLeft() + target.getRight()) >> 1;

        int startY = parent.getMeasuredHeight();

        // M3 plain tooltip over the hovered action (components/tooltips, PlainTooltipTokens): inverseSurface,
        // CornerExtraSmall 4dp, body small 12sp in inverseOnSurface, 24dp high, padding 8dp, 4dp from the element.
        final int paddingHorizontal = Screen.dp(8f);
        final int marginBottom = Screen.dp(4f);

        final int rectHeight = Screen.dp(24f);
        final int diffY = rectHeight + marginBottom;

        final int rectTop = startY - (int) ((float) diffY * factor);

        RectF rectF = Paints.getRectF();
        rectF.set(centerX - width / 2 - paddingHorizontal, rectTop, centerX + width / 2 + paddingHorizontal, rectTop + rectHeight);

        float alpha = MathUtils.clamp(factor);
        c.drawRoundRect(rectF, Screen.dp(M3.CORNER_EXTRA_SMALL), Screen.dp(M3.CORNER_EXTRA_SMALL), Paints.fillingPaint(ColorUtils.alphaColor(alpha, M3.inverseSurface())));
        Paint textPaint = Paints.getRegularTextPaint(12f, ColorUtils.alphaColor(alpha, M3.inverseOnSurface()));
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        c.drawText(text, centerX - width / 2, rectF.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint);
      }
    }

    // FactorAnimator

    private boolean isVisible;

    public void setIsVisible (boolean isVisible) {
      if (this.isVisible != isVisible) {
        this.isVisible = isVisible;
        animateFactor(isVisible ? 1f : 0f);
        if (isVisible) {
          UI.forceVibrate(target, false);
          // target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING | HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
      }
    }

    private void setFactor (float factor) {
      if (this.factor != factor) {
        this.factor = factor;
        this.parent.invalidate();
      }
    }

    private FactorAnimator animator;

    private static final Interpolator interpolator = new OvershootInterpolator(1.28f);

    private void animateFactor (float factor) {
      if (animator == null) {
        animator = new FactorAnimator(0, this, interpolator, 230l, this.factor);
      }
      animator.animateTo(factor);
    }

    @Override
    public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
      setFactor(factor);
    }

    @Override
    public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) { }
  }

  private static class PopupWrapView extends View {
    private PopupContext[] items;

    public PopupWrapView (Context context) {
      super(context);
    }

    public void setItems (PopupContext[] items) {
      this.items = items;
    }

    @Override
    protected void onDraw (Canvas c) {
      if (items != null) {
        for (PopupContext item : items) {
          item.draw(c);
        }
      }
    }
  }

  // Animation

  private static final Interpolator OVERSHOOT_INTERPOLATOR = new OvershootInterpolator(1.24f); // 1.78f
  private FactorAnimator revealAnimator;

  @Override
  public void prepareShowAnimation () {
    FactorAnimator revealAnimator;
    switch (forceTouchContext.animationType) {
      case ForceTouchContext.ANIMATION_TYPE_EXPAND_VERTICALLY:
        revealAnimator = expandVerticallyAnimator();
        break;
      case ForceTouchContext.ANIMATION_TYPE_SCALE:
      default:
        revealAnimator = scaleAnimator();
        break;
    }
    this.revealAnimator = revealAnimator;
  }

  public boolean isAnimatingReveal () {
    return revealAnimator == null || revealAnimator.isAnimating();
  }

  private FactorAnimator expandVerticallyAnimator () {
    return new FactorAnimator(REVEAL_ANIMATOR, this, new DecelerateInterpolator(1.46f), 140l);
  }

  private FactorAnimator scaleAnimator () {
    if (Device.NEED_REDUCE_BOUNCE || Settings.instance().needReduceMotion()) {
      return new FactorAnimator(REVEAL_ANIMATOR, this, new DecelerateInterpolator(1.46f), 140l);
    }
    return new FactorAnimator(REVEAL_ANIMATOR, this, OVERSHOOT_INTERPOLATOR, 260l);
  }

  private PopupLayout pendingPopup;

  @Override
  public void launchShowAnimation (PopupLayout popup) {
    this.pendingPopup = popup;
    if (listener != null) {
      listener.onPrepareToEnterForceTouch(forceTouchContext);
    }
    // UI.getContext(getContext()).setLowProfile(true, false);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && forceTouchContext.isMatchParent) {
      revealAnimator.setStartDelay(68l);
    }
    revealAnimator.animateTo(1f);
  }

  private PopupLayout pendingCustomPopup;
  private boolean isMaximizing;
  private float maximizingFromFactor;

  private boolean forceMaximize;

  public boolean maximize () {
    if (!isMaximizing && !forceMaximize) {
      forceMaximize = true;
    }
    return true;
  }

  @Override
  public boolean launchHideAnimation (PopupLayout popup, FactorAnimator originalAnimator) {
    if (forceTouchContext != null && forceTouchContext.boundController != null && forceTouchContext.boundController.wouldMaximizeFromPreview()) {
      forceMaximize = true;
    }
    if (listener != null) {
      listener.onPrepareToExitForceTouch(forceTouchContext);
    }
    pendingCustomPopup = popup;
    if (forceMaximize || (lastActionId == R.id.maximize && revealFactor >= .8f)) {
      // revealAnimator.setStartDelay(62l);
      revealAnimator.setStartDelay(40l);
      revealAnimator.setDuration(140l);
      maximizingFromFactor = revealFactor;
      isMaximizing = true;
      // revealAnimator.animateTo(MAXIMIZE_FACTOR);
      revealAnimator.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);

      if (forceTouchContext.maximizeListener != null) {
        if (forceTouchContext.maximizeListener.onPerformMaximize(revealAnimator, MAXIMIZE_FACTOR, forceTouchContext.listenerArg)) {
          return true;
        }
      }

      revealAnimator.animateTo(MAXIMIZE_FACTOR);
    } else {
      revealAnimator.setStartDelay(0);
      revealAnimator.animateTo(0f);
      performAction(false);
    }
    return true;
  }

  public static int getMatchParentTopMargin () {
    return HeaderView.getTopOffset() + Screen.dp(20f);
  }

  public static int getMatchParentBottomMargin () {
    return HeaderView.getTopOffset() + (Device.NEED_LESS_PREVIEW_MARGINS ? Screen.dp(16f) : Screen.dp(20f));
  }

  public static int getMatchParentHorizontalMargin () {
    return Device.NEED_LESS_PREVIEW_MARGINS ? Screen.dp(16f) : Screen.dp(26f);
  }

  private static final float MAXIMIZE_FACTOR = 1.3f;

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    DataType.CHAT, DataType.USER, DataType.ACCOUNT
  })
  private @interface DataType {
    int CHAT = 1, USER = 2, ACCOUNT = 3;
  }

  // Context

  public static class ForceTouchContext {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ANIMATION_TYPE_SCALE, ANIMATION_TYPE_EXPAND_VERTICALLY})
    public @interface AnimationType {}

    public static final int ANIMATION_TYPE_SCALE = 0;
    public static final int ANIMATION_TYPE_EXPAND_VERTICALLY = 1;

    private final View sourceView;
    private final View contentView;
    private @Nullable Point sourcePoint;

    private boolean allowFullscreen;
    private boolean needHeader, needHeaderAvatar;
    private boolean isMatchParent;

    private @ColorInt int backgroundColor;
    private @AnimationType int animationType;

    // Header

    private AvatarPlaceholder.Metadata avatarPlaceholder;
    private TdApi.MessageSender avatarSender;

    private String title, subtitle;

    private @DataType int boundDataType;
    private long boundDataId;
    private @Nullable Object boundArg1;

    private StateListener stateListener;

    // Footer

    private Tdlib tdlib;
    private ActionListener actionListener;
    private MaximizeListener maximizeListener;
    private Object listenerArg;
    private ArrayList<BaseView.ActionItem> actionItems;
    private boolean shrunkenFooter;
    private boolean excludeHeader;

    public View getSourceView () {
      return sourceView;
    }

    private ViewController<?> boundController;

    public ForceTouchContext (Tdlib tdlib, View sourceView, View contentView, @Nullable ViewController<?> controller) {
      this.tdlib = tdlib;
      this.sourceView = sourceView;
      this.contentView = contentView;
      this.stateListener = contentView instanceof StateListener ? (StateListener) contentView : null;
      this.boundController = controller;
    }

    public void setAnimationType(@AnimationType int animationType) {
      this.animationType = animationType;
    }

    private boolean isSticky;
    private @Nullable String openButtonText;
    private @Nullable View scrollableView;

    /** The footer is one «Open» button (opens the previewed chat) instead of action icons. */
    public void setOpenButton (@Nullable String text) {
      this.openButtonText = text;
    }

    public boolean hasOpenButton () {
      return openButtonText != null && !hasFooter();
    }

    /** The view a sticky preview scrolls (the messages list). */
    public void setScrollableView (@Nullable View scrollableView) {
      this.scrollableView = scrollableView;
    }

    /** Opened without a held finger (the «Preview» menu item): taps on the preview act instead of the finger release. */
    public void setSticky (boolean isSticky) {
      this.isSticky = isSticky;
    }

    public boolean isSticky () {
      return isSticky;
    }

    public void setAnimationSourcePoint (@Px int screenX, @Px int screenY) {
      if (sourcePoint == null) {
        sourcePoint = new Point(screenX, screenY);
      } else {
        sourcePoint.set(screenX, screenY);
      }
    }

    public void setTdlib (Tdlib tdlib) {
      this.tdlib = tdlib;
    }

    public ForceTouchContext setBackgroundColor (@ColorInt int backgroundColor) {
      this.backgroundColor = backgroundColor;
      return this;
    }

    public ForceTouchContext setExcludeHeader (boolean excludeHeader) {
      this.excludeHeader = excludeHeader;
      return this;
    }

    public void setShrunkenFooter (boolean shrunkenFooter) {
      this.shrunkenFooter = shrunkenFooter;
    }

    public float getAddX (ForceTouchView target) {
      int[] location = Views.getLocationOnScreen(sourceView);
      int sourceX = location[0];
      location = Views.getLocationOnScreen(target);
      return sourceX - location[0];
    }

    public float getAddY (ForceTouchView target) {
      int[] location = Views.getLocationOnScreen(sourceView);
      int sourceY = location[1];
      location = Views.getLocationOnScreen(target);
      return sourceY - location[1];
      /*int y = 0;
      if (offsetView != null) {
        y += offsetView.getTop();
        y += (int) offsetView.getTranslationY();
      }
      if (sourceView != null) {
        y += sourceView.getTop();
      }
      for (View view : additionalOffsetViews) {
        y += view.getTop();
      }
      if (!excludeHeader) {
        y += HeaderView.getSize(true);
      }
      return y;*/
    }

    public boolean hasFooter () {
      return actionItems != null && actionItems.size() > 0;
    }

    public boolean hasHeader () {
      return needHeader;
    }

    public int getMinimumWidth () {
      return hasFooter() ? (actionItems.size() > 1 ? actionItems.size() + 1 : actionItems.size()) * Screen.dp(48f) : 0;
    }

    public void setButtons (ActionListener listener, Object listenerArg, ArrayList<BaseView.ActionItem> items) {
      this.actionListener = listener;
      if (this.listenerArg == null) { // FIXME code design
        this.listenerArg = listenerArg;
      }
      this.actionItems = items;
    }

    public void setButtons (ActionListener listener, Object listenerArg, int[] ids, int[] icons, String[] hints) {
      ArrayList<BaseView.ActionItem> items = new ArrayList<>(ids.length);
      for (int i = 0; i < ids.length; i++) {
        items.add(new BaseView.ActionItem(ids[i], icons[i], hints[i]));
      }
      setButtons(listener, listenerArg, items);
    }

    public MaximizeListener getMaximizeListener () {
      return maximizeListener;
    }

    public ForceTouchContext setMaximizeListener (MaximizeListener maximizeListener) {
      this.maximizeListener = maximizeListener;
      return this;
    }

    public void setHeader (String title, String subtitle) {
      this.needHeader = true;
      this.title = title;
      this.subtitle = subtitle;
    }

    public void setBoundChatId (long chatId, @Nullable ThreadInfo messageThread) {
      this.needHeader = true;
      this.needHeaderAvatar = true;
      this.boundDataType = DataType.CHAT;
      this.boundDataId = chatId;
      this.boundArg1 = messageThread;
    }

    public void setBoundUserId (long userId) {
      this.needHeader = userId != 0;
      this.needHeaderAvatar = true;
      this.boundDataType = DataType.USER;
      this.boundDataId = userId;
      this.boundArg1 = 0;
    }

    public void setBoundAccountId (int accountId) {
      this.needHeader = accountId != TdlibAccount.NO_ID;
      this.needHeaderAvatar = true;
      this.boundDataType = DataType.ACCOUNT;
      this.boundDataId = accountId;
      this.boundArg1 = 0;
    }

    public void setHeaderAvatar (TdApi.MessageSender avatarSender, AvatarPlaceholder.Metadata avatarPlaceholder) {
      this.needHeaderAvatar = true;
      this.avatarSender = avatarSender;
      this.avatarPlaceholder = avatarPlaceholder;
    }

    public ForceTouchContext setStateListener (StateListener stateListener) {
      this.stateListener = stateListener;
      return this;
    }

    public Object getStateListenerArgument () {
      return listenerArg;
    }

    public ForceTouchContext setStateListenerArgument (Object arg) {
      this.listenerArg = arg;
      return this;
    }

    public ForceTouchContext setAllowFullscreen (boolean allowFullscreen) {
      this.allowFullscreen = (!Device.DISABLE_FULLSCREEN_PREVIEW || (boundController != null && boundController.context().isKeyboardVisible())) && allowFullscreen;
      return this;
    }

    public ForceTouchContext setIsMatchParent (boolean isMatchParent) {
      this.isMatchParent = isMatchParent;
      return this;
    }

    public boolean allowFullscreen () {
      return allowFullscreen;
    }

    public boolean needHideKeyboard () {
      return !allowFullscreen && (boundController == null || boundController.wouldHideKeyboardInForceTouchMode());
    }
  }

  // Header

  private @DataType int boundDataType;
  private TdApi.User boundUser;
  private TdApi.Chat boundChat;
  private TdlibAccount boundAccount;
  private ThreadInfo boundMessageThread;

  private void setupUser (long userId, ComplexHeaderView headerView) {
    TdApi.User user = tdlib.cache().user(userId);
    if (user == null) {
      throw new NullPointerException();
    }

    this.boundDataType = DataType.USER;
    this.boundUser = user;
    addUserListeners(user.id, true);

    setHeaderUser(user);
  }

  private void setupAccount (int accountId, ComplexHeaderView headerView) {
    TdlibAccount account = TdlibManager.instanceForAccountId(accountId).account(accountId);
    TdApi.User user = account.getUser();
    if (user == null) {
      this.boundDataType = DataType.ACCOUNT;
      this.boundAccount = account;
      addUserListeners(account.getKnownUserId(), true);
      setHeaderUser(account.getDisplayInformation());
    } else {
      this.boundDataType = DataType.USER;
      this.boundUser = user;
      addUserListeners(user.id, true);
      setHeaderUser(user);
    }

  }

  private void setHeaderUser (DisplayInformation displayInformation) {
    headerView.setShowVerify(displayInformation.isVerified());
    headerView.setText(TD.getUserName(displayInformation.getFirstName(), displayInformation.getLastName()), "");
  }

  private void setHeaderUser (TdApi.User user) {
    headerView.setShowVerify(Td.isVerified(user));
    headerView.setShowScam(Td.isScam(user));
    headerView.setShowFake(Td.isFake(user));
    headerView.setEmojiStatus(user);
    headerView.setText(TD.getUserName(user), tdlib.status().getPrivateChatSubtitle(user.id, user, false));
    setChatAvatar();
  }

  private void setupChat (long chatId, @Nullable ThreadInfo messageThread, ComplexHeaderView headerView) {
    TdApi.Chat chat = tdlib.chatSync(chatId);
    if (chat == null) {
      throw new NullPointerException();
    }

    this.boundDataType = DataType.CHAT;
    this.boundChat = chat;
    this.boundMessageThread = messageThread;
    addChatListeners(chat, messageThread, true);

    headerView.setShowLock(ChatId.isSecret(chatId));
    headerView.setShowVerify(tdlib.chatVerified(chat));
    headerView.setShowScam(tdlib.chatScam(chat));
    headerView.setShowFake(tdlib.chatFake(chat));
    headerView.setShowMute(tdlib.chatNeedsMuteIcon(chat));
    headerView.setEmojiStatus(tdlib.chatUser(chat));
    if (messageThread != null) {
      headerView.setText(messageThread.chatHeaderTitle(), messageThread.chatHeaderSubtitle());
    } else {
      headerView.setText(tdlib.chatTitle(chat), tdlib.status().chatStatus(chat));
    }
    setChatAvatar();
  }

  private void setChatAvatar () {
    if (!isDestroyed) {
      switch (boundDataType) {
        case DataType.CHAT: {
          if (boundChat != null) {
            headerView.getAvatarReceiver().requestChat(tdlib, boundChat.id, AvatarReceiver.Options.NONE);
          }
          break;
        }
        case DataType.USER: {
          if (boundUser != null) {
            headerView.getAvatarReceiver().requestUser(tdlib, boundUser.id, AvatarReceiver.Options.NONE);
          }
          break;
        }
        case DataType.ACCOUNT: {
          if (boundAccount != null) {
            headerView.getAvatarReceiver().requestAccount(tdlib, boundAccount.id, AvatarReceiver.Options.NONE);
          }
          break;
        }
      }
    }
  }

  private void setChatTitle () {
    if (!isDestroyed && boundChat != null) {
      headerView.setTitle(tdlib.chatTitle(boundChat));
    }
  }

  private void setChatSubtitle () {
    if (!isDestroyed) {
      if (boundChat != null) {
        if (boundMessageThread != null) {
          headerView.setSubtitle(boundMessageThread.chatHeaderSubtitle());
        } else {
          headerView.setSubtitle(tdlib.status().chatStatus(boundChat));
        }
      }
      if (boundUser != null) {
        headerView.setSubtitle(tdlib.status().getPrivateChatSubtitle(boundUser.id, boundUser, false));
      }
    }
  }

  private void setChatMute () {
    if (!UI.inUiThread()) {
      tdlib.ui().post(this::setChatMute);
      return;
    }
    if (!isDestroyed && boundChat != null) {
      headerView.setShowMute(tdlib.chatNeedsMuteIcon(boundChat.id));
    }
  }

  private void removeListeners () {
    if (boundChat != null) {
      addChatListeners(boundChat, boundMessageThread, false);
      boundChat = null;
    }
    if (boundUser != null) {
      addUserListeners(boundUser.id, false);
      boundUser = null;
    }
    if (boundAccount != null) {
      addUserListeners(boundAccount.getKnownUserId(), false);
      boundAccount = null;
    }
  }

  private void addUserListeners (long userId, boolean add) {
    if (add) {
      tdlib.cache().subscribeToUserUpdates(userId, this);
    } else {
      tdlib.cache().unsubscribeFromUserUpdates(userId, this);
    }
  }

  private void addChatListeners (TdApi.Chat chat, @Nullable ThreadInfo messageThread, boolean add) {
    if (add) {
      tdlib.listeners().subscribeToChatUpdates(chat.id, this);
      tdlib.listeners().subscribeToSettingsUpdates(chat.id, this);
      if (messageThread == null || chat.id == messageThread.getChatId()) {
        headerView.attachChatStatus(chat.id, messageThread != null ? messageThread.getMessageTopicId() : null);
      }
      if (messageThread != null) {
        messageThread.addListener(this);
      }
      // tdlib.status().subscribeForChatUpdates(chat.id, this);
    } else {
      tdlib.listeners().unsubscribeFromChatUpdates(chat.id, this);
      tdlib.listeners().unsubscribeFromSettingsUpdates(chat.id, this);
      headerView.removeChatStatus();
      if (messageThread != null) {
        messageThread.removeListener(this);
      }
      // tdlib.status().unsubscribeFromChatUpdates(chat.id, this);
    }
    switch (chat.type.getConstructor()) {
      case TdApi.ChatTypePrivate.CONSTRUCTOR:
      case TdApi.ChatTypeSecret.CONSTRUCTOR: {
        if (add) {
          tdlib.cache().subscribeToUserUpdates(TD.getUserId(chat.type), this);
        } else {
          tdlib.cache().unsubscribeFromUserUpdates(TD.getUserId(chat.type), this);
        }
        break;
      }
      case TdApi.ChatTypeSupergroup.CONSTRUCTOR: {
        if (add) {
          tdlib.cache().subscribeToSupergroupUpdates(ChatId.toSupergroupId(chat.id), this);
        } else {
          tdlib.cache().unsubscribeFromSupergroupUpdates(ChatId.toSupergroupId(chat.id), this);
        }
        break;
      }
      case TdApi.ChatTypeBasicGroup.CONSTRUCTOR: {
        if (add) {
          tdlib.cache().subscribeToGroupUpdates(ChatId.toBasicGroupId(chat.id), this);
        } else {
          tdlib.cache().unsubscribeFromGroupUpdates(ChatId.toBasicGroupId(chat.id), this);
        }
        break;
      }
    }
  }

  @Override
  public void onUserUpdated (TdApi.User user) {
    switch (boundDataType) {
      case DataType.CHAT:
        break;
      case DataType.USER:
      case DataType.ACCOUNT:
        setHeaderUser(user);
        break;
    }
  }

  @Override
  public boolean needUserStatusUiUpdates () {
    return true;
  }

  @UiThread
  @Override
  public void onUserStatusChanged (long userId, TdApi.UserStatus status, boolean uiOnly) {
    switch (boundDataType) {
      case DataType.CHAT:
      case DataType.USER:
      case DataType.ACCOUNT:
        setChatSubtitle();
        break;
    }
  }

  @Override
  public void onBasicGroupUpdated (TdApi.BasicGroup basicGroup, boolean migratedToSupergroup) {
    tdlib.ui().post(this::setChatSubtitle);
  }

  @Override
  public void onBasicGroupFullUpdated (long basicGroupId, TdApi.BasicGroupFullInfo basicGroupFull) {
    tdlib.ui().post(this::setChatSubtitle);
  }

  @Override
  public void onChatOnlineMemberCountChanged (long chatId, int onlineMemberCount) {
    tdlib.ui().post(this::setChatSubtitle);
  }

  @Override
  public void onSupergroupUpdated (TdApi.Supergroup supergroup) {
    tdlib.ui().post(this::setChatSubtitle);
  }

  @Override
  public void onSupergroupFullUpdated (long supergroupId, TdApi.SupergroupFullInfo newSupergroupFull) {
    tdlib.ui().post(this::setChatSubtitle);
  }

  @Override
  public void onChatTitleChanged (long chatId, String title) {
    tdlib.ui().post(this::setChatTitle);
  }

  @Override
  public void onChatMarkedAsUnread (long chatId, boolean isMarkedAsUnread) { }

  @Override
  public void onChatPhotoChanged (long chatId, @Nullable TdApi.ChatPhotoInfo photo) {
    tdlib.ui().post(this::setChatAvatar);
  }

  @Override
  public void onChatDefaultDisableNotifications (long chatId, boolean defaultDisableNotifications) { }

  @Override
  public void onNotificationSettingsChanged (TdApi.NotificationSettingsScope scope, TdApi.ScopeNotificationSettings settings) {
    setChatMute();
  }

  @Override
  public void onNotificationSettingsChanged (long chatId, TdApi.ChatNotificationSettings settings) {
    setChatMute();
  }

  @Override
  public void onMessageThreadReplyCountChanged (long chatId, TdApi.MessageTopic topicId, int replyCount) {
    setChatSubtitle();
  }
}
