/*
 * ***** BEGIN LICENSE BLOCK *****
 * 
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2007 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Yahoo! Public License
 * Version 1.0 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * 
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.im.provider;

import java.io.File;
import java.util.HashMap;

import org.jivesoftware.util.JiveProperties;
import org.jivesoftware.util.PropertyProvider;

import com.zimbra.common.localconfig.LC;

public class IMGlobalProperties implements PropertyProvider {
    

    /**
     * LocalConfig mappings into jive properties
     */
    private HashMap<String, String> mLocalConfigMap = new HashMap<String, String>();
    
    private JiveProperties mJiveProps;
    
    public IMGlobalProperties() {
        mLocalConfigMap.put("xmpp.socket.ssl.keystore", LC.mailboxd_keystore.value());
        mLocalConfigMap.put("xmpp.socket.ssl.keypass", LC.mailboxd_keystore_password.value());
        
        // search out the trustStore
        String trustStoreLocation = System.getProperty("javax.net.ssl.trustStore", null);
        if (trustStoreLocation == null) {
            trustStoreLocation = LC.zimbra_java_home.value() + File.separator + "lib" + File.separator + "security" + File.separator + "cacerts"; 
            if (!new File(trustStoreLocation).exists()) {
                trustStoreLocation = LC.zimbra_java_home.value() + File.separator + "jre" + File.separator + "lib" + File.separator + "security" + File.separator + "cacerts"; 
            }
            mLocalConfigMap.put("xmpp.socket.ssl.truststore", trustStoreLocation);
        }
        
        mLocalConfigMap.put("xmpp.socket.ssl.trustpass", LC.mailboxd_truststore_password.value());
        mLocalConfigMap.put("xmpp.socket.blocking", "false");
        mLocalConfigMap.put("xmpp.server.certificate.verify", "false");
        
        if (LC.debug_xmpp_disable_client_tls.booleanValue()) {
            mLocalConfigMap.put("xmpp.client.tls.policy", "disabled");
        }
        
        if (LC.im_dnsutil_dnsoverride.value() != null && LC.im_dnsutil_dnsoverride.value().length() > 0) {
            mLocalConfigMap.put("dnsutil.dnsOverride", LC.im_dnsutil_dnsoverride.value());
        }
    }
    
    public String get(String key) {
        synchronized(this) {
            if (mLocalConfigMap.containsKey(key)) {
                return mLocalConfigMap.get(key);
            } else {
                if (mJiveProps == null)
                    mJiveProps = JiveProperties.getInstance();
            }
        }
        return mJiveProps.get(key);
    }

    public String put(String key, String value) {
        synchronized(this) {
            if (mLocalConfigMap.containsKey(key)) {
                throw new UnsupportedOperationException("Cannot write to provisioning-mapped props yet");
            }
            if (mJiveProps == null)
                mJiveProps = JiveProperties.getInstance();
        }
        return mJiveProps.put(key, value);
    }

    public String remove(String key) {
        synchronized(this) {
            if (mLocalConfigMap.containsKey(key)) {
                throw new UnsupportedOperationException("Cannot write to provisioning-mapped props yet");
            }
            if (mJiveProps == null)
                mJiveProps = JiveProperties.getInstance();
        }
        return mJiveProps.remove(key);
    }
}
