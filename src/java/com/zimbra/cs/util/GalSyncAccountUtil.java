/*
 * ***** BEGIN LICENSE BLOCK *****
 * 
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2009 Zimbra, Inc.
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
package com.zimbra.cs.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.zimbra.common.auth.ZAuthToken;
import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.AccountConstants;
import com.zimbra.common.soap.AdminConstants;
import com.zimbra.common.soap.Element;
import com.zimbra.common.soap.SoapHttpTransport;
import com.zimbra.common.soap.Element.XMLElement;
import com.zimbra.common.util.CliUtil;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Provisioning.AccountBy;
import com.zimbra.cs.httpclient.URLUtil;

public class GalSyncAccountUtil {
	private static final int CREATE_ACCOUNT = 10;
	private static final int TRICKLE_SYNC = 11;
	private static final int FULL_SYNC = 12;

	private static final String CREATE_ACCOUNT_COMMAND = "createaccount";
	private static final String TRICKLE_SYNC_COMMAND = "tricklesync";
	private static final String FULL_SYNC_COMMAND = "fullsync";
	
	private static Map<String,Integer> mCommands;
	
	private static void usage() {
		System.out.println("zmgsautil: {command}");
		System.out.println("\tcreateAccount -a {account-name} -n {datasource-name} [-f {folder-id}] [-p {polling-interval}] [-domain]");
		System.out.println("\ttrickleSync -i {account-id} [-d {datasource-id}] [-n {datasource-name}]");
		System.out.println("\tfullSync -i {account-id} [-d {datasource-id}] [-n {datasource-name}]");
		System.exit(1);
	}
	
	private static void addCommand(String cmd, int cmdId) {
		mCommands.put(cmd, new Integer(cmdId));
	}
	
	private static int lookupCmd(String cmd) {
		Integer i = (Integer) mCommands.get(cmd.toLowerCase());
		if (i == null) {
			usage();
		}
		return i.intValue();
	}
	
	private static void setup() {
		mCommands = new HashMap<String,Integer>();
		addCommand(CREATE_ACCOUNT_COMMAND, CREATE_ACCOUNT);
		addCommand(TRICKLE_SYNC_COMMAND, TRICKLE_SYNC);
		addCommand(FULL_SYNC_COMMAND, FULL_SYNC);
	}
	
	private String mUsername;
	private String mPassword;
	private String mAdminURL;
	private ZAuthToken mAuth;
	private SoapHttpTransport mTransport;

	private GalSyncAccountUtil() {
        String server = LC.zimbra_zmprov_default_soap_server.value();
        mAdminURL = URLUtil.getAdminURL(server);
        mUsername = LC.zimbra_ldap_user.value();
        mPassword = LC.zimbra_ldap_password.value();
	}

	private void checkArgs() {
		if (mAccountId == null || (mDataSourceId == null && mDataSourceName == null))
			usage();
	}

	private String mAccountId;
	private String mDataSourceId;
	private String mDataSourceName;
	private boolean mFullSync;
	
	private void syncGalAccount() throws ServiceException, IOException {
		checkArgs();
        mTransport = null;
        try {
            mTransport = new SoapHttpTransport(mAdminURL);
            auth();
            mTransport.setAuthToken(mAuth);
    		XMLElement req = new XMLElement(AdminConstants.SYNC_GAL_ACCOUNT_REQUEST);
    		Element acct = req.addElement(AdminConstants.E_ACCOUNT);
    		acct.addAttribute(AdminConstants.A_ID, mAccountId);
    		Element ds = acct.addElement(AdminConstants.E_DATASOURCE);
    		if (mDataSourceId != null)
    			ds.addAttribute(AdminConstants.A_BY, "id").setText(mDataSourceId);
    		else
    			ds.addAttribute(AdminConstants.A_BY, "name").setText(mDataSourceName);
    		if (mFullSync)
    			ds.addAttribute(AdminConstants.A_FULLSYNC, "TRUE");
    			
    		mTransport.invoke(req);
        } finally {
            if (mTransport != null)
                mTransport.shutdown();
        }                        
	}
	private void createGalSyncAccount(String accountName, String dsName, String domain, String folder, String pollingInterval) throws ServiceException, IOException {
        mTransport = null;
        try {
            mTransport = new SoapHttpTransport(mAdminURL);
            auth();
            mTransport.setAuthToken(mAuth);
    		XMLElement req = new XMLElement(AdminConstants.CREATE_GAL_SYNC_ACCOUNT_REQUEST);
    		req.addAttribute(AdminConstants.A_NAME, dsName);
    		if (domain != null)
        		req.addAttribute(AdminConstants.A_DOMAIN, domain);
    		if (folder != null)
        		req.addAttribute(AdminConstants.E_FOLDER, domain);
    		Element acct = req.addElement(AdminConstants.E_ACCOUNT);
    		acct.addAttribute(AdminConstants.A_BY, AccountBy.name.name());
    		acct.setText(accountName);
    		if (pollingInterval != null)
    			req.addElement(AdminConstants.E_A).addAttribute(AdminConstants.A_N, Provisioning.A_zimbraDataSourcePollingInterval).setText(pollingInterval);
    			
    		mTransport.invokeWithoutSession(req);
        } finally {
            if (mTransport != null)
                mTransport.shutdown();
        }                        
	}
	private void setAccountId(String aid) {
		mAccountId = aid;
	}
	private void setDataSourceId(String did) {
		mDataSourceId = did;
	}
	private void setDataSourceName(String name) {
		mDataSourceName = name;
	}
	private void setFullSync() {
		mFullSync = true;
	}
	private void auth() throws ServiceException, IOException {
		XMLElement req = new XMLElement(AdminConstants.AUTH_REQUEST);
		req.addElement(AdminConstants.E_NAME).setText(mUsername);
		req.addElement(AdminConstants.E_PASSWORD).setText(mPassword);
		Element resp = mTransport.invoke(req);
		mAuth = new ZAuthToken(resp.getElement(AccountConstants.E_AUTH_TOKEN), true);
	}
	
	public static void main(String[] args) throws Exception {
		if (args.length < 1)
			usage();
		CliUtil.toolSetup();
        CommandLineParser parser = new GnuParser();
        Options options = new Options();
        options.addOption("a", "account", true, "gal sync account name");
        options.addOption("i", "id", true, "gal sync account id");
        options.addOption("n", "name", true, "datasource name");
        options.addOption("d", "did", true, "datasource id");
        options.addOption("x", "domain", false, "for domain gal sync account");
        options.addOption("f", "folder", true, "folder id");
        options.addOption("p", "polling", true, "polling interval");
        options.addOption("h", "help", true, "help");
        CommandLine cl = null;
        boolean err = false;
        try {
            cl = parser.parse(options, args, false);
        } catch (ParseException pe) {
            System.out.println("error: " + pe.getMessage());
            err = true;
        }

        GalSyncAccountUtil cli = new GalSyncAccountUtil();
        if (err || cl.hasOption('h')) {
        	usage();
        }
        if (cl.hasOption('i'))
            cli.setAccountId(cl.getOptionValue('i'));
        if (cl.hasOption('n'))
            cli.setDataSourceName(cl.getOptionValue('n'));
        if (cl.hasOption('d'))
            cli.setDataSourceId(cl.getOptionValue('d'));
		setup();
		int cmd = lookupCmd(args[0]);
		switch (cmd) {
		case TRICKLE_SYNC:
			cli.syncGalAccount();
			break;
		case FULL_SYNC:
			cli.setFullSync();
			cli.syncGalAccount();
			break;
		case CREATE_ACCOUNT:
			String acctName = cl.getOptionValue('a');
			String dsName = cl.getOptionValue('n');
			String domain = cl.getOptionValue("domain");
			String fid = cl.getOptionValue('f');
			String pollingInterval = cl.getOptionValue('p');
			if (acctName == null || dsName == null)
				usage();
			cli.createGalSyncAccount(acctName, dsName, domain, fid, pollingInterval);
			break;
		default:
			usage();
		}
	}
}
