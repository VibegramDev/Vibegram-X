package org.thunderdog.challegram.component.chat;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.annotation.DrawableRes;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.widget.CircleButton;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;

/**
 * Floating M3 square icon button with a counter badge (chat search «previous/next result», attach camera).
 * The container is a rounded square ({@link CircleButton#initSquare}) inside a {@code padding} margin that keeps room
 * for the elevation shadow; the counter sits in the top-right corner of the button, inside its bounds.
 */
public class CircleCounterBadgeView extends FrameLayout implements FactorAnimator.Target {
  private static final float DISABLED_BUTTON_ALPHA = .4f;

  public static final int BUTTON_WRAPPER_WIDTH = Screen.dp(118);
  public static final int PADDING = Screen.dp(4);

  /** Room above the button inside the wrapper (same as the chat scroll-to-bottom button wrapper). */
  private static final float WRAPPER_EXTRA_HEIGHT = 18f;

  public static final int ANIMATOR_ENABLED = 0;

  private final CircleButton circleButton;
  private final CounterBadgeView counterBadgeView;
  private final BoolAnimator animatorEnabled = new BoolAnimator(ANIMATOR_ENABLED, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 200l);

  public CircleCounterBadgeView (ViewController<?> controller, int id, View.OnClickListener onClickListener, View.OnLongClickListener onLongClickListener) {
    super(controller.context());
    Context context = controller.context();

    RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(BUTTON_WRAPPER_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
    params.addRule(RelativeLayout.ABOVE, R.id.msg_bottom);
    params.rightMargin = params.bottomMargin = Screen.dp(16f) - PADDING;
    setLayoutParams(params);

    counterBadgeView = new CounterBadgeView(context);
    counterBadgeView.setLayoutParams(FrameLayoutFix.newParams(0, 0, Gravity.RIGHT | Gravity.BOTTOM));

    circleButton = new CircleButton(context);
    circleButton.setId(id);
    if (onClickListener != null) {
      circleButton.setOnClickListener(onClickListener);
    }
    if (onLongClickListener != null) {
      circleButton.setOnLongClickListener(onLongClickListener);
    }
    circleButton.setTag(counterBadgeView);
    circleButton.setLayoutParams(FrameLayoutFix.newParams(0, 0, Gravity.RIGHT | Gravity.BOTTOM));

    controller.addThemeInvalidateListener(counterBadgeView);
    controller.addThemeInvalidateListener(circleButton);

    addView(circleButton);
    addView(counterBadgeView);

    setEnabled(true, false);
  }

  /**
   * M3 icon button corner radius of the square shape for the given container size
   * (icon-buttons › Button corner radius › Square button: XS 12dp, S 12dp, M 16dp, L 28dp, XL 28dp).
   */
  private static float getSquareCornerRadius (float size) {
    if (size >= 96f) {
      return 28f;
    }
    if (size >= 56f) {
      return 16f;
    }
    return 12f;
  }

  public void init (@DrawableRes int icon, float size, float padding, @ColorId int backgroundColorId, @ColorId int iconColorId) {
    init(icon, size, getSquareCornerRadius(size), padding, backgroundColorId, iconColorId);
  }

  public void init (@DrawableRes int icon, float size, float cornerRadius, float padding, @ColorId int backgroundColorId, @ColorId int iconColorId) {
    final int buttonSize = Screen.dp(size) + Screen.dp(padding) * 2;
    ViewGroup.LayoutParams params = circleButton.getLayoutParams();
    params.width = params.height = buttonSize;
    circleButton.setLayoutParams(params);
    params = counterBadgeView.getLayoutParams();
    params.width = params.height = buttonSize;
    counterBadgeView.setLayoutParams(params);
    params = getLayoutParams();
    if (params != null) {
      params.height = buttonSize + Screen.dp(WRAPPER_EXTRA_HEIGHT);
      setLayoutParams(params);
    }
    circleButton.initSquare(icon, cornerRadius, padding, backgroundColorId, iconColorId);
  }

  public void setInProgress (boolean inProgress) {
    circleButton.setInProgress(inProgress);
  }

  public void setEnabled (boolean enabled, boolean animated) {
    animatorEnabled.setValue(enabled, animated);
  }

  public boolean getEnabled () {
    return animatorEnabled.getValue();
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    switch (id) {
      case ANIMATOR_ENABLED: {
        circleButton.setIconAlpha(DISABLED_BUTTON_ALPHA + (1 - DISABLED_BUTTON_ALPHA) * factor);
        break;
      }
    }
  }
}
