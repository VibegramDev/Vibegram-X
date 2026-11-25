/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.util;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.ExportException;

/** Playback diagnostics that depend on the Media3 version of the flavor. */
public final class MediaDiagnostics {
  private MediaDiagnostics () { }

  public static String exportCodecSuffix (ExportException error) {
    ExportException.CodecInfo info = error.codecInfo;
    return info == null ? "" : ", " + (info.isVideo ? "video " : "audio ") + (info.isDecoder ? "decoder " : "encoder ") + info.name;
  }

  public static boolean isReleased (ExoPlayer player) {
    return player.isReleased();
  }
}
