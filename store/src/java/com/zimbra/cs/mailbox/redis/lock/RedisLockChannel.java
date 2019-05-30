/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2018 Synacor, Inc.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.cs.mailbox.redis.lock;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.redisson.api.RTopic;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.StringCodec;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.mailbox.RedissonClientHolder;
import com.zimbra.cs.mailbox.redis.RedisUtils.RedisKey;
import com.zimbra.cs.mailbox.redis.lock.RedisLock.LockResponse;
import com.zimbra.cs.mailbox.util.MailboxClusterUtil;


public class RedisLockChannel implements MessageListener<String> {

    private Map<String, LockQueue> waitingLocksQueues = new ConcurrentHashMap<>();
    private boolean isActive = false;
    private RedisKey channelName;
    private RTopic topic;

    public RedisLockChannel(RedisKey channelName) {
        this.channelName = channelName;
        this.topic = RedissonClientHolder.getInstance().getRedissonClient().getTopic(channelName.getKey(), StringCodec.INSTANCE);
    }

    public RedisKey getChannelName() {
        return channelName;
    }

    private void subscribe() {
        ZimbraLog.mailboxlock.info("beginning listening on channel %s", channelName);
        topic.addListener(String.class, this);
    }

    private LockQueue getQueue(String accountId) {
        return waitingLocksQueues.computeIfAbsent(accountId, k -> new LockQueue(accountId));
    }

    public synchronized QueuedLockRequest add(RedisLock lock, QueuedLockRequest.LockCallback callback) throws ServiceException {
        return add(lock, callback, false);
    }

    public synchronized QueuedLockRequest add(RedisLock lock, QueuedLockRequest.LockCallback callback, boolean skipQueue) throws ServiceException {
        if (!isActive && waitingLocksQueues.isEmpty()) {
            isActive = true; //lazily activate the channel
            subscribe();
        }
        QueuedLockRequest waitingLock = new QueuedLockRequest(lock, callback);
        boolean tryAcquireNow;
        LockQueue lockQueue = getQueue(lock.getAccountId());
        if (skipQueue) {
            lockQueue.addToFront(waitingLock);
            tryAcquireNow = true;
        } else if (lockQueue.queue.size() >= LC.zimbra_mailbox_lock_max_waiting_threads.intValue()) {
            throw ServiceException.LOCK_FAILED("too many waiting locks");
        } else {
            tryAcquireNow = lockQueue.add(waitingLock);
        }
        waitingLock.setTryAcquireNow(tryAcquireNow);
        return waitingLock;
    }

    public void remove(QueuedLockRequest waitingLock) {
        if (getQueue(waitingLock.getAccountId()).remove(waitingLock) && ZimbraLog.mailboxlock.isTraceEnabled()) {
            ZimbraLog.mailboxlock.trace("removed %s from lock queue", waitingLock);
        }
    }

    public LockResponse waitForUnlock(QueuedLockRequest waitingLock, long timeoutMillis) throws ServiceException {
        try {
            return waitingLock.waitForUnlock(timeoutMillis);
        } finally {
            remove(waitingLock);
        }
    }

    @Override
    public void onMessage(CharSequence channel, String unlockMsg) {
        String[] parts = unlockMsg.split("\\|");
        String accountId;
        if (parts.length == 2) {
            //normal case when the unlock message is triggered by a mailbox releasing a lock
            accountId = parts[0];
            String lockUuid = parts[1];
            if (ZimbraLog.mailboxlock.isTraceEnabled()) {
                ZimbraLog.mailboxlock.trace("received unlock message for acct=%s, uuid=%s", accountId, lockUuid);
            }
        } else if (parts.length == 3 && parts[0].equals("SHUTDOWN")) {
            /*
             * Secondary case when the unlock message is triggered by the script that releases all
             * locks during mailbox pod shutdown. In this case, there are three considerations:
             * 1. We don't know what the releasing lock UUID was.
             * 2. To avoid re-acquiring locks prior to shutdown, this unlock message contains the name of the pod.
             *    If this is that pod, then ignore the message.
             * 3. Since the shutdown script isn't targeting a specific account, it can't publish the actual account ID,
             *    only the name of the lock it is releasing. Therefore we have to parse the ID from the lock name.
             *    It is easier to do this here rather than in lua, which has limited string manipulation.
             */
            String lockName = parts[1];
            String shutdownPodName = parts[2];
            if (shutdownPodName.equals(MailboxClusterUtil.getMailboxWorkerName())) {
                ZimbraLog.mailboxlock.info("ignoring unlock message, since this pod (%s) is shutting down!", shutdownPodName);
                return;
            }
            //lock name is of the form {LOCK-#}-accountId-LOCK
            lockName = lockName.substring(0, lockName.length() - 5);
            accountId = lockName.split("}-")[1];
            ZimbraLog.mailboxlock.info("received shutdown unlock message from pod %s for account %s", shutdownPodName, accountId);
        } else {
            ZimbraLog.mailboxlock.warn("unrecognized unlock message: %s", unlockMsg);
            return;
        }
        List<String> notifiedLockUuids = getQueue(accountId).notifyWaitingLocks();
        if (notifiedLockUuids.size() > 0) {
            if (ZimbraLog.mailboxlock.isTraceEnabled()) {
                ZimbraLog.mailboxlock.debug("notified %s waiting locks for account %s: %s", notifiedLockUuids.size(), accountId, Joiner.on(", ").join(notifiedLockUuids));
            } else {
                ZimbraLog.mailboxlock.debug("notified %s waiting locks for account %s", notifiedLockUuids.size(), accountId);
            }
        }
    }

