/*
 * This file is a part of Vibegram X, a fork of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.player.RoundRecordStats;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Vibegram: Settings → Video messages → Recording diagnostics. The frame counts of the last recording at every
 * stage (camera, rendering, encoder), to see where frames are lost. A tap on a row copies it, the button copies all.
 */
public class SettingsRoundDiagnosticsController extends RecyclerViewController<Void> implements View.OnClickListener {
  public SettingsRoundDiagnosticsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgRoundDiagnostics;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.vg_RoundDiagTitle);
  }

  private RoundRecordStats.Report report;

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    report = RoundRecordStats.getLastReport();
    SettingsAdapter adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getData() instanceof RoundRecordStats.Row) {
          view.setData(((RoundRecordStats.Row) item.getData()).value);
        }
      }
    };

    ArrayList<ListItem> items = new ArrayList<>();
    if (report == null) {
      items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.vg_RoundDiagEmpty));
    } else {
      items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_vgRoundDiagCopy, R.drawable.baseline_content_copy_24, R.string.vg_RoundDiagCopy));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, Lang.getString(R.string.vg_RoundDiagDescription, Lang.getTimestamp(report.date, TimeUnit.MILLISECONDS)), false));
      for (RoundRecordStats.Section section : report.sections) {
        items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, section.title));
        items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
        boolean first = true;
        for (RoundRecordStats.Row row : section.rows) {
          if (!first) {
            items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
          }
          first = false;
          items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgRoundDiagRow, 0, row.label).setData(row));
        }
        items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      }
    }
    adapter.setItems(items, false);
    adapter.setSegmented();
    recyclerView.setAdapter(adapter);
  }

  @Override
  public void onClick (View v) {
    final int id = v.getId();
    if (id == R.id.btn_vgRoundDiagCopy) {
      if (report != null) {
        UI.copyText(report.toText(), R.string.CopiedText);
      }
    } else if (id == R.id.btn_vgRoundDiagRow) {
      Object tag = v.getTag();
      if (tag instanceof ListItem && ((ListItem) tag).getData() instanceof RoundRecordStats.Row) {
        RoundRecordStats.Row row = (RoundRecordStats.Row) ((ListItem) tag).getData();
        UI.copyText(Lang.getString(row.label) + ": " + row.value, R.string.CopiedText);
      }
    }
  }
}
