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
package org.thunderdog.challegram.stars;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.android.AnimatorUtils;
import org.thunderdog.challegram.navigation.ViewPagerTopView;
import android.animation.ValueAnimator;
import android.content.Context;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.widget.M3Sheets;
import org.thunderdog.challegram.widget.M3ButtonView;
import org.thunderdog.challegram.widget.InfoHeaderView;
import org.thunderdog.challegram.util.text.TextStyleProvider;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.loader.AvatarReceiver;
import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.SettingsAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;

/**
 * Telegram Stars transactions of the current user or of a bot, as in the official app: «All Transactions /
 * Incoming / Outgoing» tabs, rows with the counterpart and the signed amount, details in a sheet.
 */
public final class StarsTransactions {
  public static final int VIEW_TABS = 40, VIEW_TRANSACTION = 41;
  public static final int LIST_TYPE_TRANSACTION = ListItem.TYPE_CUSTOM - VIEW_TRANSACTION;

  private static final int PAGE_SIZE = 30;

  // Description

  public static final class Info {
    public String title = "", subtitle = "";
    public long userId, chatId;
    public @Nullable TdApi.MessageSender sender;
    public @DrawableRes int iconRes = R.drawable.vg_stars_24;
    public @Nullable String status;
    public @Nullable TdApi.Photo photo;
  }