    private class LockQueue {

        private String accountId;
        private LinkedList<QueuedLockRequest> queue;
        private int numWriters = 0;

        public LockQueue(String accountId) {
            this.accountId = accountId;
            this.queue = new LinkedList<>();
            ZimbraLog.mailboxlock.info("initializing lock queue for account %s on channel %s", accountId, RedisLockChannel.this.channelName);
        }

        /**
         * returns TRUE if the thread should try to acquire the lock in redis; FALSE if it needs to wait.
         * Specifically:
         *  - if acquiring a read lock, a thread should only wait for unlock if there is a writer ahead of it in the queue.
         *  - if acquiring a write lock, a thread should wait for unlock if there is anything ahead of it in the queue
         */
        public boolean add(QueuedLockRequest lock) {
            synchronized(this) {
                if (lock.isWriteLock()) {
                    numWriters++;
                }
                int curSize = queue.size();
                queue.add(lock);
                if (curSize == 0) {
                    trace("adding %s to queue (no locks queued)", lock);
                    return true;
                } else if (lock.isWriteLock()) {
                    trace("adding %s to queue: %s", lock, this);
                    return false;
                } else {
                    trace("adding %s to queue (%d writers): %s", lock, numWriters, this);
                    return numWriters == 0;
                }
            }
        }

        /**
         * Add a QueuedLockRequest to the front of the queue, bypassing all other waiting locks.
         * This is used to reinstate read locks when releasing a mailbox write lock that was upgraded from a read lock.
         * @param lock
         */
        public void addToFront(QueuedLockRequest lock) {
            int curSize = queue.size();
            if (curSize == 0) {
                trace("adding %s to the front of the queue (no locks queued)", lock);
            } else {
                trace("adding %s to the front of the queue: %s", lock, this);
            }
            queue.addFirst(lock);
        }

        public boolean remove(QueuedLockRequest lock) {
            synchronized(this) {
                boolean removed = queue.remove(lock);
                if (removed && lock.isWriteLock()) {
                    numWriters--;
                }
                return removed;
            }
        }

        public List<String> notifyWaitingLocks() {
            synchronized(this) {
                List<String> notifiedLockUuids = new ArrayList<>();
                QueuedLockRequest firstLock = queue.peek();
                if (firstLock != null && firstLock.isWriteLock()) {
                    trace("notifying write lock at head of %s", this);
                    firstLock.notifyUnlock();
                    notifiedLockUuids.add(firstLock.getUuid());
                } else if (firstLock != null) {
                    trace("notifying read locks in %s", this);
                    Iterator<QueuedLockRequest> iter = queue.iterator();
                    while (iter.hasNext()) {
                        QueuedLockRequest waitingLock = iter.next();
                        if (waitingLock.isWriteLock()) {
                            break;
                        } else {
                            waitingLock.notifyUnlock();
                            notifiedLockUuids.add(waitingLock.getUuid());
                        }
                    }
                }
                return notifiedLockUuids;
            }
        }

        private void trace(String msg, Object... objects) {
            if (ZimbraLog.mailboxlock.isTraceEnabled()) {
                ZimbraLog.mailboxlock.trace(msg, objects);
            }
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("accountId", accountId)
                    .add("size", queue.size())
                    .add("writers", numWriters)
                    .add("queue", queue.stream().map(l -> l.getUuid()).collect(Collectors.toList())).toString();
        }
    }

    public static class LockTimingContext {
        public int attempts;
        public long startTime;
        public long timeoutTime;

        public LockTimingContext() {
            attempts = 0;
            startTime = System.currentTimeMillis();
        }

        public void setTimeout(long timeoutMillis) {
            timeoutTime = startTime + timeoutMillis;
        }

        public long getRemainingTime() {
            return timeoutTime - System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("attempts", attempts)
                    .add("elapsed", System.currentTimeMillis() - startTime).toString();
        }
    }
}