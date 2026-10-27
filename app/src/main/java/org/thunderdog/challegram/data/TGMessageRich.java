/*
 * This file is a part of Vibegram X
 *
 * Rich messages (MessageRichMessage): structured content made of page blocks
 * (headings, paragraphs, lists, quotes, tables, collapsible sections, media,
 * documents, formulas and buttons), drawn right inside the message bubble.
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewParent;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.component.MediaCollectorDelegate;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.mediaview.MediaViewDelegate;
import org.thunderdog.challegram.mediaview.MediaViewThumbLocation;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.mediaview.data.MediaStack;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.TextStyleProvider;
import org.thunderdog.challegram.util.text.TextWrapper;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import me.vkryl.core.ColorUtils;
import me.vkryl.core.StringUtils;
import tgx.td.Td;

public class TGMessageRich extends TGMessage implements MediaWrapper.OnClickListener, MediaCollectorDelegate, MediaViewDelegate {
  private static final float BLOCK_SPACING = 8f;
  private static final float QUOTE_PADDING = 10f;
  private static final float LIST_GAP = 6f;
  private static final float CELL_PADDING = 6f;
  private static final float SCROLLBAR_SPACE = 10f;
  /** Wide tables keep natural column widths and scroll horizontally instead of being squeezed */
  private static final boolean ALLOW_TABLE_SCROLL = true;

  private static final long FILE_KEY_OFFSET = 1L << 40;

  private TdApi.RichMessage richMessage;
  private boolean fullMessageRequested;

  private final ArrayList<Block> blocks = new ArrayList<>();
  private final ArrayList<TextWrapper> texts = new ArrayList<>();
  private final ArrayList<MosaicWrapper> mosaics = new ArrayList<>();
  private final ArrayList<FileBlock> files = new ArrayList<>();
  private final ArrayList<ButtonsBlock> buttonRows = new ArrayList<>();
  private final ArrayList<MediaWrapper> mediaWrappers = new ArrayList<>();
  private final ArrayList<TdApi.PageBlockCaption> mediaCaptions = new ArrayList<>();
  private final ArrayList<Toggle> toggles = new ArrayList<>();
  private final ArrayList<TableBlock> tables = new ArrayList<>();
  /** Collapsible blocks the user has toggled (value = expanded). Survives rebuilds. */
  private final IdentityHashMap<Object, Boolean> expandedState = new IdentityHashMap<>();

  private int contentWidth, contentHeight;
  private TGInlineKeyboard inlineButtonHost;

  public TGMessageRich (MessagesManager manager, TdApi.Message msg, TdApi.MessageRichMessage content) {
    super(manager, msg);
    setRichMessage(content.message);
  }

  private void setRichMessage (TdApi.RichMessage richMessage) {
    this.richMessage = richMessage;
    parseBlocks();
    if (!richMessage.isFull) {
      requestFullMessage();
    }
  }

  private void requestFullMessage () {
    if (fullMessageRequested || msg.chatId == 0 || msg.id == 0) {
      return;
    }
    fullMessageRequested = true;
    final long chatId = msg.chatId, messageId = msg.id;
    tdlib.send(new TdApi.GetFullRichMessage(chatId, messageId), (fullMessage, error) -> {
      if (fullMessage != null) {
        runOnUiThreadOptional(() -> {
          if (msg.chatId == chatId && msg.id == messageId && !isDestroyed()) {
            msg.content = new TdApi.MessageRichMessage(fullMessage);
            this.richMessage = fullMessage;
            parseBlocks();
            rebuildAndUpdateContent();
            invalidateTextMediaReceiver();
            invalidateContentReceiver();
          }
        });
      }
    });
  }

  // Content updates

  @Override
  protected boolean onMessageContentChanged (TdApi.Message message, TdApi.MessageContent oldContent, TdApi.MessageContent newContent, boolean isBottomMessage) {
    return updateMessageContent(message, newContent, isBottomMessage);
  }

  @Override
  protected boolean updateMessageContent (TdApi.Message message, TdApi.MessageContent newContent, boolean isBottomMessage) {
    if (newContent.getConstructor() != TdApi.MessageRichMessage.CONSTRUCTOR) {
      return false;
    }
    this.msg.content = newContent;
    this.fullMessageRequested = false;
    setRichMessage(((TdApi.MessageRichMessage) newContent).message);
    rebuildAndUpdateContent();
    invalidateTextMediaReceiver();
    invalidateContentReceiver();
    return true;
  }

  // Parsing

  private void parseBlocks () {
    for (MosaicWrapper mosaic : mosaics) {
      mosaic.destroy();
    }
    for (TextWrapper text : texts) {
      text.performDestroy();
    }
    blocks.clear();
    texts.clear();
    mosaics.clear();
    files.clear();
    buttonRows.clear();
    tables.clear();
    mediaWrappers.clear();
    mediaCaptions.clear();
    if (richMessage != null && richMessage.blocks != null) {
      parseBlocks(richMessage.blocks, blocks);
    }
  }

  private void parseBlocks (TdApi.PageBlock[] in, List<Block> out) {
    for (TdApi.PageBlock block : in) {
      Block parsed = parseBlock(block);
      if (parsed != null) {
        out.add(parsed);
      }
    }
  }

  private TextStyleProvider styleProvider (float sizeDiff) {
    return provider(sizeDiff);
  }

  private static final android.util.SparseArray<TextStyleProvider> providers = new android.util.SparseArray<>();

  private static TextStyleProvider provider (float sizeDiff) {
    int key = Math.round(sizeDiff * 10);
    TextStyleProvider provider = providers.get(key);
    if (provider == null) {
      provider = new TextStyleProvider(Fonts.newRobotoStorage()).setTextSizeDiff(sizeDiff).setTextSize(Settings.instance().getChatFontSize()).setAllowSp(true);
      Settings.instance().addChatFontSizeChangeListener(provider);
      providers.put(key, provider);
    }
    return provider;
  }

  private TextWrapper newText (@Nullable TdApi.RichText richText, float sizeDiff, TextColorSet colorSet) {
    if (richText == null || isEmpty(richText)) {
      return null;
    }
    TdlibUi.UrlOpenParameters openParameters = openParameters();
    TextWrapper wrapper = TextWrapper.parseRichText(controller(), clickCallback(), richText, styleProvider(sizeDiff), colorSet, openParameters, (w, text, specificMedia) -> {
      if (texts.contains(w)) {
        invalidateTextMediaReceiver(text, specificMedia);
      }
    });
    wrapper.setViewProvider(currentViews);
    if (useBubbles()) {
      wrapper.addTextFlags(Text.FLAG_ADJUST_TO_CURRENT_WIDTH);
    }
    texts.add(wrapper);
    return wrapper;
  }

  private static boolean isEmpty (TdApi.RichText richText) {
    return richText.getConstructor() == TdApi.RichTextPlain.CONSTRUCTOR && StringUtils.isEmpty(((TdApi.RichTextPlain) richText).text);
  }

  private static TdApi.RichText bold (TdApi.RichText text) {
    return text != null ? new TdApi.RichTextBold(text) : null;
  }

  private static TdApi.RichText italic (TdApi.RichText text) {
    return text != null ? new TdApi.RichTextItalic(text) : null;
  }

  private static TdApi.RichText fixed (TdApi.RichText text) {
    return text != null ? new TdApi.RichTextFixed(text) : null;
  }

  private static TdApi.RichText plain (String text) {
    return new TdApi.RichTextPlain(text);
  }

  private @Nullable Block textBlock (@Nullable TdApi.RichText richText, float sizeDiff, TextColorSet colorSet, int topMargin) {
    TextWrapper wrapper = newText(richText, sizeDiff, colorSet);
    if (wrapper == null) {
      return null;
    }
    TextBlock block = new TextBlock(wrapper);
    block.marginTop = topMargin;
    return block;
  }

  private Block withCredit (Block content, @Nullable TdApi.RichText credit) {
    TextWrapper creditText = newText(credit, -2f, getDecentColorSet());
    if (creditText == null) {
      return content;
    }
    ArrayList<Block> children = new ArrayList<>(2);
    children.add(content);
    TextBlock creditBlock = new TextBlock(creditText);
    creditBlock.marginTop = Screen.dp(4f);
    children.add(creditBlock);
    return new StackBlock(children, 0, StackBlock.STYLE_PLAIN, 0);
  }

  private @Nullable Block parseBlock (TdApi.PageBlock block) {
    final TextColorSet textColors = getTextColorSet();
    final TextColorSet decentColors = getDecentColorSet();
    switch (block.getConstructor()) {
      case TdApi.PageBlockTitle.CONSTRUCTOR:
        return textBlock(bold(((TdApi.PageBlockTitle) block).title), 6f, textColors, Screen.dp(4f));
      case TdApi.PageBlockSubtitle.CONSTRUCTOR:
        return textBlock(((TdApi.PageBlockSubtitle) block).subtitle, 2f, decentColors, 0);
      case TdApi.PageBlockHeader.CONSTRUCTOR:
        return textBlock(bold(((TdApi.PageBlockHeader) block).header), 4f, textColors, Screen.dp(4f));
      case TdApi.PageBlockSubheader.CONSTRUCTOR:
        return textBlock(bold(((TdApi.PageBlockSubheader) block).subheader), 2f, textColors, Screen.dp(2f));
      case TdApi.PageBlockSectionHeading.CONSTRUCTOR: {
        TdApi.PageBlockSectionHeading heading = (TdApi.PageBlockSectionHeading) block;
        float diff;
        switch (heading.size) {
          case 1: diff = 6f; break;
          case 2: diff = 4f; break;
          case 3: diff = 3f; break;
          case 4: diff = 2f; break;
          case 5: diff = 1f; break;
          default: diff = 0f; break;
        }
        return textBlock(bold(heading.text), diff, textColors, Screen.dp(heading.size <= 3 ? 4f : 2f));
      }
      case TdApi.PageBlockKicker.CONSTRUCTOR:
        return textBlock(bold(((TdApi.PageBlockKicker) block).kicker), -2f, decentColors, 0);
      case TdApi.PageBlockParagraph.CONSTRUCTOR:
        return textBlock(((TdApi.PageBlockParagraph) block).text, 0f, textColors, 0);
      case TdApi.PageBlockFooter.CONSTRUCTOR:
        return textBlock(((TdApi.PageBlockFooter) block).footer, -2f, decentColors, 0);
      case TdApi.PageBlockAuthorDate.CONSTRUCTOR: {
        TdApi.PageBlockAuthorDate authorDate = (TdApi.PageBlockAuthorDate) block;
        TdApi.RichText text = authorDate.author;
        if (authorDate.publishDate != 0) {
          String date = Lang.getDate(authorDate.publishDate, java.util.concurrent.TimeUnit.SECONDS);
          text = isEmpty(text) ? plain(date) : new TdApi.RichTexts(new TdApi.RichText[] {text, plain(" • " + date)});
        }
        return textBlock(text, -2f, decentColors, 0);
      }
      case TdApi.PageBlockPreformatted.CONSTRUCTOR: {
        TdApi.PageBlockPreformatted pre = (TdApi.PageBlockPreformatted) block;
        TextWrapper wrapper = newText(fixed(pre.text), -1f, textColors);
        if (wrapper == null) {
          return null;
        }
        TextBlock textBlock = new TextBlock(wrapper);
        textBlock.style = TextBlock.STYLE_CODE;
        textBlock.label = StringUtils.isEmpty(pre.language) ? null : newText(plain(pre.language), -3f, decentColors);
        return textBlock;
      }
      case TdApi.PageBlockMathematicalExpression.CONSTRUCTOR: {
        String expression = ((TdApi.PageBlockMathematicalExpression) block).expression;
        TextWrapper wrapper = newText(fixed(plain(expression)), 0f, textColors);
        if (wrapper == null) {
          return null;
        }
        TextBlock textBlock = new TextBlock(wrapper);
        textBlock.style = TextBlock.STYLE_CODE;
        return textBlock;
      }
      case TdApi.PageBlockThinking.CONSTRUCTOR: {
        Block text = textBlock(italic(((TdApi.PageBlockThinking) block).text), -1f, decentColors, 0);
        if (text == null) {
          return null;
        }
        ArrayList<Block> children = new ArrayList<>(1);
        children.add(text);
        return new StackBlock(children, Screen.dp(QUOTE_PADDING), StackBlock.STYLE_THINKING, 0);
      }
      case TdApi.PageBlockDivider.CONSTRUCTOR:
        return new DividerBlock();
      case TdApi.PageBlockAnchor.CONSTRUCTOR:
        return null;
      case TdApi.PageBlockBlockQuote.CONSTRUCTOR: {
        TdApi.PageBlockBlockQuote quote = (TdApi.PageBlockBlockQuote) block;
        ArrayList<Block> children = new ArrayList<>();
        parseBlocks(quote.blocks, children);
        TextWrapper credit = newText(quote.credit, -2f, decentColors);
        if (credit != null) {
          children.add(new TextBlock(credit));
        }
        if (children.isEmpty()) {
          return null;
        }
        return new StackBlock(children, Screen.dp(QUOTE_PADDING), StackBlock.STYLE_QUOTE, Screen.dp(4f));
      }
      case TdApi.PageBlockPullQuote.CONSTRUCTOR: {
        TdApi.PageBlockPullQuote quote = (TdApi.PageBlockPullQuote) block;
        ArrayList<Block> children = new ArrayList<>();
        Block text = textBlock(italic(quote.text), 1f, textColors, 0);
        if (text != null) {
          children.add(text);
        }
        TextWrapper credit = newText(quote.credit, -2f, decentColors);
        if (credit != null) {
          children.add(new TextBlock(credit));
        }
        if (children.isEmpty()) {
          return null;
        }
        return new StackBlock(children, Screen.dp(QUOTE_PADDING), StackBlock.STYLE_QUOTE, Screen.dp(4f));
      }
      case TdApi.PageBlockExpandableBlockQuote.CONSTRUCTOR: {
        TdApi.PageBlockExpandableBlockQuote quote = (TdApi.PageBlockExpandableBlockQuote) block;
        TextWrapper text = newText(quote.text, 0f, textColors);
        if (text == null) {
          return null;
        }
        return new ExpandableQuoteBlock(quote, text, newText(quote.credit, -2f, decentColors));
      }
      case TdApi.PageBlockList.CONSTRUCTOR:
        return parseList((TdApi.PageBlockList) block);
      case TdApi.PageBlockDetails.CONSTRUCTOR: {
        TdApi.PageBlockDetails details = (TdApi.PageBlockDetails) block;
        TextWrapper header = newText(bold(details.header), 0f, textColors);
        ArrayList<Block> children = new ArrayList<>();
        parseBlocks(details.blocks, children);
        if (header == null && children.isEmpty()) {
          return null;
        }
        if (header == null) {
          header = newText(plain(Lang.getString(org.thunderdog.challegram.R.string.RichMessageDetails)), 0f, textColors);
        }
        return new DetailsBlock(details, header, new StackBlock(children, 0, StackBlock.STYLE_PLAIN, Screen.dp(BLOCK_SPACING)));
      }
      case TdApi.PageBlockTable.CONSTRUCTOR:
        return parseTable((TdApi.PageBlockTable) block);
      case TdApi.PageBlockPhoto.CONSTRUCTOR: {
        TdApi.PageBlockPhoto photo = (TdApi.PageBlockPhoto) block;
        if (photo.photo == null) {
          return null;
        }
        MediaWrapper wrapper = new MediaWrapper(context(), tdlib, photo.photo, msg.chatId, msg.id, this, false);
        return mediaBlock(singletonList(wrapper), photo.caption, singletonList(photo.caption));
      }
      case TdApi.PageBlockVideo.CONSTRUCTOR: {
        TdApi.PageBlockVideo video = (TdApi.PageBlockVideo) block;
        if (video.video == null) {
          return null;
        }
        MediaWrapper wrapper = new MediaWrapper(context(), tdlib, video.video, null, msg.chatId, msg.id, this, false);
        return mediaBlock(singletonList(wrapper), video.caption, singletonList(video.caption));
      }
      case TdApi.PageBlockAnimation.CONSTRUCTOR: {
        TdApi.PageBlockAnimation animation = (TdApi.PageBlockAnimation) block;
        if (animation.animation == null) {
          return null;
        }
        MediaWrapper wrapper = new MediaWrapper(context(), tdlib, animation.animation, msg.chatId, msg.id, this, false);
        return mediaBlock(singletonList(wrapper), animation.caption, singletonList(animation.caption));
      }
      case TdApi.PageBlockCollage.CONSTRUCTOR:
      case TdApi.PageBlockSlideshow.CONSTRUCTOR: {
        TdApi.PageBlock[] items;
        TdApi.PageBlockCaption caption;
        if (block.getConstructor() == TdApi.PageBlockCollage.CONSTRUCTOR) {
          items = ((TdApi.PageBlockCollage) block).blocks;
          caption = ((TdApi.PageBlockCollage) block).caption;
        } else {
          items = ((TdApi.PageBlockSlideshow) block).blocks;
          caption = ((TdApi.PageBlockSlideshow) block).caption;
        }
        ArrayList<MediaWrapper> wrappers = new ArrayList<>(items.length);
        ArrayList<TdApi.PageBlockCaption> captions = new ArrayList<>(items.length);
        for (TdApi.PageBlock item : items) {
          MediaWrapper wrapper = null;
          TdApi.PageBlockCaption itemCaption = null;
          switch (item.getConstructor()) {
            case TdApi.PageBlockPhoto.CONSTRUCTOR: {
              TdApi.PageBlockPhoto photo = (TdApi.PageBlockPhoto) item;
              if (photo.photo != null) {
                wrapper = new MediaWrapper(context(), tdlib, photo.photo, msg.chatId, msg.id, this, false);
              }
              itemCaption = photo.caption;
              break;
            }
            case TdApi.PageBlockVideo.CONSTRUCTOR: {
              TdApi.PageBlockVideo video = (TdApi.PageBlockVideo) item;
              if (video.video != null) {
                wrapper = new MediaWrapper(context(), tdlib, video.video, null, msg.chatId, msg.id, this, false);
              }
              itemCaption = video.caption;
              break;
            }
            case TdApi.PageBlockAnimation.CONSTRUCTOR: {
              TdApi.PageBlockAnimation animation = (TdApi.PageBlockAnimation) item;
              if (animation.animation != null) {
                wrapper = new MediaWrapper(context(), tdlib, animation.animation, msg.chatId, msg.id, this, false);
              }
              itemCaption = animation.caption;
              break;
            }
          }
          if (wrapper != null) {
            wrappers.add(wrapper);
            captions.add(itemCaption);
          }
        }
        if (wrappers.isEmpty()) {
          return null;
        }
        return mediaBlock(wrappers, caption, captions);
      }
      case TdApi.PageBlockDocument.CONSTRUCTOR: {
        TdApi.PageBlockDocument document = (TdApi.PageBlockDocument) block;
        if (document.document == null) {
          return null;
        }
        FileComponent component = new FileComponent(this, msg, document.document);
        component.setViewProvider(currentViews);
        FileBlock fileBlock = new FileBlock(component, files.size());
        files.add(fileBlock);
        return withCaption(fileBlock, document.caption);
      }
      case TdApi.PageBlockAudio.CONSTRUCTOR: {
        TdApi.PageBlockAudio audio = (TdApi.PageBlockAudio) block;
        if (audio.audio == null) {
          return null;
        }
        String title = TD.getTitle(audio.audio);
        String subtitle = TD.getSubtitle(audio.audio);
        String text = StringUtils.isEmpty(subtitle) ? title : subtitle + " — " + title;
        if (audio.audio.duration > 0) {
          text += " (" + Strings.buildDuration(audio.audio.duration) + ")";
        }
        return withCaption(textBlock(plain("🎵 " + text), 0f, textColors, 0), audio.caption);
      }
      case TdApi.PageBlockVoiceNote.CONSTRUCTOR: {
        TdApi.PageBlockVoiceNote voice = (TdApi.PageBlockVoiceNote) block;
        if (voice.voiceNote == null) {
          return null;
        }
        String text = Lang.getString(org.thunderdog.challegram.R.string.ChatContentVoice);
        if (voice.voiceNote.duration > 0) {
          text += " (" + Strings.buildDuration(voice.voiceNote.duration) + ")";
        }
        return withCaption(textBlock(plain("🎤 " + text), 0f, textColors, 0), voice.caption);
      }
      case TdApi.PageBlockButtonRow.CONSTRUCTOR: {
        TdApi.PageBlockButtonRow row = (TdApi.PageBlockButtonRow) block;
        if (row.buttons == null || row.buttons.length == 0) {
          return null;
        }
        TdApi.InlineKeyboardButton[] buttons = new TdApi.InlineKeyboardButton[row.buttons.length];
        for (int i = 0; i < row.buttons.length; i++) {
          buttons[i] = toKeyboardButton(row.buttons[i]);
        }
        ButtonsBlock buttonsBlock = new ButtonsBlock(new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[][] {buttons}, false));
        buttonRows.add(buttonsBlock);
        return buttonsBlock;
      }
      case TdApi.PageBlockMap.CONSTRUCTOR: {
        TdApi.PageBlockMap map = (TdApi.PageBlockMap) block;
        String coordinates = String.format(java.util.Locale.US, "%.5f, %.5f", map.location.latitude, map.location.longitude);
        String url = "https://maps.google.com/maps?q=" + map.location.latitude + "," + map.location.longitude;
        return withCaption(textBlock(new TdApi.RichTextUrl(plain("📍 " + coordinates), url, false), 0f, textColors, 0), map.caption);
      }
      case TdApi.PageBlockChatLink.CONSTRUCTOR: {
        TdApi.PageBlockChatLink chatLink = (TdApi.PageBlockChatLink) block;
        TdApi.RichText title = plain(chatLink.title);
        if (!StringUtils.isEmpty(chatLink.username)) {
          title = new TdApi.RichTextUrl(title, "https://t.me/" + chatLink.username, false);
        }
        return textBlock(bold(title), 0f, textColors, 0);
      }
      case TdApi.PageBlockEmbedded.CONSTRUCTOR: {
        TdApi.PageBlockEmbedded embedded = (TdApi.PageBlockEmbedded) block;
        if (StringUtils.isEmpty(embedded.url)) {
          return null;
        }
        return withCaption(textBlock(new TdApi.RichTextUrl(plain(embedded.url), embedded.url, false), 0f, textColors, 0), embedded.caption);
      }
      case TdApi.PageBlockEmbeddedPost.CONSTRUCTOR: {
        TdApi.PageBlockEmbeddedPost post = (TdApi.PageBlockEmbeddedPost) block;
        ArrayList<Block> children = new ArrayList<>();
        Block author = textBlock(bold(plain(post.author)), 0f, textColors, 0);
        if (author != null) {
          children.add(author);
        }
        parseBlocks(post.blocks, children);
        if (children.isEmpty()) {
          return null;
        }
        return new StackBlock(children, Screen.dp(QUOTE_PADDING), StackBlock.STYLE_QUOTE, Screen.dp(4f));
      }
      case TdApi.PageBlockRelatedArticles.CONSTRUCTOR: {
        TdApi.PageBlockRelatedArticles related = (TdApi.PageBlockRelatedArticles) block;
        ArrayList<Block> children = new ArrayList<>();
        Block header = textBlock(bold(related.header), 0f, textColors, 0);
        if (header != null) {
          children.add(header);
        }
        for (TdApi.PageBlockRelatedArticle article : related.articles) {
          String title = !StringUtils.isEmpty(article.title) ? article.title : article.url;
          Block item = textBlock(new TdApi.RichTextUrl(plain(title), article.url, false), 0f, textColors, 0);
          if (item != null) {
            children.add(item);
          }
        }
        return children.isEmpty() ? null : new StackBlock(children, 0, StackBlock.STYLE_PLAIN, Screen.dp(4f));
      }
      case TdApi.PageBlockCover.CONSTRUCTOR:
        return parseBlock(((TdApi.PageBlockCover) block).cover);
      case TdApi.PageBlockUnsupported.CONSTRUCTOR:
      default:
        return textBlock(italic(plain(Lang.getString(org.thunderdog.challegram.R.string.RichMessageUnsupportedBlock))), -1f, decentColors, 0);
    }
  }

  private static <T> ArrayList<T> singletonList (T item) {
    ArrayList<T> list = new ArrayList<>(1);
    list.add(item);
    return list;
  }

  private static TdApi.InlineKeyboardButton toKeyboardButton (TdApi.InlineButton button) {
    return new TdApi.InlineKeyboardButton(button.text != null ? TD.getText(button.text) : "", 0, button.style, button.type);
  }

  private Block withCaption (@Nullable Block content, @Nullable TdApi.PageBlockCaption caption) {
    if (content == null || caption == null) {
      return content;
    }
    TextWrapper text = newText(caption.text, -2f, getDecentColorSet());
    TextWrapper credit = newText(caption.credit, -3f, getDecentColorSet());
    if (text == null && credit == null) {
      return content;
    }
    ArrayList<Block> children = new ArrayList<>(3);
    children.add(content);
    if (text != null) {
      TextBlock textBlock = new TextBlock(text);
      textBlock.marginTop = Screen.dp(4f);
      children.add(textBlock);
    }
    if (credit != null) {
      TextBlock creditBlock = new TextBlock(credit);
      creditBlock.marginTop = Screen.dp(2f);
      children.add(creditBlock);
    }
    return new StackBlock(children, 0, StackBlock.STYLE_PLAIN, 0);
  }

  private Block mediaBlock (List<MediaWrapper> wrappers, @Nullable TdApi.PageBlockCaption caption, List<TdApi.PageBlockCaption> itemCaptions) {
    MosaicWrapper mosaic = null;
    for (MediaWrapper wrapper : wrappers) {
      wrapper.setViewProvider(currentViews);
      wrapper.setOnClickListener(this);
      mediaWrappers.add(wrapper);
      if (mosaic == null) {
        mosaic = new MosaicWrapper(wrapper, this);
      } else {
        mosaic.addItem(wrapper, true);
      }
    }
    mediaCaptions.addAll(itemCaptions);
    mosaic.setViewProvider(currentViews);
    mosaics.add(mosaic);
    return withCaption(new MediaBlock(mosaic), caption);
  }

  private Block parseList (TdApi.PageBlockList list) {
    ArrayList<ListBlock.Item> items = new ArrayList<>(list.items.length);
    for (TdApi.PageBlockListItem item : list.items) {
      String label;
      if (item.hasCheckbox) {
        label = item.isChecked ? "☑" : "☐";
      } else if (!StringUtils.isEmpty(item.label)) {
        label = item.label;
      } else {
        label = "•";
      }
      TextWrapper labelText = newText(plain(label), 0f, item.hasCheckbox || !"•".equals(label) ? getTextColorSet() : getDecentColorSet());
      ArrayList<Block> children = new ArrayList<>();
      parseBlocks(item.blocks, children);
      items.add(new ListBlock.Item(labelText, new StackBlock(children, 0, StackBlock.STYLE_PLAIN, Screen.dp(4f))));
    }
    return items.isEmpty() ? null : new ListBlock(items);
  }

  private Block parseTable (TdApi.PageBlockTable table) {
    if (table.cells == null || table.cells.length == 0) {
      return null;
    }
    int columnCount = 0;
    for (TdApi.PageBlockTableCell[] row : table.cells) {
      int count = 0;
      for (TdApi.PageBlockTableCell cell : row) {
        count += Math.max(1, cell.colspan);
      }
      columnCount = Math.max(columnCount, count);
    }
    if (columnCount == 0) {
      return null;
    }
    TableBlock.Cell[][] cells = new TableBlock.Cell[table.cells.length][];
    for (int rowIndex = 0; rowIndex < table.cells.length; rowIndex++) {
      TdApi.PageBlockTableCell[] row = table.cells[rowIndex];
      cells[rowIndex] = new TableBlock.Cell[row.length];
      for (int cellIndex = 0; cellIndex < row.length; cellIndex++) {
        TdApi.PageBlockTableCell cell = row[cellIndex];
        TdApi.RichText text = cell.text;
        if (text != null && cell.isHeader) {
          text = bold(text);
        }
        TextWrapper wrapper = text != null ? newText(text, table.isCompact ? -2f : -1f, getTextColorSet()) : null;
        cells[rowIndex][cellIndex] = new TableBlock.Cell(cell, wrapper);
      }
    }
    TableBlock tableBlock = new TableBlock(table, cells, columnCount);
    tables.add(tableBlock);
    return withCaption(tableBlock, table.caption != null ? new TdApi.PageBlockCaption(table.caption, null) : null);
  }

  // Layout

  @Override
  protected void buildContent (int maxWidth) {
    toggles.clear();
    int y = 0;
    int width = 0;
    boolean first = true;
    for (Block block : blocks) {
      if (!first) {
        y += Screen.dp(BLOCK_SPACING) + block.marginTop;
      }
      block.layout(maxWidth);
      block.y = y;
      y += block.height;
      width = Math.max(width, block.width);
      first = false;
    }
    if (blocks.isEmpty()) {
      TextWrapper empty = newText(italic(plain(Lang.getString(org.thunderdog.challegram.R.string.RichMessageEmpty))), 0f, getDecentColorSet());
      if (empty != null) {
        TextBlock block = new TextBlock(empty);
        blocks.add(block);
        block.layout(maxWidth);
        y = block.height;
        width = block.width;
      }
    }
    this.contentWidth = Math.max(Math.min(width, maxWidth), computeBubbleTimePartWidth(false));
    this.contentHeight = y;
  }

  @Override
  protected int getContentWidth () {
    return contentWidth;
  }

  @Override
  protected int getContentHeight () {
    return contentHeight;
  }

  @Override
  protected int getBottomLineContentWidth () {
    return BOTTOM_LINE_EXPAND_HEIGHT;
  }

  @Override
  protected int getFooterPaddingTop () {
    return Screen.dp(6f);
  }

  // Drawing

  @Override
  public boolean needComplexReceiver () {
    return true;
  }

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth, ComplexReceiver receiver) {
    ComplexReceiver textMediaReceiver = view.getTextMediaReceiver();
    toggles.clear();
    for (TableBlock table : tables) {
      table.isVisible = false;
    }
    for (Block block : blocks) {
      block.draw(view, c, startX, startY + block.y, receiver, textMediaReceiver);
    }
  }

  @Override
  public void requestTextMedia (ComplexReceiver textMediaReceiver) {
    long startKey = 0;
    for (TextWrapper text : texts) {
      int count = text.getMaxMediaCount();
      if (count > 0) {
        text.requestMedia(textMediaReceiver, startKey, count);
        startKey += count;
      }
    }
    textMediaReceiver.clearReceiversWithHigherKey(startKey);
  }

  @Override
  public void requestMediaContent (ComplexReceiver receiver, boolean invalidate, int invalidateArg) {
    for (MosaicWrapper mosaic : mosaics) {
      mosaic.requestFilesKeepOthers(receiver, invalidate);
    }
    for (FileBlock file : files) {
      long key = FILE_KEY_OFFSET + file.index;
      DoubleImageReceiver preview = receiver.getPreviewReceiver(key);
      preview.setTag(this);
      file.component.requestPreview(preview);
      ImageReceiver image = receiver.getImageReceiver(key);
      image.setTag(this);
      file.component.requestContent(image);
    }
    receiver.clearReceivers((receiverType, r, key) -> r.getTag() == this || (r.getTag() instanceof MosaicWrapper && mosaics.contains(r.getTag())));
  }

  @Override
  public void autoDownloadContent (TdApi.ChatType type) {
    for (MosaicWrapper mosaic : mosaics) {
      mosaic.autoDownloadContent(type);
    }
  }

  @Override
  protected void onMessageIdChanged (long oldMessageId, long newMessageId, boolean success) {
    for (MosaicWrapper mosaic : mosaics) {
      mosaic.updateMessageId(oldMessageId, newMessageId, success);
    }
  }

  @Override
  protected void onMessageContainerDestroyed () {
    for (MosaicWrapper mosaic : mosaics) {
      mosaic.destroy();
    }
    for (TextWrapper text : texts) {
      text.performDestroy();
    }
  }

  // Touch

  // Horizontal scrolling of wide tables

  private @Nullable TableBlock touchTable;
  private float touchDownX, touchDownY;
  private int touchStartScrollX;
  private boolean isTableDragging, touchCaughtByContent;
  private @Nullable VelocityTracker velocityTracker;
  private @Nullable OverScroller tableScroller;
  private @Nullable TableBlock flingTable;
  private @Nullable View flingView;

  private @Nullable TableBlock findScrollableTable (float x, float y) {
    for (TableBlock table : tables) {
      if (table.isVisible && table.getMaxScrollX() > 0 &&
        x >= table.drawX && x < table.drawX + table.width &&
        y >= table.drawY && y < table.drawY + table.height) {
        return table;
      }
    }
    return null;
  }

  private static void setDisallowIntercept (View view, boolean disallow) {
    ViewParent parent = view.getParent();
    if (parent != null) {
      parent.requestDisallowInterceptTouchEvent(disallow);
    }
  }

  private void stopTableFling () {
    if (tableScroller != null && !tableScroller.isFinished()) {
      tableScroller.forceFinished(true);
    }
    if (flingView != null) {
      flingView.removeCallbacks(flingRunnable);
      flingView = null;
    }
    flingTable = null;
  }

  private final Runnable flingRunnable = new Runnable() {
    @Override
    public void run () {
      if (tableScroller == null || flingTable == null || flingView == null) {
        return;
      }
      if (tableScroller.computeScrollOffset()) {
        flingTable.setScrollX(tableScroller.getCurrX());
        invalidate();
        flingView.postOnAnimation(this);
      } else {
        flingTable = null;
        flingView = null;
      }
    }
  };

  private void resetTableTouch () {
    touchTable = null;
    isTableDragging = false;
    touchCaughtByContent = false;
    if (velocityTracker != null) {
      velocityTracker.recycle();
      velocityTracker = null;
    }
  }

  private MotionEvent obtainCancelEvent (MotionEvent e) {
    MotionEvent cancel = MotionEvent.obtain(e);
    cancel.setAction(MotionEvent.ACTION_CANCEL);
    return cancel;
  }

  private boolean onTableTouchEvent (MessageView view, MotionEvent e) {
    final TableBlock table = touchTable;
    if (table == null) {
      return false;
    }
    if (velocityTracker != null) {
      velocityTracker.addMovement(e);
    }
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_MOVE: {
        if (!isTableDragging) {
          float dx = e.getX() - touchDownX, dy = e.getY() - touchDownY;
          float slop = Screen.getTouchSlop();
          if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
            isTableDragging = true;
            // Cancel clicks and link highlights that started under the finger
            MotionEvent cancel = obtainCancelEvent(e);
            dispatchContentTouchEvent(view, cancel);
            cancel.recycle();
            touchDownX = e.getX();
            touchStartScrollX = table.scrollX;
          } else if (Math.abs(dy) > slop) {
            // Vertical gesture: let the chat scroll
            setDisallowIntercept(view, false);
            boolean result = dispatchContentTouchEvent(view, e);
            resetTableTouch();
            return result;
          } else {
            dispatchContentTouchEvent(view, e);
            return true;
          }
        }
        table.setScrollX(touchStartScrollX - (int) (e.getX() - touchDownX));
        invalidate();
        return true;
      }
      case MotionEvent.ACTION_UP: {
        setDisallowIntercept(view, false);
        if (isTableDragging) {
          if (velocityTracker != null) {
            velocityTracker.computeCurrentVelocity(1000);
            int velocityX = (int) velocityTracker.getXVelocity();
            if (Math.abs(velocityX) > Screen.dp(120f)) {
              if (tableScroller == null) {
                tableScroller = new OverScroller(view.getContext());
              }
              stopTableFling();
              tableScroller.fling(table.scrollX, 0, -velocityX, 0, 0, table.getMaxScrollX(), 0, 0);
              flingTable = table;
              flingView = view;
              view.postOnAnimation(flingRunnable);
            }
          }
          resetTableTouch();
          return true;
        }
        boolean caught = touchCaughtByContent;
        boolean result = dispatchContentTouchEvent(view, e);
        resetTableTouch();
        if (!caught && !result) {
          view.performMessageClick(e.getX(), e.getY());
        }
        return true;
      }
      case MotionEvent.ACTION_CANCEL: {
        setDisallowIntercept(view, false);
        if (!isTableDragging) {
          dispatchContentTouchEvent(view, e);
        }
        resetTableTouch();
        return true;
      }
    }
    return true;
  }

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    if (touchTable != null) {
      return onTableTouchEvent(view, e);
    }
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      TableBlock table = findScrollableTable(e.getX(), e.getY());
      if (table != null) {
        stopTableFling();
        touchTable = table;
        touchDownX = e.getX();
        touchDownY = e.getY();
        touchStartScrollX = table.scrollX;
        isTableDragging = false;
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(e);
        // Keep the chat list and swipe-to-reply from stealing the horizontal gesture
        setDisallowIntercept(view, true);
        touchCaughtByContent = dispatchContentTouchEvent(view, e);
        return true;
      }
    }
    return dispatchContentTouchEvent(view, e);
  }

  private boolean dispatchContentTouchEvent (MessageView view, MotionEvent e) {
    if (super.onTouchEvent(view, e)) {
      return true;
    }
    for (Toggle toggle : toggles) {
      if (toggle.onTouchEvent(view, e)) {
        return true;
      }
    }
    for (TextWrapper text : texts) {
      if (text.onTouchEvent(view, e)) {
        return true;
      }
    }
    for (MosaicWrapper mosaic : mosaics) {
      if (mosaic.onTouchEvent(view, e)) {
        return true;
      }
    }
    for (FileBlock file : files) {
      if (file.component.onTouchEvent(view, e)) {
        return true;
      }
    }
    for (ButtonsBlock row : buttonRows) {
      if (row.keyboard != null && row.keyboard.onTouchEvent(view, e)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean performLongPress (View view, float x, float y) {
    boolean result = super.performLongPress(view, x, y);
    for (TextWrapper text : texts) {
      if (text.performLongPress(view)) {
        return true;
      }
    }
    for (ButtonsBlock row : buttonRows) {
      if (row.keyboard != null && row.keyboard.performLongPress(view)) {
        return true;
      }
    }
    return result;
  }

  @Override
  protected boolean onRichButtonClick (View view, TdApi.InlineButton button, @NonNull TdlibUi.UrlOpenParameters openParameters) {
    if (inlineButtonHost == null) {
      inlineButtonHost = new TGInlineKeyboard(this, false);
      inlineButtonHost.setViewProvider(currentViews);
    }
    TdApi.ReplyMarkupInlineKeyboard keyboard = new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[][] {{toKeyboardButton(button)}}, false);
    inlineButtonHost.set(msg.id, keyboard, 0, Math.max(Screen.dp(200f), contentWidth));
    return inlineButtonHost.clickFirstButton(view);
  }

  private void toggleExpanded (Object key, boolean defaultValue) {
    Boolean current = expandedState.get(key);
    boolean expanded = current != null ? current : defaultValue;
    expandedState.put(key, !expanded);
    rebuildAndUpdateContent();
  }

  private boolean isExpanded (Object key, boolean defaultValue) {
    Boolean current = expandedState.get(key);
    return current != null ? current : defaultValue;
  }

  // Media viewer

  @Override
  public boolean onClick (View view, MediaWrapper clickedWrapper) {
    MediaStack stack = new MediaStack(context(), tdlib);
    ArrayList<MediaItem> items = new ArrayList<>(mediaWrappers.size());
    int foundIndex = -1;
    for (int i = 0; i < mediaWrappers.size(); i++) {
      MediaWrapper wrapper = mediaWrappers.get(i);
      TdApi.PageBlockCaption caption = i < mediaCaptions.size() ? mediaCaptions.get(i) : null;
      String captionString = caption != null && caption.text != null ? TD.getText(caption.text) : null;
      TdApi.FormattedText captionText = !StringUtils.isEmpty(captionString) ? new TdApi.FormattedText(captionString, null) : null;
      MediaItem item;
      if (wrapper.getPhoto() != null) {
        item = MediaItem.valueOf(context(), tdlib, wrapper.getPhoto(), captionText);
      } else if (wrapper.getVideo() != null) {
        item = MediaItem.valueOf(context(), tdlib, wrapper.getVideo(), null, null, captionText);
      } else if (wrapper.getAnimation() != null) {
        item = MediaItem.valueOf(context(), tdlib, wrapper.getAnimation(), captionText);
      } else {
        item = null;
      }
      if (item != null) {
        if (wrapper == clickedWrapper) {
          foundIndex = items.size();
        }
        items.add(item);
      }
    }
    if (foundIndex == -1) {
      return false;
    }
    stack.set(foundIndex, items);
    MediaViewController.openWithStack(controller(), stack, null, this, true);
    return true;
  }

  @Override
  public void modifyMediaArguments (Object cause, MediaViewController.Args args) {
    args.delegate = this;
  }

  @Override
  public MediaViewThumbLocation getTargetLocation (int indexInStack, MediaItem item) {
    return null; // Viewer opens with a fade instead of zooming out of the bubble
  }

  @Override
  public void setMediaItemVisible (int index, MediaItem item, boolean isVisible) { }

  // Colors

  private int lineColor () {
    return getVerticalLineColor();
  }

  private int textColor () {
    return getTextColorSet().defaultTextColor();
  }

  private int decentColor () {
    return getDecentColorSet().defaultTextColor();
  }

  // Blocks

  private abstract static class Block {
    int y;
    int width, height;
    int marginTop;

    abstract void layout (int maxWidth);

    abstract void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver);
  }

  private final class TextBlock extends Block {
    static final int STYLE_NORMAL = 0, STYLE_CODE = 1;

    final TextWrapper text;
    int style = STYLE_NORMAL;
    @Nullable TextWrapper label;

    TextBlock (TextWrapper text) {
      this.text = text;
    }

    private int padding () {
      return style == STYLE_CODE ? Screen.dp(8f) : 0;
    }

    @Override
    void layout (int maxWidth) {
      int padding = padding();
      text.prepare(Math.max(Screen.dp(40f), maxWidth - padding * 2));
      int labelHeight = 0;
      if (label != null) {
        label.prepare(Math.max(Screen.dp(40f), maxWidth - padding * 2));
        labelHeight = label.getHeight() + Screen.dp(4f);
      }
      width = style == STYLE_CODE ? maxWidth : text.getWidth();
      height = text.getHeight() + labelHeight + padding * 2;
    }

    @Override
    void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver) {
      int padding = padding();
      if (style == STYLE_CODE) {
        RectF rect = Paints.getRectF();
        rect.set(x, y, x + width, y + height);
        c.drawRoundRect(rect, Screen.dp(6f), Screen.dp(6f), Paints.fillingPaint(ColorUtils.alphaColor(.08f, textColor())));
      }
      int textY = y + padding;
      if (label != null) {
        label.draw(c, x + padding, textY, null, 1f, textMediaReceiver);
        textY += label.getHeight() + Screen.dp(4f);
      }
      text.draw(c, x + padding, textY, null, 1f, textMediaReceiver);
    }
  }

  private final class StackBlock extends Block {
    static final int STYLE_PLAIN = 0, STYLE_QUOTE = 1, STYLE_THINKING = 2;

    final List<Block> children;
    final int indent, style, spacing;

    StackBlock (List<Block> children, int indent, int style, int spacing) {
      this.children = children;
      this.indent = indent;
      this.style = style;
      this.spacing = spacing;
    }

    private int verticalPadding () {
      return style == STYLE_PLAIN ? 0 : Screen.dp(6f);
    }

    @Override
    void layout (int maxWidth) {
      int contentWidth = Math.max(Screen.dp(40f), maxWidth - indent - (style == STYLE_PLAIN ? 0 : Screen.dp(8f)));
      int y = verticalPadding();
      int w = 0;
      boolean first = true;
      for (Block child : children) {
        if (!first) {
          y += spacing + child.marginTop;
        }
        child.layout(contentWidth);
        child.y = y;
        y += child.height;
        w = Math.max(w, child.width);
        first = false;
      }
      height = y + verticalPadding();
      width = style == STYLE_PLAIN ? w : Math.min(maxWidth, w + indent + Screen.dp(8f));
    }

    @Override
    void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver) {
      if (style != STYLE_PLAIN) {
        int color = style == STYLE_THINKING ? decentColor() : lineColor();
        RectF rect = Paints.getRectF();
        rect.set(x, y, x + width, y + height);
        c.drawRoundRect(rect, Screen.dp(6f), Screen.dp(6f), Paints.fillingPaint(ColorUtils.alphaColor(.1f, color)));
        rect.set(x, y, x + Screen.dp(3f), y + height);
        c.drawRoundRect(rect, Screen.dp(1.5f), Screen.dp(1.5f), Paints.fillingPaint(color));
      }
      for (Block child : children) {
        child.draw(view, c, x + indent, y + child.y, receiver, textMediaReceiver);
      }
    }
  }

  private final class ListBlock extends Block {
    static final class Item {
      final TextWrapper label;
      final StackBlock content;
      int y;

      Item (TextWrapper label, StackBlock content) {
        this.label = label;
        this.content = content;
      }
    }

    final List<Item> items;
    int labelWidth;

    ListBlock (List<Item> items) {
      this.items = items;
    }

    @Override
    void layout (int maxWidth) {
      labelWidth = 0;
      for (Item item : items) {
        if (item.label != null) {
          item.label.prepare(maxWidth / 3);
          labelWidth = Math.max(labelWidth, item.label.getWidth());
        }
      }
      int contentX = labelWidth + Screen.dp(LIST_GAP);
      int y = 0;
      int w = 0;
      for (int i = 0; i < items.size(); i++) {
        Item item = items.get(i);
        if (i > 0) {
          y += Screen.dp(4f);
        }
        item.content.layout(Math.max(Screen.dp(40f), maxWidth - contentX));
        item.y = y;
        int itemHeight = Math.max(item.content.height, item.label != null ? item.label.getHeight() : 0);
        y += itemHeight;
        w = Math.max(w, contentX + item.content.width);
      }
      height = y;
      width = Math.min(maxWidth, w);
    }

    @Override
    void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver) {
      int contentX = x + labelWidth + Screen.dp(LIST_GAP);
      for (Item item : items) {
        if (item.label != null) {
          item.label.draw(c, x + labelWidth - item.label.getWidth(), y + item.y, null, 1f, textMediaReceiver);
        }
        item.content.draw(view, c, contentX, y + item.y, receiver, textMediaReceiver);
      }
    }
  }

  private final class DividerBlock extends Block {
    @Override
    void layout (int maxWidth) {
      width = maxWidth;
      height = Screen.dp(9f);
    }

    @Override
    void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver) {
      int lineY = y + height / 2;
      c.drawRect(x, lineY, x + width, lineY + Math.max(1, Screen.dp(.5f)), Paints.fillingPaint(ColorUtils.alphaColor(.25f, decentColor())));
    }
  }

  private final class ExpandableQuoteBlock extends Block {
    static final int COLLAPSED_LINES = 3;

    final Object key;
    final TextWrapper text;
    final @Nullable TextWrapper credit;
    boolean canExpand;

    ExpandableQuoteBlock (Object key, TextWrapper text, @Nullable TextWrapper credit) {
      this.key = key;
      this.text = text;
      this.credit = credit;
    }

    @Override
    void layout (int maxWidth) {
      int textWidth = Math.max(Screen.dp(40f), maxWidth - Screen.dp(QUOTE_PADDING) - Screen.dp(8f) - Screen.dp(20f));
      boolean expanded = isExpanded(key, false);
      text.setMaxLines(-1);
      text.prepare(textWidth);
      canExpand = text.getLineCount() > COLLAPSED_LINES;
      if (canExpand && !expanded) {
        text.setMaxLines(COLLAPSED_LINES);
        text.prepare(textWidth);
      }
      int h = Screen.dp(6f) + text.getHeight();
      if (credit != null && (expanded || !canExpand)) {
        credit.prepare(textWidth);
        h += Screen.dp(4f) + credit.getHeight();
      }
      height = h + Screen.dp(6f);
      width = Math.min(maxWidth, Screen.dp(QUOTE_PADDING) + text.getWidth() + Screen.dp(8f) + (canExpand ? Screen.dp(20f) : 0));
      if (canExpand) {
        width = maxWidth;
      }
    }

    @Override
    void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver) {
      int color = lineColor();
      RectF rect = Paints.getRectF();
      rect.set(x, y, x + width, y + height);
      c.drawRoundRect(rect, Screen.dp(6f), Screen.dp(6f), Paints.fillingPaint(ColorUtils.alphaColor(.1f, color)));
      rect.set(x, y, x + Screen.dp(3f), y + height);
      c.drawRoundRect(rect, Screen.dp(1.5f), Screen.dp(1.5f), Paints.fillingPaint(color));
      int textX = x + Screen.dp(QUOTE_PADDING);
      int textY = y + Screen.dp(6f);
      text.draw(c, textX, textY, null, 1f, textMediaReceiver);
      boolean expanded = isExpanded(key, false);
      if (credit != null && (expanded || !canExpand)) {
        credit.draw(c, textX, textY + text.getHeight() + Screen.dp(4f), null, 1f, textMediaReceiver);
      }
      if (canExpand) {
        drawChevron(c, x + width - Screen.dp(14f), y + height - Screen.dp(12f), expanded, color);
        // Collapsed: the whole quote expands it. Expanded: only the bottom strip collapses it, so links inside stay clickable.
        int toggleTop = expanded ? y + height - Screen.dp(24f) : y;
        toggles.add(new Toggle(x, toggleTop, x + width, y + height, key, false));
      }
    }
  }

  private final class DetailsBlock extends Block {
    final TdApi.PageBlockDetails details;
    final TextWrapper header;
    final StackBlock content;
    int headerHeight;

    DetailsBlock (TdApi.PageBlockDetails details, TextWrapper header, StackBlock content) {
      this.details = details;
      this.header = header;
      this.content = content;
    }

    @Override
    void layout (int maxWidth) {
      int chevronWidth = Screen.dp(22f);
      header.prepare(Math.max(Screen.dp(40f), maxWidth - chevronWidth));
      headerHeight = Math.max(header.getHeight(), Screen.dp(20f)) + Screen.dp(8f);
      int h = headerHeight;
      int w = header.getWidth() + chevronWidth;
      if (isExpanded(details, details.isOpen)) {
        content.layout(maxWidth);
        h += Screen.dp(4f) + content.height;
        w = Math.max(w, content.width);
      }
      height = h;
      width = maxWidth;
    }

    @Override
    void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver) {
      boolean expanded = isExpanded(details, details.isOpen);
      int textY = y + (headerHeight - header.getHeight()) / 2;
      drawChevron(c, x + Screen.dp(7f), y + headerHeight / 2, expanded, decentColor());
      header.draw(c, x + Screen.dp(22f), textY, null, 1f, textMediaReceiver);
      c.drawRect(x, y + headerHeight - Math.max(1, Screen.dp(.5f)), x + width, y + headerHeight, Paints.fillingPaint(ColorUtils.alphaColor(.2f, decentColor())));
      toggles.add(new Toggle(x, y, x + width, y + headerHeight, details, details.isOpen));
      if (expanded) {
        content.draw(view, c, x, y + headerHeight + Screen.dp(4f), receiver, textMediaReceiver);
      }
    }
  }

  private void drawChevron (Canvas c, int cx, int cy, boolean expanded, int color) {
    Paint paint = Paints.getProgressPaint(color, Screen.dp(1.5f));
    int size = Screen.dp(4f);
    if (expanded) {
      c.drawLine(cx - size, cy - size / 2f, cx, cy + size / 2f, paint);
      c.drawLine(cx, cy + size / 2f, cx + size, cy - size / 2f, paint);
    } else {
      c.drawLine(cx - size / 2f, cy - size, cx + size / 2f, cy, paint);
      c.drawLine(cx + size / 2f, cy, cx - size / 2f, cy + size, paint);
    }
  }

  private final class TableBlock extends Block {
    static final class Cell {
      final TdApi.PageBlockTableCell cell;
      final @Nullable TextWrapper text;
      int x, y, w, h;

      Cell (TdApi.PageBlockTableCell cell, @Nullable TextWrapper text) {
        this.cell = cell;
        this.text = text;
      }
    }

    final TdApi.PageBlockTable table;
    final Cell[][] cells;
    final int columnCount;
    int[] rowHeights;
    int contentWidth, scrollX;
    int drawX, drawY;
    boolean isVisible;

    int getMaxScrollX () {
      return Math.max(0, contentWidth - width);
    }

    void setScrollX (int scrollX) {
      this.scrollX = Math.max(0, Math.min(getMaxScrollX(), scrollX));
    }

    TableBlock (TdApi.PageBlockTable table, Cell[][] cells, int columnCount) {
      this.table = table;
      this.cells = cells;
      this.columnCount = columnCount;
    }

    @Override
    void layout (int maxWidth) {
      final int padding = Screen.dp(table.isCompact ? CELL_PADDING / 2 : CELL_PADDING);
      final int minColumnWidth = Screen.dp(32f);
      int[] natural = new int[columnCount];
      // Natural (single line) width of each column, ignoring cells spanning several columns
      for (Cell[] row : cells) {
        int column = 0;
        for (Cell cell : row) {
          int span = Math.max(1, cell.cell.colspan);
          if (span == 1 && column < columnCount && cell.text != null) {
            cell.text.prepare(Screen.dp(1000f));
            natural[column] = Math.max(natural[column], cell.text.getWidth() + padding * 2);
          }
          column += span;
        }
      }
      // Very long cells wrap instead of making a single column huge
      final int maxColumnWidth = Math.max(Screen.dp(200f), maxWidth * 4 / 5);
      int[] widths = new int[columnCount];
      int total = 0;
      for (int i = 0; i < columnCount; i++) {
        widths[i] = Math.max(minColumnWidth, Math.min(maxColumnWidth, natural[i]));
        total += widths[i];
      }
      //noinspection ConstantConditions
      if (total > maxWidth && !ALLOW_TABLE_SCROLL) {
        // Shrink the widest columns first, keeping narrow ones at their natural width
        int fair = maxWidth / columnCount;
        int fixedTotal = 0, flexibleNatural = 0;
        for (int w : widths) {
          if (w <= fair) {
            fixedTotal += w;
          } else {
            flexibleNatural += w;
          }
        }
        int flexibleSpace = Math.max(0, maxWidth - fixedTotal);
        total = 0;
        for (int i = 0; i < columnCount; i++) {
          if (widths[i] > fair && flexibleNatural > 0) {
            widths[i] = Math.max(minColumnWidth, (int) ((long) widths[i] * flexibleSpace / flexibleNatural));
          }
          total += widths[i];
        }
      }
      int y = 0;
      rowHeights = new int[cells.length];
      for (int rowIndex = 0; rowIndex < cells.length; rowIndex++) {
        Cell[] row = cells[rowIndex];
        int column = 0, x = 0, rowHeight = Screen.dp(20f);
        for (Cell cell : row) {
          int span = Math.max(1, cell.cell.colspan);
          int w = 0;
          for (int i = column; i < Math.min(columnCount, column + span); i++) {
            w += widths[i];
          }
          cell.x = x;
          cell.y = y;
          cell.w = w;
          if (cell.text != null) {
            cell.text.prepare(Math.max(Screen.dp(16f), w - padding * 2));
            rowHeight = Math.max(rowHeight, cell.text.getHeight() + padding * 2);
          }
          x += w;
          column += span;
        }
        for (Cell cell : row) {
          cell.h = rowHeight;
        }
        rowHeights[rowIndex] = rowHeight;
        y += rowHeight;
      }
      width = Math.min(maxWidth, total);
      contentWidth = total;
      height = y + (contentWidth > width ? Screen.dp(SCROLLBAR_SPACE) : 0);
      setScrollX(scrollX);
    }

    @Override
    void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver) {
      final int padding = Screen.dp(table.isCompact ? CELL_PADDING / 2 : CELL_PADDING);
      final int borderColor = ColorUtils.alphaColor(.22f, decentColor());
      final int stroke = Math.max(1, Screen.dp(.5f));
      this.drawX = x;
      this.drawY = y;
      this.isVisible = true;
      final int ox = x - scrollX;
      final int maxScrollX = getMaxScrollX();
      final int tableHeight = height - (maxScrollX > 0 ? Screen.dp(SCROLLBAR_SPACE) : 0);
      int saveCount = c.save();
      c.clipRect(x, y, x + width, y + height);
      for (int rowIndex = 0; rowIndex < cells.length; rowIndex++) {
        Cell[] row = cells[rowIndex];
        for (Cell cell : row) {
          int left = ox + cell.x, top = y + cell.y;
          if (cell.cell.isHeader) {
            c.drawRect(left, top, left + cell.w, top + cell.h, Paints.fillingPaint(ColorUtils.alphaColor(.07f, textColor())));
          } else if (table.isStriped && rowIndex % 2 == 1) {
            c.drawRect(left, top, left + cell.w, top + cell.h, Paints.fillingPaint(ColorUtils.alphaColor(.04f, textColor())));
          }
          if (cell.text != null) {
            int textX = left + padding;
            int textY = top + padding;
            int free = cell.w - padding * 2 - cell.text.getWidth();
            if (free > 0 && cell.cell.align != null) {
              if (cell.cell.align.getConstructor() == TdApi.PageBlockHorizontalAlignmentCenter.CONSTRUCTOR) {
                textX += free / 2;
              } else if (cell.cell.align.getConstructor() == TdApi.PageBlockHorizontalAlignmentRight.CONSTRUCTOR) {
                textX += free;
              }
            }
            int freeV = cell.h - padding * 2 - cell.text.getHeight();
            if (freeV > 0 && cell.cell.valign != null) {
              if (cell.cell.valign.getConstructor() == TdApi.PageBlockVerticalAlignmentMiddle.CONSTRUCTOR) {
                textY += freeV / 2;
              } else if (cell.cell.valign.getConstructor() == TdApi.PageBlockVerticalAlignmentBottom.CONSTRUCTOR) {
                textY += freeV;
              }
            }
            cell.text.draw(c, textX, textY, null, 1f, textMediaReceiver);
          }
        }
      }
      // Grid
      Paint borderPaint = Paints.fillingPaint(borderColor);
      int lineY = y;
      for (int rowIndex = 0; rowIndex <= cells.length; rowIndex++) {
        if (table.isBordered || (rowIndex > 0 && rowIndex < cells.length)) {
          c.drawRect(x, lineY - (rowIndex == cells.length ? stroke : 0), x + width, lineY + (rowIndex == cells.length ? 0 : stroke), borderPaint);
        }
        if (rowIndex < cells.length) {
          lineY += rowHeights[rowIndex];
        }
      }
      for (Cell[] row : cells) {
        for (int i = 0; i < row.length; i++) {
          Cell cell = row[i];
          int left = ox + cell.x;
          if (i > 0) {
            c.drawRect(left, y + cell.y, left + stroke, y + cell.y + cell.h, borderPaint);
          }
        }
      }
      if (table.isBordered) {
        c.drawRect(x, y, x + stroke, y + tableHeight, borderPaint);
        c.drawRect(x + width - stroke, y, x + width, y + tableHeight, borderPaint);
      }
      if (maxScrollX > 0) {
        // Scroll indicator under the table
        final int barHeight = Screen.dp(3f);
        final int barTop = y + tableHeight + Screen.dp(SCROLLBAR_SPACE) / 2 - barHeight / 2;
        final int barWidth = Math.max(Screen.dp(24f), (int) ((long) width * width / contentWidth));
        final int barLeft = x + (int) ((long) (width - barWidth) * scrollX / maxScrollX);
        RectF rect = Paints.getRectF();
        rect.set(x, barTop, x + width, barTop + barHeight);
        c.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, Paints.fillingPaint(ColorUtils.alphaColor(.1f, decentColor())));
        rect.set(barLeft, barTop, barLeft + barWidth, barTop + barHeight);
        c.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, Paints.fillingPaint(ColorUtils.alphaColor(.45f, decentColor())));
      }
      c.restoreToCount(saveCount);
    }
  }

  private final class MediaBlock extends Block {
    final MosaicWrapper mosaic;

    MediaBlock (MosaicWrapper mosaic) {
      this.mosaic = mosaic;
    }

    @Override
    void layout (int maxWidth) {
      int maxHeight = (int) (maxWidth * 1.25f);
      mosaic.build(maxWidth, maxHeight, Math.min(maxWidth, Screen.dp(MosaicWrapper.MIN_LAYOUT_WIDTH)), Screen.dp(MosaicWrapper.MIN_LAYOUT_HEIGHT), MosaicWrapper.MODE_FIT_WIDTH, false);
      width = mosaic.getWidth();
      height = mosaic.getHeight();
    }

    @Override
    void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver) {
      mosaic.draw(view, c, x, y, receiver, false);
    }
  }

  private final class FileBlock extends Block {
    final FileComponent component;
    final int index;

    FileBlock (FileComponent component, int index) {
      this.component = component;
      this.index = index;
    }

    @Override
    void layout (int maxWidth) {
      component.buildLayout(maxWidth);
      width = component.getWidth();
      height = component.getHeight();
    }

    @Override
    void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver) {
      long key = FILE_KEY_OFFSET + index;
      component.draw(view, c, x, y, receiver.getPreviewReceiver(key), receiver.getImageReceiver(key), getContentBackgroundColor(), getContentReplaceColor(), 1f, 0f);
    }
  }

  private final class ButtonsBlock extends Block {
    final TdApi.ReplyMarkupInlineKeyboard markup;
    TGInlineKeyboard keyboard;

    ButtonsBlock (TdApi.ReplyMarkupInlineKeyboard markup) {
      this.markup = markup;
    }

    @Override
    void layout (int maxWidth) {
      if (keyboard == null) {
        keyboard = new TGInlineKeyboard(TGMessageRich.this, false);
        keyboard.setViewProvider(currentViews);
      }
      keyboard.set(msg.id, markup, maxWidth, maxWidth);
      width = keyboard.getWidth();
      height = keyboard.getHeight();
    }

    @Override
    void draw (MessageView view, Canvas c, int x, int y, ComplexReceiver receiver, ComplexReceiver textMediaReceiver) {
      keyboard.draw(view, c, x, y);
    }
  }

  private final class Toggle {
    final int left, top, right, bottom;
    final Object key;
    final boolean defaultValue;

    Toggle (int left, int top, int right, int bottom, Object key, boolean defaultValue) {
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
      this.key = key;
      this.defaultValue = defaultValue;
    }

    boolean contains (float x, float y) {
      return x >= left && x < right && y >= top && y < bottom;
    }

    boolean onTouchEvent (View view, MotionEvent e) {
      switch (e.getAction()) {
        case MotionEvent.ACTION_DOWN:
          if (contains(e.getX(), e.getY())) {
            pressedToggleKey = key;
            return true;
          }
          return false;
        case MotionEvent.ACTION_UP:
          if (pressedToggleKey == key) {
            pressedToggleKey = null;
            if (contains(e.getX(), e.getY())) {
              view.playSoundEffect(android.view.SoundEffectConstants.CLICK);
              toggleExpanded(key, defaultValue);
            }
            return true;
          }
          return false;
        case MotionEvent.ACTION_CANCEL:
          if (pressedToggleKey == key) {
            pressedToggleKey = null;
            return true;
          }
          return false;
        case MotionEvent.ACTION_MOVE:
          return pressedToggleKey == key;
      }
      return false;
    }
  }

  private Object pressedToggleKey;
}
