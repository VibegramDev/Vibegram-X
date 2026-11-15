/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;

import me.vkryl.core.ColorUtils;

/** Round preview: active = the round is sent without sound. Looks like {@link RecordDisposableSwitchButton}. */
public class RecordSoundSwitchButton extends RecordControllerButton {
  private final Drawable soundOn, soundOff;

  public RecordSoundSwitchButton (Context context) {
    super(context);
    soundOn = Drawables.get(context.getResources(), R.drawable.baseline_volume_up_24);
    soundOff = Drawables.get(context.getResources(), R.drawable.deproko_baseline_sound_muted_24);
  }

  @Override
  protected void dispatchDraw (@NonNull Canvas canvas) {
    super.dispatchDraw(canvas);
    final float cx = getMeasuredWidth() / 2f;
    final float cy = getMeasuredHeight() / 2f;
    final float active = getActiveFactor();
    final int color = ColorUtils.fromToArgb(Theme.getColor(ColorId.icon), Theme.getColor(ColorId.fillingPositiveContent), active);
    if (active < 1f) {
      Drawables.drawCentered(canvas, soundOn, cx, cy, Paints.getPorterDuffPaint(ColorUtils.alphaColor(1f - active, color)));
    }
    if (active > 0f) {
      Drawables.drawCentered(canvas, soundOff, cx, cy, Paints.getPorterDuffPaint(ColorUtils.alphaColor(active, color)));
    }
  }
}
