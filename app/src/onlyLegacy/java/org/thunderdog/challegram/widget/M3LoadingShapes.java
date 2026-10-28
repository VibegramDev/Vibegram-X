/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.widget;

/** Legacy flavor (minSdk 16): androidx.graphics.shapes needs API 21, the M3 loading indicator falls back to the spinner. */
public final class M3LoadingShapes {
  private M3LoadingShapes () { }

  public static M3LoadingIndicator.Shapes create () {
    return null;
  }
}
