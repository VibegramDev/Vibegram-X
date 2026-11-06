/*
 * This file is a part of Vibegram X (based on Telegram X)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.component.chat;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import me.vkryl.core.StringUtils;

/**
 * Drag & drop of files from other apps into a chat (split screen, freeform windows, DeX, Chromebooks).
 *
 * Android only allows cross-app drag & drop since API 24 (Nougat), and reading the dropped content:// URIs
 * needs {@link Activity#requestDragAndDropPermissions(DragEvent)} (API 24), so the target is installed only
 * there; older devices keep the current behaviour.
 *
 * The listener sits on the chat layout and on the composer (TextView accepts every drag by default and would
 * paste a dropped file as text). Dropped text is inserted into the composer. Dropped items are copied into the app cache (the URI grant ends with the drop),
 * then handed to {@link Callback}: nothing is sent without the user confirming it in the preview / dialog.
 */
@TargetApi(Build.VERSION_CODES.N)
public final class ChatDropTarget implements View.OnDragListener {
  public interface Callback {
    /** Whether the chat can take a drop right now (focused, composer visible, not a preview). */
    boolean canAcceptDrop ();
    /** Files were copied (main thread). Mime types are parallel to paths. */
    void onFilesDropped (List<String> paths, List<String> mimeTypes);
    /** A text-only drag was dropped outside the composer. */
    void onTextDropped (CharSequence text);
  }

  private static final String DROP_DIR = "dropped";
  private static final long KEEP_MS = 24L * 60L * 60L * 1000L;

