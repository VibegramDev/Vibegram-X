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
package org.thunderdog.challegram.webapp;

import android.Manifest;
import android.os.Build;
import android.webkit.PermissionRequest;

import androidx.annotation.RequiresApi;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.navigation.ViewController;

/** Camera and microphone requests of mini app pages. */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
final class WebAppPermissions {
  private WebAppPermissions () { }

  static void onPermissionRequest (
    ViewController<?> context,
    String botName,
    PermissionRequest request
  ) {
    final String[] resources = request.getResources();
    boolean audio = false, video = false, other = false;
    for (String resource : resources) {
      if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
        audio = true;
      } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
        video = true;
      } else {
        other = true;
      }
    }
    if (other || (!audio && !video)) {
      request.deny();
      return;
    }
    final String[] permissions;
    final int textRes;
    if (audio && video) {
      permissions = new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
      textRes = R.string.vg_BotWebViewRequestCameraMicPermission;
    } else if (video) {
      permissions = new String[] {Manifest.permission.CAMERA};
      textRes = R.string.vg_BotWebViewRequestCameraPermission;
    } else {
      permissions = new String[] {Manifest.permission.RECORD_AUDIO};
      textRes = R.string.vg_BotWebViewRequestMicrophonePermission;
    }
    WebApp.askPermission(context, botName, textRes, permissions, granted -> {
      if (granted) {
        request.grant(resources);
      } else {
        request.deny();
      }
    });
  }
}
