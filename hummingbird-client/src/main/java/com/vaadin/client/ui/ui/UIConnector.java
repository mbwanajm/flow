/*
 * Copyright 2000-2014 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.client.ui.ui;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dev.cfg.Styles;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.HeadElement;
import com.google.gwt.dom.client.LinkElement;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.dom.client.StyleElement;
import com.google.gwt.dom.client.StyleInjector;
import com.google.gwt.event.logical.shared.ResizeEvent;
import com.google.gwt.event.logical.shared.ResizeHandler;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Widget;
import com.vaadin.client.ApplicationConnection;
import com.vaadin.client.ApplicationConnection.ApplicationStoppedEvent;
import com.vaadin.client.BrowserInfo;
import com.vaadin.client.ComponentConnector;
import com.vaadin.client.ConnectorHierarchyChangeEvent;
import com.vaadin.client.ResourceLoader;
import com.vaadin.client.ResourceLoader.ResourceLoadEvent;
import com.vaadin.client.ResourceLoader.ResourceLoadListener;
import com.vaadin.client.ServerConnector;
import com.vaadin.client.UIDL;
import com.vaadin.client.VConsole;
import com.vaadin.client.ValueMap;
import com.vaadin.client.WidgetUtil;
import com.vaadin.client.annotations.OnStateChange;
import com.vaadin.client.communication.StateChangeEvent;
import com.vaadin.client.ui.AbstractConnector;
import com.vaadin.client.ui.AbstractHasComponentsConnector;
import com.vaadin.client.ui.JavaScriptManager;
import com.vaadin.client.ui.VUI;
import com.vaadin.shared.ApplicationConstants;
import com.vaadin.shared.Version;
import com.vaadin.shared.communication.SharedState;
import com.vaadin.shared.ui.ui.DebugWindowClientRpc;
import com.vaadin.shared.ui.ui.DebugWindowServerRpc;
import com.vaadin.shared.ui.ui.PageClientRpc;
import com.vaadin.shared.ui.ui.PageState;
import com.vaadin.shared.ui.ui.UIClientRpc;
import com.vaadin.shared.ui.ui.UIServerRpc;
import com.vaadin.shared.ui.ui.UIState;

public class UIConnector extends AbstractHasComponentsConnector {

    private String activeTheme = null;

    @Override
    protected Widget createWidget() {
        return new VUI();
    };

    @Override
    protected SharedState createState() {
        return new UIState();
    }

    @Override
    protected void init() {
        super.init();
        jsManager = new JavaScriptManager(this);
        registerRpc(PageClientRpc.class, new PageClientRpc() {

            @Override
            public void reload() {
                Window.Location.reload();

            }
        });
        registerRpc(UIClientRpc.class, new UIClientRpc() {
            @Override
            public void uiClosed(final boolean sessionExpired) {
                Scheduler.get().scheduleDeferred(new ScheduledCommand() {
                    @Override
                    public void execute() {
                        // Only notify user if we're still running and not eg.
                        // navigating away (#12298)
                        if (getConnection().isApplicationRunning()) {
                            if (sessionExpired) {
                                getConnection().showSessionExpiredError(null);
                            } else {
                                getState().enabled = false;
                                updateEnabledState(getState().enabled);
                            }
                            getConnection().setApplicationRunning(false);
                        }
                    }
                });
            }
        });
        registerRpc(DebugWindowClientRpc.class, new DebugWindowClientRpc() {

            @Override
            public void reportLayoutProblems(String json) {
                VConsole.printLayoutProblems(getValueMap(json),
                        getConnection());
            }

            private native ValueMap getValueMap(String json)
            /*-{
                return JSON.parse(json);
            }-*/;
        });

        getWidget().addResizeHandler(new ResizeHandler() {
            @Override
            public void onResize(ResizeEvent event) {
                getRpcProxy(UIServerRpc.class).resize(event.getHeight(),
                        event.getWidth(), Window.getClientWidth(),
                        Window.getClientHeight());
                if (getState().immediate) {
                    getConnection().getServerRpcQueue().flush();
                }
            }
        });
    }

    private native void open(String url, String name)
    /*-{
        $wnd.open(url, name);
     }-*/;

    /**
     * Reads CSS strings and resources injected by {@link Styles#inject} from
     * the UIDL stream.
     *
     * @param uidl
     *            The uidl which contains "css-resource" and "css-string" tags
     */
    private void injectCSS(UIDL uidl) {

        /*
         * Search the UIDL stream for CSS resources and strings to be injected.
         */
        for (Iterator<?> it = uidl.getChildIterator(); it.hasNext();) {
            UIDL cssInjectionsUidl = (UIDL) it.next();

            // Check if we have resources to inject
            if (cssInjectionsUidl.getTag().equals("css-resource")) {
                String url = getWidget().connection.translateVaadinUri(
                        cssInjectionsUidl.getStringAttribute("url"));
                LinkElement link = LinkElement
                        .as(DOM.createElement(LinkElement.TAG));
                link.setRel("stylesheet");
                link.setHref(url);
                link.setType("text/css");
                getHead().appendChild(link);
                // Check if we have CSS string to inject
            } else if (cssInjectionsUidl.getTag().equals("css-string")) {
                for (Iterator<?> it2 = cssInjectionsUidl.getChildIterator(); it2
                        .hasNext();) {
                    StyleInjector.injectAtEnd((String) it2.next());
                    StyleInjector.flush();
                }
            }
        }
    }

    /**
     * Internal helper to get the <head> tag of the page
     *
     * @since 7.3
     * @return the head element
     */
    private HeadElement getHead() {
        return HeadElement.as(Document.get()
                .getElementsByTagName(HeadElement.TAG).getItem(0));
    }

    /**
     * Internal helper for removing any stylesheet with the given URL
     *
     * @since 7.3
     * @param url
     *            the url to match with existing stylesheets
     */
    private void removeStylesheet(String url) {
        NodeList<Element> linkTags = getHead()
                .getElementsByTagName(LinkElement.TAG);
        for (int i = 0; i < linkTags.getLength(); i++) {
            LinkElement link = LinkElement.as(linkTags.getItem(i));
            if (!"stylesheet".equals(link.getRel())) {
                continue;
            }
            if (!"text/css".equals(link.getType())) {
                continue;
            }
            if (url.equals(link.getHref())) {
                getHead().removeChild(link);
            }
        }
    }

    public void init(String rootPanelId,
            ApplicationConnection applicationConnection) {
        // Create a style tag for style injections so they don't end up in
        // the theme tag in IE8-IE10 (we don't want to wipe them out if we
        // change theme).
        // StyleInjectorImplIE always injects to the last style tag on the page.
        if (BrowserInfo.get().isIE()
                && BrowserInfo.get().getBrowserMajorVersion() < 11) {
            StyleElement style = Document.get().createStyleElement();
            style.setType("text/css");
            getHead().appendChild(style);
        }

        DOM.sinkEvents(getWidget().getElement(),
                Event.ONKEYDOWN | Event.ONSCROLL);

        applicationConnection.addHandler(
                ApplicationConnection.ApplicationStoppedEvent.TYPE,
                new ApplicationConnection.ApplicationStoppedHandler() {

                    @Override
                    public void onApplicationStopped(
                            ApplicationStoppedEvent event) {
                        // Stop any polling
                        if (pollTimer != null) {
                            pollTimer.cancel();
                            pollTimer = null;
                        }
                    }
                });
    }

    // private ClickEventHandler clickEventHandler = new ClickEventHandler(this)
    // {
    //
    // @Override
    // protected void fireClick(NativeEvent event,
    // MouseEventDetails mouseDetails) {
    // getRpcProxy(UIServerRpc.class).click(mouseDetails);
    // }
    //
    // };

    private Timer pollTimer = null;

    private JavaScriptManager jsManager;

    @Override
    public VUI getWidget() {
        return (VUI) super.getWidget();
    }

    protected ComponentConnector getContent() {
        List<ComponentConnector> children = getChildComponents();
        if (children.isEmpty()) {
            return null;
        } else {
            return children.get(0);
        }
    }

    @Override
    public UIState getState() {
        return (UIState) super.getState();
    }

    /**
     * Returns the state of the Page associated with the UI.
     * <p>
     * Note that state is considered an internal part of the connector. You
     * should not rely on the state object outside of the connector who owns it.
     * If you depend on the state of other connectors you should use their
     * public API instead of their state object directly. The page state might
     * not be an independent state object but can be embedded in UI state.
     * </p>
     *
     * @since 7.1
     * @return state object of the page
     */
    public PageState getPageState() {
        return getState().pageState;
    }

    @Override
    public void onConnectorHierarchyChange(
            ConnectorHierarchyChangeEvent event) {
        ComponentConnector oldChild = null;
        ComponentConnector newChild = getContent();

        for (ComponentConnector c : event.getOldChildren()) {
            oldChild = c;
            break;
        }

        if (oldChild != newChild) {
            if (newChild != null) {
                getWidget().setWidget(newChild.getWidget());
            } else {
                getWidget().setWidget(null);
            }
        }

    }

    /**
     * Tries to scroll the viewport so that the given connector is in view.
     *
     * @param componentConnector
     *            The connector which should be visible
     *
     */
    public void scrollIntoView(final ComponentConnector componentConnector) {
        if (componentConnector == null) {
            return;
        }

        Scheduler.get().scheduleDeferred(new Command() {
            @Override
            public void execute() {
                componentConnector.getWidget().getElement().scrollIntoView();
            }
        });
    }

    @Override
    public void onStateChanged(StateChangeEvent stateChangeEvent) {
        super.onStateChanged(stateChangeEvent);
        if (stateChangeEvent
                .hasPropertyChanged("loadingIndicatorConfiguration")) {
            getConnection().getLoadingIndicator().setFirstDelay(
                    getState().loadingIndicatorConfiguration.firstDelay);
            getConnection().getLoadingIndicator().setSecondDelay(
                    getState().loadingIndicatorConfiguration.secondDelay);
            getConnection().getLoadingIndicator().setThirdDelay(
                    getState().loadingIndicatorConfiguration.thirdDelay);
        }

        if (stateChangeEvent.hasPropertyChanged("pollInterval")) {
            configurePolling();
        }

        if (stateChangeEvent.hasPropertyChanged("pageState.title")) {
            String title = getState().pageState.title;
            if (title != null) {
                com.google.gwt.user.client.Window.setTitle(title);
            }
        }
    }

    private void configurePolling() {
        if (pollTimer != null) {
            pollTimer.cancel();
            pollTimer = null;
        }
        if (getState().pollInterval >= 0) {
            pollTimer = new Timer() {
                @Override
                public void run() {
                    if (getState().pollInterval < 0) {
                        // Polling has been cancelled server side
                        pollTimer.cancel();
                        pollTimer = null;
                        return;
                    }
                    getRpcProxy(UIServerRpc.class).poll();
                    // Send changes even though poll is @Delayed
                    getConnection().getServerRpcQueue().flush();
                }
            };
            pollTimer.scheduleRepeating(getState().pollInterval);
        } else {
            // Ensure no more polls are sent as polling has been disabled
        }
    }

    /**
     * Sends a request to the server to print details to console that will help
     * the developer to locate the corresponding server-side connector in the
     * source code.
     *
     * @since 7.1
     * @param serverConnector
     *            the connector to locate
     */
    public void showServerDebugInfo(ServerConnector serverConnector) {
        getRpcProxy(DebugWindowServerRpc.class)
                .showServerDebugInfo(serverConnector);
    }

    /**
     * Sends a request to the server to print a design to the console for the
     * given component.
     *
     * @since 7.5
     * @param connector
     *            the component connector to output a declarative design for
     */
    public void showServerDesign(ServerConnector connector) {
        getRpcProxy(DebugWindowServerRpc.class).showServerDesign(connector);
    }

    @OnStateChange("theme")
    void onThemeChange() {
        final String oldTheme = activeTheme;
        final String newTheme = getState().theme;
        final String oldThemeUrl = getThemeUrl(oldTheme);
        final String newThemeUrl = getThemeUrl(newTheme);

        if (Objects.equals(oldTheme, newTheme)) {
            // This should only happen on the initial load when activeTheme has
            // been updated in init.

            if (newTheme == null) {
                return;
            }

            // For the embedded case we cannot be 100% sure that the theme has
            // been loaded and that the style names have been set.

            if (findStylesheetTag(oldThemeUrl) == null) {
                // If there is no style tag, load it the normal way (the class
                // name will be added when theme has been loaded)
                replaceTheme(null, newTheme, null, newThemeUrl);
            } else if (!getWidget().getParent().getElement()
                    .hasClassName(newTheme)) {
                // If only the class name is missing, add that
                activateTheme(newTheme);
            }
            return;
        }

        getLogger().info("Changing theme from " + oldTheme + " to " + newTheme);
        replaceTheme(oldTheme, newTheme, oldThemeUrl, newThemeUrl);
    }

    /**
     * Loads the new theme and removes references to the old theme
     *
     * @since 7.4.3
     * @param oldTheme
     *            The name of the old theme
     * @param newTheme
     *            The name of the new theme
     * @param oldThemeUrl
     *            The url of the old theme
     * @param newThemeUrl
     *            The url of the new theme
     */
    protected void replaceTheme(final String oldTheme, final String newTheme,
            String oldThemeUrl, final String newThemeUrl) {

        LinkElement tagToReplace = null;

        if (oldTheme != null) {
            tagToReplace = findStylesheetTag(oldThemeUrl);

            if (tagToReplace == null) {
                getLogger()
                        .warning("Did not find the link tag for the old theme ("
                                + oldThemeUrl
                                + "), adding a new stylesheet for the new theme ("
                                + newThemeUrl + ")");
            }
        }

        if (newTheme != null) {
            loadTheme(newTheme, newThemeUrl, tagToReplace);
        } else {
            if (tagToReplace != null) {
                tagToReplace.getParentElement().removeChild(tagToReplace);
            }

            activateTheme(null);
        }

    }

    private void updateVaadinFavicon(String newTheme) {
        NodeList<Element> iconElements = WidgetUtil
                .querySelectorAll("link[rel~=\"icon\"]");
        for (int i = 0; i < iconElements.getLength(); i++) {
            Element iconElement = iconElements.getItem(i);

            String href = iconElement.getAttribute("href");
            if (href != null && href.contains("VAADIN/themes")
                    && href.endsWith("/favicon.ico")) {
                href = href.replaceFirst("VAADIN/themes/.+?/favicon.ico",
                        "VAADIN/themes/" + newTheme + "/favicon.ico");
                iconElement.setAttribute("href", href);
            }
        }
    }

    /**
     * Finds a link tag for a style sheet with the given URL
     *
     * @since 7.3
     * @param url
     *            the URL of the style sheet
     * @return the link tag or null if no matching link tag was found
     */
    private LinkElement findStylesheetTag(String url) {
        NodeList<Element> linkTags = getHead()
                .getElementsByTagName(LinkElement.TAG);
        for (int i = 0; i < linkTags.getLength(); i++) {
            final LinkElement link = LinkElement.as(linkTags.getItem(i));
            if ("stylesheet".equals(link.getRel())
                    && "text/css".equals(link.getType())
                    && url.equals(link.getHref())) {
                return link;
            }
        }
        return null;
    }

    /**
     * Loads the given theme and replaces the given link element with the new
     * theme link element.
     *
     * @param newTheme
     *            The name of the new theme
     * @param newThemeUrl
     *            The url of the new theme
     * @param tagToReplace
     *            The link element to replace. If null, then the new link
     *            element is added at the end.
     */
    private void loadTheme(final String newTheme, final String newThemeUrl,
            final LinkElement tagToReplace) {
        LinkElement newThemeLinkElement = Document.get().createLinkElement();
        newThemeLinkElement.setRel("stylesheet");
        newThemeLinkElement.setType("text/css");
        newThemeLinkElement.setHref(newThemeUrl);
        ResourceLoader.addOnloadHandler(newThemeLinkElement,
                new ResourceLoadListener() {

                    @Override
                    public void onLoad(ResourceLoadEvent event) {
                        getLogger().info("Loading of " + newTheme + " from "
                                + newThemeUrl + " completed");

                        if (tagToReplace != null) {
                            tagToReplace.getParentElement()
                                    .removeChild(tagToReplace);
                        }
                        activateTheme(newTheme);
                    }

                    @Override
                    public void onError(ResourceLoadEvent event) {
                        getLogger().warning("Could not load theme from "
                                + getThemeUrl(newTheme));
                    }
                }, null);

        if (tagToReplace != null) {
            getHead().insertBefore(newThemeLinkElement, tagToReplace);
        } else {
            getHead().appendChild(newThemeLinkElement);
        }
    }

    /**
     * Activates the new theme. Assumes the theme has been loaded and taken into
     * use in the browser.
     *
     * @since 7.4.3
     * @param newTheme
     *            The name of the new theme
     */
    protected void activateTheme(String newTheme) {
        if (activeTheme != null) {
            getWidget().getParent().removeStyleName(activeTheme);
        }

        String oldThemeBase = getConnection().translateVaadinUri("theme://");

        activeTheme = newTheme;

        if (newTheme != null) {
            getWidget().getParent().addStyleName(newTheme);

            updateVaadinFavicon(newTheme);

        }

        forceStateChangeRecursively(UIConnector.this);
        // UIDL has no stored URL which we can repaint so we do some find and
        // replace magic...
        String newThemeBase = getConnection().translateVaadinUri("theme://");
        replaceThemeAttribute(oldThemeBase, newThemeBase);

    }

    /**
     * Finds all attributes where theme:// urls have possibly been used and
     * replaces any old theme url with a new one
     *
     * @param oldPrefix
     *            The start of the old theme URL
     * @param newPrefix
     *            The start of the new theme URL
     */
    private void replaceThemeAttribute(String oldPrefix, String newPrefix) {
        // Images
        replaceThemeAttribute("src", oldPrefix, newPrefix);
        // Embedded flash
        replaceThemeAttribute("value", oldPrefix, newPrefix);
        replaceThemeAttribute("movie", oldPrefix, newPrefix);
    }

    /**
     * Finds any attribute of the given type where theme:// urls have possibly
     * been used and replaces any old theme url with a new one
     *
     * @param attributeName
     *            The name of the attribute, e.g. "src"
     * @param oldPrefix
     *            The start of the old theme URL
     * @param newPrefix
     *            The start of the new theme URL
     */
    private void replaceThemeAttribute(String attributeName, String oldPrefix,
            String newPrefix) {
        // Find all "attributeName=" which start with "oldPrefix" using e.g.
        // [^src='http://oldpath']
        NodeList<Element> elements = WidgetUtil.querySelectorAll(
                "[" + attributeName + "^='" + oldPrefix + "']");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = elements.getItem(i);
            element.setAttribute(attributeName, element
                    .getAttribute(attributeName).replace(oldPrefix, newPrefix));
        }
    }

    /**
     * Force a full recursive recheck of every connector's state variables.
     *
     * @see #forceStateChange()
     *
     * @since 7.3
     */
    protected static void forceStateChangeRecursively(
            AbstractConnector connector) {
        connector.forceStateChange();

        for (ServerConnector child : connector.getChildren()) {
            if (child instanceof AbstractConnector) {
                forceStateChangeRecursively((AbstractConnector) child);
            } else {
                getLogger().warning(
                        "Could not force state change for unknown connector type: "
                                + child.getClass().getName());
            }
        }

    }

    /**
     * Internal helper to get the theme URL for a given theme
     *
     * @since 7.3
     * @param theme
     *            the name of the theme
     * @return The URL the theme can be loaded from
     */
    private String getThemeUrl(String theme) {
        String themeUrl = getConnection()
                .translateVaadinUri(ApplicationConstants.VAADIN_PROTOCOL_PREFIX
                        + "themes/" + theme + "/styles" + ".css");
        // Parameter appended to bypass caches after version upgrade.
        themeUrl += "?v=" + Version.getFullVersion();
        return themeUrl;

    }

    /**
     * Returns the name of the theme currently in used by the UI
     *
     * @since 7.3
     * @return the theme name used by this UI
     */
    public String getActiveTheme() {
        return activeTheme;
    }

    private static Logger getLogger() {
        return Logger.getLogger(UIConnector.class.getName());
    }

    /**
     * Returns the widget (if any) of the content of the container.
     *
     * @return widget of the only/first connector of the container, null if no
     *         content or if there is no widget for the connector
     */
    protected Widget getContentWidget() {
        ComponentConnector content = getContent();
        if (null != content) {
            return content.getWidget();
        } else {
            return null;
        }
    }

    @OnStateChange("javascriptManager")
    private void javascriptChange() {
        jsManager.onStateChanged();
    }

}