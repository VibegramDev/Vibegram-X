/*
 * Vibegram X: Settings → Video messages. Everything about recording round videos in one place:
 * camera API (Camera2 by default, Camera1 as the fallback), the camera a recording starts with,
 * video size, frame rate and bitrate (the maximum the phone can do by default), and playback to the earpiece.
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.SettingsWrapBuilder;
import org.thunderdog.challegram.player.RoundVideoQuality;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;

public class SettingsRoundVideoController extends RecyclerViewController<Void> implements View.OnClickListener {
  public SettingsRoundVideoController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgRoundVideo;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.vg_RoundTitle);
  }

  private SettingsAdapter adapter;

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        final int id = item.getId();
        if (id == R.id.btn_vgRoundCameraApi) {
          view.setData(getCameraApiValue());
        } else if (id == R.id.btn_vgRoundStartCamera) {
          view.setData(getStartCameraName(Settings.instance().getRoundStartCamera()));
        } else if (id == R.id.btn_vgRoundSide) {
          view.setData(getSideName(Settings.instance().getRoundVideoSide()));
        } else if (id == R.id.btn_vgRoundFps) {
          view.setData(getFpsName(Settings.instance().getRoundVideoFps()));
        } else if (id == R.id.btn_vgRoundBitrate) {
          view.setData(getBitrateName(Settings.instance().getRoundVideoBitrate()));
        } else if (id == R.id.btn_vgRoundLockLens) {
          view.getToggler().setRadioEnabled(Settings.instance().isRoundLensLocked(), isUpdate);
        } else if (id == R.id.btn_vgRoundShowQualityInfo) {
          view.getToggler().setRadioEnabled(Settings.instance().showRoundQualityInfo(), isUpdate);
        } else if (id == R.id.btn_vgRoundEarpiece || id == R.id.btn_vgRoundEarpieceVoice) {
          switch (Settings.instance().getEarpieceMode(id == R.id.btn_vgRoundEarpiece)) {
            case Settings.EARPIECE_MODE_ALWAYS:
              view.setData(R.string.EarpieceModeAlways);
              break;
            case Settings.EARPIECE_MODE_PROXIMITY:
              view.setData(R.string.EarpieceModeProximity);
              break;
            case Settings.EARPIECE_MODE_NEVER:
            default:
              view.setData(R.string.EarpieceModeNever);
              break;
          }
        }
      }
    };

    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.vg_RoundCameraHeader));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgRoundCameraApi, 0, R.string.vg_RoundCameraApi));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgRoundStartCamera, 0, R.string.vg_RoundStartCamera));
    if (isLensLockAvailable()) {
      items.add(newLensLockSeparator());
      items.add(newLensLockItem());
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    if (isLensLockAvailable()) {
      items.add(newLensLockDescription());
    }

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.vg_RoundQualityHeader));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgRoundSide, 0, R.string.vg_RoundSide));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgRoundFps, 0, R.string.vg_RoundFps));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgRoundBitrate, 0, R.string.vg_RoundBitrate));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, R.id.btn_vgRoundQualityInfo, 0, buildQualityInfo(), false));

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.vg_RoundPlaybackHeader));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgRoundEarpieceVoice, 0, R.string.VoiceMessages));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgRoundEarpiece, 0, R.string.VideoMessages));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.vg_RoundDiagHeader));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_vgRoundShowQualityInfo, 0, R.string.vg_RoundShowQualityInfo));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_vgRoundDiagnostics, R.drawable.baseline_bug_report_24, R.string.vg_RoundDiagTitle));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.vg_RoundDiagHint));

    adapter.setItems(items, false);
    adapter.setSegmented();
    recyclerView.setAdapter(adapter);
  }

  // Values

  private static boolean isCamera2Available () {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
  }

  // Lens lock: Camera2 only (Camera1 has no separate lenses), so hidden while Camera1 records

  private static boolean isLensLockAvailable () {
    Settings settings = Settings.instance();
    return isCamera2Available() && settings.getRoundCameraApi() == Settings.ROUND_CAMERA_API_CAMERA2 && !settings.isRoundCamera2Failed();
  }

  private static ListItem newLensLockSeparator () {
    return new ListItem(ListItem.TYPE_SEPARATOR_FULL, R.id.btn_vgRoundLockLensSeparator);
  }

  private static ListItem newLensLockItem () {
    return new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_vgRoundLockLens, 0, R.string.vg_RoundLockLens);
  }

  private static ListItem newLensLockDescription () {
    return new ListItem(ListItem.TYPE_DESCRIPTION, R.id.btn_vgRoundLockLensDescription, 0, R.string.vg_RoundLockLensDescription);
  }

  private void updateLensLockVisibility () {
    boolean available = isLensLockAvailable();
    int index = adapter.indexOfViewById(R.id.btn_vgRoundLockLens);
    if (available == (index != -1)) {
      return;
    }
    if (available) {
      int startIndex = adapter.indexOfViewById(R.id.btn_vgRoundStartCamera);
      // start camera, separator, lens lock, shadow, description
      adapter.addItems(startIndex + 1, newLensLockSeparator(), newLensLockItem());
      adapter.addItem(startIndex + 4, newLensLockDescription());
    } else {
      adapter.removeItemById(R.id.btn_vgRoundLockLensDescription);
      adapter.removeRange(index - 1, 2);
    }
  }

  private static String getCameraApiValue () {
    Settings settings = Settings.instance();
    if (!isCamera2Available()) {
      return Lang.getString(R.string.vg_RoundCameraApiCamera1Only);
    }
    if (settings.getRoundCameraApi() == Settings.ROUND_CAMERA_API_CAMERA1) {
      return Lang.getString(R.string.vg_RoundCameraApiCamera1);
    }
    if (settings.isRoundCamera2Failed()) {
      return Lang.getString(R.string.vg_RoundCameraApiCamera2Failed);
    }
    return Lang.getString(R.string.vg_RoundCameraApiCamera2);
  }

  private static @StringRes int getStartCameraName (int mode) {
    switch (mode) {
      case Settings.ROUND_START_CAMERA_REAR:
        return R.string.vg_RoundStartRear;
      case Settings.ROUND_START_CAMERA_LAST:
        return R.string.vg_RoundStartLast;
      case Settings.ROUND_START_CAMERA_FRONT:
      default:
        return R.string.vg_RoundStartFront;
    }
  }

  private static String getSideName (int side) {
    if (side == 0) {
      return Lang.getString(R.string.vg_RoundMax);
    }
    return Lang.getString(R.string.vg_RoundSideValue, side, side);
  }

  private static String getFpsName (int fps) {
    if (fps == 0) {
      return Lang.getString(R.string.vg_RoundMax);
    }
    return Lang.getString(R.string.vg_RoundFpsValue, fps);
  }

  private static String getBitrateName (int kbps) {
    if (kbps == 0) {
      return Lang.getString(R.string.vg_RoundBitrateMax);
    }
    return formatBitrate(kbps);
  }

  public static String formatBitrate (int kbps) {
    if (kbps >= 1000) {
      float mbps = kbps / 1000f;
      String value = mbps == Math.round(mbps) ? String.valueOf(Math.round(mbps)) :
        String.valueOf(Math.round(mbps * 10f) / 10f).replace('.', java.text.DecimalFormatSymbols.getInstance(Lang.locale()).getDecimalSeparator());
      return Lang.getString(R.string.vg_RoundMbps, value);
    }
    return Lang.getString(R.string.vg_RoundKbps, kbps);
  }

  /** "Your phone records at most …" plus the size of a minute at the chosen quality. */
  private static String buildQualityInfo () {
    RoundVideoQuality max = RoundVideoQuality.getMaxSupported();
    RoundVideoQuality current = RoundVideoQuality.resolve(Settings.instance());
    StringBuilder b = new StringBuilder(Lang.getString(R.string.vg_RoundQualityDescription));
    if (max != null) {
      b.append("\n\n").append(Lang.getString(R.string.vg_RoundDeviceMax, max.side, max.side, max.fps, formatBitrate(max.videoBitrate / 1000)));
    }
    if (current != null) {
      b.append("\n").append(Lang.getString(R.string.vg_RoundCurrent, current.side, current.side, current.fps, formatBitrate(current.videoBitrate / 1000)));
    }
    b.append("\n\n").append(Lang.getString(R.string.vg_RoundSizeLimit));
    return b.toString();
  }

  private void updateQualityInfo () {
    int index = adapter.indexOfViewById(R.id.btn_vgRoundQualityInfo);
    if (index != -1) {
      adapter.getItems().get(index).setString(buildQualityInfo());
      adapter.notifyItemChanged(index);
    }
  }

  // Clicks

  @Override
  public void onClick (View v) {
    final int id = v.getId();
    if (id == R.id.btn_vgRoundCameraApi) {
      showCameraApiOptions();
    } else if (id == R.id.btn_vgRoundStartCamera) {
      showStartCameraOptions();
    } else if (id == R.id.btn_vgRoundSide) {
      int[] values = RoundVideoQuality.SIDE_OPTIONS;
      showIntOptions(R.id.btn_vgRoundSide, values, false, Settings.instance().getRoundVideoSide(), side -> getSideName(side), value -> Settings.instance().setRoundVideoSide(value));
    } else if (id == R.id.btn_vgRoundFps) {
      showIntOptions(R.id.btn_vgRoundFps, RoundVideoQuality.FPS_OPTIONS, false, Settings.instance().getRoundVideoFps(), fps -> getFpsName(fps), value -> Settings.instance().setRoundVideoFps(value));
    } else if (id == R.id.btn_vgRoundBitrate) {
      showIntOptions(R.id.btn_vgRoundBitrate, RoundVideoQuality.SETTINGS_BITRATE_OPTIONS_KBPS, true, Settings.instance().getRoundVideoBitrate(), kbps -> getBitrateName(kbps), value -> Settings.instance().setRoundVideoBitrate(value));
    } else if (id == R.id.btn_vgRoundEarpiece) {
      showEarpieceOptions(true);
    } else if (id == R.id.btn_vgRoundEarpieceVoice) {
      showEarpieceOptions(false);
    } else if (id == R.id.btn_vgRoundLockLens) {
      Settings.instance().setRoundLensLocked(adapter.toggleView(v));
    } else if (id == R.id.btn_vgRoundShowQualityInfo) {
      Settings.instance().setShowRoundQualityInfo(adapter.toggleView(v));
    } else if (id == R.id.btn_vgRoundDiagnostics) {
      navigateTo(new SettingsRoundDiagnosticsController(context, tdlib));
    }
  }

  private interface IntName {
    String getName (int value);
  }

  private interface IntSetter {
    void set (int value);
  }

  /** Option ids of the radio lists: the radio group reports the id of the checked row. */
  private static final int[] OPTION_IDS = {
    R.id.btn_vgRoundOption0, R.id.btn_vgRoundOption1, R.id.btn_vgRoundOption2, R.id.btn_vgRoundOption3,
    R.id.btn_vgRoundOption4, R.id.btn_vgRoundOption5, R.id.btn_vgRoundOption6, R.id.btn_vgRoundOption7,
    R.id.btn_vgRoundOption8, R.id.btn_vgRoundOption9
  };

  /** Radio list with "Maximum" (0) first if withMax and then the given values; values above what the phone can do are marked. */
  private void showIntOptions (int settingId, int[] values, boolean withMax, int current, IntName name, IntSetter setter) {
    final int offset = withMax ? 1 : 0;
    int count = Math.min(values.length + offset, OPTION_IDS.length);
    int[] optionValues = new int[count];
    ListItem[] items = new ListItem[count];
    RoundVideoQuality max = RoundVideoQuality.getMaxSupported();
    for (int i = 0; i < count; i++) {
      int value = withMax && i == 0 ? 0 : values[i - offset];
      optionValues[i] = value;
      String text = name.getName(value);
      if (value != 0 && max != null && !isSupported(settingId, value, max)) {
        text = Lang.getString(R.string.vg_RoundUnsupported, text);
      }
      items[i] = new ListItem(ListItem.TYPE_RADIO_OPTION, OPTION_IDS[i], 0, text, settingId, current == value);
    }
    showSettings(new SettingsWrapBuilder(settingId).setRawItems(items).setAllowResize(false).setIntDelegate((id, result) -> {
      int checkedId = result.get(settingId);
      for (int i = 0; i < count; i++) {
        if (OPTION_IDS[i] == checkedId) {
          setter.set(optionValues[i]);
          break;
        }
      }
      adapter.updateValuedSettingById(settingId);
      updateQualityInfo();
    }));
  }

  private static boolean isSupported (int settingId, int value, RoundVideoQuality max) {
    if (settingId == R.id.btn_vgRoundSide) {
      return value <= max.side;
    } else if (settingId == R.id.btn_vgRoundFps) {
      return value <= max.fps;
    }
    // Bitrate: "Maximum" is a sensible cap for the size, not the encoder limit, so higher values are not marked
    return true;
  }

  private void showCameraApiOptions () {
    if (!isCamera2Available()) {
      return;
    }
    Settings settings = Settings.instance();
    boolean camera2 = settings.getRoundCameraApi() == Settings.ROUND_CAMERA_API_CAMERA2;
    showSettings(new SettingsWrapBuilder(R.id.btn_vgRoundCameraApi).setRawItems(new ListItem[] {
      new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_vgRoundCameraApi2, 0, R.string.vg_RoundCameraApiCamera2, R.id.btn_vgRoundCameraApi, camera2),
      new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_vgRoundCameraApi1, 0, R.string.vg_RoundCameraApiCamera1, R.id.btn_vgRoundCameraApi, !camera2),
    }).setAllowResize(false).addHeaderItem(Lang.getString(R.string.vg_RoundCameraApiHint)).setIntDelegate((id, result) -> {
      int resultId = result.get(R.id.btn_vgRoundCameraApi);
      settings.setRoundCameraApi(resultId == R.id.btn_vgRoundCameraApi1 ? Settings.ROUND_CAMERA_API_CAMERA1 : Settings.ROUND_CAMERA_API_CAMERA2);
      adapter.updateValuedSettingById(R.id.btn_vgRoundCameraApi);
      updateLensLockVisibility();
    }));
  }

  private void showStartCameraOptions () {
    int mode = Settings.instance().getRoundStartCamera();
    showSettings(new SettingsWrapBuilder(R.id.btn_vgRoundStartCamera).setRawItems(new ListItem[] {
      new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_vgRoundStartFront, 0, R.string.vg_RoundStartFront, R.id.btn_vgRoundStartCamera, mode == Settings.ROUND_START_CAMERA_FRONT),
      new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_vgRoundStartRear, 0, R.string.vg_RoundStartRear, R.id.btn_vgRoundStartCamera, mode == Settings.ROUND_START_CAMERA_REAR),
      new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_vgRoundStartLast, 0, R.string.vg_RoundStartLast, R.id.btn_vgRoundStartCamera, mode == Settings.ROUND_START_CAMERA_LAST),
    }).setAllowResize(false).setIntDelegate((id, result) -> {
      int resultId = result.get(R.id.btn_vgRoundStartCamera);
      int newMode = resultId == R.id.btn_vgRoundStartRear ? Settings.ROUND_START_CAMERA_REAR :
        resultId == R.id.btn_vgRoundStartLast ? Settings.ROUND_START_CAMERA_LAST :
        Settings.ROUND_START_CAMERA_FRONT;
      Settings.instance().setRoundStartCamera(newMode);
      adapter.updateValuedSettingById(R.id.btn_vgRoundStartCamera);
    }));
  }

  private void showEarpieceOptions (boolean isVideo) {
    int mode = Settings.instance().getEarpieceMode(isVideo);
    int settingId = isVideo ? R.id.btn_vgRoundEarpiece : R.id.btn_vgRoundEarpieceVoice;
    ListItem never = new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_earpieceMode_never, 0, R.string.EarpieceModeNever, settingId, mode == Settings.EARPIECE_MODE_NEVER);
    ListItem proximity = new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_earpieceMode_proximity, 0, R.string.EarpieceModeProximity, settingId, mode == Settings.EARPIECE_MODE_PROXIMITY);
    // "Always" plays voice messages through the earpiece; round videos do not offer it (as in the old settings)
    ListItem[] items = isVideo ? new ListItem[] {never, proximity} : new ListItem[] {
      never, proximity, new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_earpieceMode_always, 0, R.string.EarpieceModeAlways, settingId, mode == Settings.EARPIECE_MODE_ALWAYS)
    };
    showSettings(new SettingsWrapBuilder(settingId).setRawItems(items).setAllowResize(false).setIntDelegate((id, result) -> {
      int resultId = result.get(settingId);
      int newMode = resultId == R.id.btn_earpieceMode_always ? Settings.EARPIECE_MODE_ALWAYS :
        resultId == R.id.btn_earpieceMode_proximity ? Settings.EARPIECE_MODE_PROXIMITY :
        Settings.EARPIECE_MODE_NEVER;
      Settings.instance().setEarpieceMode(isVideo, newMode);
      adapter.updateValuedSettingById(settingId);
    }));
  }

}
