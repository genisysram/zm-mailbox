package com.zimbra.cs.ldap.unboundid;

import java.util.ArrayList;
import java.util.List;
import javax.net.SocketFactory;

import com.unboundid.ldap.sdk.FailoverServerSet;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.ServerSet;
import com.unboundid.ldap.sdk.SingleServerSet;

import com.zimbra.cs.ldap.LdapConnType;
import com.zimbra.cs.ldap.LdapTODO.*;
import com.zimbra.cs.ldap.LdapServerType;
import com.zimbra.cs.ldap.LdapException;


public class LdapHost {
    List<LDAPURL> urls;
    LdapServerType serverType;  // do we need this?
    LdapConnType connType;
    
    ServerSet serverSet;

    /**
     * 
     * @param serverType
     * @param urls space separated urls
     */
    public LdapHost(String urls, LdapServerType serverType, LdapConnType connType,
            SocketFactory socketFactory) 
    throws LdapException {
        this.urls = new ArrayList<LDAPURL>();
        
        String[] ldapUrls = urls.split(" ");
        for (String ldapUrl : ldapUrls) {
            try {
                LDAPURL url = new LDAPURL(ldapUrl);
                this.urls.add(url);
            } catch (LDAPException e) {
                throw LdapException.INVALID_CONFIG(e);
            }
            
        }
        
        this.serverType = serverType;
        this.connType = connType;
        
        this.serverSet = createServerSet(socketFactory);
    }
    
    public List<LDAPURL> getUrls() {
        return urls;
    }
    
    public boolean isMaster() {
        return serverType.isMaster();
    }
    
    public LdapConnType getConnectionType() {
        return connType;
    }
    
    public ServerSet getServerSet() {
        return serverSet;
    }
    
    @TODO // get value from LdapConfig
    private LDAPConnectionOptions getConnectionOptions() {
        return new LDAPConnectionOptions();
    }
    
    @TODO
    private ServerSet createServerSet(SocketFactory socketFactory){
        
        LDAPConnectionOptions connOpts = getConnectionOptions();
        
        if (urls.size() == 1) {
            LDAPURL url = urls.get(0);
            if (socketFactory == null) {
                return new SingleServerSet(url.getHost(), url.getPort(), connOpts);
            } else {
                return new SingleServerSet(url.getHost(), url.getPort(), socketFactory, connOpts);
            }
        } else {
            int numUrls = urls.size();
            
            final String[] hosts = new String[numUrls];
            final int[]    ports = new int[numUrls];
            
            for (int i=0; i < numUrls; i++) {
                LDAPURL url = urls.get(i);
                hosts[i] = url.getHost();
                ports[i] = url.getPort();
            }
            
            if (socketFactory == null) {
                return new FailoverServerSet(hosts, ports, connOpts);
            } else {
                return new FailoverServerSet(hosts, ports, socketFactory, connOpts);
            }
        }
    }
}
