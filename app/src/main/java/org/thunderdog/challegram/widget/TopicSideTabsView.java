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
package org.thunderdog.challegram.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.widget.FrameLayoutFix;

/**
 * Topic tabs on the side of a forum or a chat with a bot with topics, as in the official app: a
 * column of topic icons with names and unread counters. Telegram X has only horizontal tabs
 * ({@code ViewPagerTopView}), so the column is its own list.
 */
public class TopicSideTabsView extends RecyclerView {
  public static final float WIDTH = 64f;

  private static final float PADDING_VERTICAL = 6f;
  private static final float ICON_SIZE = 30f;
  private static final float ICON_TEXT_GAP = 4f;
  private static final float TEXT_PADDING_HORIZONTAL = 4f;
  private static final float COUNTER_OFFSET_X = 12f;
  private static final float COUNTER_OFFSET_Y = 2f;
  private static final float INDICATOR_WIDTH = 3f;
  private static final float INDICATOR_INSET = 3f;
  private static final float LETTER_BASELINE_OFFSET = 5.5f;

  /** Text width of a name, the column without its paddings. */
  public static int getTextMaxWidth () {
    return Screen.dp(WIDTH - TEXT_PADDING_HORIZONTAL * 2);
  }

  /** One tab. Everything is prepared once, so binding and drawing don't allocate. */
  public static final class Tab {
    final long id;
    final @Nullable Text name;
    final @Nullable Text emoji;
    final @Nullable String letter;
    final float letterWidth;
    final int letterColor;
    final @Nullable Drawable icon;
    final @Nullable Counter counter;
    /** Everything shown by the tab, to find tabs that changed. */
    public final String contentKey;

    public Tab (long id, @Nullable Text name, @Nullable Text emoji, @Nullable String letter,
      float letterWidth, int letterColor, @DrawableRes int iconRes, @Nullable Counter counter,
      String contentKey) {
      this.id = id;
      this.name = name;
      this.emoji = emoji;
      this.letter = letter;
      this.letterWidth = letterWidth;
      this.letterColor = letterColor;
      this.icon = iconRes != 0 ? Drawables.get(iconRes) : null;
      this.counter = counter;
      this.contentKey = contentKey;
    }
  }

  public interface Listener {
    void onTabClick (int position);
  }

  private final List<Tab> tabs = new ArrayList<>();
  private final TabsAdapter adapter;
  private final LinearLayoutManager manager;
  private int selectedPosition;

  public TopicSideTabsView (Context context, Listener listener) {
    super(context);
    this.manager = new LinearLayoutManager(context, VERTICAL, false);
    this.adapter = new TabsAdapter(this, listener);
    setLayoutManager(manager);
    setAdapter(adapter);
    // Rows change in place when counters or names change
    setItemAnimator(null);
    setOverScrollMode(OVER_SCROLL_NEVER);
  }

