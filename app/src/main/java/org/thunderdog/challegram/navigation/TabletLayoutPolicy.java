package org.thunderdog.challegram.navigation;

/** Window-size policy shared by the navigation layout and its JVM tests. */
public final class TabletLayoutPolicy {
  private TabletLayoutPolicy () { }

  public static int chatListWidth (int width, float density, int smallestScreenWidthDp) {
    if (width <= 0 || density <= 0f || smallestScreenWidthDp < 600 || width / density < 700f) {
      return 0;
    }
    int preferredWidth = Math.max(Math.round(300f * density), Math.round(width * .35f));
    return Math.min(preferredWidth, Math.min(Math.round(420f * density), width - Math.round(400f * density)));
  }
}
