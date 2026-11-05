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

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.ui.SettingsAdapter;
import org.thunderdog.challegram.widget.AvatarView;
import org.thunderdog.challegram.widget.M3StateLayerDrawable;
import org.thunderdog.challegram.widget.NoScrollTextView;
import org.thunderdog.challegram.widget.VgSheets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.ColorUtils;
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

  /** Avatar of the counterpart or an icon in a tonal circle. */
  static View newAvatar (Context context, Tdlib tdlib, Info info, float sizeDp) {
    if (info.userId != 0 || info.chatId != 0 || info.sender != null) {
      AvatarView avatarView = new AvatarView(context);
      if (info.userId != 0) {
        avatarView.setUser(tdlib, info.userId, false);
      } else if (info.chatId != 0) {
        avatarView.setChat(tdlib, tdlib.chat(info.chatId));
      } else {
        avatarView.setMessageSender(tdlib, info.sender);
      }
      return avatarView;
    }
    ImageView icon = new ImageView(context);
    icon.setScaleType(ImageView.ScaleType.CENTER);
    GradientDrawable circle = new GradientDrawable();
    circle.setShape(GradientDrawable.OVAL);
    circle.setColor(M3.secondaryContainer());
    icon.setBackground(circle);
    icon.setImageDrawable(Drawables.get(context.getResources(), info.iconRes));
    icon.setColorFilter(M3.onSecondaryContainer());
    return icon;
  }

  static final int COLOR_INCOMING = 0xFF3FB950, COLOR_OUTGOING = 0xFFE5534B;

  static CharSequence amountText (TdApi.StarAmount amount) {
    return Stars.withIcons(Stars.signedAmount(amount), 1.1f);
  }

  static int amountColor (TdApi.StarAmount amount) {
    return Stars.isNegative(amount) ? COLOR_OUTGOING : COLOR_INCOMING;
  }

  /** Row of the list: avatar, title, counterpart and date, signed amount. */
  static final class TransactionView extends LinearLayout {
    private final FrameLayout avatarWrap;
    private final TextView title, subtitle, amount, status;

    TransactionView (Context context) {
      super(context);
      setOrientation(HORIZONTAL);
      setGravity(Gravity.CENTER_VERTICAL);
      setMinimumHeight(Screen.dp(64f));
      setPadding(Screen.dp(16f), Screen.dp(8f), Screen.dp(16f), Screen.dp(8f));
      avatarWrap = new FrameLayout(context);
      addView(avatarWrap, new LayoutParams(Screen.dp(40f), Screen.dp(40f)));

      LinearLayout texts = new LinearLayout(context);
      texts.setOrientation(VERTICAL);
      title = VgSheets.newBody(context, "", false);
      title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
      title.setTypeface(Fonts.getRobotoMedium());
      title.setSingleLine(true);
      title.setEllipsize(TextUtils.TruncateAt.END);
      texts.addView(title);
      subtitle = VgSheets.newCaption(context, "", false);
      subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
      subtitle.setMaxLines(2);
      subtitle.setEllipsize(TextUtils.TruncateAt.END);
      texts.addView(subtitle);
      LayoutParams textsParams = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
      textsParams.leftMargin = Screen.dp(16f);
      textsParams.rightMargin = Screen.dp(8f);
      addView(texts, textsParams);

      LinearLayout right = new LinearLayout(context);
      right.setOrientation(VERTICAL);
      right.setGravity(Gravity.END);
      amount = new NoScrollTextView(context);
      amount.setTypeface(Fonts.getRobotoMedium());
      amount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
      amount.setSingleLine(true);
      right.addView(amount);
      status = VgSheets.newCaption(context, "", false);
      status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
      right.addView(status);
      addView(right, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      setBackground(new M3StateLayerDrawable(null, VgSheets.roundRect(0xffffffff, 0f), ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface())));
      Views.setClickable(this);
    }

    void bind (Tdlib tdlib, TdApi.StarTransaction transaction) {
      Info info = describe(tdlib, transaction);
      avatarWrap.removeAllViews();
      avatarWrap.addView(newAvatar(getContext(), tdlib, info, 40f), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      title.setText(info.title);
      String date = Lang.getModifiedTimestamp(transaction.date, TimeUnit.SECONDS);
      subtitle.setText(StringUtils.isEmpty(info.subtitle) ? date : info.subtitle + "\n" + date);
      amount.setText(amountText(transaction.starAmount));
      amount.setTextColor(amountColor(transaction.starAmount));
      status.setText(info.status != null ? info.status : "");
      status.setVisibility(info.status != null ? VISIBLE : GONE);
    }
  }

  // Details

  public static void showDetails (@NonNull ViewController<?> context, @NonNull Tdlib tdlib, @NonNull TdApi.StarTransaction transaction) {
    Info info = describe(tdlib, transaction);
    VgSheets.showSheet(context, popupLayout -> {
      Context ctx = context.context();
      LinearLayout content = VgSheets.newContent(ctx);
      View avatar = StarsUi.newPhotoView(ctx, tdlib, info.photo, 80f, 16f);
      if (avatar == null) {
        avatar = newAvatar(ctx, tdlib, info, 80f);
      }
      content.addView(avatar, VgSheets.centered(Screen.dp(80f), 12f, 16f));
      content.addView(VgSheets.newTitle(ctx, info.title), VgSheets.wide(0, 4f));
      TextView amountView = VgSheets.newTitle(ctx, amountText(transaction.starAmount));
      amountView.setTextColor(amountColor(transaction.starAmount));
      amountView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f);
      content.addView(amountView, VgSheets.wide(0, info.status != null ? 2f : 20f));
      if (info.status != null) {
        content.addView(VgSheets.newCaption(ctx, info.status, true), VgSheets.wide(0, 20f));
      }

      LinearLayout card = VgSheets.newCard(ctx);
      if (!StringUtils.isEmpty(info.subtitle)) {
        card.addView(newDetailRow(ctx, Lang.getString(Stars.isNegative(transaction.starAmount) ? R.string.vg_StarsTransactionTo : R.string.vg_StarsTransactionRecipient), info.subtitle, null));
      }
      card.addView(newDetailRow(ctx, Lang.getString(R.string.vg_StarsTransactionID), transaction.id, v -> UI.copyText(transaction.id, R.string.vg_StarsTransactionIDCopied)));
      card.addView(newDetailRow(ctx, Lang.getString(R.string.vg_StarsTransactionDate), Lang.getTimestamp(transaction.date, TimeUnit.SECONDS), null));
      content.addView(card, VgSheets.wide(0, 12f));

      TextView terms = VgSheets.newCaption(ctx, StarsUi.linkified(context, Lang.getString(R.string.vg_StarsTransactionTOS), StarsUi.TERMS_URL), true);
      VgSheets.setLinksClickable(terms);
      content.addView(terms, VgSheets.wide(4f, 16f));

      content.addView(VgSheets.newTonalButton(ctx, Lang.getString(R.string.OK), v -> popupLayout.hideWindow(true)), VgSheets.buttonParams(0));
      return content;
    });
  }

  private static View newDetailRow (Context context, CharSequence label, CharSequence value, @Nullable View.OnClickListener onClick) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setMinimumHeight(Screen.dp(48f));
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(Screen.dp(16f), Screen.dp(8f), Screen.dp(16f), Screen.dp(8f));
    TextView labelView = VgSheets.newCaption(context, label, false);
    labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
    row.addView(labelView, new LinearLayout.LayoutParams(Screen.dp(110f), ViewGroup.LayoutParams.WRAP_CONTENT));
    TextView valueView = VgSheets.newBody(context, value, false);
    valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
    valueView.setTextIsSelectable(false);
    row.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    if (onClick != null) {
      ImageView copy = new ImageView(context);
      copy.setScaleType(ImageView.ScaleType.CENTER);
      copy.setImageDrawable(Drawables.get(context.getResources(), R.drawable.baseline_content_copy_24));
      copy.setColorFilter(M3.onSurfaceVariant());
      row.addView(copy, new LinearLayout.LayoutParams(Screen.dp(24f), Screen.dp(24f)));
      row.setOnClickListener(onClick);
      row.setBackground(new M3StateLayerDrawable(null, VgSheets.roundRect(0xffffffff, M3.CORNER_LARGE), ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface())));
      Views.setClickable(row);
    }
    return row;
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
    private @Nullable LinearLayout tabsView;
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

    public View newTabsView (Context ctx) {
      LinearLayout tabs = new LinearLayout(ctx);
      tabs.setOrientation(LinearLayout.HORIZONTAL);
      tabs.setPadding(Screen.dp(16f), Screen.dp(4f), Screen.dp(16f), Screen.dp(8f));
      int[] labels = {R.string.vg_StarsTransactionsAll, R.string.vg_StarsTransactionsIncoming, R.string.vg_StarsTransactionsOutgoing};
      for (int i = 0; i < labels.length; i++) {
        final int tab = i;
        TextView chip = new NoScrollTextView(ctx);
        chip.setGravity(Gravity.CENTER);
        chip.setTypeface(Fonts.getRobotoMedium());
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setPadding(Screen.dp(12f), 0, Screen.dp(12f), 0);
        chip.setText(Lang.getString(labels[i]));
        chip.setOnClickListener(v -> setDirection(tab));
        Views.setClickable(chip);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Screen.dp(36f), 1f);
        if (i > 0) {
          params.leftMargin = Screen.dp(8f);
        }
        tabs.addView(chip, params);
      }
      tabs.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      tabsView = tabs;
      updateTabs();
      return tabs;
    }

    private void updateTabs () {
      if (tabsView == null) {
        return;
      }
      for (int i = 0; i < tabsView.getChildCount(); i++) {
        TextView chip = (TextView) tabsView.getChildAt(i);
        boolean selected = i == direction;
        chip.setTextColor(selected ? M3.onSecondaryContainer() : M3.onSurfaceVariant());
        GradientDrawable shape = VgSheets.roundRect(selected ? M3.secondaryContainer() : 0, 18f);
        if (!selected) {
          shape.setStroke(Screen.dp(1f), M3.outlineVariant());
        }
        chip.setBackground(new M3StateLayerDrawable(shape, VgSheets.roundRect(0xffffffff, 18f), ColorUtils.alphaColor(M3.STATE_PRESSED, M3.onSurface())));
      }
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
