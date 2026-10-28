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
import org.thunderdog.challegram.compose.M3ActionSheet;
import org.thunderdog.challegram.compose.M3Compose;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.telegram.PrivacySettings;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * Material 3 (Jetpack Compose) action sheets / menus and the birthday picker of the «Account» section
 * ({@link SettingsAccountController}), opt-in for this screen only.
 *
 * Every list mirrors the classic popup it replaces for the current user (same ids, icons, strings and handlers):
 *  - phone: {@code TdlibUi.handleProfileClick(…, allowChangeNumber = true)};
 *  - username: the same method (no username → it navigates to EditUsernameController, so we do not intercept);
 *  - user ID: the one-item «Copy» list that SettingsAccountController showed;
 *  - birthday: {@code TdlibUi.openBirthdateEditor(…, BirthdateOpenOrigin.PROFILE)} («Изменить» opens EditBirthdateController instead of the old date picker popup).
 * Selected phone/username items go to {@code TdlibUi.handleProfileOption}, exactly like
 * {@code SettingsAccountController.onOptionItemPressed} does for the classic popups.
 *
 * Returns false whenever the classic path must run instead (legacy flavor without Compose, missing data),
 * so the caller simply continues with its old code. «Log out» is not handled here: {@code TdlibUi.logOut(c, true)}
 * opens the SettingsLogOutController screen, it shows no popup.
 */
final class SettingsAccountPopups {
  private SettingsAccountPopups () { }

  /** @return true if the click was handled with M3 UI. */
  static boolean onClick (SettingsAccountController c, View v) {
    if (!M3Compose.isSupported()) {
      return false;
    }
    final int id = v.getId();
    if (id == R.id.btn_phone) {
      return showPhoneActions(c, v);
    } else if (id == R.id.btn_username) {
      return showUsernameActions(c, v);
    } else if (id == R.id.btn_peer_id) {
      return showUserIdActions(c, v);
    } else if (id == R.id.btn_birthdate) {
      return showBirthdateActions(c, v);
    }
    return false;
  }

  private static M3ActionSheet.Listener profileOptionHandler (SettingsAccountController c) {
    return itemId -> c.tdlib().ui().handleProfileOption(c, itemId, c.tdlib().myUser());
  }

  private static boolean showPhoneActions (SettingsAccountController c, View v) {
    TdApi.User user = c.tdlib().myUser();
    if (user == null || StringUtils.isEmpty(user.phoneNumber)) {
      return false; // classic path: nothing is shown for the own profile without a number
    }
    M3ActionSheet.Item[] items = {
      new M3ActionSheet.Item(R.id.btn_changePhoneNumber, R.drawable.baseline_edit_24, Lang.getString(R.string.PhoneNumberChange)),
      new M3ActionSheet.Item(R.id.btn_phone_call, R.drawable.baseline_phone_24, Lang.getString(R.string.Call)),
      new M3ActionSheet.Item(R.id.btn_phone_copy, R.drawable.baseline_content_copy_24, Lang.getString(R.string.Copy)),
      new M3ActionSheet.Item(R.id.btn_phone_share, R.drawable.baseline_forward_24, Lang.getString(R.string.ShareContact))
    };
    return M3ActionSheet.show(c, v, Strings.formatPhone(user.phoneNumber), null, items, profileOptionHandler(c));
  }

  private static boolean showUsernameActions (SettingsAccountController c, View v) {
    TdApi.User user = c.tdlib().myUser();
    if (user == null || !Td.hasUsername(user)) {
      return false; // classic path opens EditUsernameController directly
    }
    M3ActionSheet.Item[] items = {
      new M3ActionSheet.Item(R.id.btn_username_edit, R.drawable.baseline_edit_24, Lang.getString(R.string.EditUsername)),
      new M3ActionSheet.Item(R.id.btn_username_copy_link, R.drawable.baseline_link_24, Lang.getString(R.string.CopyLink)),
      new M3ActionSheet.Item(R.id.btn_username_share, R.drawable.baseline_forward_24, Lang.getString(R.string.ShareLink))
    };
    return M3ActionSheet.show(c, v, "@" + Td.primaryUsername(user), null, items, profileOptionHandler(c));
  }

  private static boolean showUserIdActions (SettingsAccountController c, View v) {
    long selfId = c.tdlib().myUserId(true);
    if (selfId == 0) {
      return false;
    }
    String idText = Long.toString(selfId);
    M3ActionSheet.Item[] items = {
      new M3ActionSheet.Item(R.id.btn_peer_id_copy, R.drawable.baseline_content_copy_24, Lang.getString(R.string.Copy))
    };
    return M3ActionSheet.show(c, v, idText, null, items, itemId -> {
      if (itemId == R.id.btn_peer_id_copy) {
        UI.copyText(idText, R.string.CopiedMyUserId);
      }
    });
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
      List<M3ActionSheet.Item> items = new ArrayList<>(3);
      items.add(new M3ActionSheet.Item(R.id.btn_birthdate, R.drawable.baseline_date_range_24, Lang.getString(currentBirthdate == null ? R.string.MenuBirthdateSet : R.string.MenuBirthdateEdit)));
      items.add(new M3ActionSheet.Item(R.id.btn_privacySettings, R.drawable.baseline_lock_24, Lang.getString(R.string.MenuBirthdatePrivacy)));
      if (currentBirthdate != null) {
        items.add(new M3ActionSheet.Item(R.id.btn_delete, R.drawable.baseline_cancel_24, Lang.getString(R.string.MenuBirthdateRemove)));
      }
      M3ActionSheet.Listener listener = itemId -> {
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
      };
      if (!M3ActionSheet.show(c, v, null, StringUtils.isEmpty(hint) ? null : hint, items.toArray(new M3ActionSheet.Item[0]), listener)) {
        tdlib.ui().openBirthdateEditor(c, v, TdlibUi.BirthdateOpenOrigin.PROFILE);
      }
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
