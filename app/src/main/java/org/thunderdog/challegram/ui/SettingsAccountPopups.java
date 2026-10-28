/*
 * This file is a part of Vibegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.ui;

import android.view.View;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.PrivacySettings;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.OptionDelegate;

import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * Options of the «Account» section ({@link SettingsAccountController}): phone, username, user ID and birthday.
 * They are the native options popup ({@link ViewController#showOptions}): a bottom sheet in compact windows, an M3
 * menu at the tapped row in wide ones. The value (phone number, @username, ID) is the info header.
 *
 * Every list mirrors the classic popup for the current user (same ids, icons, strings and handlers):
 *  - phone: {@code TdlibUi.handleProfileClick(…, allowChangeNumber = true)};
 *  - username: the same method (no username → it navigates to EditUsernameController, so we do not intercept);
 *  - user ID: the one-item «Copy» list;
 *  - birthday: {@code TdlibUi.openBirthdateEditor(…, BirthdateOpenOrigin.PROFILE)} («Изменить» opens EditBirthdateController instead of the old date picker popup).
 * Selected phone/username items go to {@code TdlibUi.handleProfileOption}, exactly like
 * {@code SettingsAccountController.onOptionItemPressed} does.
 *
 * Returns false whenever the classic path must run instead (missing data). «Log out» is not handled here:
 * {@code TdlibUi.logOut(c, true)} opens the SettingsLogOutController screen, it shows no popup.
 */
final class SettingsAccountPopups {
  private SettingsAccountPopups () { }

  /** @return true if the click was handled. */
  static boolean onClick (SettingsAccountController c, View v) {
    final int id = v.getId();
    if (id == R.id.btn_phone) {
      return showPhoneActions(c);
    } else if (id == R.id.btn_username) {
      return showUsernameActions(c);
    } else if (id == R.id.btn_peer_id) {
      return showUserIdActions(c);
    } else if (id == R.id.btn_birthdate) {
      return showBirthdateActions(c, v);
    }
    return false;
  }

  private static OptionDelegate profileOptionHandler (SettingsAccountController c) {
    return (itemView, itemId) -> {
      c.tdlib().ui().handleProfileOption(c, itemId, c.tdlib().myUser());
      return true;
    };
  }

  private static boolean showPhoneActions (SettingsAccountController c) {
    TdApi.User user = c.tdlib().myUser();
    if (user == null || StringUtils.isEmpty(user.phoneNumber)) {
      return false; // classic path: nothing is shown for the own profile without a number
    }
    c.showOptions(Strings.formatPhone(user.phoneNumber),
      new int[] {R.id.btn_changePhoneNumber, R.id.btn_phone_call, R.id.btn_phone_copy, R.id.btn_phone_share},
      new String[] {Lang.getString(R.string.PhoneNumberChange), Lang.getString(R.string.Call), Lang.getString(R.string.Copy), Lang.getString(R.string.ShareContact)},
      null,
      new int[] {R.drawable.baseline_edit_24, R.drawable.baseline_phone_24, R.drawable.baseline_content_copy_24, R.drawable.baseline_forward_24},
      profileOptionHandler(c)
    );
    return true;
  }

  private static boolean showUsernameActions (SettingsAccountController c) {
    TdApi.User user = c.tdlib().myUser();
    if (user == null || !Td.hasUsername(user)) {
      return false; // classic path opens EditUsernameController directly
    }
    c.showOptions("@" + Td.primaryUsername(user),
      new int[] {R.id.btn_username_edit, R.id.btn_username_copy_link, R.id.btn_username_share},
      new String[] {Lang.getString(R.string.EditUsername), Lang.getString(R.string.CopyLink), Lang.getString(R.string.ShareLink)},
      null,
      new int[] {R.drawable.baseline_edit_24, R.drawable.baseline_link_24, R.drawable.baseline_forward_24},
      profileOptionHandler(c)
    );
    return true;
  }

  private static boolean showUserIdActions (SettingsAccountController c) {
    long selfId = c.tdlib().myUserId(true);
    if (selfId == 0) {
      return false;
    }
    String idText = Long.toString(selfId);
    c.showOptions(idText, new int[] {R.id.btn_peer_id_copy}, new String[] {Lang.getString(R.string.Copy)}, null, new int[] {R.drawable.baseline_content_copy_24}, (itemView, itemId) -> {
      if (itemId == R.id.btn_peer_id_copy) {
        UI.copyText(idText, R.string.CopiedMyUserId);
      }
      return true;
    });
    return true;
  }

  // Birthday (same flow as TdlibUi.openBirthdateEditor with BirthdateOpenOrigin.PROFILE)

  private static boolean showBirthdateActions (SettingsAccountController c, View v) {
    final Tdlib tdlib = c.tdlib();
    TdApi.UserFullInfo userFull = tdlib.myUserFull();
    if (userFull == null) {
      return true; // classic path does nothing until the full info is loaded
    }
    final TdApi.Birthdate currentBirthdate = userFull.birthdate;
    tdlib.send(new TdApi.GetUserPrivacySettingRules(new TdApi.UserPrivacySettingShowBirthdate()), (rules, error) -> c.runOnUiThreadOptional(() -> {
      if (error != null) {
        c.showErrorTooltip(v, TD.toErrorString(error));
        return;
      }
      String hint = TD.getPrivacyRulesString(tdlib, TdApi.UserPrivacySettingShowBirthdate.CONSTRUCTOR, PrivacySettings.valueOf(rules));
      ViewController.Options.Builder b = new ViewController.Options.Builder();
      if (!StringUtils.isEmpty(hint)) {
        b.info(hint);
      }
      b.item(new ViewController.OptionItem(R.id.btn_birthdate, Lang.getString(currentBirthdate == null ? R.string.MenuBirthdateSet : R.string.MenuBirthdateEdit), ViewController.OptionColor.NORMAL, R.drawable.baseline_date_range_24));
      b.item(new ViewController.OptionItem(R.id.btn_privacySettings, Lang.getString(R.string.MenuBirthdatePrivacy), ViewController.OptionColor.NORMAL, R.drawable.baseline_lock_24));
      if (currentBirthdate != null) {
        b.item(new ViewController.OptionItem(R.id.btn_delete, Lang.getString(R.string.MenuBirthdateRemove), ViewController.OptionColor.NORMAL, R.drawable.baseline_cancel_24));
      }
      c.showOptions(b.build(), (itemView, itemId) -> {
        if (itemId == R.id.btn_privacySettings) {
          SettingsPrivacyKeyController privacy = new SettingsPrivacyKeyController(c.context(), tdlib);
          privacy.setArguments(new SettingsPrivacyKeyController.Args(new TdApi.UserPrivacySettingShowBirthdate()));
          c.navigateTo(privacy);
        } else if (itemId == R.id.btn_birthdate) {
          showBirthdatePicker(c, currentBirthdate);
        } else if (itemId == R.id.btn_delete) {
          tdlib.send(new TdApi.SetBirthdate(null), (ok, setError) -> {
            if (setError != null) {
              c.runOnUiThreadOptional(() -> c.showErrorTooltip(v, TD.toErrorString(setError)));
            }
          });
        }
        return true;
      });
    }));
    return true;
  }

  private static void showBirthdatePicker (SettingsAccountController c, @Nullable TdApi.Birthdate currentBirthdate) {
    // A separate page with a date text field, like «Имя» / «О себе».
    EditBirthdateController page = new EditBirthdateController(c.context(), c.tdlib());
    page.setArguments(new EditBirthdateController.Arguments(currentBirthdate));
    c.navigateTo(page);
  }
}
