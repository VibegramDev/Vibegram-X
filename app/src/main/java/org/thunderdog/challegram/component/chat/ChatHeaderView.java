/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 10/08/2015 at 19:56
 */
package org.thunderdog.challegram.component.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TopicIcon;
import org.thunderdog.challegram.data.AvatarPlaceholder;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.data.ThreadInfo;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.loader.gif.GifFile;
import org.thunderdog.challegram.navigation.ComplexHeaderView;
import org.thunderdog.challegram.navigation.HeaderButton;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.ConnectionState;
import org.thunderdog.challegram.telegram.GlobalConnectionListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.telegram.TdlibAccentColor;
import org.thunderdog.challegram.telegram.TdlibEmojiManager;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeDeprecated;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Letters;

import me.vkryl.core.StringUtils;
import tgx.td.ChatId;

public class ChatHeaderView extends ComplexHeaderView {
  public interface Callback {
    void onChatHeaderClick ();
  }

  private Callback callback;

  /** Icon of the forum topic in place of the chat avatar: the letter as the avatar placeholder, the custom emoji over it. */
  private @Nullable TopicIcon topicIcon;
  private final ComplexReceiver topicIconReceiver;

  public ChatHeaderView (Context context, Tdlib tdlib, @Nullable ViewController<?> parent) {
    super(context, tdlib, parent);
    setPhotoOpenDisabled(true);
    setOnClickListener(v -> {
      if (callback != null) {
        callback.onChatHeaderClick();
      }
    });
    setUseDefaultClickListener(true);
    setBackgroundResource(ThemeDeprecated.headerSelector());
    setInnerMargins(Screen.dp(56f), Screen.dp(49f));
    setAllowCenterContent(true);
    topicIconReceiver = new ComplexReceiver(this);
  }

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    topicIconReceiver.attach();
    TdlibManager.instance().global().addConnectionListener(connectionListener);
    refreshConnection();
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    topicIconReceiver.detach();
    TdlibManager.instance().global().removeConnectionListener(connectionListener);
  }

  @Override
  public void performDestroy () {
    super.performDestroy();
    clearTopicEmoji();
    topicIconReceiver.performDestroy();
  }

  private void clearTopicEmoji () {
    if (topicIcon != null) {
      topicIcon.performDestroy();
      topicIcon = null;
    }
    topicIconReceiver.clear();
  }

  private CharSequence forcedSubtitle;

  public void setForcedSubtitle (CharSequence subtitle) {
    if (!StringUtils.equalsOrBothEmpty(forcedSubtitle, subtitle)) {
      this.forcedSubtitle = subtitle;
      setNoStatus(!StringUtils.isEmpty(subtitle));
      if (hasSubtitle()) {
        setSubtitle(subtitle);
      }
    }
  }

  public void setCallback (Callback callback) {
    this.callback = callback;
  }

  /**
   * Centered titles (iOS-like header): the avatar is drawn in place of the icon of the "more"
   * button, which has to be the last button of the menu; the transparent button keeps opening it.
   */
  public static void bindAvatarButton (@Nullable View moreButton) {
    if (moreButton instanceof HeaderButton && HeaderView.needCenterTitle()) {
      ((HeaderButton) moreButton).setImageResource(0);
      moreButton.setContentDescription(Lang.getString(R.string.MoreChatOptions));
    }
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    setMeasuredDimension(widthMeasureSpec, HeaderView.getSize(scaleFactor != 0f, true));
  }

  @Override
  public void setScaleFactor (float scaleFactor, float fromFactor, float toScaleFactor, boolean byScroll) {
    if (this.scaleFactor != scaleFactor) {
      boolean layout = this.scaleFactor == 0f || scaleFactor == 0f;
      super.setScaleFactor(scaleFactor, fromFactor, toScaleFactor, byScroll);
      if (layout) {
        setEnabled(scaleFactor == 0f);
        requestLayout();
      }
    }
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    return callback != null && super.onTouchEvent(e);
  }

  public void setChat (Tdlib tdlib, TdApi.Chat chat, @Nullable ThreadInfo messageThread) {
    setChat(tdlib, chat, messageThread, null);
  }

  public void setChat (Tdlib tdlib, TdApi.Chat chat, @Nullable ThreadInfo messageThread, @Nullable TdApi.ForumTopic forumTopic) {
    this.tdlib = tdlib;
    this.lastChat = chat;
    this.lastThread = messageThread;
    this.lastForumTopic = forumTopic;
    this.lastDirectMessagesTopic = null;

    // Clear previous topic emoji
    clearTopicEmoji();

    if (chat == null) {
      setText("Debug controller", "nobody should find this view");
      return;
    }

    // For forum topics, show topic icon instead of chat avatar
    if (forumTopic != null) {
      TopicIcon icon = new TopicIcon(tdlib, forumTopic.info, Screen.dp(40f), () -> {
        if (topicIcon != null) {
          topicIcon.requestFiles(topicIconReceiver);
        }
        invalidate();
      });
      topicIcon = icon;
      icon.requestFiles(topicIconReceiver);
      getAvatarReceiver().requestPlaceholder(tdlib, icon.placeholder(), AvatarReceiver.Options.FULL_SIZE);
    } else {
      getAvatarReceiver().requestChat(tdlib, chat.id, AvatarReceiver.Options.FULL_SIZE);
    }

    setShowVerify(tdlib.chatVerified(chat));
    setShowScam(tdlib.chatScam(chat));
    setShowFake(tdlib.chatFake(chat));
    setShowMute(tdlib.chatNeedsMuteIcon(chat));
    setShowLock(ChatId.isSecret(chat.id));
    final boolean connected = isConnected();
    setSubtitleLoading(!connected && connectionHasProgress());
    if (forumTopic != null) {
      // Forum topic: show topic name as title, chat name as subtitle
      setEmojiStatus(null);
      setText(forumTopic.info.name, currentSubtitle());
      setExpandedSubtitle(null);
      setUseRedHighlight(false);
      attachChatStatus(chat.id, new TdApi.MessageTopicForum(forumTopic.info.forumTopicId));
    } else if (messageThread != null) {
      setEmojiStatus(null);
      setText(messageThread.chatHeaderTitle(), currentSubtitle());
      setExpandedSubtitle(null);
      setUseRedHighlight(false);
      attachChatStatus(messageThread.getChatId(), messageThread.getMessageTopicId());
    } else {
      setEmojiStatus(tdlib.isSelfChat(chat) ? null : tdlib.chatUser(chat));
      setText(tdlib.chatTitle(chat), currentSubtitle());
      setExpandedSubtitle(connected ? tdlib.status().chatStatusExpanded(chat) : null);
      setUseRedHighlight(tdlib.isRedTeam(chat.id));
      attachChatStatus(chat.id, null);
    }
  }

  /**
   * Topic of a channel direct messages chat: the sender's avatar and name, the chat title below
   * (as a forum topic shows its name above the forum title).
   */
  public void setDirectMessagesTopic (Tdlib tdlib, TdApi.Chat chat, TdApi.DirectMessagesChatTopic topic) {
    this.tdlib = tdlib;
    this.lastChat = chat;
    this.lastThread = null;
    this.lastForumTopic = null;
    this.lastDirectMessagesTopic = topic;
    clearTopicEmoji();
    getAvatarReceiver().requestMessageSender(tdlib, topic.senderId, AvatarReceiver.Options.FULL_SIZE);
    setShowVerify(false);
    setShowScam(false);
    setShowFake(false);
    setShowMute(tdlib.chatNeedsMuteIcon(chat));
    setShowLock(false);
    setSubtitleLoading(!isConnected() && connectionHasProgress());
    setEmojiStatus(null);
    setText(tdlib.senderName(topic.senderId), currentSubtitle());
    setExpandedSubtitle(null);
    setUseRedHighlight(false);
    attachChatStatus(chat.id, new TdApi.MessageTopicDirectMessages(topic.id));
  }

  private @Nullable TdApi.DirectMessagesChatTopic lastDirectMessagesTopic;

  // Updates (new)

  private Tdlib tdlib;
  private TdApi.Chat lastChat;
  private @Nullable ThreadInfo lastThread;
  private @Nullable TdApi.ForumTopic lastForumTopic;

  public void updateUserStatus (TdApi.Chat chat) {
    if (StringUtils.isEmpty(forcedSubtitle)) {
      lastChat = chat;
      setSubtitle(currentSubtitle());
      setExpandedSubtitle(isConnected() && lastThread == null && lastForumTopic == null && lastDirectMessagesTopic == null ? tdlib.status().chatStatusExpanded(chat) : null);
    }
  }

  // Connection state: shown instead of the last seen status, with a spinner

  private boolean isConnected () {
    return tdlib == null || tdlib.connectionState() == ConnectionState.CONNECTED;
  }

  private boolean connectionHasProgress () {
    return tdlib != null && tdlib.connectionState() != ConnectionState.WAITING_FOR_NETWORK;
  }

  private CharSequence currentSubtitle () {
    if (!StringUtils.isEmpty(forcedSubtitle)) {
      return forcedSubtitle;
    }
    if (!isConnected()) {
      return tdlib.connectionStateText();
    }
    if ((lastForumTopic != null || lastDirectMessagesTopic != null) && lastChat != null) {
      return tdlib.chatTitle(lastChat);
    }
    if (lastThread != null) {
      return lastThread.chatHeaderSubtitle();
    }
    return tdlib.status().chatStatus(lastChat);
  }

  private void refreshConnection () {
    if (lastChat == null || tdlib == null || !StringUtils.isEmpty(forcedSubtitle)) {
      return;
    }
    setSubtitleLoading(!isConnected() && connectionHasProgress());
    setSubtitle(currentSubtitle());
    setExpandedSubtitle(isConnected() && lastThread == null && lastForumTopic == null && lastDirectMessagesTopic == null ? tdlib.status().chatStatusExpanded(lastChat) : null);
  }

  private final GlobalConnectionListener connectionListener = new GlobalConnectionListener() {
    @Override
    public void onConnectionDisplayStatusChanged (Tdlib changed, boolean isCurrent) {
      if (changed == tdlib) {
        refreshConnection();
      }
    }

    @Override
    public void onConnectionStateChanged (Tdlib changed, int newState, boolean isCurrent) {
      if (changed == tdlib) {
        refreshConnection();
      }
    }
  };

  @Override
  protected void onDraw (@NonNull Canvas c) {
    super.onDraw(c);

    // The custom emoji of the topic over the letter placeholder, once loaded
    if (topicIcon != null && !topicIcon.needPlaceholder()) {
      AvatarReceiver avatarReceiver = getAvatarReceiver();
      int left = avatarReceiver.getLeft(), top = avatarReceiver.getTop(), right = avatarReceiver.getRight(), bottom = avatarReceiver.getBottom();
      c.drawCircle((left + right) / 2f, (top + bottom) / 2f, (right - left) / 2f, Paints.fillingPaint(Theme.headerColor()));
      topicIcon.draw(c, topicIconReceiver, left, top, right, bottom);
    }
  }
}
