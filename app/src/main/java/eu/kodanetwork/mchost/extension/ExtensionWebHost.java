/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * KodaHosting. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 KodaHosting
 * SPDX-License-Identifier: GPL-3.0-only
 */
package eu.kodanetwork.mchost.extension;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;

import eu.kodanetwork.mchost.service.KodaServerService;

/**
 * the hardened WebView sandbox of one extension, reused everywhere an
 * extension page shows up (the host screen and the server tabs).
 *
 * only the extension's own local files are served, every other request is
 * blocked before it leaves the WebView, so remote code can never end up in
 * here. window.koda comes from the injected shim and is answered by the
 * {@link ExtensionBridge} (permissions, rate limits, error objects).
 */
public final class ExtensionWebHost {

    public static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String TAG = "KodaExtensions";

    public interface ErrorListener {
        /** the main frame failed or the renderer died. */
        void onLoadError();
        /** a page finished loading without trouble. */
        void onLoadOk();
    }

    private final Activity activity;
    private final ExtensionRepository.InstalledExtension ext;
    private final WebView webView;
    private final ExtensionBridge bridge;
    private final ExtensionAssetHandler assetHandler;
    private ErrorListener errorListener;
    private boolean destroyed;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public ExtensionWebHost(Activity activity, WebView webView,
                            ExtensionRepository.InstalledExtension ext) {
        this.activity = activity;
        this.webView = webView;
        this.ext = ext;
        this.bridge = new ExtensionBridge(activity, ext);
        this.assetHandler = new ExtensionAssetHandler(ext.dir, ext.id);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.i(TAG, "console[" + cm.messageLevel() + "] " + cm.message()
                        + " @" + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u != null && ORIGIN.equals(u.getScheme() + "://" + u.getHost())) {
                    WebResourceResponse r = assetHandler.handle(u.getPath());
                    if (r != null) return r;
                }
                // Everything that is not the extension's own file is blocked.
                Log.w(TAG, "blocked request: " + (u == null ? "null" : u.toString()));
                return blocked();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u == null) return true;
                if (ORIGIN.equals(u.getScheme() + "://" + u.getHost())) return false;
                if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Throwable ignored) {}
                }
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // the extension UI renderer crashed, the app has to survive that on its own
                if (errorListener != null) errorListener.onLoadError();
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame() && errorListener != null) {
                    errorListener.onLoadError();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (errorListener != null) errorListener.onLoadOk();
            }
        });

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "kodaTransport",
                    Collections.singleton(ORIGIN),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (!isMainFrame || message == null) return;
                        Object data = message.getData();
                        if (!(data instanceof String)) return;
                        final String payload = (String) data;
                        final long callId = idOf(payload);
                        bridge.dispatch(payload, result -> {
                            try {
                                JSONObject envelope = new JSONObject();
                                envelope.put("id", callId);
                                envelope.put("result", result);
                                replyProxy.postMessage(envelope.toString());
                            } catch (Throwable ignored) {}
                        });
                    });
        } else {
            webView.addJavascriptInterface(new JavaTransport(), "kodaBridgeJava");
        }
    }

    /** fallback transport for WebViews without WebMessageListener. */
    private class JavaTransport {
        @JavascriptInterface
        public void call(String payloadJson) {
            final long callId = idOf(payloadJson);
            bridge.dispatch(payloadJson, result -> {
                if (destroyed) return;
                final String js = "window.__kodaReply(" + callId + "," + JSONObject.quote(result) + ")";
                webView.post(() -> {
                    try {
                        webView.evaluateJavascript(js, null);
                    } catch (Throwable ignored) {}
                });
            });
        }
    }

    private static long idOf(String payloadJson) {
        try {
            return new JSONObject(payloadJson).optLong("id", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked",
                new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    /** hands the bound service to the bridge, console.tail needs it. */
    public void setService(KodaServerService service) {
        bridge.setService(service);
    }

    public void setErrorListener(ErrorListener listener) {
        this.errorListener = listener;
    }

    /**
     * loads a page of the extension. {@code hash} becomes the URL fragment, so
     * a page can deep-link into a view ("#overview") or a server ("#server=<id>").
     */
    public void load(String entry, String hash) {
        if (destroyed) return;
        String url = ORIGIN + "/ext/" + ext.id + "/" + entry;
        if (hash != null && !hash.isEmpty()) url += "#" + hash;
        Log.i(TAG, "load " + url + " (exists=" + new File(ext.dir, entry).isFile() + ")");
        webView.loadUrl(url);
    }

    /** a page plus the optional target and server context, in one call. */
    public void load(String entry, String target, String serverId) {
        StringBuilder hash = new StringBuilder();
        if (target != null && !target.isEmpty()) hash.append(target);
        if (serverId != null && !serverId.isEmpty()) {
            if (hash.length() > 0) hash.append("&");
            hash.append("server=").append(serverId);
        }
        load(entry, hash.toString());
    }

    public boolean canGoBack() {
        return !destroyed && webView.canGoBack();
    }

    public void goBack() {
        if (!destroyed) webView.goBack();
    }

    public void destroy() {
        destroyed = true;
        bridge.setService(null);
        try {
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.stopLoading();
            webView.destroy();
        } catch (Throwable ignored) {}
    }

    /** the view to attach to the layout, the server tabs use this. */
    public View view() {
        return webView;
    }
}
