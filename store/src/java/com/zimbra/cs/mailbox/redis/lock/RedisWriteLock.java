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

import java.util.Arrays;

import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;

import com.google.common.base.MoreObjects.ToStringHelper;
import com.zimbra.common.localconfig.LC;

public class RedisWriteLock extends RedisLock {

    public RedisWriteLock(String accountId, String lockBaseName, String lockId) {
        super(accountId, lockBaseName, lockId);
    }

    @Override
    protected long getLeaseTime() {
        return LC.zimbra_mailbox_lock_write_lease_seconds.intValue() *1000;
    }

    @Override
    protected LockResponse tryAcquire() {
        String script =
                "local mode = redis.call('hget', KEYS[1], 'mode'); " +
                "local last_write_access_key = KEYS[1] .. ':last_writer'; " +
                "local reads_since_last_write_key = KEYS[1] .. ':reads_since_last_write'; " +
                 // store id of last mailbox worker to acquire a write lock
                "local last_writer = redis.call('get', last_write_access_key); " +
                "if (mode == false) then " +
                      //no one is holding the lock, so set the mode to "write"
                      "redis.call('hset', KEYS[1], 'mode', 'write'); " +
                      //set this thread's write hold count to 1 in the lock hash
                      "redis.call('hset', KEYS[1], ARGV[2], 1); " +
                      //set the lock hash expiry to the lease time
                      "redis.call('pexpire', KEYS[1], ARGV[1]); " +
                      //track the uuid of the holder instance in a separate key, with the same expiry
                      "redis.call('set', KEYS[2] .. ':1', ARGV[4]); " +
                      "redis.call('pexpire', KEYS[2] .. ':1', ARGV[1]); " +
                      //update id of last worker to acquire a write lock
                      "redis.call('set', last_write_access_key, ARGV[3]); " +
                      //delete the reads_since_last_write set
                      "redis.call('del', reads_since_last_write_key); " +
                      "return {1, last_writer}; " +
                  "end; " +
                  "if (mode == 'write') then " +
                      "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +
                          //if this thread is already holding a write lock, increment the hold count
                          "local new_hold_count = redis.call('hincrby', KEYS[1], ARGV[2], 1); " +
                          "local currentExpire = redis.call('pttl', KEYS[1]); " +
                          "redis.call('pexpire', KEYS[1], currentExpire + ARGV[1]); " +
                          "redis.call('set', last_write_access_key, ARGV[3]); " +
                          "redis.call('del', reads_since_last_write_key); " +
                          //track the uuid of the holder instance in a separate key, with the same expiry
                          "local uuid_key = KEYS[2] .. ':' .. new_hold_count; " +
                          "redis.call('set', uuid_key, ARGV[4]); " +
                          "redis.call('pexpire', uuid_key, ARGV[1]); " +
                          "return {1, last_writer}; " +
                      "end; " +
                    "end;" +
                     //can't obtain a write lock, return the TTL of the lock hash and the uuids of client lock instances
                     "local retvals = {0, redis.call('pttl', KEYS[1])}; " +
                     "for n, key in ipairs(redis.call('hkeys', KEYS[1])) do " +
                         "local counter = tonumber(redis.call('hget', KEYS[1], key)); " +
                         "if type(counter) == 'number' then " +
                             "for i=counter, 1, -1 do " +
                                 "local uuid_key; " +
                                 "if (mode == 'write') then " +
                                     //client lock uuids are stored in dedicated "{lock name}:{thread}:uuid:{#}" key
                                     "uuid_key = KEYS[1] .. ':' .. key .. ':uuid:' .. i; " +
                                 "else " +
                                     //client lock uuids are stored in "{lock name}:{thread}:rwlock_timeout:{#}" key
                                     //that is also used to track reader expiration
                                     "uuid_key = KEYS[1] .. ':' .. key .. ':rwlock_timeout:' .. i; " +
                                 "end; "+
                                 "local uuid = redis.call('get', uuid_key); " +
                                 "if uuid == nil then " +
                                 //if uuid is unknown, return which thread it is
                                     "table.insert(retvals, uuid_key .. ':nil'); " +
                                 "else " +
                                     "table.insert(retvals, uuid); " +
                                 "end; " +
                             "end; " +
                         "end; " +
                     "end; " +
                    "return retvals;";

        return execute(script, StringCodec.INSTANCE, RedisLock.LOCK_RESPONSE_CMD,
            Arrays.<Object>asList(lockName, getWriterUuidPrefix()),
            getLeaseTime(), getThreadLockName(), lockId, uuid);
    }

    @Override
    protected String getThreadLockName() {
        return super.getThreadLockName() + ":write";
    }

    @Override
    protected Boolean unlockInner() {
        String script =
                "local mode = redis.call('hget', KEYS[1], 'mode'); " +
                "if (mode == false) then " +
                    //no one is holding the lock, so publish an unlock message
                    "redis.call('publish', KEYS[2], ARGV[1]); " +
                    "return 1; " +
                "end;" +
                "if (mode == 'write') then " +
                    "local lockExists = redis.call('hexists', KEYS[1], ARGV[3]); " +
                    "if (lockExists == 0) then " +
                        //someone else is holding the lock (returning nil results in a warning)
                        "return nil;" +
                    "else " +
                        //decrement the write hold count for this thread
                        "local counter = redis.call('hincrby', KEYS[1], ARGV[3], -1); " +
                        //delete the client lock uuid
                        "redis.call('del', KEYS[3] .. ':' .. (counter+1)); " +
                        "if (counter > 0) then " +
                            //if we are still holding write locks, update the TTL on the hash
                            "redis.call('pexpire', KEYS[1], ARGV[2]); " +
                            "return 0; " +
                        "else " +
                            //delete the lock hash entry for this thread
                            "redis.call('hdel', KEYS[1], ARGV[3]); " +
                            "if (redis.call('hlen', KEYS[1]) == 1) then " +
                                //no more holds on this lock (only the "mode" key is left),
                                //so delete the lock hash and publish an unlock message
                                "redis.call('del', KEYS[1]); " +
                                "redis.call('publish', KEYS[2], ARGV[1]); " +
                            "else " +
                                //this thread still has a read lock, so toggle the mode to "read"
                                "redis.call('hset', KEYS[1], 'mode', 'read'); " +
                            "end; " +
                            "return 1; "+
                        "end; " +
                    "end; " +
                "end; "
                + "return nil;";
        return execute(script, LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
        Arrays.<Object>asList(lockName, lockChannelName, getWriterUuidPrefix()),
        getUnlockMsg(), getLeaseTime(), getThreadLockName());
    }

    private String getWriterUuidPrefix() {
        return lockName + ":" + getThreadLockName() + ":uuid";
    }

    @Override
    protected ToStringHelper toStringHelper() {
        return super.toStringHelper().add("type", "write");
    }
}