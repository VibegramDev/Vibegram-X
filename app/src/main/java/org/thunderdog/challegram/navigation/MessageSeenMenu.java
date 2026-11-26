/*
 * This file is a part of Vibegram-X
 *
 * Message viewers in the message menu, as in Telegram for Android (MessageSeenView): a row with the
 * double check, "5 views" (or the viewer's name) and up to three overlapping avatars; a tap opens a page
 * of the menu with a back row and the viewers with their read dates. The code is written for Telegram X's
 * menu (MenuMoreWrap).
 */
package org.thunderdog.challegram.navigation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.popups.MessageSeenController;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TGMessage;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.EmojiStatusHelper;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.TextColorSetOverride;
import org.thunderdog.challegram.util.text.TextColorSets;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public final class MessageSeenMenu {
  /** Header avatars: 24dp, each next one 12dp further, cut out from the previous one by 1.67dp (AvatarsDrawable.STYLE_MESSAGE_SEEN). */
  private static final float AVATAR_SIZE = 24f, AVATAR_STEP = 12f, AVATAR_STROKE = 1.67f;
  /** Viewer rows: 34dp avatar, name 16sp and read date 13sp (MessageSeenView.UserCell). */
  private static final float ROW_AVATAR_SIZE = 34f, ROW_AVATAR_START = 7f, ROW_TEXT_START = ROW_AVATAR_START + ROW_AVATAR_SIZE + 11f;
  private static final float CHECKS_SIZE = 16f, CHECKS_GAP = 2f;

  private MessageSeenMenu () { }

  /** Adds the viewers row to {@code menu}; {@code hideMenu} closes the menu before a profile is opened. */
  public static void add (MenuMoreWrap menu, ViewController<?> context, TGMessage message, Runnable hideMenu) {
    Tdlib tdlib = context.tdlib();
    HeaderView header = new HeaderView(menu.getContext(), menu);
    menu.addCustomItem(header, header.getDesiredWidth(), v -> {
      TdApi.MessageViewers viewers = header.viewers;
      if (viewers == null || viewers.viewers.length == 0) {
        return;
      }
      if (viewers.viewers.length == 1 && viewers.viewers[0].viewDate == 0) {
        // Nothing to list but the name: the profile (as Telegram does).
        hideMenu.run();
        tdlib.ui().openPrivateProfile(context, viewers.viewers[0].userId, new TdlibUi.UrlOpenParameters());
        return;
      }
      openViewersPage(menu, context, viewers, hideMenu);
    });
    header.setEnabled(false);
    menu.addDestroyable(header::destroy);
    tdlib.send(new TdApi.GetMessageViewers(message.getChatId(), message.getId()), (viewers, error) -> {
      if (viewers == null) {
        return;
      }
      context.runOnUiThreadOptional(() -> {
        if (!header.isDestroyed) {
          header.setViewers(tdlib, message, viewers);
          menu.updateRowWidth(header, header.getDesiredWidth());
        }
      });
    });
  }

  private static void openViewersPage (MenuMoreWrap menu, ViewController<?> context, TdApi.MessageViewers viewers, Runnable hideMenu) {
    Tdlib tdlib = context.tdlib();
    Context c = menu.getContext();
    TextView back = menu.createItem(View.NO_ID, Lang.getString(R.string.vg_MenuBack), R.drawable.baseline_arrow_back_24, null, v -> menu.closePage());
    View divider = menu.createDivider();

    ViewerAdapter adapter = new ViewerAdapter(menu, tdlib, viewers, userId -> {
      hideMenu.run();
      tdlib.ui().openPrivateProfile(context, userId, new TdlibUi.UrlOpenParameters());
    });
    menu.addDestroyable(adapter::destroy);
    RecyclerView list = new RecyclerView(c);
    list.setOverScrollMode(View.OVER_SCROLL_NEVER);
    list.setItemAnimator(null);
    list.setLayoutManager(new LinearLayoutManager(c, RecyclerView.VERTICAL, false));
    list.setAdapter(adapter);
    int rowHeight = Screen.dp(MenuMoreWrap.ITEM_HEIGHT);
    int maxHeight = menu.getAvailableItemsHeight() - rowHeight - Screen.dp(17f);
    int height = rowHeight * viewers.viewers.length;
    if (maxHeight > rowHeight * 3) {
      height = Math.min(height, maxHeight);
    }
    list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
    list.setTag(Math.min(adapter.getDesiredWidth(), Screen.dp(MenuMoreWrap.MAX_WIDTH)));

    menu.openPage(back, divider, list);
  }

  private interface UserClickListener {
    void onUserClick (long userId);
  }

  // Header row

  @SuppressLint("ViewConstructor")
  private static final class HeaderView extends View {
    private final MenuMoreWrap menu;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;
    private final ArrayList<AvatarReceiver> avatars = new ArrayList<>(3);
    private String label;
    private CharSequence displayLabel = "";
    private float displayLabelWidth;
    private TdApi.MessageViewers viewers;
    private boolean isDestroyed;

    HeaderView (Context context, MenuMoreWrap menu) {
      super(context);
      this.menu = menu;
      textPaint.setTypeface(Fonts.getRobotoRegular());
      textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, getResources().getDisplayMetrics()));
      clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
      icon = Drawables.get(getResources(), R.drawable.vg_menu_seen_24);
      label = Lang.getString(R.string.LoadingMessageSeen);
      setWillNotDraw(false);
    }

    void setViewers (Tdlib tdlib, TGMessage message, TdApi.MessageViewers viewers) {
      this.viewers = viewers;
      int count = viewers.viewers.length;
      if (count == 1) {
        label = tdlib.senderName(new TdApi.MessageSenderUser(viewers.viewers[0].userId));
      } else if (count > 1) {
        label = MessageSeenController.getViewString(message, count).toString();
      } else {
        label = MessageSeenController.getNobodyString(message);
      }
      for (int i = 0; i < Math.min(3, count); i++) {
        AvatarReceiver receiver = new AvatarReceiver(this);
        receiver.requestMessageSender(tdlib, new TdApi.MessageSenderUser(viewers.viewers[i].userId), AvatarReceiver.Options.NONE);
        if (getWindowToken() != null) {
          receiver.attach();
        } else {
          receiver.detach();
        }
        avatars.add(receiver);
      }
      setEnabled(count > 0);
      layoutContent();
      invalidate();
    }

    int getAvatarsWidth () {
      int count = avatars.size();
      return count == 0 ? 0 : Screen.dp(AVATAR_SIZE + AVATAR_STEP * (count - 1));
    }

    int getDesiredWidth () {
      int avatarsWidth = getAvatarsWidth();
      return Screen.dp(MenuMoreWrap.ITEM_TEXT_START) + (int) Math.ceil(textPaint.measureText(label)) +
        (avatarsWidth > 0 ? Screen.dp(8f) + avatarsWidth : 0) + Screen.dp(MenuMoreWrap.ITEM_PADDING);
    }

    private void layoutContent () {
      int width = getMeasuredWidth(), height = getMeasuredHeight();
      if (width == 0 || height == 0) {
        return;
      }
      int count = avatars.size();
      int size = Screen.dp(AVATAR_SIZE);
      int top = (height - size) / 2;
      int end = width - Screen.dp(MenuMoreWrap.ITEM_PADDING);
      int avatarsWidth = getAvatarsWidth();
      int available = end - Screen.dp(MenuMoreWrap.ITEM_TEXT_START) - (avatarsWidth > 0 ? avatarsWidth + Screen.dp(8f) : 0);
      displayLabel = available > 0 ? TextUtils.ellipsize(label, textPaint, available, TextUtils.TruncateAt.END) : "";
      displayLabelWidth = textPaint.measureText(displayLabel, 0, displayLabel.length());
      for (int i = 0; i < count; i++) {
        // The first viewer is the leftmost one, drawn on top.
        int right = end - Screen.dp(AVATAR_STEP) * (count - 1 - i);
        int left = right - size;
        if (Lang.rtl()) {
          int l = width - right;
          right = width - left;
          left = l;
        }
        avatars.get(i).setBounds(left, top, left + size, top + size);
      }
    }

    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
      super.onLayout(changed, left, top, right, bottom);
      layoutContent();
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      int width = getMeasuredWidth(), height = getMeasuredHeight();
      boolean rtl = Lang.rtl();
      int iconColor = M3.get(M3.ON_SURFACE_VARIANT, menu.getForcedTheme());
      if (icon != null) {
        float iconX = Screen.dp(MenuMoreWrap.ITEM_PADDING + 12) - icon.getMinimumWidth() / 2f;
        if (rtl) {
          iconX = width - iconX - icon.getMinimumWidth();
        }
        Drawables.draw(c, icon, iconX, (height - icon.getMinimumHeight()) / 2f, Paints.getPorterDuffPaint(iconColor));
      }
      if (displayLabel.length() > 0) {
        textPaint.setColor(menu.getOnContainerColor());
        int textStart = Screen.dp(MenuMoreWrap.ITEM_TEXT_START);
        float x = rtl ? width - textStart - displayLabelWidth : textStart;
        float baseline = (height - textPaint.ascent() - textPaint.descent()) / 2f;
        c.drawText(displayLabel, 0, displayLabel.length(), x, baseline, textPaint);
      }
      if (!avatars.isEmpty()) {
        int saveCount = Views.save(c);
        c.saveLayerAlpha(0, 0, width, height, 255);
        float radius = Screen.dp(AVATAR_SIZE) / 2f + Screen.dp(AVATAR_STROKE);
        for (int i = avatars.size() - 1; i >= 0; i--) {
          AvatarReceiver receiver = avatars.get(i);
          c.drawCircle(receiver.centerX(), receiver.centerY(), radius, clearPaint);
          if (receiver.needPlaceholder()) {
            receiver.drawPlaceholder(c);
          }
          receiver.draw(c);
        }
        Views.restore(c, saveCount);
      }
    }

    @Override
    protected void onAttachedToWindow () {
      super.onAttachedToWindow();
      for (AvatarReceiver receiver : avatars) {
        receiver.attach();
      }
    }

    @Override
    protected void onDetachedFromWindow () {
      super.onDetachedFromWindow();
      for (AvatarReceiver receiver : avatars) {
        receiver.detach();
      }
    }

    void destroy () {
      isDestroyed = true;
      for (AvatarReceiver receiver : avatars) {
        receiver.destroy();
      }
    }
  }

  // Viewers page

  private static final class ViewerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final MenuMoreWrap menu;
    private final Tdlib tdlib;
    private final TdApi.MessageViewer[] viewers;
    private final UserClickListener listener;
    private final ArrayList<RowView> rows = new ArrayList<>();
    private final TextColorSet nameColorSet;

    ViewerAdapter (MenuMoreWrap menu, Tdlib tdlib, TdApi.MessageViewers viewers, UserClickListener listener) {
      this.menu = menu;
      this.tdlib = tdlib;
      this.viewers = viewers.viewers;
      this.listener = listener;
      this.nameColorSet = menu::getOnContainerColor;
    }

    int getDesiredWidth () {
      // Widest row: avatar, name (+ emoji status) or date, end padding.
      TextPaint namePaint = RowView.newPaint(16f), datePaint = RowView.newPaint(13f);
      long nowMs = tdlib.currentTimeMillis();
      float max = 0;
      for (TdApi.MessageViewer viewer : viewers) {
        float nameWidth = namePaint.measureText(tdlib.senderName(new TdApi.MessageSenderUser(viewer.userId)));
        TdApi.User user = tdlib.cache().user(viewer.userId);
        if (user != null && user.emojiStatus != null && user.isPremium) {
          nameWidth += Screen.dp(3f + 20f);
        }
        float dateWidth = viewer.viewDate != 0 ? Screen.dp(CHECKS_SIZE + CHECKS_GAP) + datePaint.measureText(Lang.getSeenDate(viewer.viewDate, TimeUnit.SECONDS, nowMs)) : 0;
        max = Math.max(max, Math.max(nameWidth, dateWidth));
      }
      return Screen.dp(ROW_TEXT_START + MenuMoreWrap.ITEM_PADDING) + (int) Math.ceil(max);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder (@NonNull ViewGroup parent, int viewType) {
      RowView row = new RowView(parent.getContext(), menu, tdlib);
      menu.prepareCustomItem(row, 0, v -> {
        if (row.userId != 0) {
          listener.onUserClick(row.userId);
        }
      });
      row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(MenuMoreWrap.ITEM_HEIGHT)));
      rows.add(row);
      return new RecyclerView.ViewHolder(row) { };
    }

    @Override
    public void onBindViewHolder (@NonNull RecyclerView.ViewHolder holder, int position) {
      ((RowView) holder.itemView).setViewer(viewers[position], nameColorSet);
    }

    @Override
    public int getItemCount () {
      return viewers.length;
    }

    void destroy () {
      for (RowView row : rows) {
        row.destroy();
      }
      rows.clear();
    }
  }

  @SuppressLint("ViewConstructor")
  private static final class RowView extends View {
    private final MenuMoreWrap menu;
    private final Tdlib tdlib;
    private final AvatarReceiver avatar;
    private final EmojiStatusHelper emojiStatus;
    private final TextPaint datePaint = newPaint(13f);
    private final Drawable checks;
    private long userId;
    private Text name;
    private String date;

    static TextPaint newPaint (float sizeDp) {
      TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      paint.setTypeface(Fonts.getRobotoRegular());
      paint.setTextSize(Screen.dp(sizeDp));
      return paint;
    }

    RowView (Context context, MenuMoreWrap menu, Tdlib tdlib) {
      super(context);
      this.menu = menu;
      this.tdlib = tdlib;
      this.avatar = new AvatarReceiver(this);
      this.emojiStatus = new EmojiStatusHelper(tdlib, this, null);
      this.checks = Drawables.get(getResources(), R.drawable.vg_mini_checks_16);
      setWillNotDraw(false);
    }

    void setViewer (TdApi.MessageViewer viewer, TextColorSet nameColorSet) {
      this.userId = viewer.userId;
      avatar.requestMessageSender(tdlib, new TdApi.MessageSenderUser(viewer.userId), AvatarReceiver.Options.NONE);
      TdApi.User user = tdlib.cache().user(viewer.userId);
      emojiStatus.updateEmoji(tdlib, user, new TextColorSetOverride(TextColorSets.Regular.NORMAL) {
        @Override
        public long mediaTextComplexColor () {
          return Theme.newComplexColor(true, ColorId.iconActive);
        }
      });
      int maxNameWidth = Screen.dp(MenuMoreWrap.MAX_WIDTH - ROW_TEXT_START - MenuMoreWrap.ITEM_PADDING) -
        (emojiStatus.needDrawEmojiStatus() ? emojiStatus.getWidth() + Screen.dp(3f) : 0);
      String nameText = tdlib.senderName(new TdApi.MessageSenderUser(viewer.userId));
      name = new Text.Builder(nameText, Math.max(1, maxNameWidth), Paints.robotoStyleProvider(16f), nameColorSet).singleLine().build();
      date = viewer.viewDate != 0 ? Lang.getSeenDate(viewer.viewDate, TimeUnit.SECONDS, tdlib.currentTimeMillis()) : null;
      setContentDescription(date != null ? nameText + ", " + date : nameText);
      invalidate();
    }

    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
      super.onLayout(changed, left, top, right, bottom);
      int size = Screen.dp(ROW_AVATAR_SIZE);
      int avatarLeft = Screen.dp(ROW_AVATAR_START);
      if (Lang.rtl()) {
        avatarLeft = getMeasuredWidth() - avatarLeft - size;
      }
      int avatarTop = (getMeasuredHeight() - size) / 2;
      avatar.setBounds(avatarLeft, avatarTop, avatarLeft + size, avatarTop + size);
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      int width = getMeasuredWidth(), height = getMeasuredHeight();
      boolean rtl = Lang.rtl();
      if (avatar.needPlaceholder()) {
        avatar.drawPlaceholder(c);
      }
      avatar.draw(c);
      if (name == null) {
        return;
      }
      int textStart = Screen.dp(ROW_TEXT_START);
      int nameTop = date != null ? Screen.dp(5f) : (height - name.getHeight()) / 2;
      int nameX = rtl ? width - textStart - name.getWidth() : textStart;
      name.draw(c, nameX, nameTop);
      if (emojiStatus.needDrawEmojiStatus()) {
        int emojiX = rtl ? nameX - Screen.dp(3f) - emojiStatus.getWidth() : nameX + name.getWidth() + Screen.dp(3f);
        emojiStatus.draw(c, emojiX, nameTop);
      }
      if (date != null) {
        int color = M3.get(M3.ON_SURFACE_VARIANT, menu.getForcedTheme());
        datePaint.setColor(color);
        float baseline = Screen.dp(39f);
        int checksSize = Screen.dp(CHECKS_SIZE);
        float checksX = rtl ? width - textStart - checksSize : textStart;
        if (checks != null) {
          // The glyph sits in the lower half of its 16dp box: its bottom is on the baseline of the date.
          Drawables.draw(c, checks, checksX, baseline - Screen.dp(12.5f), Paints.getPorterDuffPaint(color));
        }
        float dateWidth = datePaint.measureText(date);
        float dateX = rtl ? checksX - Screen.dp(CHECKS_GAP) - dateWidth : checksX + checksSize + Screen.dp(CHECKS_GAP);
        c.drawText(date, dateX, baseline, datePaint);
      }
    }

    @Override
    protected void onAttachedToWindow () {
      super.onAttachedToWindow();
      avatar.attach();
      emojiStatus.attach();
    }

    @Override
    protected void onDetachedFromWindow () {
      super.onDetachedFromWindow();
      avatar.detach();
      emojiStatus.detach();
    }

    void destroy () {
      avatar.destroy();
      emojiStatus.performDestroy();
    }
  }
}
