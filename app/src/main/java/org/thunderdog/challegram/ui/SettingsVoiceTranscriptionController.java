/*
 * Vibegram X: Settings → Voice transcription. Lists the speech recognition models with their size and quality,
 * downloads, deletes and selects them, and credits the authors of the models and engines.
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.stt.SttManager;
import org.thunderdog.challegram.stt.SttModel;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.DrawModifier;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;

import me.vkryl.android.widget.FrameLayoutFix;

public class SettingsVoiceTranscriptionController extends RecyclerViewController<Void> implements View.OnClickListener, SttManager.ModelListener {
  public SettingsVoiceTranscriptionController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgStt;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.vg_SttTitle);
  }

  private SettingsAdapter adapter;

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getId() == R.id.btn_vgSttModel) {
          SttModel model = (SttModel) item.getData();
          view.setData(buildSubtitle(model));
          SttManager manager = SttManager.instance();
          boolean selected = manager.getSelectedModel() == model && manager.isDownloaded(model);
          view.findRadioView().setChecked(selected, isUpdate);
          bindModelRow(view, model);
        } else if (item.getId() == R.id.btn_vgSttCredit) {
          view.setData(((SttModel.Credit) item.getData()).displayUrl());
        }
      }
    };

    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.vg_SttDescription));

    // One radio group in two sections: the Russian-only model on top, the multilingual ones below.
    addModels(items, R.string.vg_SttRussianHeader, true);
    addModels(items, R.string.vg_SttMultilingualHeader, false);

    addCredits(items, R.string.vg_SttCreditsModels, SttModel.MODEL_CREDITS);
    addCredits(items, R.string.vg_SttCreditsEngines, SttModel.ENGINE_CREDITS);

    adapter.setItems(items, false);
    recyclerView.setAdapter(adapter);
    SttManager.instance().addModelListener(this);
  }

  private static void addModels (ArrayList<ListItem> items, int headerRes, boolean russianOnly) {
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, headerRes));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    boolean first = true;
    for (SttModel model : SttModel.ALL) {
      if (model.isRussianOnly() != russianOnly) {
        continue;
      }
      if (!first) {
        items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      }
      first = false;
      items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT_WITH_RADIO, R.id.btn_vgSttModel, 0, model.title, false).setData(model));
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
  }

  private static void addCredits (ArrayList<ListItem> items, int headerRes, SttModel.Credit[] credits) {
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, headerRes));
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
  }

  @Override
  public void destroy () {
    super.destroy();
    SttManager.instance().removeModelListener(this);
  }

  private static String buildSubtitle (SttModel model) {
    SttManager manager = SttManager.instance();
    String size = model.sizeText();
    if (manager.isDownloading(model)) {
      // Download / delete state is shown by the action button; only the progress needs words.
      return size + " · " + Lang.getString(R.string.vg_SttDownloadingProgress, Math.round(manager.getDownloadProgress(model) * 100f) + "%");
    }
    return size + " · " + Lang.getString(model.languagesRes);
  }

  // Model row: quality stars and an action button (download / cancel / delete) at the end

  private static final float STAR_SIZE = 14f, STAR_GAP = 2f;
  private static final float ACTION_SIZE = 48f, ACTION_MARGIN = 4f, STARS_TO_ACTION = 4f, TEXT_TO_STARS = 12f;
  /** SettingView already keeps this much free space at the end of the text. */
  private static final float SETTING_PADDING_END = 17f;

  private static float starsWidth () {
    return SttModel.MAX_QUALITY * STAR_SIZE + (SttModel.MAX_QUALITY - 1) * STAR_GAP;
  }

  private void bindModelRow (SettingView view, SttModel model) {
    ModelAction action = view.findViewById(R.id.btn_vgSttAction);
    if (action == null) {
      action = new ModelAction(view.getContext(), this);
      int margin = Screen.dp(ACTION_MARGIN);
      boolean rtl = Lang.rtl();
      view.addView(action, FrameLayoutFix.newParams(Screen.dp(ACTION_SIZE), Screen.dp(ACTION_SIZE), Gravity.CENTER_VERTICAL | (rtl ? Gravity.LEFT : Gravity.RIGHT), rtl ? margin : 0, 0, rtl ? 0 : margin, 0));
      // The icon and the stars are drawn by the row itself, so a theme change that redraws the row repaints them.
      view.addDrawModifier(action.decoration, false);
    }
    action.setModel(model);
    view.invalidate();
  }

  /** Downloaded, or a stopped download left files behind: the action deletes them. */
  private static boolean canDelete (SttManager manager, SttModel model) {
    return manager.isDownloaded(model) || manager.hasPartialDownload(model);
  }

  private static @DrawableRes int actionIcon (SttModel model) {
    SttManager manager = SttManager.instance();
    if (manager.isDownloading(model)) {
      return R.drawable.baseline_close_24;
    }
    if (canDelete(manager, model)) {
      return R.drawable.baseline_delete_24;
    }
    return R.drawable.baseline_file_download_24;
  }

  /** Touch target with its own ripple; what it shows is drawn by {@link RowDecoration}. */
  private static final class ModelAction extends View {
    private final RowDecoration decoration = new RowDecoration();
    private SttModel model;

    ModelAction (Context context, SettingsVoiceTranscriptionController controller) {
      super(context);
      setId(R.id.btn_vgSttAction);
      RippleSupport.setTransparentSelector(this, Screen.dp(ACTION_SIZE / 2f), controller);
      setOnClickListener(v -> {
        if (model != null) {
          controller.onActionClick(model);
        }
      });
    }

    void setModel (SttModel model) {
      this.model = model;
      decoration.setModel(model);
      SttManager manager = SttManager.instance();
      int description;
      if (manager.isDownloading(model)) {
        description = R.string.vg_SttCancelDownload;
      } else if (canDelete(manager, model)) {
        description = R.string.vg_SttDeleteAction;
      } else {
        description = R.string.vg_SttDownloadAction;
      }
      setContentDescription(Lang.getString(description));
    }
  }

  /** Draws the action icon and the stars. The state and the icons are resolved on bind, not while drawing. */
  private static final class RowDecoration implements DrawModifier {
    private SttModel model;
    private @DrawableRes int iconRes;
    private Drawable icon, starFilled, starEmpty;

    void setModel (SttModel model) {
      this.model = model;
      int res = actionIcon(model);
      if (icon == null || res != iconRes) {
        iconRes = res;
        icon = Drawables.get(res);
      }
    }

    private Drawable star (boolean filled) {
      if (filled) {
        if (starFilled == null) {
          starFilled = Drawables.get(R.drawable.baseline_star_20);
        }
        return starFilled;
      }
      if (starEmpty == null) {
        starEmpty = Drawables.get(R.drawable.baseline_star_border_24);
      }
      return starEmpty;
    }

    @Override
    public int getWidth () {
      return Screen.dp(ACTION_MARGIN + ACTION_SIZE + STARS_TO_ACTION + starsWidth() + TEXT_TO_STARS - SETTING_PADDING_END);
    }

    @Override
    public void afterDraw (View view, Canvas c) {
      if (model == null || icon == null) {
        return;
      }
      boolean rtl = Lang.rtl();
      int width = view.getWidth();
      float centerY = view.getHeight() / 2f;

      float iconCenterX = Screen.dp(ACTION_MARGIN + ACTION_SIZE / 2f);
      if (!rtl) {
        iconCenterX = width - iconCenterX;
      }
      Drawables.draw(c, icon, iconCenterX - icon.getMinimumWidth() / 2f, centerY - icon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(Theme.getColor(ColorId.icon)));

      if (model.quality <= 0) {
        // not rated: GigaAM is compared only with itself on Russian speech, stars would mislead in other languages
        return;
      }
      float starsStart = Screen.dp(ACTION_MARGIN + ACTION_SIZE + STARS_TO_ACTION + starsWidth());
      float size = Screen.dp(STAR_SIZE), step = Screen.dp(STAR_SIZE + STAR_GAP);
      for (int i = 0; i < SttModel.MAX_QUALITY; i++) {
        boolean filled = i < model.quality;
        // Stars fill from the start of the reading direction.
        float x = rtl ? starsStart - size - i * step : width - starsStart + i * step;
        drawStar(c, star(filled), filled, x, centerY - size / 2f, size);
      }
    }

    private static void drawStar (Canvas c, Drawable star, boolean filled, float x, float y, float size) {
      float scale = size / star.getMinimumWidth();
      c.save();
      c.translate(x, y);
      c.scale(scale, scale);
      Drawables.draw(c, star, 0, 0, Paints.getPorterDuffPaint(Theme.getColor(filled ? ColorId.iconActive : ColorId.iconLight)));
      c.restore();
    }
  }

  private void onActionClick (@NonNull SttModel model) {
    SttManager manager = SttManager.instance();
    if (manager.isDownloading(model)) {
      manager.cancelDownload(model);
    } else if (canDelete(manager, model)) {
      // a partial download is deleted the same way: its .part files take space too
      String title = manager.isDownloaded(model) ? Lang.getString(R.string.vg_SttDelete, model.sizeText()) : Lang.getString(R.string.vg_SttDeleteAction);
      showOptions(model.title, new int[] {R.id.btn_vgSttDelete}, new String[] {title}, new int[] {OptionColor.RED}, new int[] {R.drawable.baseline_delete_24}, (itemView, id) -> {
        if (id == R.id.btn_vgSttDelete) {
          manager.delete(model);
        }
        return true;
      });
    } else {
      manager.download(model);
    }
  }

  @Override
  public void onModelStateChanged (SttModel changed) {
    if (adapter == null || isDestroyed()) {
      return;
    }
    // The selection may move between models, so refresh every row.
    adapter.updateAllValuedSettingsById(R.id.btn_vgSttModel);
  }

  @Override
  public void onModelDownloadProgress (SttModel model) {
    if (adapter == null || isDestroyed()) {
      return;
    }
    // only the percentage in this model's subtitle changed
    adapter.updateValuedSettingByData(model);
  }

  @Override
  public void onClick (View v) {
    if (v.getId() == R.id.btn_vgSttCredit) {
      SttModel.Credit credit = (SttModel.Credit) ((ListItem) v.getTag()).getData();
      tdlib.ui().openUrl(this, credit.url, new TdlibUi.UrlOpenParameters().disableInstantView());
      return;
    }
    if (v.getId() != R.id.btn_vgSttModel) {
      return;
    }
    ListItem item = (ListItem) v.getTag();
    SttModel model = (SttModel) item.getData();
    SttManager manager = SttManager.instance();
    if (manager.isDownloading(model)) {
      showOptions(model.title, new int[] {R.id.btn_vgSttCancel}, new String[] {Lang.getString(R.string.vg_SttCancelDownload)}, new int[] {OptionColor.RED}, new int[] {R.drawable.baseline_cancel_24}, (itemView, id) -> {
        if (id == R.id.btn_vgSttCancel) {
          manager.cancelDownload(model);
        }
        return true;
      });
    } else if (manager.isDownloaded(model)) {
      if (manager.getSelectedModel() != model) {
        manager.setSelectedModel(model);
      }
    } else {
      showOptions(model.title, new int[] {R.id.btn_vgSttDownload}, new String[] {Lang.getString(R.string.vg_SttDownload, model.sizeText())}, null, new int[] {R.drawable.baseline_file_download_24}, (itemView, id) -> {
        if (id == R.id.btn_vgSttDownload) {
          manager.download(model);
        }
        return true;
      });
    }
  }
}