  public static @Nullable ChatDropTarget attach (ViewGroup container, @Nullable View composer, Callback callback) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return null;
    }
    ChatDropTarget target = new ChatDropTarget(container, callback);
    container.setOnDragListener(target);
    if (composer != null) {
      // TextView accepts every drag and would paste a dropped file as its URI / contents.
      composer.setOnDragListener(target);
    }
    return target;
  }

  private final ViewGroup container;
  private final Callback callback;
  private @Nullable Overlay overlay;
  private boolean dragActive;
  private int hoverCount;

  private ChatDropTarget (ViewGroup container, Callback callback) {
    this.container = container;
    this.callback = callback;
  }

  @Override
  public boolean onDrag (View v, DragEvent event) {
    switch (event.getAction()) {
      case DragEvent.ACTION_DRAG_STARTED: {
        if (!callback.canAcceptDrop()) {
          return false;
        }
        // A dragged .txt file and dragged text both arrive as text/plain: the kind is known only on drop.
        if (!dragActive) {
          dragActive = true;
          hoverCount = 0;
          showOverlay(true);
        }
        return true;
      }
      case DragEvent.ACTION_DRAG_ENTERED: {
        hoverCount++;
        updateHover();
        return true;
      }
      case DragEvent.ACTION_DRAG_EXITED: {
        hoverCount = Math.max(0, hoverCount - 1);
        updateHover();
        return true;
      }
      case DragEvent.ACTION_DRAG_LOCATION:
        return true;
      case DragEvent.ACTION_DROP: {
        hideOverlay();
        return handleDrop(event);
      }
      case DragEvent.ACTION_DRAG_ENDED: {
        hideOverlay();
        return true;
      }
    }
    return false;
  }

  private void updateHover () {
    if (overlay != null) {
      overlay.setDropHovered(hoverCount > 0);
    }
  }

  private void showOverlay (boolean animated) {
    if (overlay == null) {
      overlay = new Overlay(container.getContext());
    }
    if (overlay.getParent() == null) {
      container.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    } else {
      overlay.bringToFront();
    }
    overlay.animate().cancel();
    overlay.setAlpha(animated ? 0f : 1f);
    overlay.setDropHovered(false);
    overlay.animate().alpha(1f).setDuration(150L).start();
  }

  private void hideOverlay () {
    dragActive = false;
    hoverCount = 0;
    Overlay overlay = this.overlay;
    if (overlay != null && overlay.getParent() != null) {
      overlay.animate().cancel();
      overlay.animate().alpha(0f).setDuration(150L).withEndAction(() -> {
        if (!dragActive && overlay.getParent() != null) {
          ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
      }).start();
    }
  }

  private boolean handleDrop (DragEvent event) {
    ClipData clip = event.getClipData();
    if (clip == null || clip.getItemCount() == 0) {
      return false;
    }
    List<Uri> uris = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < clip.getItemCount(); i++) {
      ClipData.Item item = clip.getItemAt(i);
      if (item.getUri() != null) {
        uris.add(item.getUri());
      } else if (item.getText() != null) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(item.getText());
      }
    }
    if (uris.isEmpty()) {
      if (text.length() == 0) {
        return false;
      }
      callback.onTextDropped(text);
      return true;
    }
    Activity activity = UI.getContext(container.getContext());
    final DragAndDropPermissions permissions = activity != null ? activity.requestDragAndDropPermissions(event) : null;
    final ContentResolver resolver = container.getContext().getContentResolver();
    final File cacheDir = container.getContext().getCacheDir();
    new Thread(() -> {
      List<String> paths = new ArrayList<>();
      List<String> mimeTypes = new ArrayList<>();
      try {
        File root = new File(cacheDir, DROP_DIR);
        cleanup(root);
        File dir = new File(root, Long.toString(System.currentTimeMillis()));
        if (dir.isDirectory() || dir.mkdirs()) {
          for (Uri uri : uris) {
            copy(resolver, uri, dir, paths, mimeTypes);
          }
        }
      } finally {
        if (permissions != null) {
          permissions.release();
        }
      }
      UI.post(() -> {
        if (paths.isEmpty()) {
          UI.showToast(R.string.DropReadError, android.widget.Toast.LENGTH_SHORT);
        } else {
          callback.onFilesDropped(paths, mimeTypes);
        }
      });
    }, "ChatDrop").start();
    return true;
  }

  private static void copy (ContentResolver resolver, Uri uri, File dir, List<String> paths, List<String> mimeTypes) {
    String name = null;
    if ("file".equals(uri.getScheme())) {
      name = uri.getLastPathSegment();
    } else {
      try (Cursor c = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
        if (c != null && c.moveToFirst()) {
          name = c.getString(0);
        }
      } catch (Throwable ignored) { }
    }
    String mime = null;
    try {
      mime = resolver.getType(uri);
    } catch (Throwable ignored) { }
    if (StringUtils.isEmpty(name)) {
      String ext = mime != null ? MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) : null;
      name = "file_" + (paths.size() + 1) + (ext != null ? "." + ext : "");
    }
    name = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
    if (StringUtils.isEmpty(mime)) {
      int dot = name.lastIndexOf('.');
      mime = dot != -1 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase()) : null;
    }
    File out = new File(dir, name);
    for (int i = 1; out.exists(); i++) {
      out = new File(dir, i + "_" + name);
    }
    try (InputStream in = resolver.openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
      if (in == null) {
        return;
      }
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        os.write(buffer, 0, read);
      }
    } catch (Throwable t) {
      //noinspection ResultOfMethodCallIgnored
      out.delete();
      return;
    }
    paths.add(out.getAbsolutePath());
    mimeTypes.add(mime != null ? mime : "application/octet-stream");
  }

  /** Copies are kept for a day: TDLib uploads from the path after the message is sent. */
  private static void cleanup (File root) {
    File[] dirs = root.listFiles();
    if (dirs == null) {
      return;
    }
    long now = System.currentTimeMillis();
    for (File dir : dirs) {
      if (now - dir.lastModified() > KEEP_MS) {
        File[] files = dir.listFiles();
        if (files != null) {
          for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
          }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
      }
    }
  }

  /** Full-chat drop zone: scrim + M3 primary-container card (CornerExtraLarge) with an icon and a one-word label. */
  private static final class Overlay extends View {
    private final RectF rect = new RectF();
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;
    private boolean hovered;
    private @Nullable StaticLayout titleLayout;
    private int layoutWidth;

    Overlay (Context context) {
      super(context);
      titlePaint.setTypeface(Fonts.getRobotoMedium());
      titlePaint.setTextSize(Screen.sp(22f));
      strokePaint.setStyle(Paint.Style.STROKE);
      strokePaint.setStrokeWidth(Screen.dp(2f));
      icon = Drawables.get(context.getResources(), R.drawable.vg_drop_attach_48);
    }

    void setDropHovered (boolean hovered) {
      if (this.hovered != hovered) {
        this.hovered = hovered;
        invalidate();
      }
    }

    private void buildLayouts (int width) {
      if (layoutWidth == width && titleLayout != null) {
        return;
      }
      layoutWidth = width;
      titleLayout = new StaticLayout(Lang.getString(R.string.DropToAttach), titlePaint, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
    }

    @Override
    protected void onDraw (Canvas c) {
      int width = getWidth(), height = getHeight();
      int surface = M3.surface();
      c.drawColor((surface & 0x00ffffff) | 0xd9000000);

      float inset = Screen.dp(16f);
      rect.set(inset, inset, width - inset, height - inset);
      float radius = Screen.dp(M3.CORNER_EXTRA_LARGE);
      int container = M3.primaryContainer();
      int content = M3.onPrimaryContainer();
      c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(hovered ? container : ((container & 0x00ffffff) | 0xb3000000)));
      strokePaint.setColor(hovered ? M3.primary() : M3.outline());
      c.drawRoundRect(rect, radius, radius, strokePaint);

      int textWidth = (int) Math.max(0, rect.width() - Screen.dp(48f));
      buildLayouts(textWidth);
      int iconSize = Screen.dp(72f);
      int gap = Screen.dp(16f);
      int blockHeight = iconSize + gap + (titleLayout != null ? titleLayout.getHeight() : 0);
      float top = rect.centerY() - blockHeight / 2f;

      if (icon != null) {
        int left = (int) (rect.centerX() - iconSize / 2f);
        icon.setBounds(left, (int) top, left + iconSize, (int) top + iconSize);
        icon.setColorFilter(Paints.createColorFilter(content));
        icon.draw(c);
      }
      float textLeft = rect.centerX() - textWidth / 2f;
      float y = top + iconSize + gap;
      if (titleLayout != null) {
        titlePaint.setColor(content);
        c.save();
        c.translate(textLeft, y);
        titleLayout.draw(c);
        c.restore();
      }
    }
  }
}
