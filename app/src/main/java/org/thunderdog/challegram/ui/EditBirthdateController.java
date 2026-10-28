/*
 * This file is a part of Vibegram X (fork of Telegram X)
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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import me.vkryl.android.widget.FrameLayoutFix;

/**
 * «День рождения» page of the «Аккаунт» section: one text field «ДД.ММ.ГГГГ» (the year is optional,
 * «ДД.ММ» = birthday without a year, like the old picker's «no year» option) and the ✓ button, same as the
 * «Имя» / «О себе» pages (EditBaseController).
 *
 * Saving = {@code TdApi.SetBirthdate}, as in {@code TdlibUi.showBirthdatePicker}; the old confirmation popup
 * (SetBirthdateWarning: the date can't be changed again right away) is shown as the supporting text under the field.
 * The row in «Аккаунт» updates by itself through {@code onUserFullUpdated}.
 */
public class EditBirthdateController extends EditBaseController<EditBirthdateController.Arguments> {
  public static class Arguments {
    public final @Nullable TdApi.Birthdate currentBirthdate;

    public Arguments (@Nullable TdApi.Birthdate currentBirthdate) {
      this.currentBirthdate = currentBirthdate;
    }
  }

  private static final int MIN_YEAR = 1900;
  /** A leap year: lets «29.02» without a year pass validation. */
  private static final int LEAP_YEAR = 2024;

