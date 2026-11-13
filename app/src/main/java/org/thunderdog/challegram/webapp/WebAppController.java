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
package org.thunderdog.challegram.webapp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.drinkless.tdlib.TdApi;
import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.emoji.Emoji;
import org.thunderdog.challegram.navigation.AlertPopup;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.MenuMoreWrap;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.M3;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.ThemeChangeListener;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.theme.ThemeManager;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.Keyboard;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.MessagesController;
import org.thunderdog.challegram.widget.M3ButtonView;
import org.thunderdog.challegram.widget.M3Sheets;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.widget.SnackBar;

import java.util.ArrayList;
import java.util.Locale;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableBool;
import me.vkryl.core.lambda.RunnableData;
import tgx.td.ChatId;
import tgx.td.Td;

/**
 * A mini app: the bottom sheet of the official Android client with the WebView, its header, the
 * main and secondary buttons and the fullscreen mode. Minimizing keeps this controller, its popup
 * and the WebView alive and puts it into {@link WebAppTabs}.
 */
public class WebAppController extends ViewController<WebAppController.Args> implements
  FactorAnimator.Target,
  PopupLayout.AnimatedPopupProvider,
  PopupLayout.TouchSectionProvider,
  WebAppButtonsView.Callback,
  WebAppTab,
  ThemeChangeListener,
  View.OnClickListener {

  public static final int TYPE_INLINE_BUTTON = 0, TYPE_KEYBOARD_BUTTON = 1, TYPE_MENU_BUTTON = 2;
  public static final int TYPE_ATTACHMENT_MENU = 3, TYPE_APP_LINK = 4, TYPE_MAIN_APP = 5;
  public static final int TYPE_SIDE_MENU = 6, TYPE_INLINE_SWITCH = 7;

  public static final int MODE_COMPACT = 0, MODE_FULL_SIZE = 1, MODE_FULLSCREEN = 2;

  public static final class Args {
    public final int type;
    public final long botUserId;
    public final long chatId;
    public @Nullable TdApi.MessageTopic topicId;
    public @Nullable TdApi.InputMessageReplyTo replyTo;
    public @Nullable String url, buttonText, startParameter, webAppShortName;
    public boolean allowWriteAccess, needsContext;
    public int mode = MODE_COMPACT;

    public Args (int type, long botUserId, long chatId) {
      this.type = type;
      this.botUserId = botUserId;
      this.chatId = chatId;
    }

    /**
     * Whether opening these arguments again must bring back the minimized app instead of a new
     * launch.
     */
    boolean isSameLaunch (Args other) {
      return type == other.type && botUserId == other.botUserId && chatId == other.chatId &&
        StringUtils.equalsOrBothEmpty(url, other.url) &&
        StringUtils.equalsOrBothEmpty(startParameter, other.startParameter) &&
        StringUtils.equalsOrBothEmpty(webAppShortName, other.webAppShortName) &&
        StringUtils.equalsOrBothEmpty(buttonText, other.buttonText);
    }
  }

  private static final ArrayList<WebAppController> openControllers = new ArrayList<>();

  private static final float
    HANDLE_AREA = 24f, HANDLE_WIDTH = 32f, HANDLE_HEIGHT = 4f, HANDLE_TOP = 12f;
  private static final float STICKY_RANGE = 64f, STICKY_RANGE_LANDSCAPE = 8f;
  private static final float FLING_DOWN_VELOCITY = 650f, FLING_UP_VELOCITY = 700f;
  private static final float LONG_DRAG_DISTANCE = 200f, CONTENT_DRAG_DEAD_ZONE = 60f;
  private static final long LONG_DRAG_DURATION = 250L, CONTEXT_OPEN_DELAY = 220L;
  private static final float PILL_HEIGHT = 30f, PILL_MARGIN = 8f, RIGHT_PILL_WIDTH = 71.66f;
  private static final float CONTENT_SAFE_AREA_TOP = PILL_MARGIN + PILL_HEIGHT + PILL_MARGIN;
  private static final long FULLSCREEN_NAME_DURATION = 2500L;
  private static final int DIM_ALPHA = 0x40;

  private static final int POSITION_COMPACT = 0, POSITION_EXPANDED = 1, POSITION_FULLSCREEN = 2;
  private static final int POSITION_HIDDEN = 3;

  private static final int ANIMATOR_POSITION = 0, ANIMATOR_HEADER = 1, ANIMATOR_FULLSCREEN = 2;
  private static final int ANIMATOR_COLORS = 3, ANIMATOR_PROGRESS = 4, ANIMATOR_BACK = 5;
  private static final int ANIMATOR_LOADED = 6;

  private static final int REQUEST_FILE_CHOOSER = 3000;

  public WebAppController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_webApp;
  }

  // Views

  private SheetLayout sheetLayout;
  private FrameLayoutFix headerView;
  private BackHeaderButton closeButton;
  private TextView titleView;
  private ImageView minimizeButton, moreButton;
  private FrameLayoutFix contentView;
  private @Nullable WebView webView;
  private PlaceholderView placeholderView;
  private @Nullable LinearLayout errorView;
  private WebAppButtonsView buttonsView;
  private @Nullable SnackBar snackBar;
  private PopupLayout popupLayout;

  private WebAppBridge bridge;

  // State

  private long launchId;
  private boolean requireSameOrigin;
  private @Nullable String trustedOrigin;
  private @Nullable String launchUrl;
  private boolean isPageLoaded, isMinimized, isClosing, hasShown;
  private boolean isBackButtonVisible, isSettingsButtonVisible, needClosingConfirmation;
  private boolean allowSwipes = true, pageAllowsScrollX = true, pageAllowsScrollY = true;
  private boolean hasPageScrollReply, isScrollScriptInjected;
  private boolean isFullscreen, isOrientationLocked;
  private int restingPosition = POSITION_COMPACT;
  private long fullscreenEnteredAt;
  private @Nullable Bitmap tabPreview;
  private @Nullable RunnableData<String> qrCodeListener;
  private @Nullable RunnableBool qrClosedListener;
  private @Nullable ValueCallback<Uri[]> fileChooserCallback;

  private final Rect insets = new Rect(), insetsWithoutIme = new Rect();

  // Positions in pixels of the sheet top edge, see updatePositions()

  private float compactTop, expandedTop, fullscreenTop, hiddenTop;
  private float sheetTop, fromTop, toTop;
  private int targetPosition = POSITION_COMPACT;
  private int bottomPadding, lastViewportHeight = -1;
  private boolean lastViewportStable, lastViewportExpanded;

  private final FactorAnimator positionAnimator = new FactorAnimator(ANIMATOR_POSITION, this,
    AnimatorUtils.DECELERATE_INTERPOLATOR, 300L);
  private final BoolAnimator headerAnimator = new BoolAnimator(ANIMATOR_HEADER, this,
    AnimatorUtils.DECELERATE_INTERPOLATOR, 180L);
  private final BoolAnimator fullscreenAnimator = new BoolAnimator(ANIMATOR_FULLSCREEN, this,
    M3.EMPHASIZED, 280L);
  private final FactorAnimator colorAnimator = new FactorAnimator(ANIMATOR_COLORS, this,
    AnimatorUtils.DECELERATE_INTERPOLATOR, 200L);
  private final FactorAnimator progressAnimator = new FactorAnimator(ANIMATOR_PROGRESS, this,
    AnimatorUtils.DECELERATE_INTERPOLATOR, 200L);
  private final BoolAnimator backAnimator = new BoolAnimator(ANIMATOR_BACK, this,
    AnimatorUtils.DECELERATE_INTERPOLATOR, 180L);
  private final BoolAnimator loadedAnimator = new BoolAnimator(ANIMATOR_LOADED, this,
    AnimatorUtils.DECELERATE_INTERPOLATOR, 220L);

  // Colors

  private @ColorInt int headerColor, backgroundColor, bottomBarColor;
  private @ColorInt int fromHeaderColor, fromBackgroundColor, fromBottomBarColor;
  private @ColorInt int toHeaderColor, toBackgroundColor, toBottomBarColor;
  private @Nullable String headerColorKey;
  private boolean isHeaderColorOverridden, isBackgroundColorOverridden, isBottomBarColorOverridden;
  private @ColorInt int botHeaderColor, botBackgroundColor;
  private float loadingProgress, progressAlpha;

  // Public API

  public void show () {
    if (popupLayout != null) {
      return;
    }
    popupLayout = new PopupLayout(context());
    popupLayout.setBoundController(this);
    popupLayout.setOverlayStatusBar(true);
    popupLayout.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    popupLayout.setTouchProvider(this);
    popupLayout.setDisableCancelOnTouchDown(true);
    popupLayout.init(true);
    popupLayout.setNeedRootInsets();
    openControllers.add(this);
    popupLayout.showAnimatedPopupView(getValue(), this);
  }

  static @Nullable WebAppController findOpen (Tdlib tdlib, Args args) {
    for (WebAppController c : openControllers) {
      if (c.tdlib == tdlib && !c.isClosing && c.getArgumentsStrict().isSameLaunch(args)) {
        return c;
      }
    }
    return null;
  }

  /**
   * {@code updateWebAppMessageSent}: the bot answered the query of the launch, so the app closes.
   */
  public static void onWebAppMessageSent (Tdlib tdlib, long webAppLaunchId) {
    for (int i = openControllers.size() - 1; i >= 0; i--) {
      WebAppController c = openControllers.get(i);
      if (c.tdlib == tdlib && c.launchId == webAppLaunchId) {
        c.closeNow();
      }
    }
  }

  boolean isMinimized () {
    return isMinimized;
  }

  /**
   * Hides the sheet while a screen that lives under the popups of the activity is open, e.g. the
   * camera.
   */
  void setSheetHidden (boolean isHidden) {
    if (popupLayout == null || isMinimized) {
      return;
    }
    popupLayout.setVisibility(isHidden ? View.INVISIBLE : View.VISIBLE);
    // A hidden sheet must not take Back from the screen that covers it.
    if (isHidden) {
      context().pretendYouDontKnowThisWindow(popupLayout);
    } else {
      context().letsRememberAboutThisWindow(popupLayout);
    }
  }

  // Bridge getters

  long getBotUserId () {
    return getArgumentsStrict().botUserId;
  }

  int getLaunchType () {
    return getArgumentsStrict().type;
  }

  @Nullable String getButtonText () {
    return getArgumentsStrict().buttonText;
  }

  String getBotName () {
    return tdlib.cache().userName(getBotUserId());
  }

  boolean isTrustedDocument () {
    if (!requireSameOrigin) {
      return true;
    }
    return webView != null && trustedOrigin != null &&
      trustedOrigin.equals(getOrigin(webView.getUrl()));
  }

  private static @Nullable String getOrigin (@Nullable String url) {
    if (StringUtils.isEmpty(url)) {
      return null;
    }
    Uri uri = Uri.parse(url);
    String scheme = uri.getScheme(), host = uri.getHost();
    if (StringUtils.isEmpty(scheme) || StringUtils.isEmpty(host)) {
      return null;
    }
    scheme = scheme.toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    boolean isDefaultPort = port == -1 || (port == 80 && scheme.equals("http")) ||
      (port == 443 && scheme.equals("https"));
    return scheme + "://" + host.toLowerCase(Locale.ROOT) + (isDefaultPort ? "" : ":" + port);
  }

  // View

  @SuppressLint("SetJavaScriptEnabled")
  @Override
  protected View onCreateView (Context context) {
    final Args args = getArgumentsStrict();
    bridge = new WebAppBridge(this);

    sheetLayout = new SheetLayout(context);
    sheetLayout.setLayoutParams(FrameLayoutFix.newParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    contentView = new FrameLayoutFix(context);
    contentView.setLayoutParams(FrameLayoutFix.newParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP));
    sheetLayout.addView(contentView);

    placeholderView = new PlaceholderView(context);
    placeholderView.setLayoutParams(FrameLayoutFix.newParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    try {
      webView = new WebView(context) {
        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent (MotionEvent event) {
          if (event.getAction() == MotionEvent.ACTION_DOWN) {
            bridge.onUserInteraction();
            getSettings().setMediaPlaybackRequiresUserGesture(false);
          }
          return super.onTouchEvent(event);
        }
      };
      setupWebView(webView);
      webView.setLayoutParams(FrameLayoutFix.newParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      webView.setAlpha(0f);
      contentView.addView(webView);
      contentView.addView(placeholderView);
    } catch (Throwable t) {
      Log.e("Cannot create WebView for a mini app", t);
      webView = null;
      TextView notAvailable = new TextView(context);
      notAvailable.setText(Lang.getString(R.string.vg_BotWebViewNotAvailablePlaceholder));
      notAvailable.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
      notAvailable.setGravity(Gravity.CENTER);
      notAvailable.setTextColor(Theme.getColor(ColorId.textLight));
      notAvailable.setPadding(Screen.dp(16f), Screen.dp(16f), Screen.dp(16f), Screen.dp(16f));
      notAvailable.setLayoutParams(FrameLayoutFix.newParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      contentView.addView(notAvailable);
    }

    buttonsView = new WebAppButtonsView(context, tdlib, this);
    buttonsView.setLayoutParams(FrameLayoutFix.newParams(
      ViewGroup.LayoutParams.MATCH_PARENT, WebAppButtonsView.getMaxHeight(), Gravity.BOTTOM));
    sheetLayout.addView(buttonsView);

    headerView = new FrameLayoutFix(context);
    headerView.setLayoutParams(FrameLayoutFix.newParams(
      ViewGroup.LayoutParams.MATCH_PARENT, HeaderView.getSize(false), Gravity.TOP));
    headerView.setAlpha(0f);

    closeButton = new BackHeaderButton(context);
    closeButton.setButtonFactor(BackHeaderButton.TYPE_CLOSE);
    closeButton.setId(R.id.btn_webAppClose);
    closeButton.setOnClickListener(this);
    closeButton.setLayoutParams(FrameLayoutFix.newParams(Screen.dp(56f),
      ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));
    headerView.addView(closeButton);

    titleView = new TextView(context);
    titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f);
    titleView.setTypeface(Fonts.getRobotoMedium());
    titleView.setSingleLine(true);
    titleView.setEllipsize(TextUtils.TruncateAt.END);
    titleView.setGravity(Gravity.CENTER_VERTICAL);
    FrameLayout.LayoutParams titleParams = FrameLayoutFix.newParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    titleParams.leftMargin = Screen.dp(68f);
    titleParams.rightMargin = Screen.dp(104f);
    titleView.setLayoutParams(titleParams);
    titleView.setText(Emoji.instance().replaceEmoji(getBotName()));
    headerView.addView(titleView);

    moreButton = newHeaderButton(context, R.id.menu_btn_more, R.drawable.baseline_more_vert_24, 0);
    minimizeButton = newHeaderButton(context, R.id.btn_webAppMinimize,
      R.drawable.baseline_keyboard_arrow_down_24, Screen.dp(48f));
    headerView.addView(minimizeButton);
    headerView.addView(moreButton);
    sheetLayout.addView(headerView);

    initColors();
    ThemeManager.instance().addThemeListener(this);
    return sheetLayout;
  }

  private ImageView newHeaderButton (Context context, int id, int icon, int rightMargin) {
    ImageView button = new ImageView(context);
    button.setId(id);
    button.setScaleType(ImageView.ScaleType.CENTER);
    button.setImageResource(icon);
    button.setOnClickListener(this);
    Views.setClickable(button);
    FrameLayout.LayoutParams params = FrameLayoutFix.newParams(Screen.dp(48f),
      ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT);
    params.rightMargin = rightMargin + Screen.dp(4f);
    button.setLayoutParams(params);
    return button;
  }

  @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
  private void setupWebView (WebView webView) {
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setGeolocationEnabled(true);
    settings.setSupportMultipleWindows(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setTextZoom(100);
    settings.setMediaPlaybackRequiresUserGesture(true);
    settings.setUserAgentString(buildUserAgent(settings.getUserAgentString()));
    webView.setVerticalScrollBarEnabled(false);
    webView.setBackgroundColor(Theme.getColor(ColorId.filling));
    webView.addJavascriptInterface(bridge, WebAppBridge.INTERFACE_NAME);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      GeolocationPermissions.getInstance().clearAll();
    }
    webView.setWebViewClient(new MiniAppWebViewClient());
    webView.setWebChromeClient(new MiniAppChromeClient());
  }

  /**
   * The official clients append a platform token that mini apps sniff to tell Android apart, so it
   * keeps the format of the official one.
   */
  private static String buildUserAgent (@Nullable String defaultUserAgent) {
    String userAgent = defaultUserAgent != null ? defaultUserAgent : "";
    userAgent = userAgent.replace("; wv)", ")").replaceAll("Version/[\\d.]+ ", "");
    String manufacturer = Build.MANUFACTURER;
    if (!StringUtils.isEmpty(manufacturer)) {
      manufacturer = manufacturer.substring(0, 1).toUpperCase(Locale.ROOT) +
        manufacturer.substring(1);
    }
    return userAgent + " Telegram-Android/" + BuildConfig.VERSION_NAME + " (" + manufacturer + " " +
      Build.MODEL + "; Android " + Build.VERSION.RELEASE + "; SDK " + Build.VERSION.SDK_INT + "; " +
      (Screen.dp(1f) >= 3 ? "HIGH" : "AVERAGE") + ")";
  }

  @Override
  public void dispatchSystemInsets (
    View parentView,
    ViewGroup.MarginLayoutParams originalParams,
    Rect legacyInsets,
    Rect insets,
    Rect insetsWithoutIme,
    Rect systemInsets,
    Rect systemInsetsWithoutIme,
    boolean fitsSystemWindows
  ) {
    super.dispatchSystemInsets(parentView, originalParams, legacyInsets, insets, insetsWithoutIme,
      systemInsets, systemInsetsWithoutIme, fitsSystemWindows);
    boolean wasKeyboardVisible = isKeyboardVisible();
    this.insets.set(systemInsets);
    this.insetsWithoutIme.set(systemInsetsWithoutIme);
    if (sheetLayout != null) {
      sheetLayout.requestLayout();
    }
    if (!wasKeyboardVisible && isKeyboardVisible() && !isFullscreen &&
      targetPosition == POSITION_COMPACT) {
      animatePosition(POSITION_EXPANDED);
    }
  }

  private boolean isKeyboardVisible () {
    return insets.bottom - insetsWithoutIme.bottom > Screen.dp(20f);
  }

  // Layout

  private int getTabsHeight () {
    // The strip of other minimized apps stays visible under an open sheet, like in the official
    // client.
    return WebAppTabs.getStripHeight(tdlib.id());
  }

  private void updatePositions (int width, int height) {
    final int headerHeight = HeaderView.getSize(false);
    final boolean isLandscape = width > height;
    bottomPadding = Math.max(insets.bottom, insetsWithoutIme.bottom + getTabsHeight());
    final int available = height - bottomPadding;
    expandedTop = insets.top + headerHeight - Screen.dp(HANDLE_AREA);
    compactTop = Math.max(expandedTop, isLandscape ? available / 3.5f : available / 5f * 2f);
    fullscreenTop = -Screen.dp(HANDLE_AREA);
    hiddenTop = height + Screen.dp(HANDLE_AREA);
  }

  private float getPositionTop (int position) {
    switch (position) {
      case POSITION_EXPANDED:
        return expandedTop;
      case POSITION_FULLSCREEN:
        return fullscreenTop;
      case POSITION_HIDDEN:
        return hiddenTop;
      case POSITION_COMPACT:
      default:
        return getArgumentsStrict().mode == MODE_COMPACT ? compactTop : expandedTop;
    }
  }

  private int getContentTop () {
    return isFullscreen ? 0 : insets.top + HeaderView.getSize(false);
  }

  /** Visible height of the web content above the buttons for the current sheet position. */
  private int getContentHeight () {
    int bottom = sheetLayout.getMeasuredHeight() - bottomPadding - buttonsView.getTargetHeight();
    return Math.max(0, bottom - Math.round(sheetTop + Screen.dp(HANDLE_AREA)));
  }

  private void applyPosition () {
    if (sheetLayout == null) {
      return;
    }
    final float handle = Screen.dp(HANDLE_AREA);
    contentView.setTranslationY(Math.max(0, sheetTop + handle - getContentTop()));
    float buttonsOffset = Math.max(0, sheetTop - getPositionTop(restingPosition));
    buttonsView.setTranslationY(buttonsOffset);
    final float halfway = compactTop - (compactTop - expandedTop) / 2f;
    headerAnimator.setValue(
      sheetTop <= halfway && !isFullscreen && !isMinimizedOrHiding(), hasShown);
    sheetLayout.invalidate();
    placeholderView.invalidate();
  }

  private boolean isMinimizedOrHiding () {
    return isMinimized || targetPosition == POSITION_HIDDEN;
  }

  private void animatePosition (int position) {
    targetPosition = position;
    if (position != POSITION_HIDDEN) {
      restingPosition = position;
    }
    fromTop = sheetTop;
    toTop = getPositionTop(position);
    positionAnimator.forceFactor(0f);
    if (fromTop == toTop) {
      onPositionAnimationFinished();
      return;
    }
    sendViewport(false, false);
    positionAnimator.animateTo(1f);
  }

  private void onPositionAnimationFinished () {
    sendViewport(false, true);
    if (targetPosition == POSITION_HIDDEN) {
      if (isClosing) {
        if (popupLayout != null) {
          popupLayout.onCustomHideAnimationComplete();
        }
      } else if (isMinimized) {
        onMinimized();
      }
    } else if (!hasShown) {
      hasShown = true;
      if (popupLayout != null) {
        popupLayout.onCustomShowComplete();
      }
    }
  }

  // Popup animations

  @Override
  public void prepareShowAnimation () { }

  @Override
  public void launchShowAnimation (PopupLayout popup) {
    sheetLayout.runWhenLaidOut(() -> {
      sheetTop = hiddenTop;
      applyPosition();
      Args args = getArgumentsStrict();
      animatePosition(args.mode == MODE_FULLSCREEN ? POSITION_FULLSCREEN :
        args.mode == MODE_FULL_SIZE ? POSITION_EXPANDED : POSITION_COMPACT);
      if (args.mode == MODE_FULLSCREEN) {
        setFullscreen(true);
      }
      requestUrl();
    });
  }

  @Override
  public boolean launchHideAnimation (PopupLayout popup, FactorAnimator originalAnimator) {
    isClosing = true;
    animatePosition(POSITION_HIDDEN);
    return true;
  }

  @Override
  public boolean shouldTouchOutside (float x, float y) {
    // Touches on the strip of other minimized apps reach it, like in the official client.
    return sheetLayout != null && getTabsHeight() > 0 &&
      y >= sheetLayout.getMeasuredHeight() - insetsWithoutIme.bottom - getTabsHeight();
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (commit) {
      if (isBackButtonVisible) {
        bridge.onBackButtonPressed();
      } else {
        minimize();
      }
    }
    return true;
  }

  // Animations

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    switch (id) {
      case ANIMATOR_POSITION: {
        sheetTop = MathUtils.fromTo(fromTop, toTop, factor);
        applyPosition();
        break;
      }
      case ANIMATOR_HEADER:
      case ANIMATOR_FULLSCREEN: {
        updateHeaderAppearance();
        break;
      }
      case ANIMATOR_COLORS: {
        headerColor = ColorUtils.fromToArgb(fromHeaderColor, toHeaderColor, factor);
        backgroundColor = ColorUtils.fromToArgb(fromBackgroundColor, toBackgroundColor, factor);
        bottomBarColor = ColorUtils.fromToArgb(fromBottomBarColor, toBottomBarColor, factor);
        applyColors();
        break;
      }
      case ANIMATOR_PROGRESS: {
        progressAlpha = 1f - factor;
        sheetLayout.invalidate();
        break;
      }
      case ANIMATOR_BACK: {
        closeButton.setFactor(MathUtils.fromTo(
          BackHeaderButton.toButtonFactor(BackHeaderButton.TYPE_CLOSE),
          BackHeaderButton.toButtonFactor(BackHeaderButton.TYPE_BACK), factor));
        sheetLayout.invalidate();
        break;
      }
      case ANIMATOR_LOADED: {
        if (webView != null) {
          webView.setAlpha(factor);
        }
        placeholderView.setAlpha(1f - factor);
        break;
      }
    }
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    switch (id) {
      case ANIMATOR_POSITION: {
        if (finalFactor == 1f) {
          onPositionAnimationFinished();
        }
        break;
      }
      case ANIMATOR_LOADED: {
        placeholderView.setVisibility(finalFactor == 1f ? View.GONE : View.VISIBLE);
        break;
      }
      case ANIMATOR_FULLSCREEN: {
        headerView.setVisibility(finalFactor == 1f ? View.INVISIBLE : View.VISIBLE);
        break;
      }
    }
  }

  private void updateHeaderAppearance () {
    final float fullscreen = fullscreenAnimator.getFloatValue();
    headerView.setAlpha(headerAnimator.getFloatValue() * (1f - fullscreen));
    headerView.setTranslationY(insets.top - headerView.getMeasuredHeight() * fullscreen);
    sheetLayout.invalidate();
  }

  // Colors

  private void initColors () {
    TdApi.UserFullInfo userFull = tdlib.cache().userFull(getBotUserId());
    botHeaderColor = 0;
    botBackgroundColor = 0;
    if (userFull != null && userFull.botInfo != null) {
      boolean isDark = Theme.isDark();
      int header = isDark ? userFull.botInfo.webAppHeaderDarkColor :
        userFull.botInfo.webAppHeaderLightColor;
      int background = isDark ? userFull.botInfo.webAppBackgroundDarkColor :
        userFull.botInfo.webAppBackgroundLightColor;
      botHeaderColor = header != -1 ? header | 0xff000000 : 0;
      botBackgroundColor = background != -1 ? background | 0xff000000 : 0;
    }
    headerColor = toHeaderColor = getDefaultHeaderColor();
    backgroundColor = toBackgroundColor = getDefaultBackgroundColor();
    bottomBarColor = toBottomBarColor = getDefaultBottomBarColor();
    applyColors();
  }

  private @ColorInt int getDefaultHeaderColor () {
    if (headerColorKey != null) {
      return Theme.getColor(
        "secondary_bg_color".equals(headerColorKey) ? ColorId.background : ColorId.filling);
    }
    return botHeaderColor != 0 ? botHeaderColor : Theme.getColor(ColorId.filling);
  }

  private @ColorInt int getDefaultBackgroundColor () {
    return botBackgroundColor != 0 ? botBackgroundColor : Theme.getColor(ColorId.filling);
  }

  private @ColorInt int getDefaultBottomBarColor () {
    return botBackgroundColor != 0 ? botBackgroundColor : Theme.getColor(ColorId.background);
  }

  private void animateColors (
    @ColorInt int header,
    @ColorInt int background,
    @ColorInt int bottomBar
  ) {
    if (header == toHeaderColor && background == toBackgroundColor &&
      bottomBar == toBottomBarColor) {
      return;
    }
    fromHeaderColor = headerColor;
    fromBackgroundColor = backgroundColor;
    fromBottomBarColor = bottomBarColor;
    toHeaderColor = header;
    toBackgroundColor = background;
    toBottomBarColor = bottomBar;
    colorAnimator.forceFactor(0f);
    if (hasShown) {
      colorAnimator.animateTo(1f);
    } else {
      colorAnimator.forceFactor(1f);
      onFactorChanged(ANIMATOR_COLORS, 1f, 1f, colorAnimator);
    }
  }

  private static boolean isLightColor (@ColorInt int color) {
    return M3.contrast(color) == 0xff000000;
  }

  private @ColorInt int getHeaderContentColor () {
    if (!isHeaderColorOverridden && headerColorKey == null && botHeaderColor == 0) {
      return Theme.getColor(ColorId.text);
    }
    return isLightColor(headerColor) ? 0xff000000 : 0xffffffff;
  }

  private void applyColors () {
    if (sheetLayout == null) {
      return;
    }
    final int contentColor = getHeaderContentColor();
    closeButton.setColor(contentColor);
    titleView.setTextColor(contentColor);
    minimizeButton.setColorFilter(contentColor);
    moreButton.setColorFilter(contentColor);
    contentView.setBackgroundColor(backgroundColor);
    if (webView != null) {
      webView.setBackgroundColor(backgroundColor);
    }
    buttonsView.setBarColor(bottomBarColor);
    placeholderView.setColor(backgroundColor);
    sheetLayout.invalidate();
  }

  void setHeaderColor (@ColorInt int color, @Nullable String colorKey) {
    if (colorKey != null) {
      headerColorKey = colorKey;
      isHeaderColorOverridden = false;
    } else {
      headerColorKey = null;
      isHeaderColorOverridden = true;
    }
    animateColors(colorKey != null ? getDefaultHeaderColor() : color, toBackgroundColor,
      toBottomBarColor);
  }

  void setAppBackgroundColor (@ColorInt int color) {
    isBackgroundColorOverridden = true;
    animateColors(toHeaderColor, color, toBottomBarColor);
  }

  void setBottomBarColor (@ColorInt int color) {
    isBottomBarColorOverridden = color != 0;
    animateColors(toHeaderColor, toBackgroundColor,
      color != 0 ? color : getDefaultBottomBarColor());
  }

  @Override
  public boolean needsTempUpdates () {
    return true;
  }

  @Override
  public void onThemeColorsChanged (boolean areTemp, @Nullable ColorState state) {
    if (sheetLayout == null) {
      return;
    }
    animateColors(
      isHeaderColorOverridden ? toHeaderColor : getDefaultHeaderColor(),
      isBackgroundColorOverridden ? toBackgroundColor : getDefaultBackgroundColor(),
      isBottomBarColorOverridden ? toBottomBarColor : getDefaultBottomBarColor()
    );
    if (!areTemp && isPageLoaded) {
      bridge.sendTheme();
    }
  }

  @Override
  public void onThemeChanged (ThemeDelegate fromTheme, ThemeDelegate toTheme) {
    initColors();
    if (isPageLoaded) {
      bridge.sendTheme();
    }
  }

  /**
   * Colors of the app in the format of {@code theme_changed}, the URL and
   * {@link TdApi.ThemeParameters}.
   */
  String buildThemeJson () {
    TdApi.ThemeParameters theme = WebApp.buildThemeParameters();
    JSONObject object = new JSONObject();
    try {
      object.put("bg_color", hex(theme.backgroundColor));
      object.put("secondary_bg_color", hex(theme.secondaryBackgroundColor));
      object.put("header_bg_color", hex(theme.headerBackgroundColor));
      object.put("bottom_bar_bg_color", hex(theme.bottomBarBackgroundColor));
      object.put("section_bg_color", hex(theme.sectionBackgroundColor));
      object.put("section_separator_color", hex(theme.sectionSeparatorColor));
      object.put("text_color", hex(theme.textColor));
      object.put("accent_text_color", hex(theme.accentTextColor));
      object.put("section_header_text_color", hex(theme.sectionHeaderTextColor));
      object.put("subtitle_text_color", hex(theme.subtitleTextColor));
      object.put("destructive_text_color", hex(theme.destructiveTextColor));
      object.put("hint_color", hex(theme.hintColor));
      object.put("link_color", hex(theme.linkColor));
      object.put("button_color", hex(theme.buttonColor));
      object.put("button_text_color", hex(theme.buttonTextColor));
    } catch (JSONException ignored) {
      // Keys are constants and values are strings.
    }
    return object.toString();
  }

  private static String hex (int color) {
    return String.format(Locale.US, "#%06X", color & 0xffffff);
  }

  // Loading

  private void requestUrl () {
    final Args args = getArgumentsStrict();
    final TdApi.WebAppOpenParameters parameters = WebApp.buildOpenParameters(args.mode);
    switch (args.type) {
      case TYPE_INLINE_BUTTON:
      case TYPE_MENU_BUTTON:
      case TYPE_ATTACHMENT_MENU: {
        long chatId = args.type == TYPE_MENU_BUTTON ?
          ChatId.fromUserId(args.botUserId) : args.chatId;
        tdlib.send(new TdApi.OpenWebApp(chatId, args.botUserId, args.url != null ? args.url : "",
          args.topicId, args.replyTo, parameters), (info, error) -> UI.post(() -> {
          if (error != null) {
            onUrlFailed(error);
          } else {
            launchId = info.launchId;
            if (isClosing) {
              tdlib.send(new TdApi.CloseWebApp(launchId), tdlib.typedOkHandler());
              return;
            }
            loadUrl(info.url);
          }
        }));
        break;
      }
      case TYPE_KEYBOARD_BUTTON:
      case TYPE_INLINE_SWITCH:
      case TYPE_SIDE_MENU: {
        tdlib.send(new TdApi.GetWebAppUrl(args.botUserId, args.url != null ? args.url : "",
          parameters), (url, error) -> UI.post(() -> onUrlReceived(url, error)));
        break;
      }
      case TYPE_APP_LINK: {
        tdlib.send(new TdApi.GetWebAppLinkUrl(args.chatId, args.botUserId,
          args.webAppShortName != null ? args.webAppShortName : "",
          args.startParameter != null ? args.startParameter : "", args.allowWriteAccess,
          parameters), (url, error) -> UI.post(() -> onUrlReceived(url, error)));
        break;
      }
      case TYPE_MAIN_APP: {
        tdlib.send(new TdApi.GetMainWebApp(args.chatId, args.botUserId,
          args.startParameter != null ? args.startParameter : "", parameters),
          (mainWebApp, error) ->
          UI.post(() -> {
            if (error != null) {
              onUrlFailed(error);
              return;
            }
            if (mainWebApp.mode != null) {
              int mode = WebApp.toMode(mainWebApp.mode);
              if (mode == MODE_FULLSCREEN && !isFullscreen) {
                requestFullscreen();
              }
            }
            loadUrl(mainWebApp.url);
          })
        );
        break;
      }
    }
  }

  private void onUrlReceived (@Nullable TdApi.WebAppUrl url, @Nullable TdApi.Error error) {
    if (error != null || url == null) {
      onUrlFailed(error);
    } else {
      loadUrl(url);
    }
  }

  private void onUrlFailed (@Nullable TdApi.Error error) {
    if (error != null) {
      UI.showError(error);
    }
    closeNow();
  }

  private void loadUrl (TdApi.WebAppUrl url) {
    if (isDestroyed() || isClosing || webView == null) {
      return;
    }
    requireSameOrigin = url.requireSameOrigin;
    trustedOrigin = requireSameOrigin ? getOrigin(url.url) : null;
    launchUrl = url.url;
    loadPlaceholder();
    webView.loadUrl(url.url);
  }

  private void loadPlaceholder () {
    tdlib.send(new TdApi.GetWebAppPlaceholder(getBotUserId()), (outline, error) -> UI.post(() -> {
      if (outline != null && !isDestroyed()) {
        placeholderView.setOutline(outline);
      }
    }));
  }

  void onPageReady () {
    if (isPageLoaded) {
      return;
    }
    isPageLoaded = true;
    loadedAnimator.setValue(true, true);
    sendViewport(true, !positionAnimator.isAnimating());
    sendSafeArea(true);
    sendContentSafeArea(true);
  }

  private void setLoadingProgress (float progress) {
    loadingProgress = progress;
    if (progress >= 1f) {
      progressAnimator.animateTo(1f);
    } else {
      progressAnimator.forceFactor(0f);
      progressAlpha = 1f;
    }
    sheetLayout.invalidate();
  }

  private void reload () {
    if (webView == null) {
      return;
    }
    setSettingsButtonVisible(false);
    isPageLoaded = false;
    hideError();
    loadedAnimator.setValue(false, false);
    placeholderView.setVisibility(View.VISIBLE);
    placeholderView.setAlpha(1f);
    webView.setAlpha(0f);
    setLoadingProgress(0f);
    webView.reload();
  }

  private void showError (@Nullable CharSequence description) {
    if (errorView == null) {
      errorView = new LinearLayout(context());
      errorView.setOrientation(LinearLayout.VERTICAL);
      errorView.setGravity(Gravity.CENTER_VERTICAL);
      errorView.setPadding(Screen.dp(32f), Screen.dp(24f), Screen.dp(32f), Screen.dp(24f));
      errorView.setLayoutParams(FrameLayoutFix.newParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      errorView.setClickable(true);
      contentView.addView(errorView);
    } else {
      errorView.removeAllViews();
      errorView.setVisibility(View.VISIBLE);
    }
    final int textColor = isLightColor(backgroundColor) ? 0xff000000 : 0xffffffff;
    errorView.setBackgroundColor(backgroundColor);
    TextView title = new TextView(context());
    title.setText(Lang.getString(R.string.vg_WebErrorTitle));
    title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f);
    title.setTypeface(Fonts.getRobotoBold());
    title.setTextColor(textColor);
    errorView.addView(title);
    TextView info = new TextView(context());
    info.setText(Lang.getStringBold(R.string.vg_WebErrorInfoBot, getBotName()));
    info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
    info.setTextColor(textColor);
    info.setMaxLines(3);
    info.setPadding(0, Screen.dp(8f), 0, 0);
    errorView.addView(info);
    if (!StringUtils.isEmpty(description)) {
      TextView details = new TextView(context());
      details.setText(description);
      details.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
      details.setTextColor(ColorUtils.alphaColor(.4f, textColor));
      details.setPadding(0, Screen.dp(4f), 0, 0);
      errorView.addView(details);
    }
    M3ButtonView refresh = new M3ButtonView(context(), M3ButtonView.STYLE_FILLED,
      M3ButtonView.SIZE_MEDIUM);
    refresh.setText(Lang.getString(R.string.vg_Refresh));
    refresh.setOnClickListener(v -> reload());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, refresh.height());
    params.topMargin = Screen.dp(16f);
    refresh.setLayoutParams(params);
    errorView.addView(refresh);
  }

  private void hideError () {
    if (errorView != null) {
      errorView.setVisibility(View.GONE);
    }
  }

  // Viewport

  void sendViewport (boolean force) {
    sendViewport(force, !positionAnimator.isAnimating() && !sheetLayout.isDragging());
  }

  /**
   * Reports {@code viewport_changed}: once with {@code is_state_stable = false} when a move starts
   * and once when it ends, instead of every frame, so frames of the sheet animation create no
   * strings for the page.
   */
  private void sendViewport (boolean force, boolean isStable) {
    if (sheetLayout == null || (!isPageLoaded && !force)) {
      return;
    }
    final int height = getContentHeight();
    final boolean isExpanded = isStable ? targetPosition == POSITION_EXPANDED ||
      targetPosition == POSITION_FULLSCREEN : lastViewportExpanded;
    if (!force && height == lastViewportHeight && isStable == lastViewportStable &&
      isExpanded == lastViewportExpanded) {
      return;
    }
    lastViewportHeight = height;
    lastViewportStable = isStable;
    lastViewportExpanded = isExpanded;
    JSONObject payload = new JSONObject();
    try {
      payload.put("height", height / Screen.density());
      payload.put("width", sheetLayout.getMeasuredWidth() / Screen.density());
      payload.put("is_state_stable", isStable);
      payload.put("is_expanded", isExpanded);
    } catch (JSONException ignored) {
      // Constant keys and numbers.
    }
    bridge.send("viewport_changed", payload);
  }

  void sendSafeArea (boolean force) {
    JSONObject payload = new JSONObject();
    boolean hasBottomInset = !isKeyboardVisible() && buttonsView.getTargetHeight() == 0;
    try {
      payload.put("left", isFullscreen ? insets.left / Screen.density() : 0);
      payload.put("top", isFullscreen ? insets.top / Screen.density() : 0);
      payload.put("right", isFullscreen ? insets.right / Screen.density() : 0);
      payload.put("bottom",
        isFullscreen && hasBottomInset ? insetsWithoutIme.bottom / Screen.density() : 0);
    } catch (JSONException ignored) {
      // Constant keys and numbers.
    }
    bridge.send("safe_area_changed", payload);
  }

  void sendContentSafeArea (boolean force) {
    JSONObject payload = new JSONObject();
    try {
      payload.put("left", 0);
      payload.put("top", isFullscreen ? CONTENT_SAFE_AREA_TOP : 0);
      payload.put("right", 0);
      payload.put("bottom", 0);
    } catch (JSONException ignored) {
      // Constant keys and numbers.
    }
    bridge.send("content_safe_area_changed", payload);
  }

  // Bridge actions

  void evaluateJs (String script) {
    if (webView != null && !isDestroyed()) {
      webView.evaluateJavascript(script, null);
    }
  }

  void expand () {
    if (!sheetLayout.isDragging() && !isFullscreen) {
      animatePosition(POSITION_EXPANDED);
    }
  }

  void setBackButtonVisible (boolean isVisible) {
    if (isBackButtonVisible != isVisible) {
      isBackButtonVisible = isVisible;
      backAnimator.setValue(isVisible, hasShown);
      if (isFullscreen) {
        sheetLayout.invalidatePills();
      }
    }
  }

  void setSettingsButtonVisible (boolean isVisible) {
    isSettingsButtonVisible = isVisible;
  }

  void setNeedClosingConfirmation (boolean needConfirmation) {
    needClosingConfirmation = needConfirmation;
  }

  void setAllowSwipes (boolean allowSwipes) {
    this.allowSwipes = allowSwipes;
  }

  void setPageScrollAllowed (boolean allowX, boolean allowY) {
    pageAllowsScrollX = allowX;
    pageAllowsScrollY = allowY;
    hasPageScrollReply = true;
  }

  void setupButton (
    boolean isMain,
    boolean isVisible,
    boolean isActive,
    @Nullable String text,
    @ColorInt int color,
    @ColorInt int textColor,
    boolean isProgressVisible,
    boolean hasShineEffect,
    long iconCustomEmojiId,
    @Nullable String position
  ) {
    buttonsView.setup(isMain, isVisible, isActive, text, color, textColor, isProgressVisible,
      hasShineEffect, iconCustomEmojiId, position);
  }

  @Override
  public void onButtonPressed (boolean isMain) {
    bridge.onButtonPressed(isMain);
  }

  @Override
  public void onVisibleHeightChanged (int targetHeight) {
    if (sheetLayout != null) {
      sheetLayout.requestLayout();
      sendViewport(true, true);
      sendSafeArea(true);
    }
  }

  @Nullable String requestFullscreen () {
    if (isFullscreen) {
      return "ALREADY_FULLSCREEN";
    }
    setFullscreen(true);
    return null;
  }

  void exitFullscreen () {
    if (isFullscreen) {
      setFullscreen(false);
    }
  }

  private void setFullscreen (boolean fullscreen) {
    isFullscreen = fullscreen;
    fullscreenEnteredAt = fullscreen ? SystemClock.uptimeMillis() : 0;
    headerView.setVisibility(View.VISIBLE);
    fullscreenAnimator.setValue(fullscreen, hasShown);
    sheetLayout.requestLayout();
    if (targetPosition != POSITION_HIDDEN && hasShown) {
      animatePosition(fullscreen ? POSITION_FULLSCREEN : POSITION_EXPANDED);
    }
    sheetLayout.invalidatePills();
    if (fullscreen) {
      sheetLayout.postDelayed(sheetLayout::invalidatePills, FULLSCREEN_NAME_DURATION);
    }
    sendSafeArea(true);
    sendContentSafeArea(true);
  }

  void setOrientationLocked (boolean locked) {
    if (isOrientationLocked == locked) {
      return;
    }
    isOrientationLocked = locked;
    BaseActivity activity = context();
    if (locked) {
      int orientation = activity.getResources().getConfiguration().orientation;
      activity.setRequestedOrientation(orientation == Configuration.ORIENTATION_LANDSCAPE ?
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE :
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    } else {
      activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }
  }

  void openBotProfile () {
    minimize();
    tdlib.ui().openPrivateProfile(this, getBotUserId(), null);
  }

  void openExternalLink (String url, boolean tryInstantView) {
    TdlibUi.UrlOpenParameters parameters = new TdlibUi.UrlOpenParameters().disableOpenPrompt();
    if (!tryInstantView) {
      parameters.disableInstantView();
    }
    tdlib.ui().openUrl(this, url, parameters);
  }

  void openTelegramLink (String url) {
    minimize();
    tdlib.ui().openUrl(this, url, new TdlibUi.UrlOpenParameters().disableOpenPrompt());
  }

  boolean openQrScanner (
    @Nullable String text,
    RunnableData<String> onCode,
    RunnableBool onClosed
  ) {
    qrCodeListener = onCode;
    qrClosedListener = onClosed;
    return WebApp.openQrScanner(this, text, code -> {
      if (qrCodeListener != null) {
        qrCodeListener.runWithData(code);
      }
    }, () -> {
      RunnableBool listener = qrClosedListener;
      qrCodeListener = null;
      qrClosedListener = null;
      if (listener != null) {
        listener.runWithBool(false);
      }
    });
  }

  void closeQrScanner () {
    RunnableBool listener = qrClosedListener;
    qrCodeListener = null;
    qrClosedListener = null;
    WebApp.closeQrScanner(this);
    if (listener != null) {
      listener.runWithBool(true);
    }
  }

  boolean isClipboardAllowed () {
    return WebApp.isAttachmentMenuBot(tdlib, getBotUserId());
  }

  void switchInlineQuery (String query, boolean currentChat) {
    TdApi.User bot = tdlib.cache().user(getBotUserId());
    if (bot == null || !Td.hasUsername(bot)) {
      return;
    }
    String username = Td.primaryUsername(bot);
    if (currentChat) {
      ViewController<?> c = context().navigation().getCurrentStackItem();
      if (c instanceof MessagesController &&
        ((MessagesController) c).getChatId() == getArgumentsStrict().chatId) {
        closeNow();
        ((MessagesController) c).switchInline(getBotUserId(),
          new TdApi.InlineKeyboardButtonTypeSwitchInline(query, new TdApi.TargetChatCurrent()));
      }
      return;
    }
    closeNow();
    tdlib.ui().switchInline(this, username, query, false);
  }

  void addToHomeScreen (RunnableBool onResult) {
    WebApp.addToHomeScreen(this, getBotUserId(), onResult);
  }

  String getHomeScreenStatus () {
    return WebApp.getHomeScreenStatus(tdlib, getBotUserId());
  }

  void showPremiumRequired () {
    UI.showToast(R.string.vg_WebAppPremiumRequired, android.widget.Toast.LENGTH_SHORT);
  }

  void showBulletin (CharSequence text) {
    if (sheetLayout == null) {
      return;
    }
    if (snackBar == null) {
      snackBar = new SnackBar(context());
      snackBar.setLayoutParams(FrameLayoutFix.newParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
      snackBar.addThemeListeners(this);
      sheetLayout.addView(snackBar, sheetLayout.indexOfChild(headerView));
    }
    snackBar.setBottomOffset(bottomPadding + buttonsView.getTargetHeight());
    snackBar.setText(text.toString());
    snackBar.showSnackBar(true);
  }

  boolean openRequestedChatPicker (
    TdApi.KeyboardButton button,
    TdApi.KeyboardButtonSource source,
    RunnableBool onResult
  ) {
    return WebApp.openRequestedChatPicker(this, getBotUserId(), button, source, onResult);
  }

  @Override
  public void hideSoftwareKeyboard () {
    super.hideSoftwareKeyboard();
    if (webView != null) {
      Keyboard.hide(webView);
    }
  }

  // Header buttons and menu

  @Override
  public void onClick (View v) {
    final int id = v.getId();
    if (id == R.id.btn_webAppClose) {
      if (isBackButtonVisible) {
        bridge.onBackButtonPressed();
      } else {
        close(true);
      }
    } else if (id == R.id.btn_webAppMinimize) {
      minimize();
    } else if (id == R.id.menu_btn_more) {
      showMenu(v);
    }
  }

  private void showMenu (View anchor) {
    int[] location = Views.getLocationOnScreen(anchor);
    showMenu(location[0], location[1], location[0] + anchor.getWidth(),
      location[1] + anchor.getHeight());
  }

  private void showMenu (int anchorLeft, int anchorTop, int anchorRight, int anchorBottom) {
    final TdApi.User bot = tdlib.cache().user(getBotUserId());
    final boolean hasMainWebApp = bot != null &&
      bot.type.getConstructor() == TdApi.UserTypeBot.CONSTRUCTOR &&
      ((TdApi.UserTypeBot) bot.type).hasMainWebApp;
    MenuMoreWrap menu = new MenuMoreWrap(context());
    menu.init(getThemeListeners(), null);
    View.OnClickListener listener = v -> {
      ((PopupLayout) v.getParent().getParent()).hideWindow(true);
      onMenuItemPressed(v.getId());
    };
    menu.addItem(R.id.btn_webAppOpenBot, Lang.getString(R.string.vg_BotWebViewOpenBot),
      R.drawable.deproko_baseline_bots_24, null, listener);
    if (isSettingsButtonVisible) {
      menu.addItem(R.id.btn_webAppSettings, Lang.getString(R.string.vg_BotWebViewSettings),
        R.drawable.baseline_settings_24, null, listener);
    }
    menu.addItem(R.id.btn_webAppReload, Lang.getString(R.string.vg_BotWebViewReloadPage),
      R.drawable.baseline_replay_24, null, listener);
    if (hasMainWebApp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      menu.addItem(R.id.btn_webAppAddToHomeScreen, Lang.getString(R.string.vg_AddShortcut),
        R.drawable.baseline_home_24, null, listener);
    }
    menu.addItem(R.id.btn_webAppTerms, Lang.getString(R.string.vg_BotWebViewToS),
      R.drawable.baseline_policy_24, null, listener);
    menu.addItem(R.id.btn_webAppReport, Lang.getString(R.string.vg_BotWebViewReportBot),
      R.drawable.baseline_report_24, null, listener);
    if (WebApp.isAttachmentMenuBot(tdlib, getBotUserId())) {
      menu.addItem(R.id.btn_webAppDelete, Lang.getString(R.string.vg_BotWebViewDeleteBot),
        R.drawable.baseline_delete_24, null, listener);
    }
    menu.setAnchor(anchorLeft, anchorTop, anchorRight, anchorBottom, true);
    PopupLayout menuPopup = new PopupLayout(context());
    menuPopup.init(true);
    menuPopup.setNeedRootInsets();
    menuPopup.setOverlayStatusBar(true);
    menuPopup.setDismissOtherPopUps(false);
    menuPopup.showMoreView(menu);
  }

  private void onMenuItemPressed (int id) {
    if (id == R.id.btn_webAppOpenBot) {
      minimize();
      tdlib.ui().openPrivateChat(this, getBotUserId(), null);
    } else if (id == R.id.btn_webAppSettings) {
      bridge.onSettingsButtonPressed();
    } else if (id == R.id.btn_webAppReload) {
      reload();
    } else if (id == R.id.btn_webAppAddToHomeScreen) {
      addToHomeScreen(ignored -> { });
    } else if (id == R.id.btn_webAppTerms) {
      openExternalLink(Lang.getString(R.string.vg_BotWebViewToSLink), true);
    } else if (id == R.id.btn_webAppReport) {
      TdlibUi.reportChat(this, ChatId.fromUserId(getBotUserId()), null, null, null, true);
    } else if (id == R.id.btn_webAppDelete) {
      WebApp.confirmRemoveFromMenu(this, getBotUserId(), this::closeNow);
    }
  }

  // Closing and minimizing

  /** Closes the app on its request: no confirmation, like the official client. */
  void closeByApp (boolean returnBack) {
    closeNow();
  }

  void close (boolean byUser) {
    if (isClosing) {
      return;
    }
    if (byUser && needClosingConfirmation) {
      final boolean[] confirmed = new boolean[1];
      AlertPopup.Builder b = new AlertPopup.Builder(context());
      b.setTitle(getBotName());
      b.setMessage(Lang.getString(R.string.vg_BotWebViewChangesMayNotBeSaved));
      b.setPositiveButton(Lang.getString(R.string.vg_BotWebViewCloseAnyway), (dialog, which) ->
        confirmed[0] = true);
      b.setPositiveButtonDestructive();
      b.setNegativeButton(Lang.getString(R.string.Cancel), null);
      b.setOnDismissListener(dialog -> {
        if (confirmed[0]) {
          closeNow();
        }
      });
      showAlert(b);
      return;
    }
    closeNow();
  }

  void closeNow () {
    if (isClosing) {
      return;
    }
    isClosing = true;
    WebAppTabs.remove(this);
    if (launchId != 0) {
      tdlib.send(new TdApi.CloseWebApp(launchId), tdlib.typedOkHandler());
    }
    if (bridge != null) {
      bridge.release();
    }
    setOrientationLocked(false);
    hideSoftwareKeyboard();
    if (popupLayout == null) {
      destroy();
      return;
    }
    if (isMinimized) {
      context().letsRememberAboutThisWindow(popupLayout);
      popupLayout.hideWindow(false);
    } else {
      popupLayout.hideWindow(true);
    }
  }

  void minimize () {
    if (isMinimized || isClosing || popupLayout == null) {
      return;
    }
    isMinimized = true;
    hideSoftwareKeyboard();
    tabPreview = takePreview();
    animatePosition(POSITION_HIDDEN);
  }

  private void onMinimized () {
    popupLayout.setVisibility(View.GONE);
    context().pretendYouDontKnowThisWindow(popupLayout);
    if (webView != null) {
      webView.onPause();
    }
    bridge.onVisibilityChanged(false);
    WebAppTabs.add(this);
  }

  private @Nullable Bitmap takePreview () {
    if (webView == null || webView.getWidth() == 0 || webView.getHeight() == 0) {
      return null;
    }
    final float scale = .5f;
    int width = Math.round(webView.getWidth() * scale);
    int height = Math.round(Math.min(webView.getHeight(), getContentHeight()) * scale);
    if (width <= 0 || height <= 0) {
      return null;
    }
    try {
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
      Canvas c = new Canvas(bitmap);
      c.scale(scale, scale);
      c.translate(-webView.getScrollX(), -webView.getScrollY());
      webView.draw(c);
      return bitmap;
    } catch (Throwable t) {
      Log.e("Cannot take a preview of a mini app", t);
      return null;
    }
  }

  // WebAppTab

  @Override
  public CharSequence getTabTitle () {
    return getBotName();
  }

  @Override
  public int getTabColor () {
    return headerColor;
  }

  @Override
  public @Nullable Bitmap getTabPreview () {
    return tabPreview;
  }

  @Override
  public void restoreFromTab () {
    if (!isMinimized || isClosing) {
      return;
    }
    WebAppTabs.remove(this);
    isMinimized = false;
    popupLayout.setVisibility(View.VISIBLE);
    context().letsRememberAboutThisWindow(popupLayout);
    if (webView != null) {
      webView.onResume();
    }
    bridge.onVisibilityChanged(true);
    sheetTop = hiddenTop;
    applyPosition();
    animatePosition(isFullscreen ? POSITION_FULLSCREEN : restingPosition);
    final Args args = getArgumentsStrict();
    if (args.needsContext) {
      ViewController<?> c = context().navigation().getCurrentStackItem();
      long botChatId = ChatId.fromUserId(args.botUserId);
      if (!(c instanceof MessagesController && ((MessagesController) c).getChatId() == botChatId)) {
        sheetLayout.postDelayed(() -> {
          if (!isDestroyed() && !isMinimized) {
            tdlib.ui().openPrivateChat(this, args.botUserId, null);
          }
        }, CONTEXT_OPEN_DELAY);
      }
    }
  }

  @Override
  public void closeFromTab (boolean force) {
    if (force) {
      closeNow();
    } else {
      close(true);
    }
  }

  // Lifecycle

  @Override
  public void onActivityPause () {
    super.onActivityPause();
    if (webView != null && !isMinimized) {
      webView.onPause();
    }
  }

  @Override
  public void onActivityResume () {
    super.onActivityResume();
    if (webView != null && !isMinimized) {
      webView.onResume();
    }
  }

  private void onFileChooserResult (int requestCode, int resultCode, Intent data) {
    if (requestCode == REQUEST_FILE_CHOOSER && fileChooserCallback != null) {
      ValueCallback<Uri[]> callback = fileChooserCallback;
      fileChooserCallback = null;
      Uri[] result = null;
      if (resultCode == android.app.Activity.RESULT_OK && data != null) {
        if (data.getClipData() != null) {
          int count = data.getClipData().getItemCount();
          result = new Uri[count];
          for (int i = 0; i < count; i++) {
            result[i] = data.getClipData().getItemAt(i).getUri();
          }
        } else if (data.getData() != null) {
          result = new Uri[] {data.getData()};
        }
      }
      callback.onReceiveValue(result);
    }
  }

  @Override
  public void destroy () {
    super.destroy();
    openControllers.remove(this);
    WebAppTabs.remove(this);
    ThemeManager.instance().removeThemeListener(this);
    if (!isClosing && launchId != 0) {
      tdlib.send(new TdApi.CloseWebApp(launchId), tdlib.typedOkHandler());
    }
    isClosing = true;
    positionAnimator.cancel();
    colorAnimator.cancel();
    if (bridge != null) {
      bridge.release();
    }
    if (buttonsView != null) {
      buttonsView.performDestroy();
    }
    if (fileChooserCallback != null) {
      fileChooserCallback.onReceiveValue(null);
      fileChooserCallback = null;
    }
    if (webView != null) {
      webView.stopLoading();
      webView.removeJavascriptInterface(WebAppBridge.INTERFACE_NAME);
      ViewGroup parent = (ViewGroup) webView.getParent();
      if (parent != null) {
        parent.removeView(webView);
      }
      webView.destroy();
      webView = null;
    }
    if (tabPreview != null) {
      tabPreview.recycle();
      tabPreview = null;
    }
  }

  // WebView clients

  private static final String SCROLL_SCRIPT =
    "(function(){if(window.__vgScroll)return;window.__vgScroll=1;" +
    "var p=window." + WebAppBridge.INTERFACE_NAME + ";if(!p)return;" +
    "var t=null,sent=false,blocked=false,pd=TouchEvent.prototype.preventDefault;" +
    "TouchEvent.prototype.preventDefault=function(){blocked=true;return pd.call(this);};" +
    "function ok(el,y){for(;el&&el.nodeType===1;el=el.parentElement){" +
    "if(y?el.scrollTop>0:el.scrollLeft>0)" +
    "return false;}var v=window.visualViewport;if(v&&(y?v.offsetTop:v.offsetLeft)>0)return false;" +
    "return y?(window.scrollY||0)<=0:(window.scrollX||0)<=0;}" +
    "function send(){if(sent)return;sent=true;" +
    "p.postEvent('web_app_allow_scroll',JSON.stringify([!blocked&&ok(t,0),!blocked&&ok(t,1)]));}" +
    "addEventListener('touchstart',function(e){t=e.target;sent=false;blocked=false;}," +
    "{capture:true,passive:true});" +
    "addEventListener('touchmove',function(){setTimeout(send,16);},{capture:true,passive:true});" +
    "addEventListener('scroll',function(){if(t)send();},{capture:true,passive:true});})();";

  private final class MiniAppWebViewClient extends WebViewClient {
    @Override
    public void onPageStarted (WebView view, String url, Bitmap favicon) {
      super.onPageStarted(view, url, favicon);
      bridge.onPageStarted();
      view.getSettings().setMediaPlaybackRequiresUserGesture(true);
      hasPageScrollReply = false;
      isScrollScriptInjected = false;
    }

    @Override
    public void onPageFinished (WebView view, String url) {
      super.onPageFinished(view, url);
      view.evaluateJavascript(SCROLL_SCRIPT, null);
      isScrollScriptInjected = true;
      onPageReady();
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading (WebView view, String url) {
      return overrideUrlLoading(url);
    }

    @Override
    public boolean shouldOverrideUrlLoading (WebView view, WebResourceRequest request) {
      return overrideUrlLoading(request.getUrl().toString());
    }

    @Override
    public void onReceivedError (WebView view, WebResourceRequest request, WebResourceError error) {
      super.onReceivedError(view, request, error);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()) {
        showError(error.getDescription());
      }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onReceivedError (
      WebView view,
      int errorCode,
      String description,
      String failingUrl
    ) {
      super.onReceivedError(view, errorCode, description, failingUrl);
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        showError(description);
      }
    }

    @Override
    public void onReceivedSslError (
      WebView view,
      SslErrorHandler handler,
      android.net.http.SslError error
    ) {
      handler.cancel();
    }

    @Override
    public boolean onRenderProcessGone (WebView view, RenderProcessGoneDetail detail) {
      if (webView == view) {
        AlertPopup.Builder b = new AlertPopup.Builder(context());
        b.setTitle(Lang.getString(R.string.vg_ChromeCrashTitle));
        b.setMessage(Lang.getMarkdownString(WebAppController.this, R.string.vg_ChromeCrashMessage));
        b.setPositiveButton(Lang.getString(R.string.OK), null);
        b.setOnDismissListener(dialog -> closeNow());
        showAlert(b);
        // The crashed WebView can't be used anymore: drop it, so nothing calls into it.
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
          parent.removeView(view);
        }
        view.destroy();
        webView = null;
      }
      return true;
    }
  }

  private boolean overrideUrlLoading (String url) {
    Uri uri = Uri.parse(url);
    String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
    if (scheme.equals("http") || scheme.equals("https")) {
      if (tdlib.isKnownHost(uri.getHost(), false)) {
        openTelegramLink(url);
        return true;
      }
      return false;
    }
    if (scheme.equals("tg")) {
      openTelegramLink(url);
      return true;
    }
    if (scheme.equals("sms")) {
      return false;
    }
    Intents.openUri(url);
    return true;
  }

  private final class MiniAppChromeClient extends WebChromeClient {
    @Override
    public void onProgressChanged (WebView view, int newProgress) {
      setLoadingProgress(newProgress / 100f);
    }

    @Override
    public boolean onConsoleMessage (ConsoleMessage consoleMessage) {
      return true;
    }

    @Override
    public void onCloseWindow (WebView window) {
      closeByApp(false);
    }

    @Override
    public boolean onCreateWindow (
      WebView view,
      boolean isDialog,
      boolean isUserGesture,
      android.os.Message message
    ) {
      // window.open: the page asks for a new window, open its URL in the app or the browser
      // instead.
      WebView target = new WebView(view.getContext());
      target.setWebViewClient(new WebViewClient() {
        @Override
        public boolean shouldOverrideUrlLoading (WebView ignored, WebResourceRequest request) {
          openLinkFromWindow(request.getUrl().toString());
          target.destroy();
          return true;
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading (WebView ignored, String url) {
          openLinkFromWindow(url);
          target.destroy();
          return true;
        }
      });
      WebView.WebViewTransport transport = (WebView.WebViewTransport) message.obj;
      transport.setWebView(target);
      message.sendToTarget();
      return true;
    }

    @Override
    public boolean onJsAlert (WebView view, String url, String message, JsResult result) {
      AlertPopup.Builder b = new AlertPopup.Builder(context());
      b.setTitle(getBotName());
      b.setMessage(message);
      b.setPositiveButton(Lang.getString(R.string.OK), null);
      b.setOnDismissListener(dialog -> result.confirm());
      if (showAlert(b) == null) {
        result.cancel();
      }
      return true;
    }

    @Override
    public boolean onJsConfirm (WebView view, String url, String message, JsResult result) {
      final boolean[] confirmed = new boolean[1];
      AlertPopup.Builder b = new AlertPopup.Builder(context());
      b.setTitle(getBotName());
      b.setMessage(message);
      b.setPositiveButton(Lang.getString(R.string.OK), (dialog, which) -> confirmed[0] = true);
      b.setNegativeButton(Lang.getString(R.string.Cancel), null);
      b.setOnDismissListener(dialog -> {
        if (confirmed[0]) {
          result.confirm();
        } else {
          result.cancel();
        }
      });
      if (showAlert(b) == null) {
        result.cancel();
      }
      return true;
    }

    @Override
    public boolean onJsPrompt (WebView view, String url, String message, String defaultValue,
                               JsPromptResult result) {
      WebApp.showPrompt(WebAppController.this, getBotName(), message, defaultValue, result);
      return true;
    }

    @Override
    public void onPermissionRequest (PermissionRequest request) {
      WebApp.onPermissionRequest(WebAppController.this, getBotName(), request);
    }

    @Override
    public void onGeolocationPermissionsShowPrompt (
      String origin,
      GeolocationPermissions.Callback callback
    ) {
      WebApp.onGeolocationPermissionRequest(WebAppController.this, getBotName(), origin, callback);
    }

    @Override
    public boolean onShowFileChooser (
      WebView view,
      ValueCallback<Uri[]> filePathCallback,
      FileChooserParams fileChooserParams
    ) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
        return false;
      }
      if (fileChooserCallback != null) {
        fileChooserCallback.onReceiveValue(null);
      }
      fileChooserCallback = filePathCallback;
      try {
        Intent intent = fileChooserParams.createIntent();
        if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
          intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        context().putActivityResultHandler(REQUEST_FILE_CHOOSER,
          WebAppController.this::onFileChooserResult);
        context().startActivityForResult(intent, REQUEST_FILE_CHOOSER);
        return true;
      } catch (Throwable t) {
        fileChooserCallback = null;
        return false;
      }
    }

    @Override
    public @Nullable Bitmap getDefaultVideoPoster () {
      return Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    }
  }

  private void openLinkFromWindow (String url) {
    if (overrideUrlLoading(url)) {
      return;
    }
    openExternalLink(url, false);
  }

  // Sheet

  private final class SheetLayout extends FrameLayoutFix {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF leftPill = new RectF(), rightPill = new RectF();
    private final Drawable backIcon, closeIcon, minimizeIcon, moreIcon;
    private String pillLabel = "";
    private float pillLabelWidth;
    private final int touchSlop;
    private @Nullable VelocityTracker velocityTracker;
    private @Nullable Runnable onLaidOut;

    private boolean isDragging, isTouchingOutside, isTouchingContent;
    private int touchedPill;
    private float startX, startY, startTop;
    private long startTime;
    private int lastWidth, lastHeight;

    SheetLayout (Context context) {
      super(context);
      setWillNotDraw(false);
      touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
      textPaint.setTypeface(Fonts.getRobotoBold());
      textPaint.setTextSize(Screen.sp(13f));
      textPaint.setColor(0xffffffff);
      backIcon = Drawables.get(getResources(), R.drawable.baseline_arrow_back_24);
      closeIcon = Drawables.get(getResources(), R.drawable.baseline_close_24);
      minimizeIcon = Drawables.get(getResources(), R.drawable.baseline_keyboard_arrow_down_24);
      moreIcon = Drawables.get(getResources(), R.drawable.baseline_more_vert_24);
    }

    boolean isDragging () {
      return isDragging;
    }

    void runWhenLaidOut (Runnable runnable) {
      if (getMeasuredHeight() != 0) {
        runnable.run();
      } else {
        onLaidOut = runnable;
      }
    }

    /**
     * The label shows the bot name for a moment after entering fullscreen, then what the left
     * button does.
     */
    void invalidatePills () {
      boolean showName =
        SystemClock.uptimeMillis() - fullscreenEnteredAt < FULLSCREEN_NAME_DURATION;
      pillLabel = showName ? getBotName() :
        Lang.getString(isBackButtonVisible ?
          R.string.vg_BotFullscreenBack : R.string.vg_BotFullscreenClose);
      pillLabelWidth = textPaint.measureText(pillLabel);
      invalidate();
    }

    @Override
    protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
      final int width = MeasureSpec.getSize(widthMeasureSpec),
        height = MeasureSpec.getSize(heightMeasureSpec);
      updatePositions(width, height);
      final int sheetWidth = M3Sheets.width(width);
      final int horizontalMargin = (width - sheetWidth) / 2;
      final int contentHeight = Math.max(0, height - bottomPadding - getContentTop() -
        buttonsView.getTargetHeight());
      setChildFrame(contentView, horizontalMargin, getContentTop(), contentHeight);
      FrameLayout.LayoutParams buttonsParams =
        (FrameLayout.LayoutParams) buttonsView.getLayoutParams();
      buttonsParams.bottomMargin = bottomPadding;
      buttonsParams.leftMargin = buttonsParams.rightMargin = horizontalMargin;
      FrameLayout.LayoutParams headerParams =
        (FrameLayout.LayoutParams) headerView.getLayoutParams();
      headerParams.leftMargin = headerParams.rightMargin = horizontalMargin;
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void setChildFrame (View view, int horizontalMargin, int top, int height) {
      FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
      params.topMargin = top;
      params.height = height;
      params.leftMargin = params.rightMargin = horizontalMargin;
    }

    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
      super.onLayout(changed, left, top, right, bottom);
      final int width = right - left, height = bottom - top;
      if (width != lastWidth || height != lastHeight) {
        lastWidth = width;
        lastHeight = height;
        if (hasShown && !positionAnimator.isAnimating() && !isDragging) {
          sheetTop = getPositionTop(targetPosition);
        }
        updateHeaderAppearance();
        applyPosition();
        sendViewport(false, true);
      }
      if (onLaidOut != null) {
        Runnable runnable = onLaidOut;
        onLaidOut = null;
        runnable.run();
      }
    }

    @Override
    protected void dispatchDraw (@NonNull Canvas c) {
      final int width = getMeasuredWidth(), height = getMeasuredHeight();
      final float handle = Screen.dp(HANDLE_AREA);
      final float fullscreen = fullscreenAnimator.getFloatValue();
      final float header = headerAnimator.getFloatValue();
      final int sheetWidth = M3Sheets.width(width);
      final float left = (width - sheetWidth) / 2f, right = left + sheetWidth;

      float openFactor = 1f;
      if (sheetTop > compactTop && hiddenTop > compactTop) {
        openFactor = MathUtils.clamp(1f - (sheetTop - compactTop) / (hiddenTop - compactTop));
      }
      final int dimAlpha = (int) (DIM_ALPHA * openFactor * (1f - fullscreen));
      final int dimBottom = height - (insetsWithoutIme.bottom + getTabsHeight());
      if (dimAlpha > 0) {
        paint.setColor(Color.argb(dimAlpha, 0, 0, 0));
        c.drawRect(0, 0, width, dimBottom, paint);
      }

      final float radius = M3Sheets.radius() * (1f - header);
      final float stripTop = MathUtils.fromTo(sheetTop, 0, header);
      paint.setColor(headerColor);
      rect.set(left, stripTop, right, sheetTop + handle + radius);
      c.drawRoundRect(rect, radius, radius, paint);
      paint.setColor(backgroundColor);
      c.drawRect(left, sheetTop + handle, right, height - bottomPadding, paint);

      final float handleAlpha = MathUtils.clamp(1f - header * 2f) * (1f - fullscreen);
      if (handleAlpha > 0f) {
        final float handleWidth = Screen.dp(HANDLE_WIDTH) * (1f - header);
        final float centerX = width / 2f, handleY = sheetTop + Screen.dp(HANDLE_TOP);
        paint.setColor(ColorUtils.alphaColor(.4f * handleAlpha, getHeaderContentColor()));
        rect.set(centerX - handleWidth / 2f, handleY - Screen.dp(HANDLE_HEIGHT) / 2f,
          centerX + handleWidth / 2f, handleY + Screen.dp(HANDLE_HEIGHT) / 2f);
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint);
      }

      final int navigationTop = height - bottomPadding;
      if (openFactor > 0f && getTabsHeight() == 0) {
        paint.setColor(ColorUtils.alphaColor(openFactor, bottomBarColor));
        c.drawRect(0, navigationTop, width, height, paint);
      }

      super.dispatchDraw(c);

      if (progressAlpha > 0f && loadingProgress > 0f) {
        paint.setColor(ColorUtils.alphaColor(progressAlpha, M3.primary()));
        float y = navigationTop - buttonsView.getVisibleHeight() - Screen.dp(2f);
        c.drawRect(left, y, left + sheetWidth * loadingProgress, y + Screen.dp(2f), paint);
      }

      if (fullscreen > 0f) {
        drawPills(c, fullscreen);
      }
    }

    private void drawPills (Canvas c, float alpha) {
      final float pillHeight = Screen.dp(PILL_HEIGHT), margin = Screen.dp(PILL_MARGIN);
      final float top = insets.top + margin;
      final float labelWidth = pillLabelWidth;
      leftPill.set(insets.left + margin, top,
        insets.left + margin + pillHeight + labelWidth + Screen.dp(12f), top + pillHeight);
      rightPill.set(getMeasuredWidth() - insets.right - margin - Screen.dp(RIGHT_PILL_WIDTH), top,
        getMeasuredWidth() - insets.right - margin, top + pillHeight);
      paint.setColor(Color.argb((int) (0x59 * alpha), 0, 0, 0));
      c.drawRoundRect(leftPill, pillHeight / 2f, pillHeight / 2f, paint);
      c.drawRoundRect(rightPill, pillHeight / 2f, pillHeight / 2f, paint);
      Paint iconPaint = Paints.getPorterDuffPaint(ColorUtils.alphaColor(alpha, 0xffffffff));
      final float iconScale = .75f;
      drawIcon(c, isBackButtonVisible ? backIcon : closeIcon, leftPill.left + pillHeight / 2f,
        leftPill.centerY(), iconScale, iconPaint);
      textPaint.setAlpha((int) (255 * alpha));
      c.drawText(pillLabel, leftPill.left + pillHeight,
        leftPill.centerY() + textPaint.getTextSize() / 2.8f, textPaint);
      final float half = rightPill.width() / 2f;
      drawIcon(c, minimizeIcon, rightPill.left + half / 2f, rightPill.centerY(), iconScale,
        iconPaint);
      drawIcon(c, moreIcon, rightPill.right - half / 2f, rightPill.centerY(), iconScale, iconPaint);
    }

    private void drawIcon (
      Canvas c,
      Drawable drawable,
      float centerX,
      float centerY,
      float scale,
      Paint iconPaint
    ) {
      c.save();
      c.scale(scale, scale, centerX, centerY);
      Drawables.draw(c, drawable, centerX - drawable.getMinimumWidth() / 2f,
        centerY - drawable.getMinimumHeight() / 2f, iconPaint);
      c.restore();
    }

    private int findPill (float x, float y) {
      if (!isFullscreen || fullscreenAnimator.getFloatValue() < 1f) {
        return 0;
      }
      final float extend = Screen.dp(PILL_MARGIN);
      if (x >= leftPill.left - extend && x <= leftPill.right + extend &&
        y >= leftPill.top - extend && y <= leftPill.bottom + extend) {
        return 1;
      }
      if (x >= rightPill.left - extend && x <= rightPill.right + extend &&
        y >= rightPill.top - extend && y <= rightPill.bottom + extend) {
        return x < rightPill.centerX() ? 2 : 3;
      }
      return 0;
    }

    private boolean canStartDrag (float dx, float dy) {
      if (!allowSwipes || Math.abs(dy) < touchSlop || Math.abs(dy) * 1.5f < Math.abs(dx)) {
        return false;
      }
      if (isTouchingContent) {
        final boolean isAtTop = targetPosition == POSITION_EXPANDED ||
          targetPosition == POSITION_FULLSCREEN;
        if (!isAtTop) {
          return true;
        }
        if (dy < 0 || isKeyboardVisible()) {
          return false;
        }
        // The page script reports whether the touched element can scroll up; until it does, the
        // page keeps the gesture, so inner scrollers of the page don't drag the sheet away.
        return webView == null || (webView.getScrollY() == 0 &&
          (!isScrollScriptInjected || (hasPageScrollReply && pageAllowsScrollY)));
      }
      return true;
    }

    @Override
    public boolean onInterceptTouchEvent (MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: {
          startX = e.getX();
          startY = e.getY();
          startTop = sheetTop;
          startTime = SystemClock.uptimeMillis();
          isDragging = false;
          touchedPill = findPill(startX, startY);
          final float handle = Screen.dp(HANDLE_AREA);
          final boolean isHeaderVisible = headerAnimator.getValue();
          isTouchingOutside = startY < sheetTop && !isHeaderVisible && !isFullscreen;
          isTouchingContent = startY >= sheetTop + handle && touchedPill == 0 &&
            startY < getMeasuredHeight() - bottomPadding - buttonsView.getVisibleHeight();
          hasPageScrollReply = false;
          obtainVelocityTracker(e);
          final boolean isHandle = startY >= sheetTop && startY < sheetTop + handle &&
            !isHeaderVisible;
          return isTouchingOutside || touchedPill != 0 || isHandle;
        }
        case MotionEvent.ACTION_MOVE: {
          trackVelocity(e);
          if (!isDragging && canStartDrag(e.getX() - startX, e.getY() - startY)) {
            startDrag(e);
            return true;
          }
          return false;
        }
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL: {
          recycleVelocityTracker();
          return false;
        }
      }
      return false;
    }

    private void startDrag (MotionEvent e) {
      isDragging = true;
      positionAnimator.cancel();
      startTop = sheetTop;
      startY = e.getY();
      sendViewport(false, false);
      getParent().requestDisallowInterceptTouchEvent(true);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent (MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          return !shouldTouchOutside(e.getX(), e.getY());
        case MotionEvent.ACTION_MOVE: {
          trackVelocity(e);
          final float dy = e.getY() - startY;
          if (!isDragging && (touchedPill != 0 || isTouchingOutside)) {
            if (Math.abs(dy) > touchSlop || Math.abs(e.getX() - startX) > touchSlop) {
              touchedPill = 0;
              isTouchingOutside = false;
            }
            if (!isTouchingOutside && touchedPill == 0 && canStartDrag(e.getX() - startX, dy)) {
              startDrag(e);
            }
            return true;
          }
          if (!isDragging && canStartDrag(e.getX() - startX, dy)) {
            startDrag(e);
          }
          if (isDragging) {
            float offset = e.getY() - startY;
            if (isTouchingContent && !isAtRest(POSITION_EXPANDED)) {
              offset = applyDeadZone(offset);
            }
            float minTop = getPositionTop(isFullscreen ? POSITION_FULLSCREEN : POSITION_EXPANDED);
            sheetTop = Math.max(minTop, startTop + offset);
            applyPosition();
          }
          return true;
        }
        case MotionEvent.ACTION_UP: {
          trackVelocity(e);
          if (touchedPill != 0) {
            onPillClick(touchedPill);
          } else if (isTouchingOutside) {
            minimize();
          } else if (isDragging) {
            finishDrag(e);
          }
          isDragging = false;
          touchedPill = 0;
          isTouchingOutside = false;
          recycleVelocityTracker();
          return true;
        }
        case MotionEvent.ACTION_CANCEL: {
          if (isDragging) {
            animatePosition(isFullscreen ? POSITION_FULLSCREEN : restingPosition);
          }
          isDragging = false;
          touchedPill = 0;
          isTouchingOutside = false;
          recycleVelocityTracker();
          return true;
        }
      }
      return true;
    }

    private boolean isAtRest (int position) {
      return restingPosition == position && !positionAnimator.isAnimating();
    }

    private float applyDeadZone (float offset) {
      final float deadZone = Screen.dp(CONTENT_DRAG_DEAD_ZONE);
      if (Math.abs(offset) <= deadZone) {
        return 0;
      }
      return offset > 0 ? offset - deadZone : offset + deadZone;
    }

    private void finishDrag (MotionEvent e) {
      final float velocity = getVelocityY();
      final float distance = Math.abs(e.getY() - startY);
      final long duration = SystemClock.uptimeMillis() - startTime;
      final boolean isLongGesture = distance > Screen.dp(LONG_DRAG_DISTANCE) ||
        duration > LONG_DRAG_DURATION;
      final boolean isLandscape = getMeasuredWidth() > getMeasuredHeight();
      final float sticky = Screen.dp(isLandscape ? STICKY_RANGE_LANDSCAPE : STICKY_RANGE);
      final boolean isFullSize = getArgumentsStrict().mode != MODE_COMPACT;
      final float restTop = getPositionTop(isFullscreen ? POSITION_FULLSCREEN : POSITION_COMPACT);
      final float offset = sheetTop - restTop;
      isDragging = false;
      if (velocity >= Screen.dp(FLING_DOWN_VELOCITY) && isLongGesture) {
        if (isFullscreen || isFullSize || offset > sticky) {
          minimize();
        } else {
          animatePosition(POSITION_COMPACT);
        }
        return;
      }
      if (velocity <= -FLING_UP_VELOCITY && !isFullscreen) {
        animatePosition(POSITION_EXPANDED);
        return;
      }
      if (isFullscreen) {
        if (offset > sticky * 2 && isLongGesture) {
          minimize();
        } else {
          animatePosition(POSITION_FULLSCREEN);
        }
        return;
      }
      if (offset <= -sticky) {
        animatePosition(POSITION_EXPANDED);
      } else if (offset <= sticky) {
        animatePosition(isFullSize ? POSITION_EXPANDED : POSITION_COMPACT);
      } else if (isLongGesture) {
        minimize();
      } else {
        animatePosition(POSITION_EXPANDED);
      }
    }

    private void onPillClick (int pill) {
      switch (pill) {
        case 1:
          if (isBackButtonVisible) {
            bridge.onBackButtonPressed();
          } else {
            close(true);
          }
          break;
        case 2:
          minimize();
          break;
        case 3:
          showMenuAt(rightPill);
          break;
      }
    }

    private void showMenuAt (RectF anchorRect) {
      int[] location = Views.getLocationOnScreen(this);
      showMenu(location[0] + (int) anchorRect.left, location[1] + (int) anchorRect.top,
        location[0] + (int) anchorRect.right, location[1] + (int) anchorRect.bottom);
    }

    private void obtainVelocityTracker (MotionEvent e) {
      if (velocityTracker == null) {
        velocityTracker = VelocityTracker.obtain();
      } else {
        velocityTracker.clear();
      }
      velocityTracker.addMovement(e);
    }

    private void trackVelocity (MotionEvent e) {
      if (velocityTracker != null) {
        velocityTracker.addMovement(e);
      }
    }

    private float getVelocityY () {
      if (velocityTracker == null) {
        return 0;
      }
      velocityTracker.computeCurrentVelocity(1000);
      return velocityTracker.getYVelocity();
    }

    private void recycleVelocityTracker () {
      if (velocityTracker != null) {
        velocityTracker.recycle();
        velocityTracker = null;
      }
    }
  }

  // Loading placeholder

  private final class PlaceholderView extends View {
    private static final float SIZE = 100f, SOURCE_SIZE = 512f;
    private static final float SQUARE = 133.69f, GAP = 31.29f, CORNER = 18f;
    private static final long PULSE_DURATION = 1200L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] hsv = new float[3];
    private @Nullable Path outlinePath;
    private @ColorInt int color;

    PlaceholderView (Context context) {
      super(context);
    }

    void setColor (@ColorInt int backgroundColor) {
      Color.colorToHSV(backgroundColor, hsv);
      if (isLightColor(backgroundColor)) {
        hsv[2] = Math.max(0f, hsv[2] - .15f);
      } else {
        hsv[1] = Math.min(1f, hsv[1] + .025f);
        hsv[2] = Math.min(1f, hsv[2] + .15f);
      }
      color = Color.HSVToColor(hsv);
      invalidate();
    }

    void setOutline (TdApi.Outline outline) {
      outlinePath = Td.buildOutline(outline.paths, (int) SOURCE_SIZE, (int) SOURCE_SIZE,
        Screen.dp(SIZE), Screen.dp(SIZE));
      invalidate();
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      final float size = Screen.dp(SIZE);
      final float left = (getMeasuredWidth() - size) / 2f;
      final float top = (Math.min(getMeasuredHeight(), getContentHeight()) - size) / 2f;
      final float pulse = (SystemClock.uptimeMillis() % PULSE_DURATION) / (float) PULSE_DURATION;
      final float alpha = .55f + .45f * (float) Math.abs(Math.sin(Math.PI * pulse));
      paint.setColor(ColorUtils.alphaColor(alpha, color));
      c.save();
      c.translate(left, top);
      if (outlinePath != null) {
        c.drawPath(outlinePath, paint);
      } else {
        final float scale = size / SOURCE_SIZE;
        final float square = SQUARE * scale, gap = GAP * scale, corner = CORNER * scale;
        final float offset = (size - square * 2 - gap) / 2f;
        for (int i = 0; i < 4; i++) {
          float x = offset + (i % 2) * (square + gap), y = offset + (i / 2) * (square + gap);
          rect.set(x, y, x + square, y + square);
          c.drawRoundRect(rect, corner, corner, paint);
        }
      }
      c.restore();
      postInvalidateOnAnimation();
    }
  }
}
