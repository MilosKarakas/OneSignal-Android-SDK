package com.onesignal;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;

import static com.onesignal.OSViewUtils.dpToPx;

// Manages WebView instances by pre-loading them, displaying them, and closing them when dismissed.
//   Includes a static map for pre-loading, showing, and dismissed so these events can't be duplicated.

// Flow for Displaying WebView
// 1. showHTMLString - Creates WebView and loads page.
// 2. Wait for JavaScriptInterface.postMessage to fire with "rendering_complete"
// 3. This calls showActivity which starts a new WebView
// 4. WebViewActivity will call WebViewManager.instanceFromIam(...) to get this instance and
//       add it's prepared WebView add add it to the Activity.

@TargetApi(Build.VERSION_CODES.KITKAT)
class WebViewManager extends ActivityLifecycleHandler.ActivityAvailableListener {

    private static final String TAG = WebViewManager.class.getCanonicalName();
    private static final int MARGIN_PX_SIZE = dpToPx(24);
    private static final int IN_APP_MESSAGE_INIT_DELAY = 200;
    private final Object messageViewSyncLock = new Object() {};

    enum Position {
        TOP_BANNER,
        BOTTOM_BANNER,
        CENTER_MODAL,
        FULL_SCREEN,
        ;

        boolean isBanner() {
            switch (this) {
                case TOP_BANNER:
                case BOTTOM_BANNER:
                    return true;
            }
            return false;
        }
    }

    @Nullable private OSWebView webView;
    @Nullable private InAppMessageView messageView;

    @Nullable protected static WebViewManager lastInstance = null;

    @NonNull private Activity activity;
    @NonNull private OSInAppMessageInternal message;
    @NonNull private OSInAppMessageContent messageContent;

    @Nullable private String currentActivityName = null;
    private Integer lastPageHeight = null;

    // dismissFired prevents onDidDismiss from getting called multiple times
    private boolean dismissFired = false;
    // closing prevents IAM being redisplayed when the activity changes during an actionHandler
    private boolean closing = false;

    interface OneSignalGenericCallback {
        void onComplete();
    }

    protected WebViewManager(@NonNull OSInAppMessageInternal message, @NonNull Activity activity, @NonNull OSInAppMessageContent content) {
        this.message = message;
        this.activity = activity;
        this.messageContent = content;
    }