  public EditBirthdateController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_vgBirthdate;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.BirthdayPopupTitle);
  }

  private SettingsAdapter adapter;
  private ListItem hintItem;
  private String currentText = "";
  private boolean errorShown;

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, RecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void modifyEditText (ListItem item, ViewGroup parent, MaterialEditTextGroup editText) {
        // Digits keypad with date separators. Dots are inserted by DateFormatter, so only digits must be typed.
        editText.getEditText().setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        editText.getEditText().setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (editText.getEditText().getTag(R.id.controller_vgBirthdate) == null) {
          editText.getEditText().setTag(R.id.controller_vgBirthdate, Boolean.TRUE);
          editText.getEditText().addTextChangedListener(new DateFormatter());
        }
        editText.setInErrorState(errorShown);
      }

      @Override
      protected void modifyDescription (ListItem item, TextView textView) {
        if (errorShown && item.getId() == R.id.description) {
          // M3 error role, same as the field outline in the error state (MaterialEditTextGroup uses M3.error()).
          textView.setTextColor(M3.error());
        }
      }
    };

    String value = format(getArguments() != null ? getArguments().currentBirthdate : null);
    currentText = value;

    List<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_EDITTEXT, R.id.input, 0, R.string.vg_BirthdateField)
      .setStringValue(value)
      .setOnEditorActionListener(new SimpleEditorActionListener(EditorInfo.IME_ACTION_DONE, this)));
    items.add(hintItem = new ListItem(ListItem.TYPE_DESCRIPTION, R.id.description, 0, R.string.vg_BirthdateHint)
      .setTextColorId(ColorId.textLight));

    adapter.setTextChangeListener((id, item, v) -> {
      currentText = v.getText().toString();
      setError(null);
    });
    adapter.setLockFocusOn(this, true);
    adapter.setItems(items, false);

    recyclerView.setAdapter(adapter);
    recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);

    setDoneVisible(true);
  }

  // Input

  /** Keeps the text as «ДД.ММ.ГГГГ»: digits only, dots added before the 3rd and 5th digit. */
  private static class DateFormatter implements TextWatcher {
    private boolean selfChange;

    @Override public void beforeTextChanged (CharSequence s, int start, int count, int after) { }
    @Override public void onTextChanged (CharSequence s, int start, int before, int count) { }

    @Override
    public void afterTextChanged (Editable s) {
      if (selfChange) {
        return;
      }
      StringBuilder digits = new StringBuilder(8);
      for (int i = 0; i < s.length() && digits.length() < 8; i++) {
        char c = s.charAt(i);
        if (c >= '0' && c <= '9') {
          digits.append(c);
        }
      }
      StringBuilder b = new StringBuilder(10);
      for (int i = 0; i < digits.length(); i++) {
        if (i == 2 || i == 4) {
          b.append('.');
        }
        b.append(digits.charAt(i));
      }
      String formatted = b.toString();
      if (!formatted.contentEquals(s)) {
        selfChange = true;
        s.replace(0, s.length(), formatted);
        selfChange = false;
      }
    }
  }

  private static String format (@Nullable TdApi.Birthdate birthdate) {
    if (birthdate == null) {
      return "";
    }
    String dayMonth = String.format(Locale.US, "%02d.%02d", birthdate.day, birthdate.month);
    return birthdate.year != 0 ? dayMonth + String.format(Locale.US, ".%04d", birthdate.year) : dayMonth;
  }

  /** @return parsed date, or null if the text is not a valid past date «ДД.ММ» / «ДД.ММ.ГГГГ». */
  private static @Nullable TdApi.Birthdate parse (String text) {
    String[] parts = text.trim().split("\\.");
    if (parts.length != 2 && parts.length != 3) {
      return null;
    }
    if (parts[0].length() != 2 || parts[1].length() != 2 || (parts.length == 3 && parts[2].length() != 4)) {
      return null;
    }
    int day, month, year;
    try {
      day = Integer.parseInt(parts[0]);
      month = Integer.parseInt(parts[1]);
      year = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
    } catch (NumberFormatException e) {
      return null;
    }
    Calendar today = Calendar.getInstance();
    if (month < 1 || month > 12 || day < 1) {
      return null;
    }
    if (year != 0 && (year < MIN_YEAR || year > today.get(Calendar.YEAR))) {
      return null;
    }
    Calendar c = Calendar.getInstance();
    c.clear();
    c.set(year != 0 ? year : LEAP_YEAR, month - 1, 1);
    if (day > c.getActualMaximum(Calendar.DAY_OF_MONTH)) {
      return null;
    }
    if (year != 0) {
      c.set(Calendar.DAY_OF_MONTH, day);
      if (c.after(today)) {
        return null;
      }
    }
    return new TdApi.Birthdate(day, month, year);
  }

  /** M3 text field error: the field turns red and the error replaces the supporting text. */
  private void setError (@Nullable CharSequence error) {
    boolean show = error != null;
    if (errorShown == show && !show) {
      return;
    }
    errorShown = show;
    adapter.updateEditTextById(R.id.input, false, show);
    hintItem.setString(show ? error : Lang.getString(R.string.vg_BirthdateHint));
    adapter.updateItemById(R.id.description);
  }

  // Save

  private static boolean sameDate (@Nullable TdApi.Birthdate a, @Nullable TdApi.Birthdate b) {
    if (a == null || b == null) {
      return a == b;
    }
    return a.day == b.day && a.month == b.month && a.year == b.year;
  }

  @Override
  protected void onProgressStateChanged (boolean inProgress) {
    adapter.updateLockEditTextById(R.id.input, inProgress ? currentText : null);
  }

  @Override
  protected final boolean onDoneClick () {
    if (isInProgress()) {
      return true;
    }
    TdApi.Birthdate birthdate = parse(currentText);
    if (birthdate == null) {
      setError(Lang.getString(currentText.trim().isEmpty() ? R.string.vg_BirthdateEmpty : R.string.vg_BirthdateInvalid));
      return true;
    }
    if (sameDate(birthdate, getArguments() != null ? getArguments().currentBirthdate : null)) {
      onSaveCompleted();
      return true;
    }
    setInProgress(true);
    tdlib.send(new TdApi.SetBirthdate(birthdate), (ok, error) -> runOnUiThreadOptional(() -> {
      setInProgress(false);
      if (error != null) {
        UI.showError(error);
      } else {
        onSaveCompleted();
      }
    }));
    return true;
  }
}
