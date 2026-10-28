/*
 * This file is a part of Vibegram X
 * Copyright © 2014 (tgx-android@pm.me)
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
import android.view.ViewGroup;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.data.TGMessage;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCache;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.OptionDelegate;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.util.text.TextWrapper;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * «Аккаунт» section of the Settings (Vibegram X): only what Telegram X already had, gathered in one screen —
 * the name and «О себе» (rows; tap opens the existing EditNameController / EditBioController pages), phone (change number flow), username, birthday, user ID
 * (when "show IDs" is on) and «Выйти» (the existing log out flow) at the bottom. Switching / adding accounts lives on
 * the main Settings page and in the long-press menu of the dock avatar.
 */
public class SettingsAccountController extends ViewController<Void> implements
  View.OnClickListener, OptionDelegate,
  TdlibCache.MyUserDataChangeListener, TdlibCache.UserDataChangeListener {

  private RecyclerView contentView;
  private SettingsAdapter adapter;

  private @Nullable TdApi.Usernames myUsernames;
  private @Nullable String myPhone;
  private @Nullable TdApi.FormattedText about;
  private @Nullable TdApi.UserFullInfo userFull;
  private long myUserId;

  public SettingsAccountController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgAccount;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.vg_Account);
  }

  @Override
  protected int getBackButton () {
    return BackHeaderButton.TYPE_BACK;
  }

  // Content

  @Override
  public boolean supportsBottomInset () {
    return true;
  }

  @Override
  protected void onBottomInsetChanged (int extraBottomInset, int extraBottomInsetWithoutIme, boolean isImeInset) {
    super.onBottomInsetChanged(extraBottomInset, extraBottomInsetWithoutIme, isImeInset);
    Views.applyBottomInset(contentView, extraBottomInset);
  }

  @Override
  protected View onCreateView (Context context) {
    contentView = new RecyclerView(context);
    contentView.setHasFixedSize(true);
    contentView.setItemAnimator(null);
    contentView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    ViewSupport.setThemedBackground(contentView, ColorId.background, this);
    contentView.setLayoutManager(new LinearLayoutManager(context, RecyclerView.VERTICAL, false));
    contentView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    Views.applyBottomInset(contentView, extraBottomInset);

    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        final int itemId = item.getId();
        view.setIconColorId(itemId == R.id.btn_logout ? ColorId.iconNegative : ColorId.NONE);
        if (itemId == R.id.btn_birthdate) {
          if (userFull == null) {
            view.setData(R.string.LoadingInformation);
          } else if (userFull.birthdate == null) {
            view.setData(R.string.SetBirthdate);
          } else {
            view.setData(Lang.getBirthdate(userFull.birthdate, true, true));
          }
        } else if (itemId == R.id.btn_username) {
          if (myUsernames == null) {
            view.setData(R.string.LoadingUsername);
          } else if (StringUtils.isEmpty(myUsernames.editableUsername)) {
            view.setData(R.string.SetUpUsername);
          } else {
            int collectibleCount = Td.secondaryUsernamesCount(myUsernames);
            view.setData("@" + myUsernames.editableUsername + (collectibleCount != 0 ? " + " + Lang.pluralBold(R.string.xOtherUsernames, collectibleCount) : ""));
          }
        } else if (itemId == R.id.btn_peer_id) {
          view.setData(Strings.buildCounter(tdlib.myUserId(true)));
        } else if (itemId == R.id.btn_phone) {
          view.setData(myPhone);
        } else if (itemId == R.id.vg_name) {
          TdApi.User user = tdlib.myUser();
          view.setData(user != null ? TD.getUserName(user) : Lang.getString(R.string.LoadingUser));
        } else if (itemId == R.id.btn_bio) {
          TdApi.FormattedText text;
          if (about == null) {
            text = TD.toFormattedText(Lang.getString(R.string.LoadingInformation), false);
          } else if (Td.isEmpty(about)) {
            text = TD.toFormattedText(Lang.getString(R.string.BioNone), false);
          } else {
            text = about;
          }
          view.setText(obtainBioWrapper(text));
        }
      }
    };

    TdApi.User user = tdlib.myUser();
    setUsername(user);
    setPhoneNumber(user);
    TdApi.UserFullInfo userFull = tdlib.myUserFull();
    if (userFull != null) {
      this.userFull = userFull;
      this.about = userFull.bio != null ? userFull.bio : new TdApi.FormattedText("", new TdApi.TextEntity[0]);
    }

    List<ListItem> items = new ArrayList<>();
    // Name and «О себе»: one card, rows like the others; tap opens the profile editor.
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_INFO_SETTING, R.id.vg_name, R.drawable.baseline_person_24, R.string.vg_Name));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_INFO_MULTILINE, R.id.btn_bio, R.drawable.baseline_info_24, R.string.UserBio).setContentStrings(R.string.LoadingInformation, R.string.BioNone));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_INFO_SETTING, R.id.btn_phone, R.drawable.baseline_phone_24, R.string.Phone));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_INFO_SETTING, R.id.btn_username, R.drawable.baseline_alternate_email_24, R.string.Username).setContentStrings(R.string.LoadingUsername, R.string.SetUpUsername));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_INFO_SETTING, R.id.btn_birthdate, R.drawable.baseline_cake_variant_24, R.string.Birthdate).setContentStrings(R.string.LoadingInformation, R.string.SetBirthdate));
    if (Settings.instance().showPeerIds()) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      items.add(new ListItem(ListItem.TYPE_INFO_SETTING, R.id.btn_peer_id, R.drawable.baseline_identifier_24, R.string.UserId).setContentStrings(R.string.LoadingInformation, R.string.LoadingInformation));
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_logout, R.drawable.baseline_logout_24, R.string.LogOut).setTextColorId(ColorId.textNegative));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    adapter.setItems(items, false);
    adapter.setSegmented();
    contentView.setAdapter(adapter);

    tdlib.cache().addMyUserListener(this);
    setMyUserId(tdlib.myUserId());

    return contentView;
  }

  @Override
  public View getViewForApplyingOffsets () {
    return contentView;
  }

  // Bio text (same rendering as the old Settings row)

  private TextWrapper bioWrapper;
  private TdApi.FormattedText bioWrapperText;

  private TextWrapper obtainBioWrapper (TdApi.FormattedText text) {
    if (bioWrapper == null || !Td.equalsTo(bioWrapperText, text)) {
      bioWrapperText = text;
      bioWrapper = new TextWrapper(tdlib, text, TGMessage.simpleTextStyleProvider(), TextColorSets.Regular.NORMAL, null, null);
      bioWrapper.addTextFlags(Text.FLAG_CUSTOM_LONG_PRESS | (Lang.rtl() ? Text.FLAG_ALIGN_RIGHT : 0));
    }
    return bioWrapper;
  }

  // Name / «О себе»: the existing editor pages

  private void openProfileEditor (@IdRes int rowId) {
    if (rowId == R.id.btn_bio) {
      EditBioController c = new EditBioController(context, tdlib);
      c.setArguments(new EditBioController.Arguments(about != null ? about.text : "", 0));
      navigateTo(c);
    } else {
      EditNameController c = new EditNameController(context, tdlib);
      c.setMode(EditNameController.Mode.RENAME_SELF);
      navigateTo(c);
    }
  }

  // Data

  private void setMyUserId (long myUserId) {
    long oldMyUserId = this.myUserId;
    if (oldMyUserId != myUserId) {
      this.myUserId = myUserId;
      if (oldMyUserId != 0) {
        tdlib.cache().removeUserDataListener(oldMyUserId, this);
      }
      if (myUserId != 0) {
        tdlib.cache().addUserDataListener(myUserId, this);
      }
    }
  }

  private boolean setUsername (@Nullable TdApi.User myUser) {
    TdApi.Usernames usernames = myUser != null ? myUser.usernames : null;
    if (myUser != null && usernames == null) {
      usernames = new TdApi.Usernames(new String[0], new String[0], "", new String[0], "");
    }
    if ((myUsernames == null && usernames != null) || (myUsernames != null && !Td.equalsTo(myUsernames, usernames))) {
      this.myUsernames = usernames;
      return true;
    }
    return false;
  }

  private boolean setPhoneNumber (@Nullable TdApi.User user) {
    String displayPhoneNumber;
    if (user != null) {
      displayPhoneNumber = Strings.formatPhone(user.phoneNumber);
      if (Settings.instance().needHidePhoneNumber()) {
        displayPhoneNumber = Strings.replaceNumbers(displayPhoneNumber);
      }
    } else {
      displayPhoneNumber = Lang.getString(R.string.LoadingPhone);
    }
    if (!StringUtils.equalsOrBothEmpty(myPhone, displayPhoneNumber)) {
      this.myPhone = displayPhoneNumber;
      return true;
    }
    return false;
  }

  private void setBio (@Nullable TdApi.FormattedText about) {
    if (about == null) {
      about = new TdApi.FormattedText("", new TdApi.TextEntity[0]);
    }
    if (this.about == null || !Td.equalsTo(this.about, about)) {
      this.about = about;
      adapter.updateValuedSettingById(R.id.btn_bio);
    }
  }

  @Override
  public void onMyUserUpdated (final TdApi.User myUser) {
    if (myUser == null) { // Ignoring log-out update
      return;
    }
    runOnUiThreadOptional(() -> {
      setMyUserId(myUser.id);
      if (setUsername(myUser)) {
        adapter.updateValuedSettingById(R.id.btn_username);
      }
      if (setPhoneNumber(myUser)) {
        adapter.updateValuedSettingById(R.id.btn_phone);
      }
      adapter.updateValuedSettingById(R.id.vg_name);
    });
  }

  @Override
  public void onMyUserBioUpdated (@Nullable TdApi.FormattedText newBio) {
    runOnUiThreadOptional(() -> setBio(newBio));
  }

  @Override
  public void onUserFullUpdated (long userId, TdApi.UserFullInfo userFull) {
    runOnUiThreadOptional(() -> {
      if (myUserId == userId && userFull != null) {
        this.userFull = userFull;
        adapter.updateValuedSettingById(R.id.btn_birthdate);
        setBio(userFull.bio);
      }
    });
  }

  // Clicks

  @Override
  public void onClick (View v) {
    // Options of phone, username, ID, birthday (sheet / menu at the tapped row); false → classic handling below.
    if (SettingsAccountPopups.onClick(this, v)) {
      return;
    }
    if (tdlib.ui().handleProfileClick(this, v, v.getId(), tdlib.myUser(), true)) {
      return;
    }
    final int viewId = v.getId();
    if (viewId == R.id.vg_name || viewId == R.id.btn_bio) {
      openProfileEditor(viewId);
    } else if (viewId == R.id.btn_birthdate) {
      tdlib.ui().openBirthdateEditor(this, v, TdlibUi.BirthdateOpenOrigin.PROFILE);
    } else if (viewId == R.id.btn_peer_id) {
      long selfId = tdlib.myUserId(true);
      if (selfId == 0) return;
      showOptions(Long.toString(selfId), new int[] {R.id.btn_peer_id_copy}, new String[] {Lang.getString(R.string.Copy)}, null, new int[] {R.drawable.baseline_content_copy_24}, (itemView, id) -> {
        if (id == R.id.btn_peer_id_copy) {
          UI.copyText(Long.toString(selfId), R.string.CopiedMyUserId);
        }
        return true;
      });
    } else if (viewId == R.id.btn_logout) {
      tdlib.ui().logOut(this, true);
    }
  }

  /**
   * Items of the menus opened by TdlibUi.handleProfileClick (phone: «Изменить номер», username: «Изменить имя
   * пользователя», copy, share…) come back here, as in SettingsController.
   */
  @Override
  public boolean onOptionItemPressed (View optionItemView, int id) {
    return tdlib.ui().handleProfileOption(this, id, tdlib.myUser());
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.cache().removeMyUserListener(this);
    setMyUserId(0);
  }
}
