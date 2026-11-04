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
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Personal channel of the account (Settings › Account), as in the official app: «Don't show channel» when one is
 * set, «Select a channel» with the channels suitable for the profile, «Start a new channel» when there are none.
 * Picking a channel applies it at once (SetPersonalChat) and goes back.
 */
public class PersonalChannelController extends RecyclerViewController<PersonalChannelController.Arguments> implements View.OnClickListener {
  public static class Arguments {
    public final long currentChatId;

    public Arguments (long currentChatId) {
      this.currentChatId = currentChatId;
    }
  }

  public PersonalChannelController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_personalChannel;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.PersonalChannelTitle);
  }

  private SettingsAdapter adapter;
  private final List<TdApi.Chat> chats = new ArrayList<>();
  private boolean loaded;

  private long getCurrentChatId () {
    Arguments args = getArguments();
    return args != null ? args.currentChatId : 0;
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this);
    buildItems();
    adapter.setSegmented();
    recyclerView.setAdapter(adapter);
    load();
  }

  private void load () {
    tdlib.send(new TdApi.GetSuitablePersonalChats(), (result, error) -> {
      List<TdApi.Chat> found = new ArrayList<>();
      if (result != null) {
        for (long chatId : result.chatIds) {
          TdApi.Chat chat = tdlib.chat(chatId);
          if (chat != null) {
            found.add(chat);
          }
        }
      }
      runOnUiThreadOptional(() -> {
        chats.clear();
        chats.addAll(found);
        loaded = true;
        if (error != null) {
          UI.showError(error);
        }
        buildItems();
      });
    });
  }

  private void buildItems () {
    final long currentChatId = getCurrentChatId();
    List<ListItem> items = new ArrayList<>();
    if (currentChatId != 0) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_personalChannelHide, R.drawable.baseline_eye_off_24, R.string.PersonalChannelHide).setTextColorId(ColorId.textNegative));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    }
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.PersonalChannelSelect));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    if (!loaded) {
      items.add(new ListItem(ListItem.TYPE_INFO, 0, 0, R.string.LoadingInformation));
    } else if (chats.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_personalChannelCreate, R.drawable.baseline_bullhorn_24, R.string.PersonalChannelCreate).setTextColorId(ColorId.textNeutral));
    } else {
      boolean first = true;
      for (TdApi.Chat chat : chats) {
        if (!first) {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR));
        }
        first = false;
        items.add(new ListItem(ListItem.TYPE_RADIO_OPTION_WITH_AVATAR, R.id.btn_personalChannel, 0, chat.title, R.id.btn_personalChannel, chat.id == currentChatId)
          .setData(chat)
          .setLongId(chat.id));
      }
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    adapter.setItems(items, false);
  }

  private void setPersonalChat (long chatId) {
    if (chatId == getCurrentChatId()) {
      navigateBack();
      return;
    }
    tdlib.send(new TdApi.SetPersonalChat(chatId), (ok, error) -> runOnUiThreadOptional(() -> {
      if (error != null) {
        UI.showError(error);
      } else {
        UI.showToast(chatId != 0 ? R.string.PersonalChannelSet : R.string.PersonalChannelRemoved, android.widget.Toast.LENGTH_SHORT);
        navigateBack();
      }
    }));
  }

  @Override
  public void onClick (View v) {
    final int viewId = v.getId();
    if (viewId == R.id.btn_personalChannelHide) {
      setPersonalChat(0);
    } else if (viewId == R.id.btn_personalChannelCreate) {
      navigateTo(new CreateChannelController(context, tdlib));
    } else if (viewId == R.id.btn_personalChannel) {
      ListItem item = (ListItem) v.getTag();
      if (item != null && item.getData() instanceof TdApi.Chat) {
        setPersonalChat(((TdApi.Chat) item.getData()).id);
      }
    }
  }

  @Override
  public void onFocus () {
    super.onFocus();
    if (loaded && chats.isEmpty()) {
      // Back from «Start a new channel»: the new channel may be suitable now.
      load();
    }
  }
}