    /**
     * Creates a new WebView
     * Dismiss WebView if already showing one and the new one is a Preview
     *
     * @param message the message to show
     * @param content the html to display on the WebView
     */
    static void showMessageContent(@NonNull final OSInAppMessageInternal message, @NonNull final OSInAppMessageContent content) {
        final Activity currentActivity = OneSignal.getCurrentActivity();
        OneSignal.onesignalLog(OneSignal.LOG_LEVEL.DEBUG, "in app message showMessageContent on currentActivity: " + currentActivity);
        /* IMPORTANT
         * This is the starting route for grabbing the current Activity and passing it to InAppMessageView */
        if (currentActivity != null) {
            // Only a preview will be dismissed, this prevents normal messages from being
            // removed when a preview is sent into the app
            if (lastInstance != null && message.isPreview) {
                // Created a callback for dismissing a message and preparing the next one
                lastInstance.dismissAndAwaitNextMessage(new OneSignalGenericCallback() {
                    @Override
                    public void onComplete() {
                        lastInstance = null;
                        initInAppMessage(currentActivity, message, content);
                    }
                });
            } else {
                initInAppMessage(currentActivity, message, content);
            }
            return;
        }

        /* IMPORTANT
         * Loop the setup for in app message until curActivity is not null */
        Looper.prepare();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                showMessageContent(message, content);
            }
        }, IN_APP_MESSAGE_INIT_DELAY);
    }

    static void dismissCurrentInAppMessage() {
        OneSignal.onesignalLog(OneSignal.LOG_LEVEL.DEBUG, "WebViewManager IAM dismissAndAwaitNextMessage lastInstance: " + lastInstance);
        if (lastInstance != null) {
            lastInstance.dismissAndAwaitNextMessage(null);
        }
    }

    private static void initInAppMessage(@NonNull final Activity currentActivity, @NonNull OSInAppMessageInternal message, @NonNull OSInAppMessageContent content) {
        try {
            final String base64Str = Base64.encodeToString(
                    content.getContentHtml().getBytes("UTF-8"),
                    Base64.NO_WRAP
            );

            final WebViewManager webViewManager = new WebViewManager(message, currentActivity, content);
            lastInstance = webViewManager;

            // Web view must be created on the main thread.
            OSUtils.runOnMainUIThread(new Runnable() {
                @Override
                public void run() {
                    // Handles exception "MissingWebViewPackageException: Failed to load WebView provider: No WebView installed"
                    try {
                        webViewManager.setupWebView(currentActivity, base64Str);
                    } catch (Exception e) {
                        // Need to check error message to only catch MissingWebViewPackageException as it isn't public
                        if (e.getMessage() != null && e.getMessage().contains("No WebView installed")) {
                            OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "Error setting up WebView: ", e);
                        } else {
                            throw e;
                        }
                    }
                }
            });
        } catch (UnsupportedEncodingException e) {
            OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "Catch on initInAppMessage: ", e);
            e.printStackTrace();
        }
    }

    // Lets JS from the page send JSON payloads to this class
    class OSJavaScriptInterface {

        static final String JS_OBJ_NAME = "OSAndroid";
        static final String GET_PAGE_META_DATA_JS_FUNCTION = "getPageMetaData()";

        static final String EVENT_TYPE_KEY = "type";
        static final String EVENT_TYPE_RENDERING_COMPLETE = "rendering_complete";
        static final String EVENT_TYPE_ACTION_TAKEN = "action_taken";
        static final String EVENT_TYPE_PAGE_CHANGE = "page_change";

        static final String IAM_DISPLAY_LOCATION_KEY = "displayLocation";
        static final String IAM_PAGE_META_DATA_KEY = "pageMetaData";
        static final String IAM_DRAG_TO_DISMISS_DISABLED_KEY = "dragToDismissDisabled";

        @JavascriptInterface
        public void postMessage(String message) {
            try {
                OneSignal.onesignalLog(OneSignal.LOG_LEVEL.DEBUG, "OSJavaScriptInterface:postMessage: " + message);

                JSONObject jsonObject = new JSONObject(message);
                String messageType = jsonObject.getString(EVENT_TYPE_KEY);

                switch (messageType) {
                    case EVENT_TYPE_RENDERING_COMPLETE:
                        handleRenderComplete(jsonObject);
                        break;
                    case EVENT_TYPE_ACTION_TAKEN:
                        // Added handling so that click actions won't trigger while dragging the IAM
                        if (!messageView.isDragging())
                            handleActionTaken(jsonObject);
                        break;
                    case EVENT_TYPE_PAGE_CHANGE:
                        handlePageChange(jsonObject);
                        break;
                    default:
                        break;
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        private void handleRenderComplete(JSONObject jsonObject) {
            Position displayType = getDisplayLocation(jsonObject);
            int pageHeight = displayType == Position.FULL_SCREEN ? -1 : getPageHeightData(jsonObject);
            boolean dragToDismissDisabled = getDragToDismissDisabled(jsonObject);
            messageContent.setDisplayLocation(displayType);
            messageContent.setPageHeight(pageHeight);
            createNewInAppMessageView(dragToDismissDisabled);
        }

        private int getPageHeightData(JSONObject jsonObject) {
            try {
                return WebViewManager.pageRectToViewHeight(activity, jsonObject.getJSONObject(IAM_PAGE_META_DATA_KEY));
            } catch (JSONException e) {
                return -1;
            }
        }

        private @NonNull Position getDisplayLocation(JSONObject jsonObject) {
            Position displayLocation = Position.FULL_SCREEN;
            try {
                if (jsonObject.has(IAM_DISPLAY_LOCATION_KEY) && !jsonObject.get(IAM_DISPLAY_LOCATION_KEY).equals(""))
                    displayLocation = Position.valueOf(jsonObject.optString(IAM_DISPLAY_LOCATION_KEY, "FULL_SCREEN").toUpperCase());
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return displayLocation;
        }

        private boolean getDragToDismissDisabled(JSONObject jsonObject) {
            try {
                return jsonObject.getBoolean(IAM_DRAG_TO_DISMISS_DISABLED_KEY);
            } catch (JSONException e) {
                return false;
            }
        }

        private void handleActionTaken(JSONObject jsonObject) throws JSONException {
            JSONObject body = jsonObject.getJSONObject("body");
            String id = body.optString("id", null);

            closing = body.getBoolean("close");

            if (message.isPreview) {
                OneSignal.getInAppMessageController().onMessageActionOccurredOnPreview(message, body);
            } else if (id != null) {
                OneSignal.getInAppMessageController().onMessageActionOccurredOnMessage(message, body);
            }

            if (closing) {
                dismissAndAwaitNextMessage(null);
            }
        }

        private void handlePageChange(JSONObject jsonObject) throws JSONException {
            OneSignal.getInAppMessageController().onPageChanged(message, jsonObject);
        }
    }

    private static int pageRectToViewHeight(final @NonNull Activity activity, @NonNull JSONObject jsonObject) {
        try {
            int pageHeight = jsonObject.getJSONObject("rect").getInt("height");
            int pxHeight = OSViewUtils.dpToPx(pageHeight);
            OneSignal.onesignalLog(OneSignal.LOG_LEVEL.DEBUG, "getPageHeightData:pxHeight: " + pxHeight);

            int maxPxHeight = getWebViewMaxSizeY(activity);
            if (pxHeight > maxPxHeight) {
                pxHeight = maxPxHeight;
                OneSignal.Log(OneSignal.LOG_LEVEL.DEBUG, "getPageHeightData:pxHeight is over screen max: " + maxPxHeight);
            }

            return pxHeight;
        } catch (JSONException e) {
            OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "pageRectToViewHeight could not get page height", e);
            return -1;
        }
    }

    // Every time an Activity is shown we update the height of the WebView since the available
    //   screen size may have changed. (Expect for Fullscreen)
    private void calculateHeightAndShowWebViewAfterNewActivity() {
        if (messageView == null)
            return;

        // Don't need a CSS / HTML height update for fullscreen
        if (messageView.getDisplayPosition() == Position.FULL_SCREEN) {
            showMessageView(null);
            return;
        }

        OneSignal.Log(OneSignal.LOG_LEVEL.DEBUG, "In app message new activity, calculate height and show ");

        // Using post to ensure that the status bar inset is already added to the view
        OSViewUtils.decorViewReady(activity, new Runnable() {
            @Override
            public void run() {
                // At time point the webView isn't attached to a view
                // Set the WebView to the max screen size then run JS to evaluate the height.
                setWebViewToMaxSize(activity);
                webView.evaluateJavascript(OSJavaScriptInterface.GET_PAGE_META_DATA_JS_FUNCTION, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(final String value) {
                        try {
                            int pagePxHeight = pageRectToViewHeight(activity, new JSONObject(value));
                            showMessageView(pagePxHeight);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
       });
    }

    @Override
    void available(final @NonNull Activity activity) {
        String lastActivityName = this.currentActivityName;
        this.activity = activity;
        this.currentActivityName = activity.getLocalClassName();

        OneSignal.Log(OneSignal.LOG_LEVEL.DEBUG, "In app message activity available " +
                "currentActivityName: " + currentActivityName + " lastActivityName: " + lastActivityName );

        if (lastActivityName == null)
            showMessageView(null);
        else if (!lastActivityName.equals(currentActivityName)) {
            if (!closing) {
                // Navigate to new activity while displaying current IAM
                if (messageView != null)
                    messageView.removeAllViews();
                showMessageView(lastPageHeight);
            }
        } else {
            // Activity rotated
            calculateHeightAndShowWebViewAfterNewActivity();
        }
    }

    @Override
    void stopped(@NonNull Activity activity) {
        OneSignal.Log(OneSignal.LOG_LEVEL.DEBUG, "In app message activity stopped, cleaning views, " +
                "currentActivityName: " + currentActivityName + "\nactivity: " + this.activity + "\nmessageView: " + messageView);

        if (messageView != null && activity.getLocalClassName().equals(currentActivityName))
            messageView.removeAllViews();
    }

    private void showMessageView(@Nullable Integer newHeight) {
        synchronized (messageViewSyncLock) {
            if (messageView == null) {
                OneSignal.Log(OneSignal.LOG_LEVEL.WARN, "No messageView found to update a with a new height.");
                return;
            }

            OneSignal.Log(OneSignal.LOG_LEVEL.DEBUG, "In app message, showing first one with height: " + newHeight);
            messageView.setWebView(webView);
            if (newHeight != null) {
                lastPageHeight = newHeight;
                messageView.updateHeight(newHeight);
            }
            messageView.showView(activity);
            messageView.checkIfShouldDismiss();
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView(@NonNull final Activity currentActivity, final @NonNull String base64Message) {
       enableWebViewRemoteDebugging();

       webView = new OSWebView(currentActivity);

       webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
       webView.setVerticalScrollBarEnabled(false);
       webView.setHorizontalScrollBarEnabled(false);
       webView.getSettings().setJavaScriptEnabled(true);

       // Setup receiver for page events / data from JS
       webView.addJavascriptInterface(new OSJavaScriptInterface(), OSJavaScriptInterface.JS_OBJ_NAME);

       blurryRenderingWebViewForKitKatWorkAround(webView);

       OSViewUtils.decorViewReady(currentActivity, new Runnable() {
          @Override
          public void run() {
             setWebViewToMaxSize(currentActivity);
             webView.loadData(base64Message,"text/html; charset=utf-8","base64");
          }
       });
    }

    private void blurryRenderingWebViewForKitKatWorkAround(@NonNull WebView webView) {
        // Android 4.4 has a rendering bug that cause the whole WebView to by extremely blurry
        // This is due to a bug with hardware rending so ensure it is disabled.
        // Tested on other version of Android and it is specific to only Android 4.4
        //    On both the emulator and real devices.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT)
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    // This sets the WebView view port sizes to the max screen sizes so the initialize
    //   max content height can be calculated.
    // A render complete or resize event will fire from JS to tell Java it's height and will then display
    //  it via this SDK's InAppMessageView class. If smaller than the screen it will correctly
    //  set it's height to match.
    private void setWebViewToMaxSize(Activity activity) {
        webView.layout(0,0, getWebViewMaxSizeX(activity), getWebViewMaxSizeY(activity));
    }

    private void setMessageView(InAppMessageView view) {
        synchronized (messageViewSyncLock) {
            messageView = view;
        }
    }

    private void createNewInAppMessageView(boolean dragToDismissDisabled) {
        lastPageHeight = messageContent.getPageHeight();
        InAppMessageView newView = new InAppMessageView(webView, messageContent, dragToDismissDisabled);
        setMessageView(newView);
        messageView.setMessageController(new InAppMessageView.InAppMessageViewListener() {
            @Override
            public void onMessageWasShown() {
                OneSignal.getInAppMessageController().onMessageWasShown(message);
            }

            @Override
            public void onMessageWillDismiss() {
                OneSignal.getInAppMessageController().onMessageWillDismiss(message);
            }

            @Override
            public void onMessageWasDismissed() {
                OneSignal.getInAppMessageController().messageWasDismissed(message);
                removeActivityListener();
            }
        });

        final ActivityLifecycleHandler activityLifecycleHandler = ActivityLifecycleListener.getActivityLifecycleHandler();
        // Fires event if available, which will call messageView.showInAppMessageView() for us.
        if (activityLifecycleHandler != null)
            activityLifecycleHandler.addActivityAvailableListener(TAG + message.messageId, this);
    }

    // Allow Chrome Remote Debugging if OneSignal.LOG_LEVEL.DEBUG or higher
    private static void enableWebViewRemoteDebugging() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                OneSignal.atLogLevel(OneSignal.LOG_LEVEL.DEBUG)) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    private static int getWebViewMaxSizeX(Activity activity) {
        return OSViewUtils.getWindowWidth(activity) - (MARGIN_PX_SIZE * 2);
    }

    private static int getWebViewMaxSizeY(Activity activity) {
       return OSViewUtils.getWindowHeight(activity) - (MARGIN_PX_SIZE * 2);
    }

    private void removeActivityListener() {
        ActivityLifecycleHandler activityLifecycleHandler = ActivityLifecycleListener.getActivityLifecycleHandler();
        if (activityLifecycleHandler != null)
            activityLifecycleHandler.removeActivityAvailableListener(TAG + message.messageId);
    }
    /**
     * Trigger the {@link #messageView} dismiss animation flow
     */
    protected void dismissAndAwaitNextMessage(@Nullable final OneSignalGenericCallback callback) {
        if (messageView == null || dismissFired) {
            if (callback != null)
                callback.onComplete();
            return;
        }
        if (message != null && messageView != null) {
            OneSignal.getInAppMessageController().onMessageWillDismiss(message);
        }
        messageView.dismissAndAwaitNextMessage(new OneSignalGenericCallback() {
            @Override
            public void onComplete() {
                dismissFired = false;
                setMessageView(null);

                if (callback != null)
                    callback.onComplete();
            }
        });
        dismissFired = true;
    }
}
