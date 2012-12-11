package org.lantern.state;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.security.auth.login.CredentialException;

import org.apache.commons.lang.SystemUtils;
import org.lantern.LanternConstants;
import org.lantern.LanternUtils;
import org.lantern.NotInClosedBetaException;
import org.lantern.Proxifier;
import org.lantern.Proxifier.ProxyConfigurationError;
import org.lantern.XmppHandler;
import org.lantern.state.Settings.Mode;
import org.lantern.win.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Class that does the dirty work of executing changes to the various settings 
 * users can configure.
 */
@Singleton
public class DefaultModelService implements ModelService {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private final File launchdPlist;
    
    private final Executor proxyQueue = Executors.newSingleThreadExecutor(
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat(
            "System-Proxy-Thread-%d").build());

    private final File gnomeAutostart;

    private final Model model;

    private final Proxifier proxifier;

    private final ModelUtils modelUtils;

    private XmppHandler xmppHandler;

    @Inject
    public DefaultModelService(final Model model,
        final Proxifier proxifier, final ModelUtils modelUtils,
        final XmppHandler xmppHandler) {
        this(LanternConstants.LAUNCHD_PLIST, LanternConstants.GNOME_AUTOSTART, 
                model, proxifier, modelUtils, xmppHandler);
    }
    
    public DefaultModelService(final File launchdPlist, 
        final File gnomeAutostart, final Model model,
        final Proxifier proxifier, final ModelUtils modelUtils,
        final XmppHandler xmppHandler) {
        this.launchdPlist = launchdPlist;
        this.gnomeAutostart = gnomeAutostart;
        this.model = model;
        this.proxifier = proxifier;
        this.modelUtils = modelUtils;
        this.xmppHandler = xmppHandler;
    }
    
    @Override
    public void setStartAtLogin(final boolean start) {
        log.debug("Setting start at login to "+start);
        
        this.model.getSettings().setStartAtLogin(start);
        if (SystemUtils.IS_OS_MAC_OSX && this.launchdPlist.isFile()) {
            setStartAtLoginOsx(start);
        } else if (SystemUtils.IS_OS_WINDOWS) {
            setStartAtLoginWindows(start);
        } else if (SystemUtils.IS_OS_LINUX) {
            log.info("Setting setStartAtLogin for Linux");
            setStartAtLoginLinux(start);
        } else {
            log.warn("setStartAtLogin not yet implemented for {}", SystemUtils.OS_NAME);
        }
    }

    public void setStartAtLoginOsx(final boolean start) {
        LanternUtils.replaceInFile(this.launchdPlist, 
                "<"+!start+"/>", "<"+start+"/>");
    }

    public void setStartAtLoginLinux(final boolean start) {
        LanternUtils.replaceInFile(this.gnomeAutostart, 
            "X-GNOME-Autostart-enabled="+!start, "X-GNOME-Autostart-enabled="+start);
    }

    public void setStartAtLoginWindows(final boolean start) {
        final String key = 
            "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        int result = 0;
        if (start) {
            try {
                final String path = 
                    "\""+new File("Lantern.exe").getCanonicalPath()+"\"" + " --launchd";
                    //"\"\\\""+new File("Lantern.exe").getCanonicalPath()+"\\\"\"" + " --launchd";
                
                
                Registry.write(key, "Lantern", path);
            } catch (final IOException e) {
                log.error("Could not get canonical path", e);
            }
        } else {
            Registry.write(key, "Lantern", "");
        }
        
        if (result != 0) {
            log.error("Error changing startAtLogin? Result: "+result);
        }
    }
    
    @Override
    public void setProxyAllSites(final boolean proxyAll) {
        this.model.getSettings().setProxyAllSites(proxyAll);
        try {
            proxifier.proxyAllSites(proxyAll);
        } catch (final ProxyConfigurationError e) {
            throw new RuntimeException("Error proxying all sites!", e);
        }
    }

    @Override
    public void setSystemProxy(final boolean isSystemProxy) {
        if (isSystemProxy == this.model.getSettings().isSystemProxy()) {
            log.info("System proxy setting is unchanged.");
            return;
        }
        this.model.getSettings().setSystemProxy(isSystemProxy);
        
        log.info("Setting system proxy");
        
        // Go ahead and change the setting so that it will affect. It will 
        // be set again by the api, but that doesn't matter.
        this.model.getSettings().setSystemProxy(isSystemProxy);
        if (!this.model.isSetupComplete()) {
            return;
        }
        
        final Runnable proxyRunner = new Runnable() {
            @Override
            public void run() {
                try {
                    if (modelUtils.shouldProxy() ) {
                        proxifier.startProxying();
                    } else {
                        proxifier.stopProxying();
                    }
                } catch (final Proxifier.ProxyConfigurationError e) {
                    log.error("Proxy reconfiguration failed: {}", e);
                }
            }
        };
        proxyQueue.execute(proxyRunner);
    }

    @Override
    public void setMode(final Mode mode) {
        log.debug("Calling set get mode. Get is: "+mode);
        // When we move to give mode, we want to start advertising our 
        // ID and to start accepting incoming connections.
        
        // We we move to get mode, we want to stop advertising our ID and to
        // stop accepting incoming connections.
        final Settings set = this.model.getSettings();
        if (mode == set.getMode()) {
            log.info("Mode is unchanged.");
            return;
        }
        
        
        if (!this.modelUtils.isConfigured()) {
            log.info("Not implementing mode change -- not configured.");
            return;
        }
        
        // Go ahead and set the setting although it will also be
        // updated by the api as well. We want to make sure the
        // state seen by the following calls is consistent with
        // this flag being aspirational vs. representational
        set.setMode(mode);
        
        // We disconnect and reconnect to create a new Jabber ID that will 
        // not advertise us as a connection point.
        if (!model.isSetupComplete()) {
            log.debug("Not disconnecting and reconnecting before setup is " +
                "complete");
            return;
        }
        
        // We dont' want to force the frontend to wait for all of this, so we
        // thread it.
        final Runnable runner = new Runnable() {

            @Override
            public void run() {
                xmppHandler.disconnect();
                try {
                    try {
                        xmppHandler.connect();
                        
                        // TODO: This isn't quite right. We don't necessarily have
                        // proxies to connect to at this point, and we shouldn't set
                        // the OS proxy until we do.
                        // may need to modify the proxying state
                        if (modelUtils.shouldProxy()) {
                            proxifier.startProxying();
                        } else {
                            proxifier.stopProxying();
                        }
                    } catch (final IOException e) {
                        log.info("Could not connect to server", e);
                        // Don't proxy if there's some error connecting.
                        proxifier.stopProxying();
                    } catch (final CredentialException e) {
                        log.info("Credentials are wrong!!");
                        proxifier.stopProxying();
                    } catch (final NotInClosedBetaException e) {
                        log.info("Not in beta!!");
                        proxifier.stopProxying();
                    }
                } catch (final Proxifier.ProxyConfigurationError e) {
                    log.info("Proxy auto-configuration failed: {}", e);
                }
            }
        };
        
        final Thread t = new Thread(runner, "Mode-Shift-Thread");
        t.setDaemon(true);
        t.start();
    }
}