  public static Info describe (@NonNull Tdlib tdlib, @NonNull TdApi.StarTransaction transaction) {
    Info info = new Info();
    TdApi.StarTransactionType type = transaction.type;
    switch (type.getConstructor()) {
      case TdApi.StarTransactionTypePremiumBotDeposit.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionTopUp);
        info.subtitle = Lang.getString(R.string.vg_StarsTransactionPremiumBot);
        break;
      case TdApi.StarTransactionTypeAppStoreDeposit.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionTopUp);
        info.subtitle = "App Store";
        break;
      case TdApi.StarTransactionTypeGooglePlayDeposit.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionTopUp);
        info.subtitle = "Google Play";
        break;
      case TdApi.StarTransactionTypeFragmentDeposit.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionFragment);
        info.subtitle = "Fragment";
        info.iconRes = R.drawable.baseline_account_balance_wallet_24;
        break;
      case TdApi.StarTransactionTypeUserDeposit.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsGiftReceived);
        info.userId = ((TdApi.StarTransactionTypeUserDeposit) type).userId;
        break;
      case TdApi.StarTransactionTypeGiveawayDeposit.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsGiveawayPrizeReceived);
        info.chatId = ((TdApi.StarTransactionTypeGiveawayDeposit) type).chatId;
        break;
      case TdApi.StarTransactionTypeFragmentWithdrawal.CONSTRUCTOR: {
        info.title = Lang.getString(R.string.vg_StarsTransactionWithdrawFragment);
        info.subtitle = "Fragment";
        info.iconRes = R.drawable.baseline_account_balance_wallet_24;
        TdApi.RevenueWithdrawalState state = ((TdApi.StarTransactionTypeFragmentWithdrawal) type).withdrawalState;
        if (state != null) {
          if (state.getConstructor() == TdApi.RevenueWithdrawalStatePending.CONSTRUCTOR) {
            info.status = Lang.getString(R.string.vg_StarsPending);
          } else if (state.getConstructor() == TdApi.RevenueWithdrawalStateFailed.CONSTRUCTOR) {
            info.status = Lang.getString(R.string.vg_StarsFailed);
          }
        }
        break;
      }
      case TdApi.StarTransactionTypeTelegramAdsWithdrawal.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionAds);
        info.subtitle = "Telegram Ads";
        info.iconRes = R.drawable.vg_campaign_24;
        break;
      case TdApi.StarTransactionTypeTelegramApiUsage.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionFloodskip);
        info.subtitle = Lang.plural(R.string.vg_StarsTransactionMessages, ((TdApi.StarTransactionTypeTelegramApiUsage) type).requestCount);
        break;
      case TdApi.StarTransactionTypeBotPaidMediaPurchase.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarMediaPurchase);
        info.userId = ((TdApi.StarTransactionTypeBotPaidMediaPurchase) type).userId;
        break;
      case TdApi.StarTransactionTypeBotPaidMediaSale.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarMediaSale);
        info.userId = ((TdApi.StarTransactionTypeBotPaidMediaSale) type).userId;
        break;
      case TdApi.StarTransactionTypeChannelPaidMediaPurchase.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarMediaPurchase);
        info.chatId = ((TdApi.StarTransactionTypeChannelPaidMediaPurchase) type).chatId;
        break;
      case TdApi.StarTransactionTypeChannelPaidMediaSale.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarMediaSale);
        info.userId = ((TdApi.StarTransactionTypeChannelPaidMediaSale) type).userId;
        break;
      case TdApi.StarTransactionTypeBotInvoicePurchase.CONSTRUCTOR: {
        TdApi.StarTransactionTypeBotInvoicePurchase purchase = (TdApi.StarTransactionTypeBotInvoicePurchase) type;
        info.userId = purchase.userId;
        info.title = purchase.productInfo != null ? purchase.productInfo.title : "";
        info.photo = purchase.productInfo != null ? purchase.productInfo.photo : null;
        break;
      }
      case TdApi.StarTransactionTypeBotInvoiceSale.CONSTRUCTOR: {
        TdApi.StarTransactionTypeBotInvoiceSale sale = (TdApi.StarTransactionTypeBotInvoiceSale) type;
        info.userId = sale.userId;
        info.title = sale.productInfo != null ? sale.productInfo.title : "";
        info.photo = sale.productInfo != null ? sale.productInfo.photo : null;
        break;
      }
      case TdApi.StarTransactionTypeBotSubscriptionPurchase.CONSTRUCTOR: {
        TdApi.StarTransactionTypeBotSubscriptionPurchase purchase = (TdApi.StarTransactionTypeBotSubscriptionPurchase) type;
        info.userId = purchase.userId;
        info.title = purchase.productInfo != null && !StringUtils.isEmpty(purchase.productInfo.title) ? purchase.productInfo.title : Lang.getString(R.string.vg_StarsSubscriptionTitle);
        info.status = Lang.getString(R.string.vg_StarsTransactionSubscriptionMonthly);
        break;
      }
      case TdApi.StarTransactionTypeBotSubscriptionSale.CONSTRUCTOR: {
        TdApi.StarTransactionTypeBotSubscriptionSale sale = (TdApi.StarTransactionTypeBotSubscriptionSale) type;
        info.userId = sale.userId;
        info.title = sale.productInfo != null && !StringUtils.isEmpty(sale.productInfo.title) ? sale.productInfo.title : Lang.getString(R.string.vg_StarsSubscriptionTitle);
        break;
      }
      case TdApi.StarTransactionTypeChannelSubscriptionPurchase.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsSubscriptionTitle);
        info.chatId = ((TdApi.StarTransactionTypeChannelSubscriptionPurchase) type).chatId;
        info.status = Lang.getString(R.string.vg_StarsTransactionSubscriptionMonthly);
        break;
      case TdApi.StarTransactionTypeChannelSubscriptionSale.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsSubscriptionTitle);
        info.userId = ((TdApi.StarTransactionTypeChannelSubscriptionSale) type).userId;
        break;
      case TdApi.StarTransactionTypeChannelPaidReactionSend.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsReactionsSent);
        info.chatId = ((TdApi.StarTransactionTypeChannelPaidReactionSend) type).chatId;
        break;
      case TdApi.StarTransactionTypeChannelPaidReactionReceive.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsReactionsSent);
        info.userId = ((TdApi.StarTransactionTypeChannelPaidReactionReceive) type).userId;
        break;
      case TdApi.StarTransactionTypeAffiliateProgramCommission.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionAffiliate);
        info.chatId = ((TdApi.StarTransactionTypeAffiliateProgramCommission) type).chatId;
        break;
      case TdApi.StarTransactionTypePaidMessageSend.CONSTRUCTOR: {
        TdApi.StarTransactionTypePaidMessageSend send = (TdApi.StarTransactionTypePaidMessageSend) type;
        info.title = Lang.plural(R.string.vg_StarsTransactionMessageFee, send.messageCount);
        info.chatId = send.chatId;
        break;
      }
      case TdApi.StarTransactionTypePaidMessageReceive.CONSTRUCTOR: {
        TdApi.StarTransactionTypePaidMessageReceive receive = (TdApi.StarTransactionTypePaidMessageReceive) type;
        info.title = Lang.plural(R.string.vg_StarsTransactionMessageFee, receive.messageCount);
        info.sender = receive.senderId;
        break;
      }
      case TdApi.StarTransactionTypePaidGroupCallMessageSend.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionLiveFee);
        info.chatId = ((TdApi.StarTransactionTypePaidGroupCallMessageSend) type).chatId;
        break;
      case TdApi.StarTransactionTypePaidGroupCallMessageReceive.CONSTRUCTOR:
      case TdApi.StarTransactionTypePaidGroupCallReactionSend.CONSTRUCTOR:
      case TdApi.StarTransactionTypePaidGroupCallReactionReceive.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionLiveFee);
        break;
      case TdApi.StarTransactionTypeSuggestedPostPaymentSend.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionSuggestedPost);
        info.chatId = ((TdApi.StarTransactionTypeSuggestedPostPaymentSend) type).chatId;
        break;
      case TdApi.StarTransactionTypeSuggestedPostPaymentReceive.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionSuggestedPost);
        info.userId = ((TdApi.StarTransactionTypeSuggestedPostPaymentReceive) type).userId;
        break;
      case TdApi.StarTransactionTypePremiumPurchase.CONSTRUCTOR:
        info.title = "Telegram Premium";
        info.userId = ((TdApi.StarTransactionTypePremiumPurchase) type).userId;
        break;
      case TdApi.StarTransactionTypeBusinessBotTransferSend.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionTransfer);
        info.userId = ((TdApi.StarTransactionTypeBusinessBotTransferSend) type).userId;
        break;
      case TdApi.StarTransactionTypeBusinessBotTransferReceive.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionTransfer);
        info.userId = ((TdApi.StarTransactionTypeBusinessBotTransferReceive) type).userId;
        break;
      case TdApi.StarTransactionTypePublicPostSearch.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionPostSearch);
        break;
      case TdApi.StarTransactionTypeGiftAuctionBid.CONSTRUCTOR:
      case TdApi.StarTransactionTypeGiftPurchase.CONSTRUCTOR:
      case TdApi.StarTransactionTypeGiftPurchaseOffer.CONSTRUCTOR:
      case TdApi.StarTransactionTypeGiftTransfer.CONSTRUCTOR:
      case TdApi.StarTransactionTypeGiftOriginalDetailsDrop.CONSTRUCTOR:
      case TdApi.StarTransactionTypeGiftSale.CONSTRUCTOR:
      case TdApi.StarTransactionTypeGiftUpgrade.CONSTRUCTOR:
      case TdApi.StarTransactionTypeGiftUpgradePurchase.CONSTRUCTOR:
      case TdApi.StarTransactionTypeUpgradedGiftPurchase.CONSTRUCTOR:
      case TdApi.StarTransactionTypeUpgradedGiftSale.CONSTRUCTOR:
        info.title = Lang.getString(R.string.vg_StarsTransactionGift);
        info.iconRes = R.drawable.baseline_gift_outline_24;
        break;
      case TdApi.StarTransactionTypeUnsupported.CONSTRUCTOR:
      default:
        info.title = Lang.getString(R.string.vg_StarsTransactionUnsupported);
        break;
    }
    if (info.userId != 0) {
      String name = tdlib.cache().userName(info.userId);
      if (StringUtils.isEmpty(info.subtitle)) {
        info.subtitle = name;
      }
      if (StringUtils.isEmpty(info.title)) {
        info.title = name;
      }
    } else if (info.chatId != 0) {
      String name = tdlib.chatTitle(info.chatId);
      if (StringUtils.isEmpty(info.subtitle)) {
        info.subtitle = name;
      }
      if (StringUtils.isEmpty(info.title)) {
        info.title = name;
      }
    } else if (info.sender != null) {
      String name = tdlib.senderName(info.sender);
      if (StringUtils.isEmpty(info.subtitle)) {
        info.subtitle = name;
      }
    }
    if (transaction.isRefund) {
      info.status = Lang.getString(R.string.vg_StarsRefunded);
    }
    return info;
  }

  // Rows

  /** Incoming amounts: the positive colour of the theme; outgoing: M3 error. */
  static final TextColorSet INCOMING_COLORS = () -> Theme.getColor(ColorId.iconPositive);
  static final TextColorSet OUTGOING_COLORS = M3::error;

  static TextColorSet amountColors (TdApi.StarAmount amount) {
    return Stars.isNegative(amount) ? OUTGOING_COLORS : INCOMING_COLORS;
  }

  static @Nullable TdApi.MessageSender senderOf (Info info) {
    if (info.userId != 0) {
      return new TdApi.MessageSenderUser(info.userId);
    }
    if (info.chatId != 0) {
      return new TdApi.MessageSenderChat(info.chatId);
    }
    return info.sender;
  }

  /**
   * Row of the list in one view (M3 list item, three lines): 40dp avatar of the counterpart or an icon in a tonal
   * circle, title (body large), counterpart and date (body medium), signed amount and status at the end. Texts are
   * drawn by the {@link Text} engine, colours are read when drawing.
   */
  static final class TransactionView extends View {
    private static final float AVATAR_SIZE = 40f, PADDING = 16f;
    private static TextStyleProvider titleStyle, subtitleStyle, statusStyle;

    private final AvatarReceiver avatarReceiver;
    private @Nullable Tdlib tdlib;
    private @Nullable TdApi.StarTransaction transaction;
    private boolean hasAvatar;
    private @DrawableRes int iconRes;
    private String titleString = "", subtitleString = "", amountString = "";
    private @Nullable String statusString;
    private TextColorSet amountColors = INCOMING_COLORS;
    private @Nullable Text title, subtitle, amount, status;
    private int builtWidth;

    TransactionView (Context context) {
      super(context);
      avatarReceiver = new AvatarReceiver(this);
      setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      Views.setClickable(this);
    }

    void bind (Tdlib tdlib, TdApi.StarTransaction transaction) {
      this.tdlib = tdlib;
      this.transaction = transaction;
      Info info = describe(tdlib, transaction);
      TdApi.MessageSender sender = senderOf(info);
      hasAvatar = sender != null;
      if (hasAvatar) {
        avatarReceiver.requestMessageSender(tdlib, sender, AvatarReceiver.Options.NONE);
      } else {
        avatarReceiver.clear();
      }
      iconRes = info.iconRes;
      titleString = info.title;
      String date = Lang.getModifiedTimestamp(transaction.date, TimeUnit.SECONDS);
      subtitleString = StringUtils.isEmpty(info.subtitle) ? date : info.subtitle + "\n" + date;
      amountString = Stars.signedAmount(transaction.starAmount);
      amountColors = amountColors(transaction.starAmount);
      statusString = info.status;
      builtWidth = 0;
      requestLayout();
      invalidate();
    }

    private static TextStyleProvider style (int kind) {
      if (titleStyle == null) {
        titleStyle = Paints.robotoStyleProvider(16f);
        subtitleStyle = Paints.robotoStyleProvider(14f);
        statusStyle = Paints.robotoStyleProvider(12f);
      }
      return kind == 0 ? titleStyle : kind == 1 ? subtitleStyle : statusStyle;
    }

    private void buildTexts (int width) {
      if (builtWidth == width) {
        return;
      }
      builtWidth = width;
      int padding = Screen.dp(PADDING);
      amount = new Text.Builder(tdlib, amountString, null, width / 2, style(0), amountColors, null).allBold().singleLine().build();
      status = !StringUtils.isEmpty(statusString) ? new Text.Builder(tdlib, statusString, null, width / 3, style(2), TextColorSets.Regular.LIGHT, null).singleLine().build() : null;
      int endWidth = Math.max(amount.getWidth(), status != null ? status.getWidth() : 0);
      int textWidth = Math.max(0, width - padding * 2 - Screen.dp(AVATAR_SIZE) - padding - Screen.dp(8f) - endWidth);
      title = new Text.Builder(tdlib, titleString, null, textWidth, style(0), TextColorSets.Regular.NORMAL, null).allBold().singleLine().build();
      subtitle = new Text.Builder(tdlib, subtitleString, null, textWidth, style(1), TextColorSets.Regular.LIGHT, null).maxLineCount(2).build();
    }

    @Override
    protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
      int width = MeasureSpec.getSize(widthMeasureSpec);
      buildTexts(width);
      int textsHeight = (title != null ? title.getHeight() : 0) + (subtitle != null ? subtitle.getHeight() : 0);
      int height = Math.max(Screen.dp(72f), textsHeight + Screen.dp(12f) * 2);
      setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      int width = getMeasuredWidth(), height = getMeasuredHeight();
      buildTexts(width);
      boolean rtl = Lang.rtl();
      int padding = Screen.dp(PADDING), avatarSize = Screen.dp(AVATAR_SIZE);
      int avatarLeft = rtl ? width - padding - avatarSize : padding;
      int avatarTop = (height - avatarSize) / 2;
      if (hasAvatar) {
        avatarReceiver.setBounds(avatarLeft, avatarTop, avatarLeft + avatarSize, avatarTop + avatarSize);
        if (avatarReceiver.needPlaceholder()) {
          avatarReceiver.drawPlaceholder(c);
        }
        avatarReceiver.draw(c);
      } else {
        float radius = avatarSize / 2f;
        c.drawCircle(avatarLeft + radius, avatarTop + radius, radius, Paints.fillingPaint(M3.secondaryContainer()));
        Drawable icon = Drawables.get(getResources(), iconRes);
        if (icon != null) {
          Drawables.drawCentered(c, icon, avatarLeft + radius, avatarTop + radius, Paints.getPorterDuffPaint(M3.onSecondaryContainer()));
        }
      }
      int textsHeight = (title != null ? title.getHeight() : 0) + (subtitle != null ? subtitle.getHeight() : 0);
      int y = (height - textsHeight) / 2;
      int textStart = padding + avatarSize + padding;
      if (title != null) {
        title.draw(c, rtl ? width - textStart - title.getWidth() : textStart, y);
        y += title.getHeight();
      }
      if (subtitle != null) {
        subtitle.draw(c, rtl ? width - textStart - subtitle.getWidth() : textStart, y);
      }
      if (amount != null) {
        int endHeight = amount.getHeight() + (status != null ? status.getHeight() : 0);
        int endY = (height - endHeight) / 2;
        amount.draw(c, rtl ? padding : width - padding - amount.getWidth(), endY);
        if (status != null) {
          status.draw(c, rtl ? padding : width - padding - status.getWidth(), endY + amount.getHeight());
        }
      }
    }

    @Override
    protected void onAttachedToWindow () {
      super.onAttachedToWindow();
      avatarReceiver.attach();
    }

    @Override
    protected void onDetachedFromWindow () {
      super.onDetachedFromWindow();
      avatarReceiver.detach();
    }
  }

  // Details

  /** Details of a transaction in an M3 sheet: Telegram X rows of a {@link SettingsAdapter}. */
  public static void showDetails (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @NonNull TdApi.StarTransaction transaction) {
    Info info = describe(tdlib, transaction);
    final PopupLayout[] popup = new PopupLayout[1];
    SettingsAdapter adapter = new SettingsAdapter(context, v -> {
      int id = v.getId();
      if (id == R.id.btn_vgStarsTransaction) {
        UI.copyText(transaction.id, R.string.vg_StarsTransactionIDCopied);
      } else if (id == R.id.btn_done && popup[0] != null) {
        popup[0].hideWindow(true);
      }
    }, context) {
      @Override
      protected void setInfoHeader (ListItem item, InfoHeaderView view, boolean isUpdate) {
        TdApi.MessageSender sender = senderOf(info);
        if (!view.setPhoto(tdlib, info.photo, 80f, 16f)) {
          if (sender != null) {
            view.setAvatar(tdlib, sender, 80f);
          } else {
            view.setIconSymbol(info.iconRes, 80f);
          }
        }
        view.setTexts(tdlib, info.title, StarsUi.linkText(Lang.getString(R.string.vg_StarsTransactionTOS), StarsUi.TERMS_URL));
        view.setAmount(Stars.signedAmount(transaction.starAmount), amountColors(transaction.starAmount), 22f);
        view.setCaption(info.status);
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        view.setData(item.getStringValue());
      }
    };
    adapter.setSegmented();
    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_INFO_HEADER));
    if (!StringUtils.isEmpty(info.subtitle)) {
      items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, 0, R.drawable.baseline_person_24, Stars.isNegative(transaction.starAmount) ? R.string.vg_StarsTransactionTo : R.string.vg_StarsTransactionRecipient).setStringValue(info.subtitle));
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    }
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_vgStarsTransaction, R.drawable.baseline_content_copy_24, R.string.vg_StarsTransactionID).setStringValue(transaction.id));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, 0, R.drawable.baseline_date_range_24, R.string.vg_StarsTransactionDate).setStringValue(Lang.getTimestamp(transaction.date, TimeUnit.SECONDS)));
    items.add(new ListItem(ListItem.TYPE_M3_BUTTON, R.id.btn_done, 0, R.string.OK).setIntValue(M3ButtonView.STYLE_TONAL));
    adapter.setItems(items, false);
    popup[0] = M3Sheets.showList(context, adapter);
  }

  // List section

  /** Tabs and pages of transactions at the end of a SettingsAdapter list. */
  public static final class Section {
    private final ViewController<?> context;
    private final Tdlib tdlib;
    private final TdApi.MessageSender ownerId;
    private SettingsAdapter adapter;
    private int direction; // 0: all, 1: incoming, 2: outgoing
    private @Nullable String nextOffset;
    private boolean isLoading;
    private int requestId;
    private final List<TdApi.StarTransaction> transactions = new ArrayList<>();
    private @Nullable ViewPagerTopView tabsView;
    /** Balance of the owner from the first page (before updateOwnedStarCount arrives). */
    public @Nullable me.vkryl.core.lambda.RunnableData<TdApi.StarAmount> onBalanceLoaded;

    public Section (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @NonNull TdApi.MessageSender ownerId) {
      this.context = context;
      this.tdlib = tdlib;
      this.ownerId = ownerId;
    }

    /** Header and tabs; the transactions follow when loaded. */
    public void addItems (List<ListItem> items) {
      items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.vg_StarsTransactions));
      items.add(new ListItem(ListItem.TYPE_CUSTOM - VIEW_TABS, R.id.btn_vgStarsTabs));
      items.add(new ListItem(ListItem.TYPE_PROGRESS, R.id.btn_vgStarsProgress));
    }

    public void attach (@NonNull SettingsAdapter adapter, @NonNull RecyclerView recyclerView) {
      this.adapter = adapter;
      recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled (@NonNull RecyclerView recyclerView, int dx, int dy) {
          LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
          if (manager != null && manager.findLastVisibleItemPosition() >= adapter.getItemCount() - 4) {
            loadMore();
          }
        }
      });
      reload();
    }

    /** Filter chips "All / Incoming / Outgoing": TGX {@link ViewPagerTopView} in the M3 filter chip style. */
    public View newTabsView (Context ctx) {
      ViewPagerTopView chips = new ViewPagerTopView(ctx);
      chips.setChipStyle(true);
      chips.setItemPadding(Screen.dp(ViewPagerTopView.CHIP_ITEM_PADDING));
      chips.setItemSpacing(0);
      chips.setItems(new String[] {
        Lang.getString(R.string.vg_StarsTransactionsAll),
        Lang.getString(R.string.vg_StarsTransactionsIncoming),
        Lang.getString(R.string.vg_StarsTransactionsOutgoing)
      });
      chips.setSelectionFactor(direction);
      chips.setOnItemClickListener(this::setDirection);
      chips.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Lang.rtl() ? Gravity.RIGHT : Gravity.LEFT));
      context.addThemeInvalidateListener(chips);

      FrameLayoutFix wrap = new FrameLayoutFix(ctx);
      // The first chip starts at the 16dp content margin: the chip draws its own 4dp half-gap.
      int edge = Screen.dp(16f) - Screen.dp(ViewPagerTopView.CHIP_INSET);
      wrap.setPadding(edge, 0, edge, 0);
      wrap.addView(chips);
      wrap.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(48f)));
      tabsView = chips;
      return wrap;
    }

    private @Nullable ValueAnimator tabsAnimator;

    private void updateTabs () {
      ViewPagerTopView chips = tabsView;
      if (chips == null) {
        return;
      }
      if (tabsAnimator != null) {
        tabsAnimator.cancel();
      }
      float from = chips.getSelectionFactor();
      float to = direction;
      if (!chips.isAttachedToWindow()) {
        chips.setSelectionFactor(to);
        return;
      }
      tabsAnimator = ValueAnimator.ofFloat(from, to);
      tabsAnimator.setDuration(180L);
      tabsAnimator.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
      tabsAnimator.addUpdateListener(animation -> chips.setSelectionFactor((float) animation.getAnimatedValue()));
      tabsAnimator.start();
    }

    public View newTransactionView (Context ctx) {
      TransactionView view = new TransactionView(ctx);
      view.setOnClickListener(v -> {
        Object data = v.getTag(R.id.vg_tagTransaction);
        if (data instanceof TdApi.StarTransaction) {
          showDetails(context, tdlib, (TdApi.StarTransaction) data);
        }
      });
      return view;
    }

    public void bindTransaction (View view, ListItem item) {
      TdApi.StarTransaction transaction = (TdApi.StarTransaction) item.getData();
      view.setTag(R.id.vg_tagTransaction, transaction);
      ((TransactionView) view).bind(tdlib, transaction);
    }

    private void setDirection (int direction) {
      if (this.direction != direction) {
        this.direction = direction;
        updateTabs();
        reload();
      }
    }

    public void reload () {
      transactions.clear();
      nextOffset = "";
      isLoading = false;
      requestId++;
      if (adapter == null) {
        return;
      }
      int tabsIndex = adapter.indexOfViewById(R.id.btn_vgStarsTabs);
      if (tabsIndex == -1) {
        return;
      }
      int from = tabsIndex + 1;
      int count = adapter.getItems().size() - from;
      if (count > 0) {
        adapter.removeRange(from, count);
      }
      adapter.addItems(from, new ListItem(ListItem.TYPE_PROGRESS, R.id.btn_vgStarsProgress));
      loadMore();
    }

    private TdApi.TransactionDirection directionObject () {
      switch (direction) {
        case 1:
          return new TdApi.TransactionDirectionIncoming();
        case 2:
          return new TdApi.TransactionDirectionOutgoing();
      }
      return null;
    }

    private void loadMore () {
      if (isLoading || nextOffset == null || adapter == null) {
        return;
      }
      isLoading = true;
      final int currentRequestId = requestId;
      tdlib.send(new TdApi.GetStarTransactions(ownerId, "", directionObject(), nextOffset, PAGE_SIZE), (result, error) -> UI.post(() -> {
        if (context.isDestroyed() || currentRequestId != requestId) {
          return;
        }
        isLoading = false;
        int progressIndex = adapter.indexOfViewById(R.id.btn_vgStarsProgress);
        if (error != null) {
          nextOffset = null;
          if (progressIndex != -1) {
            adapter.removeItem(progressIndex);
          }
          UI.showError(error);
          return;
        }
        boolean wasEmpty = transactions.isEmpty();
        if (wasEmpty && onBalanceLoaded != null && result.starAmount != null) {
          onBalanceLoaded.runWithData(result.starAmount);
        }
        nextOffset = StringUtils.isEmpty(result.nextOffset) ? null : result.nextOffset;
        List<ListItem> newItems = new ArrayList<>();
        if (wasEmpty && result.transactions.length > 0) {
          newItems.add(new ListItem(ListItem.TYPE_SHADOW_TOP, R.id.btn_vgStarsListTop));
        }
        for (TdApi.StarTransaction transaction : result.transactions) {
          if (!transactions.isEmpty() || newItems.size() > 1) {
            newItems.add(new ListItem(ListItem.TYPE_SEPARATOR));
          }
          transactions.add(transaction);
          newItems.add(new ListItem(LIST_TYPE_TRANSACTION, R.id.btn_vgStarsTransaction).setData(transaction));
        }
        if (progressIndex != -1) {
          adapter.removeItem(progressIndex);
        }
        int bottomIndex = adapter.indexOfViewById(R.id.btn_vgStarsListBottom);
        int insertAt = bottomIndex != -1 ? bottomIndex : adapter.getItems().size();
        if (wasEmpty) {
          if (transactions.isEmpty()) {
            newItems.add(new ListItem(ListItem.TYPE_DESCRIPTION_CENTERED, R.id.btn_vgStarsEmpty, 0, R.string.vg_StarsTransactionsEmpty));
          } else {
            newItems.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM, R.id.btn_vgStarsListBottom));
          }
          insertAt = adapter.getItems().size();
        }
        if (!newItems.isEmpty()) {
          adapter.addItems(insertAt, newItems.toArray(new ListItem[0]));
        }
        if (nextOffset != null) {
          adapter.addItems(adapter.getItems().size(), new ListItem(ListItem.TYPE_PROGRESS, R.id.btn_vgStarsProgress));
        }
      }));
    }
  }
}