  public void setTabs (List<Tab> newTabs) {
    List<Tab> oldTabs = new ArrayList<>(tabs);
    DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
      @Override
      public int getOldListSize () {
        return oldTabs.size();
      }

      @Override
      public int getNewListSize () {
        return newTabs.size();
      }

      @Override
      public boolean areItemsTheSame (int oldItemPosition, int newItemPosition) {
        return oldTabs.get(oldItemPosition).id == newTabs.get(newItemPosition).id;
      }

      @Override
      public boolean areContentsTheSame (int oldItemPosition, int newItemPosition) {
        String oldKey = oldTabs.get(oldItemPosition).contentKey;
        return oldKey.equals(newTabs.get(newItemPosition).contentKey);
      }
    });
    tabs.clear();
    tabs.addAll(newTabs);
    diff.dispatchUpdatesTo(adapter);
  }

  public void setSelectedPosition (int position) {
    if (selectedPosition == position) {
      return;
    }
    selectedPosition = position;
    for (int i = 0; i < getChildCount(); i++) {
      getChildAt(i).invalidate();
    }
    int first = manager.findFirstCompletelyVisibleItemPosition();
    int last = manager.findLastCompletelyVisibleItemPosition();
    if (first != NO_POSITION && (position < first || position > last)) {
      smoothScrollToPosition(position);
    }
  }

  private static class TabsAdapter extends RecyclerView.Adapter<TabHolder> {
    private final TopicSideTabsView parent;
    private final Listener listener;

    TabsAdapter (TopicSideTabsView parent, Listener listener) {
      this.parent = parent;
      this.listener = listener;
    }

    @NonNull
    @Override
    public TabHolder onCreateViewHolder (@NonNull ViewGroup group, int viewType) {
      TabView view = new TabView(group.getContext(), parent);
      TabHolder holder = new TabHolder(view);
      view.setOnClickListener(v -> {
        int position = holder.getBindingAdapterPosition();
        if (position != NO_POSITION) {
          listener.onTabClick(position);
        }
      });
      return holder;
    }

    @Override
    public void onBindViewHolder (@NonNull TabHolder holder, int position) {
      ((TabView) holder.itemView).setTab(parent.tabs.get(position), position);
    }

    @Override
    public int getItemCount () {
      return parent.tabs.size();
    }

    @Override
    public void onViewAttachedToWindow (@NonNull TabHolder holder) {
      ((TabView) holder.itemView).receiver.attach();
    }

    @Override
    public void onViewDetachedFromWindow (@NonNull TabHolder holder) {
      ((TabView) holder.itemView).receiver.detach();
    }

    @Override
    public void onViewRecycled (@NonNull TabHolder holder) {
      ((TabView) holder.itemView).receiver.clear();
    }
  }

  private static class TabHolder extends RecyclerView.ViewHolder {
    TabHolder (View itemView) {
      super(itemView);
    }
  }

  @SuppressLint("ViewConstructor")
  private static class TabView extends View {
    private final TopicSideTabsView parent;
    private final ComplexReceiver receiver;
    private final RectF indicatorRect = new RectF();
    private @Nullable Tab tab;
    private int position;

    TabView (Context context, TopicSideTabsView parent) {
      super(context);
      this.parent = parent;
      this.receiver = new ComplexReceiver(this);
      RippleSupport.setTransparentSelector(this);
      setLayoutParams(new RecyclerView.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
      ));
    }

    void setTab (Tab tab, int position) {
      this.position = position;
      if (this.tab != tab) {
        boolean heightChanged = this.tab == null || getHeight(this.tab) != getHeight(tab);
        this.tab = tab;
        if (tab.emoji != null) {
          tab.emoji.requestMedia(receiver);
        } else {
          receiver.clear();
        }
        if (heightChanged) {
          requestLayout();
        }
      }
      invalidate();
    }

    private static int getHeight (Tab tab) {
      int textHeight = tab.name != null ? tab.name.getHeight() + Screen.dp(ICON_TEXT_GAP) : 0;
      return Screen.dp(PADDING_VERTICAL) * 2 + Screen.dp(ICON_SIZE) + textHeight;
    }

    @Override
    protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
      int height = tab != null ? getHeight(tab) : Screen.dp(PADDING_VERTICAL * 2 + ICON_SIZE);
      setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      Tab tab = this.tab;
      if (tab == null) {
        return;
      }
      boolean isSelected = position == parent.selectedPosition;
      int width = getMeasuredWidth();
      float centerX = width / 2f;
      int accentColor = Theme.getColor(ColorId.textNeutral);
      if (isSelected) {
        float inset = Screen.dp(INDICATOR_INSET);
        float indicatorWidth = Screen.dp(INDICATOR_WIDTH);
        indicatorRect.set(-indicatorWidth, inset, indicatorWidth, getMeasuredHeight() - inset);
        c.drawRoundRect(indicatorRect, indicatorWidth, indicatorWidth,
          Paints.fillingPaint(accentColor));
      }
      int iconSize = Screen.dp(ICON_SIZE);
      int iconTop = Screen.dp(PADDING_VERTICAL);
      float iconCenterY = iconTop + iconSize / 2f;
      if (tab.emoji != null) {
        tab.emoji.draw(c,
          (int) (centerX - tab.emoji.getWidth() / 2f),
          (int) (iconCenterY - tab.emoji.getHeight() / 2f),
          null, 1f, receiver
        );
      } else if (tab.letter != null) {
        c.drawCircle(centerX, iconCenterY, iconSize / 2f, Paints.fillingPaint(tab.letterColor));
        c.drawText(tab.letter, centerX - tab.letterWidth / 2f,
          iconCenterY + Screen.dp(LETTER_BASELINE_OFFSET),
          Paints.getMediumTextPaint(15f, Color.WHITE, false));
      } else if (tab.icon != null) {
        int iconColor = isSelected ? accentColor : Theme.getColor(ColorId.iconLight);
        Drawables.draw(c, tab.icon,
          centerX - tab.icon.getMinimumWidth() / 2f,
          iconCenterY - tab.icon.getMinimumHeight() / 2f,
          Paints.getPorterDuffPaint(iconColor));
      }
      if (tab.counter != null) {
        tab.counter.draw(c, centerX + Screen.dp(COUNTER_OFFSET_X),
          iconTop + Screen.dp(COUNTER_OFFSET_Y), Gravity.CENTER, 1f);
      }
      if (tab.name != null) {
        int textLeft = Screen.dp(TEXT_PADDING_HORIZONTAL);
        tab.name.draw(c, textLeft, width - textLeft, 0,
          iconTop + iconSize + Screen.dp(ICON_TEXT_GAP),
          isSelected ? TextColorSets.Regular.NEUTRAL : TextColorSets.Regular.LIGHT, 1f, null);
      }
    }
  }

  /** Column with the button that moves the tabs back on top, above the tabs. */
  public static FrameLayoutFix wrap (Context context, TopicSideTabsView tabs, View topButton) {
    FrameLayoutFix layout = new FrameLayoutFix(context);
    int buttonHeight = Screen.dp(48f);
    topButton.setLayoutParams(FrameLayoutFix.newParams(
      ViewGroup.LayoutParams.MATCH_PARENT, buttonHeight, Gravity.TOP
    ));
    FrameLayoutFix.LayoutParams params = FrameLayoutFix.newParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    );
    params.topMargin = buttonHeight;
    tabs.setLayoutParams(params);
    layout.addView(topButton);
    layout.addView(tabs);
    return layout;
  }
}
