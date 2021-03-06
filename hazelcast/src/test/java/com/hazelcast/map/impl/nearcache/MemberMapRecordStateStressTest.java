/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.impl.nearcache;

import com.hazelcast.config.Config;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.internal.nearcache.NearCache;
import com.hazelcast.internal.nearcache.NearCacheRecord;
import com.hazelcast.internal.nearcache.impl.DefaultNearCache;
import com.hazelcast.internal.nearcache.impl.store.AbstractNearCacheRecordStore;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.map.impl.proxy.NearCachedMapProxyImpl;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.NightlyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hazelcast.internal.nearcache.NearCacheRecord.READ_PERMITTED;
import static com.hazelcast.util.RandomPicker.getInt;
import static org.junit.Assert.assertEquals;

@RunWith(HazelcastSerialClassRunner.class)
@Category(NightlyTest.class)
public class MemberMapRecordStateStressTest extends HazelcastTestSupport {

    private final int KEY_SPACE = 100;
    private final int TEST_RUN_SECONDS = 60;
    private final int GET_ALL_THREAD_COUNT = 3;
    private final int GET_THREAD_COUNT = 7;
    private final int PUT_LOCAL_THREAD_COUNT = 2;
    private final int PUT_THREAD_COUNT = 2;
    private final int CLEAR_THREAD_COUNT = 2;
    private final int REMOVE_THREAD_COUNT = 2;
    private final String mapName = "test";
    private final AtomicBoolean stop = new AtomicBoolean();
    private TestHazelcastInstanceFactory factory;

    @Before
    public void setUp() throws Exception {
        factory = new TestHazelcastInstanceFactory();
        stop.set(false);
    }

    @After
    public void tearDown() throws Exception {
        factory.shutdownAll();
    }

    @Test
    public void all_records_are_readable_state_in_the_end() throws Exception {

        HazelcastInstance member1 = factory.newHazelcastInstance();
        HazelcastInstance member2 = factory.newHazelcastInstance();

        Config config = new Config();
        config.getMapConfig(mapName).setNearCacheConfig(newNearCacheConfig());
        HazelcastInstance nearCachedMember = factory.newHazelcastInstance(config);

        IMap memberMap = member1.getMap(mapName);
        // initial population of imap from member
        for (int i = 0; i < KEY_SPACE; i++) {
            memberMap.put(i, i);
        }

        List<Thread> threads = new ArrayList<Thread>();

        // member
        for (int i = 0; i < PUT_THREAD_COUNT; i++) {
            Put put = new Put(memberMap);
            threads.add(put);
        }

        // nearCachedMap
        IMap nearCachedMap = nearCachedMember.getMap(mapName);

        // member
        for (int i = 0; i < PUT_LOCAL_THREAD_COUNT; i++) {
            Put put = new Put(nearCachedMap);
            threads.add(put);
        }

        for (int i = 0; i < GET_ALL_THREAD_COUNT; i++) {
            GetAll getAll = new GetAll(nearCachedMap);
            threads.add(getAll);
        }

        for (int i = 0; i < GET_THREAD_COUNT; i++) {
            Get get = new Get(nearCachedMap);
            threads.add(get);
        }

        for (int i = 0; i < REMOVE_THREAD_COUNT; i++) {
            Remove remove = new Remove(nearCachedMap);
            threads.add(remove);
        }

        for (int i = 0; i < CLEAR_THREAD_COUNT; i++) {
            Clear clear = new Clear(nearCachedMap);
            threads.add(clear);
        }

        // start threads
        for (Thread thread : threads) {
            thread.start();
        }

        // stress for a while
        sleepSeconds(TEST_RUN_SECONDS);

        // stop threads
        stop.set(true);
        for (Thread thread : threads) {
            thread.join();
        }

        assertFinalRecordStateIsReadPermitted(member1, ((NearCachedMapProxyImpl) nearCachedMap).getNearCache());
    }

    private void assertFinalRecordStateIsReadPermitted(HazelcastInstance member, NearCache nearCache) {
        InternalSerializationService ss = getSerializationService(member);

        DefaultNearCache unwrap = (DefaultNearCache) nearCache.unwrap(DefaultNearCache.class);
        for (int i = 0; i < KEY_SPACE; i++) {
            Data key = ss.toData(i);
            AbstractNearCacheRecordStore nearCacheRecordStore = (AbstractNearCacheRecordStore) unwrap.getNearCacheRecordStore();
            NearCacheRecord record = nearCacheRecordStore.getRecord(key);

            if (record != null) {
                assertEquals(record.toString(), READ_PERMITTED, record.getRecordState());
            }
        }
    }

    private class Put extends Thread {
        private final IMap map;

        private Put(IMap map) {
            this.map = map;
        }

        @Override
        public void run() {
            do {
                for (int i = 0; i < KEY_SPACE; i++) {
                    map.put(i, getInt(KEY_SPACE));
                }
                sleepAtLeastMillis(100);
            } while (!stop.get());
        }
    }

    private class Remove extends Thread {
        private final IMap map;

        private Remove(IMap map) {
            this.map = map;
        }

        @Override
        public void run() {
            do {
                for (int i = 0; i < KEY_SPACE; i++) {
                    map.remove(i);
                }
                sleepAtLeastMillis(100);
            } while (!stop.get());
        }
    }

    private class Clear extends Thread {
        private final IMap map;

        private Clear(IMap map) {
            this.map = map;
        }

        @Override
        public void run() {
            do {
                map.clear();
                sleepAtLeastMillis(5000);
            } while (!stop.get());
        }
    }

    private class GetAll extends Thread {
        private final IMap map;

        private GetAll(IMap map) {
            this.map = map;
        }

        @Override
        public void run() {
            HashSet keys = new HashSet();
            for (int i = 0; i < KEY_SPACE; i++) {
                keys.add(i);
            }

            do {
                map.getAll(keys);
                sleepAtLeastMillis(2);
            } while (!stop.get());
        }
    }

    private class Get extends Thread {
        private final IMap map;

        private Get(IMap map) {
            this.map = map;
        }

        @Override
        public void run() {
            do {
                for (int i = 0; i < KEY_SPACE; i++) {
                    map.get(i);
                }
            } while (!stop.get());
        }
    }


    protected NearCacheConfig newNearCacheConfig() {
        NearCacheConfig nearCacheConfig = new NearCacheConfig();
        nearCacheConfig.setName(mapName);
        nearCacheConfig.setInvalidateOnChange(true);
        return nearCacheConfig;
    }
}
