/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2008, 2009, 2010 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.datasource;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.security.auth.login.LoginException;

import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.RemoteServiceException;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.Log;
import com.zimbra.common.util.SSLSocketFactoryManager;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.DataSource;
import com.zimbra.cs.filter.RuleManager;
import com.zimbra.cs.mailbox.DeliveryContext;
import com.zimbra.cs.mailbox.Flag;
import com.zimbra.cs.mailbox.MailServiceException;
import com.zimbra.cs.mailbox.Message;
import com.zimbra.cs.mailclient.CommandFailedException;
import com.zimbra.cs.mailclient.pop3.ContentInputStream;
import com.zimbra.cs.mailclient.pop3.Pop3Capabilities;
import com.zimbra.cs.mailclient.pop3.Pop3Config;
import com.zimbra.cs.mailclient.pop3.Pop3Connection;
import com.zimbra.cs.mime.ParsedMessage;

public class Pop3Sync extends MailItemImport {
    private final Pop3Connection connection;
    private final boolean indexAttachments;

    private static final Log LOG = ZimbraLog.datasource;
    
    // Zimbra UID format is: item_id "." blob_digest
    private static final Pattern PATTERN_ZIMBRA_UID =
        Pattern.compile("(\\d+)\\.([^\\.]+)");

    public Pop3Sync(DataSource ds) throws ServiceException {
        super(ds);
        connection = new Pop3Connection(getPop3Config(ds));
        indexAttachments = mbox.attachmentsIndexingEnabled();
    }

    private Pop3Config getPop3Config(DataSource ds) {
        Pop3Config config = new Pop3Config();

        config.setHost(ds.getHost());
        config.setPort(ds.getPort());
        config.setAuthenticationId(ds.getUsername());
        config.setSecurity(getSecurity(ds.getConnectionType()));
        if (ds.isDebugTraceEnabled()) {
            config.setDebug(true);
            enableTrace(config);
        }
        config.setSSLSocketFactory(SSLSocketFactoryManager.getDefaultSSLSocketFactory());
        return config;
    }
    
    public synchronized void test() throws ServiceException {
        validateDataSource();
        setTimeout(LC.javamail_pop3_test_timeout.intValue());
        enableTrace(connection.getPop3Config());
        try {
            connect();
        } finally {
            connection.close();
        }
    }

    private void setTimeout(int timeout) {
        Pop3Config config = connection.getPop3Config();
        config.setReadTimeout(timeout);
        config.setConnectTimeout(timeout);
    }
    
    private void enableTrace(Pop3Config config) {
        config.setTrace(true);
        if (dataSource.isOffline()) {
            config.setTraceStream(
                new PrintStream(new LogOutputStream(ZimbraLog.pop), true));
        } else {
            config.setTraceStream(System.out);
        }
    }

    public synchronized void importData(List<Integer> folderIds, boolean fullSync)
        throws ServiceException {
        validateDataSource();
        setTimeout(LC.javamail_pop3_timeout.intValue());
        connect();
        try {
            if (dataSource.leaveOnServer()) {
                fetchAndRetainMessages();
            } else {
                fetchAndDeleteMessages();
            }
            connection.quit();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw ServiceException.FAILURE(
                "Synchronization of POP3 folder failed", e);
        } finally {
            connection.close();
        }
    }

    private void connect() throws ServiceException {
        if (!connection.isClosed()) return;
        boolean hasUIDL;
        try {
            connection.connect();
            // Bug 41213: Make sure to check if UIDL is supported both before
            // and after authentication.
            hasUIDL = hasUIDL();
            try {
                connection.login(dataSource.getDecryptedPassword());
            } catch (CommandFailedException e) {
                throw new LoginException(e.getError());
            }
            hasUIDL |= hasUIDL();
        } catch (Exception e) {
            connection.close();
            throw ServiceException.FAILURE(
                "Unable to connect to POP3 server: " + dataSource, e);
        }
        if (dataSource.leaveOnServer() && !hasUIDL) {
            throw RemoteServiceException.POP3_UIDL_REQUIRED();
        }
    }

    private boolean hasUIDL() {
        return connection.hasCapability(Pop3Capabilities.UIDL);
    }
    
    private void fetchAndDeleteMessages()
        throws ServiceException, IOException {
        Integer sizes[] = connection.getMessageSizes();
        
        LOG.info("Found %d new message(s) on remote server", sizes.length);
        for (int msgno = sizes.length; msgno > 0; --msgno) {
            LOG.debug("Fetching message number %d", msgno);
            fetchAndAddMessage(msgno, sizes[msgno - 1], null, true);
            connection.deleteMessage(msgno);
        }
    }

    private void fetchAndRetainMessages()
        throws ServiceException, IOException {
        String[] uids = connection.getMessageUids();
        Set<String> existingUids = PopMessage.getMatchingUids(dataSource, uids);
        int count = uids.length - existingUids.size();
        
        LOG.info("Found %d new message(s) on remote server", count);
        if (count == 0) {
            return; // No new messages
        }
        if (poppingSelf(uids[0])) {
            throw ServiceException.INVALID_REQUEST(
                "User attempted to import messages from his own mailbox", null);
        }
        for (int msgno = uids.length; msgno > 0; --msgno) {
            String uid = uids[msgno - 1];
            
            if (!existingUids.contains(uid)) {
                LOG.debug("Fetching message with uid %s", uid);
                // Don't allow filtering to a mountpoint when retaining the
                // message.  We don't have a local id, so we can't keep track
                // of it in the data_source_item table.
                fetchAndAddMessage(msgno, connection.getMessageSize(msgno), uid, false);
            }
        }
    }

    private void fetchAndAddMessage(int msgno, int size, String uid, boolean allowFilterToMountpoint)
        throws ServiceException, IOException {
        ContentInputStream cis = null;
        MessageContent mc = null;
        try {
            cis = connection.getMessage(msgno);
            mc = MessageContent.read(cis, size);
            ParsedMessage pm = mc.getParsedMessage(null, indexAttachments);
            Message msg = null;

            DeliveryContext dc = mc.getDeliveryContext();
            if (isOffline()) {
                msg = addMessage(null, pm, dataSource.getFolderId(), Flag.BITMASK_UNREAD, dc);
            } else {
                Integer localId = getFirstLocalId(
                    RuleManager.applyRulesToIncomingMessage(
                        mbox, pm, dataSource.getEmailAddress(), dc, dataSource.getFolderId(), allowFilterToMountpoint));
                if (localId != null) {
                    msg = mbox.getMessageById(null, localId);
                }
            }
            if (msg != null && uid != null) {
                PopMessage msgTracker = new PopMessage(dataSource, msg.getId(), uid);
                msgTracker.add();
            }
        } catch (CommandFailedException e) {
            LOG.warn("Error fetching message number %d: %s", msgno, e.getMessage());
        } finally {
            if (cis != null) {
                cis.close();
            }
            if (mc != null) {
                mc.cleanup();
            }
        }
    }

    private boolean poppingSelf(String uid)
        throws ServiceException {
        Matcher matcher = PATTERN_ZIMBRA_UID.matcher(uid);
        if (!matcher.matches()) {
            return false; // Not a Zimbra UID
        }
        // See if this UID comes from the specified mailbox. Popping from
        // another Zimbra mailbox is ok.
        int itemId;
        try {
            itemId = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return false;
        }
        String digest = matcher.group(2);
        Message msg;
        try {
            msg = mbox.getMessageById(null, itemId);
        } catch (MailServiceException.NoSuchItemException e) {
            return false;
        }
        return digest.equals(msg.getDigest());
    }
}
