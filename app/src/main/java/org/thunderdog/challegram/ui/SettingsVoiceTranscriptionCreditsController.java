/*
 * Vibegram X: Settings → Voice transcription → Model authors / Recognition engines. Links to the projects.
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.stt.SttModel;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;

/** Argument: true for the model authors, false for the engines. */
public class SettingsVoiceTranscriptionCreditsController extends RecyclerViewController<Boolean> implements View.OnClickListener {
  public SettingsVoiceTranscriptionCreditsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgSttCredits;
  }

  private boolean isModels () {
    Boolean args = getArguments();
    return args == null || args;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(isModels() ? R.string.vg_SttCreditsModels : R.string.vg_SttCreditsEngines);
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    SettingsAdapter adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getId() == R.id.btn_vgSttCredit) {
          view.setData(((SttModel.Credit) item.getData()).displayUrl());
        }
      }
    };
    SttModel.Credit[] credits = isModels() ? SttModel.MODEL_CREDITS : SttModel.ENGINE_CREDITS;
    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    for (int i = 0; i < credits.length; i++) {
      if (i > 0) {
        items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      }
      SttModel.Credit credit = credits[i];
      int icon = credit.github ? R.drawable.baseline_github_24 : R.drawable.baseline_open_in_browser_24;
      items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgSttCredit, icon, credit.title, false).setData(credit));
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    adapter.setItems(items, false);
    recyclerView.setAdapter(adapter);
  }

  @Override
  public void onClick (View v) {
    if (v.getId() == R.id.btn_vgSttCredit) {
      SttModel.Credit credit = (SttModel.Credit) ((ListItem) v.getTag()).getData();
      tdlib.ui().openUrl(this, credit.url, new TdlibUi.UrlOpenParameters().disableInstantView());
    }
  }
}
