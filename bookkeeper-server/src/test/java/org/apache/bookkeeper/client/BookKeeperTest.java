/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.client;

import static org.apache.bookkeeper.client.BookKeeperClientStats.WRITE_DELAYED_DUE_TO_NOT_ENOUGH_FAULT_DOMAINS;
import static org.apache.bookkeeper.client.BookKeeperClientStats.WRITE_TIMED_OUT_DUE_TO_NOT_ENOUGH_FAULT_DOMAINS;
import static org.apache.bookkeeper.common.concurrent.FutureUtils.result;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.netty.util.IllegalReferenceCountException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.AsyncCallback.ReadCallback;
import org.apache.bookkeeper.client.BKException.BKBookieHandleNotAvailableException;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.bookkeeper.client.api.WriteFlag;
import org.apache.bookkeeper.client.api.WriteHandle;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.discover.BookieServiceInfo;
import org.apache.bookkeeper.net.BookieId;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.test.BookKeeperClusterTestCase;
import org.apache.bookkeeper.test.TestStatsProvider;
import org.apache.bookkeeper.util.StaticDNSResolver;
import org.apache.bookkeeper.versioning.Versioned;
import org.apache.bookkeeper.zookeeper.BoundExponentialBackoffRetryPolicy;
import org.apache.bookkeeper.zookeeper.ZooKeeperClient;
import org.apache.bookkeeper.zookeeper.ZooKeeperWatcherBase;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.data.ACL;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests of the main BookKeeper client.
 */
public class BookKeeperTest extends BookKeeperClusterTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(BookKeeperTest.class);
    private static final long INVALID_LEDGERID = -1L;
    private final DigestType digestType;

    public BookKeeperTest() {
        super(3);
        this.digestType = DigestType.CRC32;
    }

    @Test
    @EnabledForJreRange(max = JRE.JAVA_17)
    public void testConstructionZkDelay() throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri())
            .setZkTimeout(20000);

        CountDownLatch l = new CountDownLatch(1);
        zkUtil.sleepCluster(200, TimeUnit.MILLISECONDS, l);
        l.await();

        BookKeeper bkc = new BookKeeper(conf);
        bkc.createLedger(digestType, "testPasswd".getBytes()).close();
        bkc.close();
    }

    @Test
    @EnabledForJreRange(max = JRE.JAVA_17)
    public void testConstructionNotConnectedExplicitZk() throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri())
            .setZkTimeout(20000);

        CountDownLatch l = new CountDownLatch(1);
        zkUtil.sleepCluster(200, TimeUnit.MILLISECONDS, l);
        l.await();

        ZooKeeper zk = new ZooKeeper(
            zkUtil.getZooKeeperConnectString(),
            50,
            event -> {});
        assertFalse(zk.getState().isConnected(), "ZK shouldn't have connected yet");
        try {
            BookKeeper bkc = new BookKeeper(conf, zk);
            fail("Shouldn't be able to construct with unconnected zk");
        } catch (IOException cle) {
            // correct behaviour
            assertTrue(cle.getCause() instanceof ConnectionLossException);
        }
    }

    /**
     * Test that bookkeeper is not able to open ledgers if
     * it provides the wrong password or wrong digest.
     */
    @Test
    public void testBookkeeperDigestPasswordWithAutoDetection() throws Exception {
        testBookkeeperDigestPassword(true);
    }

    @Test
    public void testBookkeeperDigestPasswordWithoutAutoDetection() throws Exception {
        testBookkeeperDigestPassword(false);
    }

    void testBookkeeperDigestPassword(boolean autodetection) throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        conf.setEnableDigestTypeAutodetection(autodetection);
        BookKeeper bkc = new BookKeeper(conf);

        DigestType digestCorrect = digestType;
        byte[] passwdCorrect = "AAAAAAA".getBytes();
        DigestType digestBad = digestType == DigestType.MAC ? DigestType.CRC32 : DigestType.MAC;
        byte[] passwdBad = "BBBBBBB".getBytes();


        LedgerHandle lh = null;
        try {
            lh = bkc.createLedger(digestCorrect, passwdCorrect);
            long id = lh.getId();
            for (int i = 0; i < 100; i++) {
                lh.addEntry("foobar".getBytes());
            }
            lh.close();

            // try open with bad passwd
            try {
                bkc.openLedger(id, digestCorrect, passwdBad);
                fail("Shouldn't be able to open with bad passwd");
            } catch (BKException.BKUnauthorizedAccessException bke) {
                // correct behaviour
            }

            // try open with bad digest
            try {
                bkc.openLedger(id, digestBad, passwdCorrect);
                if (!autodetection) {
                    fail("Shouldn't be able to open with bad digest");
                }
            } catch (BKException.BKDigestMatchException bke) {
                // correct behaviour
                if (autodetection) {
                    fail("Should not throw digest match exception if `autodetection` is enabled");
                }
            }

            // try open with both bad
            try {
                bkc.openLedger(id, digestBad, passwdBad);
                fail("Shouldn't be able to open with bad passwd and digest");
            } catch (BKException.BKUnauthorizedAccessException bke) {
                // correct behaviour
            }

            // try open with both correct
            bkc.openLedger(id, digestCorrect, passwdCorrect).close();
        } finally {
            if (lh != null) {
                lh.close();
            }
            bkc.close();
        }
    }

    /**
     * Tests that when trying to use a closed BK client object we get
     * a callback error and not an InterruptedException.
     * @throws Exception
     */
    @Test
    public void testAsyncReadWithError() throws Exception {
        LedgerHandle lh = bkc.createLedger(3, 3, DigestType.CRC32, "testPasswd".getBytes());
        bkc.close();

        final AtomicInteger result = new AtomicInteger(0);
        final CountDownLatch counter = new CountDownLatch(1);

        // Try to write, we should get and error callback but not an exception
        lh.asyncAddEntry("test".getBytes(), new AddCallback() {
            public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
                result.set(rc);
                counter.countDown();
            }
        }, null);

        counter.await();

        assertTrue(result.get() != 0);
    }

    /**
     * Test that bookkeeper will close cleanly if close is issued
     * while another operation is in progress.
     */
    @Test
    public void testCloseDuringOp() throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        for (int i = 0; i < 10; i++) {
            final BookKeeper client = new BookKeeper(conf);
            final CountDownLatch l = new CountDownLatch(1);
            final AtomicBoolean success = new AtomicBoolean(false);
            Thread t = new Thread() {
                    public void run() {
                        try {
                            LedgerHandle lh = client.createLedger(3, 3, digestType, "testPasswd".getBytes());
                            startNewBookie();
                            killBookie(0);
                            lh.asyncAddEntry("test".getBytes(), new AddCallback() {
                                    @Override
                                    public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
                                        // noop, we don't care if this completes
                                    }
                                }, null);
                            client.close();
                            success.set(true);
                            l.countDown();
                        } catch (Exception e) {
                            LOG.error("Error running test", e);
                            success.set(false);
                            l.countDown();
                        }
                    }
                };
            t.start();
            assertTrue(l.await(10, TimeUnit.SECONDS), "Close never completed");
            assertTrue(success.get(), "Close was not successful");
        }
    }

    @Test
    public void testIsClosed() throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());

        BookKeeper bkc = new BookKeeper(conf);
        LedgerHandle lh = bkc.createLedger(digestType, "testPasswd".getBytes());
        long lId = lh.getId();

        lh.addEntry("000".getBytes());
        boolean result = bkc.isClosed(lId);
        assertFalse(result, "Ledger shouldn't be flagged as closed!");

        lh.close();
        result = bkc.isClosed(lId);
        assertTrue(result, "Ledger should be flagged as closed!");

        bkc.close();
    }

    @Test
    public void testReadFailureCallback() throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());

        BookKeeper bkc = new BookKeeper(conf);
        LedgerHandle lh = bkc.createLedger(digestType, "testPasswd".getBytes());

        final int numEntries = 10;
        for (int i = 0; i < numEntries; i++) {
            lh.addEntry(("entry-" + i).getBytes());
        }

        stopBKCluster();

        try {
            lh.readEntries(0, numEntries - 1);
            fail("Read operation should have failed");
        } catch (BKBookieHandleNotAvailableException e) {
            // expected
        }

        final CountDownLatch counter = new CountDownLatch(1);
        final AtomicInteger receivedResponses = new AtomicInteger(0);
        final AtomicInteger returnCode = new AtomicInteger();
        lh.asyncReadEntries(0, numEntries - 1, new ReadCallback() {
            @Override
            public void readComplete(int rc, LedgerHandle lh, Enumeration<LedgerEntry> seq, Object ctx) {
                returnCode.set(rc);
                receivedResponses.incrementAndGet();
                counter.countDown();
            }
        }, null);

        counter.await();

        // Wait extra time to ensure no extra responses received
        Thread.sleep(1000);

        assertEquals(1, receivedResponses.get());
        assertEquals(BKException.Code.BookieHandleNotAvailableException, returnCode.get());

        bkc.close();
    }

    @Test
    public void testAutoCloseableBookKeeper() throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        BookKeeper bkc2;
        try (BookKeeper bkc = new BookKeeper(conf)) {
            bkc2 = bkc;
            long ledgerId;
            try (LedgerHandle lh = bkc.createLedger(digestType, "testPasswd".getBytes())) {
                ledgerId = lh.getId();
                for (int i = 0; i < 100; i++) {
                    lh.addEntry("foobar".getBytes());
                }
            }
            assertTrue(bkc.isClosed(ledgerId), "Ledger should be closed!");
        }
        assertTrue(bkc2.closed, "BookKeeper should be closed!");
    }

    @Test
    public void testReadAfterLastAddConfirmed() throws Exception {

        ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.setMetadataServiceUri(zkUtil.getMetadataServiceUri());

        try (BookKeeper bkWriter = new BookKeeper(clientConfiguration)) {
            LedgerHandle writeLh = bkWriter.createLedger(digestType, "testPasswd".getBytes());
            long ledgerId = writeLh.getId();
            int numOfEntries = 5;
            for (int i = 0; i < numOfEntries; i++) {
                writeLh.addEntry(("foobar" + i).getBytes());
            }

            try (BookKeeper bkReader = new BookKeeper(clientConfiguration);
                LedgerHandle rlh = bkReader.openLedgerNoRecovery(ledgerId, digestType, "testPasswd".getBytes())) {
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 2)), "Expected LAC of rlh: "
                        + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                assertFalse(writeLh.isClosed());

                // with readUnconfirmedEntries we are able to read all of the entries
                Enumeration<LedgerEntry> entries = rlh.readUnconfirmedEntries(0, numOfEntries - 1);
                int entryId = 0;
                while (entries.hasMoreElements()) {
                    LedgerEntry entry = entries.nextElement();
                    String entryString = new String(entry.getEntry());
                    assertEquals(entryString, "foobar" + entryId, "Expected entry String: " + ("foobar" + entryId)
                            + " actual entry String: " + entryString);
                    entryId++;
                }
            }

            try (BookKeeper bkReader = new BookKeeper(clientConfiguration);
                LedgerHandle rlh = bkReader.openLedgerNoRecovery(ledgerId, digestType, "testPasswd".getBytes())) {
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 2)), "Expected LAC of rlh: "
                        + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                assertFalse(writeLh.isClosed());

                // without readUnconfirmedEntries we are not able to read all of the entries
                try {
                    rlh.readEntries(0, numOfEntries - 1);
                    fail("should not be able to read up to " + (numOfEntries - 1) + " with readEntries");
                } catch (BKException.BKReadException expected) {
                }

                // read all entries within the 0..LastAddConfirmed range with readEntries
                assertEquals(rlh.getLastAddConfirmed() + 1,
                    Collections.list(rlh.readEntries(0, rlh.getLastAddConfirmed())).size());

                // assert local LAC does not change after reads
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 2)), "Expected LAC of rlh: "
                        + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                // read all entries within the 0..LastAddConfirmed range with readUnconfirmedEntries
                assertEquals(rlh.getLastAddConfirmed() + 1,
                    Collections.list(rlh.readUnconfirmedEntries(0, rlh.getLastAddConfirmed())).size());

                // assert local LAC does not change after reads
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 2)), "Expected LAC of rlh: "
                        + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                // read all entries within the LastAddConfirmed..numOfEntries - 1 range with readUnconfirmedEntries
                assertEquals(numOfEntries - rlh.getLastAddConfirmed(),
                    Collections.list(rlh.readUnconfirmedEntries(rlh.getLastAddConfirmed(), numOfEntries - 1)).size());

                // assert local LAC does not change after reads
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 2)), "Expected LAC of rlh: "
                        + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                try {
                    // read all entries within the LastAddConfirmed..numOfEntries range  with readUnconfirmedEntries
                    // this is an error, we are going outside the range of existing entries
                    rlh.readUnconfirmedEntries(rlh.getLastAddConfirmed(), numOfEntries);
                    fail("the read tried to access data for unexisting entry id " + numOfEntries);
                } catch (BKException.BKNoSuchEntryException expected) {
                    // expecting a BKNoSuchEntryException, as the entry does not exist on bookies
                }

                try {
                    // read all entries within the LastAddConfirmed..numOfEntries range with readEntries
                    // this is an error, we are going outside the range of existing entries
                    rlh.readEntries(rlh.getLastAddConfirmed(), numOfEntries);
                    fail("the read tries to access data for unexisting entry id " + numOfEntries);
                } catch (BKException.BKReadException expected) {
                    // expecting a BKReadException, as the client rejected the request to access entries
                    // after local LastAddConfirmed
                }

            }

            // ensure that after restarting every bookie entries are not lost
            // even entries after the LastAddConfirmed
            restartBookies();

            try (BookKeeper bkReader = new BookKeeper(clientConfiguration);
                LedgerHandle rlh = bkReader.openLedgerNoRecovery(ledgerId, digestType, "testPasswd".getBytes())) {
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 2)), "Expected LAC of rlh: "
                        + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                assertFalse(writeLh.isClosed());

                // with readUnconfirmedEntries we are able to read all of the entries
                Enumeration<LedgerEntry> entries = rlh.readUnconfirmedEntries(0, numOfEntries - 1);
                int entryId = 0;
                while (entries.hasMoreElements()) {
                    LedgerEntry entry = entries.nextElement();
                    String entryString = new String(entry.getEntry());
                    assertEquals(entryString, "foobar" + entryId, "Expected entry String: " + ("foobar" + entryId)
                            + " actual entry String: " + entryString);
                    entryId++;
                }
            }

            try (BookKeeper bkReader = new BookKeeper(clientConfiguration);
                LedgerHandle rlh = bkReader.openLedgerNoRecovery(ledgerId, digestType, "testPasswd".getBytes())) {
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 2)), "Expected LAC of rlh: "
                        + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                assertFalse(writeLh.isClosed());

                // without readUnconfirmedEntries we are not able to read all of the entries
                try {
                    rlh.readEntries(0, numOfEntries - 1);
                    fail("should not be able to read up to " + (numOfEntries - 1) + " with readEntries");
                } catch (BKException.BKReadException expected) {
                }

                // read all entries within the 0..LastAddConfirmed range with readEntries
                assertEquals(rlh.getLastAddConfirmed() + 1,
                    Collections.list(rlh.readEntries(0, rlh.getLastAddConfirmed())).size());

                // assert local LAC does not change after reads
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 2)), "Expected LAC of rlh: "
                        + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                // read all entries within the 0..LastAddConfirmed range with readUnconfirmedEntries
                assertEquals(rlh.getLastAddConfirmed() + 1,
                    Collections.list(rlh.readUnconfirmedEntries(0, rlh.getLastAddConfirmed())).size());

                // assert local LAC does not change after reads
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 2)), "Expected LAC of rlh: "
                        + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                // read all entries within the LastAddConfirmed..numOfEntries - 1 range with readUnconfirmedEntries
                assertEquals(numOfEntries - rlh.getLastAddConfirmed(),
                    Collections.list(rlh.readUnconfirmedEntries(rlh.getLastAddConfirmed(), numOfEntries - 1)).size());

                // assert local LAC does not change after reads
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 2)), "Expected LAC of rlh: "
                        + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                try {
                    // read all entries within the LastAddConfirmed..numOfEntries range  with readUnconfirmedEntries
                    // this is an error, we are going outside the range of existing entries
                    rlh.readUnconfirmedEntries(rlh.getLastAddConfirmed(), numOfEntries);
                    fail("the read tried to access data for unexisting entry id " + numOfEntries);
                } catch (BKException.BKNoSuchEntryException expected) {
                    // expecting a BKNoSuchEntryException, as the entry does not exist on bookies
                }

                try {
                    // read all entries within the LastAddConfirmed..numOfEntries range with readEntries
                    // this is an error, we are going outside the range of existing entries
                    rlh.readEntries(rlh.getLastAddConfirmed(), numOfEntries);
                    fail("the read tries to access data for unexisting entry id " + numOfEntries);
                } catch (BKException.BKReadException expected) {
                    // expecting a BKReadException, as the client rejected the request to access entries
                    // after local LastAddConfirmed
                }

            }

            // open ledger with fencing, this will repair the ledger and make the last entry readable
            try (BookKeeper bkReader = new BookKeeper(clientConfiguration);
                LedgerHandle rlh = bkReader.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                assertTrue((rlh.getLastAddConfirmed() == (numOfEntries - 1)), "Expected LAC of rlh: "
                        + (numOfEntries - 1) + " actual LAC of rlh: " + rlh.getLastAddConfirmed());

                assertFalse(writeLh.isClosed());

                // without readUnconfirmedEntries we are not able to read all of the entries
                Enumeration<LedgerEntry> entries = rlh.readEntries(0, numOfEntries - 1);
                int entryId = 0;
                while (entries.hasMoreElements()) {
                    LedgerEntry entry = entries.nextElement();
                    String entryString = new String(entry.getEntry());
                    assertEquals(entryString, "foobar" + entryId, "Expected entry String: " + ("foobar" + entryId)
                            + " actual entry String: " + entryString);
                    entryId++;
                }
            }

            // should still be able to close as long as recovery closed the ledger
            // with the same last entryId and length as in the write handle.
            writeLh.close();
        }
    }

    @Test
    public void testReadWriteWithV2WireProtocol() throws Exception {
        ClientConfiguration conf = new ClientConfiguration().setUseV2WireProtocol(true);
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        int numEntries = 100;
        byte[] data = "foobar".getBytes();
        try (BookKeeper bkc = new BookKeeper(conf)) {

            // basic read/write
            {
                long ledgerId;
                try (LedgerHandle lh = bkc.createLedger(digestType, "testPasswd".getBytes())) {
                    ledgerId = lh.getId();
                    for (int i = 0; i < numEntries; i++) {
                        lh.addEntry(data);
                    }
                }
                try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                    assertEquals(numEntries - 1, lh.readLastConfirmed());
                    for (Enumeration<LedgerEntry> readEntries = lh.readEntries(0, numEntries - 1);
                        readEntries.hasMoreElements();) {
                        LedgerEntry entry = readEntries.nextElement();
                        assertArrayEquals(data, entry.getEntry());
                    }
                }
            }

            // basic fencing
            {
                long ledgerId;
                try (LedgerHandle lh2 = bkc.createLedger(digestType, "testPasswd".getBytes())) {
                    ledgerId = lh2.getId();
                    lh2.addEntry(data);
                    try (LedgerHandle lh2Fence = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                    }
                    try {
                        lh2.addEntry(data);
                        fail("ledger should be fenced");
                    } catch (BKException.BKLedgerFencedException ex){
                    }
                }
            }
        }
    }

    @Test
    public void testBatchReadFailBackToSingleRead1() throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        int numEntries = 100;
        byte[] data = "foobar".getBytes();
        try (BookKeeper bkc = new BookKeeper(conf)) {
            // basic read/write
            {
                long ledgerId;
                try (LedgerHandle lh = bkc.createLedger(2, 2, 2,
                        digestType, "testPasswd".getBytes())) {
                    ledgerId = lh.getId();
                    for (int i = 0; i < numEntries; i++) {
                        lh.addEntry(data);
                    }
                }
                try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                    assertEquals(numEntries - 1, lh.readLastConfirmed());
                    //V3 protocol not support batch read. In theory, it will throw UnsupportedOperationException.
                    try {
                        lh.batchReadEntries(0, numEntries, 5 * 1024 * 1024);
                        fail("Should throw UnsupportedOperationException.");
                    } catch (UnsupportedOperationException e) {
                        assertEquals("Unsupported batch read entry operation for v3 protocol.", e.getMessage());
                    }
                }
            }
        }

        try (BookKeeper bkc = new BookKeeper(conf)) {
            // basic read/write
            {
                long ledgerId;
                try (LedgerHandle lh = bkc.createLedger(3, 2, 2,
                        digestType, "testPasswd".getBytes())) {
                    ledgerId = lh.getId();
                    for (int i = 0; i < numEntries; i++) {
                        lh.addEntry(data);
                    }
                }
                try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                    assertEquals(numEntries - 1, lh.readLastConfirmed());
                    //The ledger ensemble is not equals write quorum, so failback to single read, it also can
                    //read data successfully.
                    for (Enumeration<LedgerEntry> readEntries = lh.batchReadEntries(0, numEntries, 5 * 1024 * 1024);
                            readEntries.hasMoreElements();) {
                        LedgerEntry entry = readEntries.nextElement();
                        assertArrayEquals(data, entry.getEntry());
                    }
                }
            }
        }
    }

    @Test
    public void testBatchReadFailBackToSingleRead2() throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        int numEntries = 100;
        byte[] data = "foobar".getBytes();
        try (BookKeeper bkc = new BookKeeper(conf)) {
            // basic read/write
            {
                long ledgerId;
                try (LedgerHandle lh = bkc.createLedger(2, 2, 2,
                        digestType, "testPasswd".getBytes())) {
                    ledgerId = lh.getId();
                    for (int i = 0; i < numEntries; i++) {
                        lh.addEntry(data);
                    }
                }
                try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                    assertEquals(numEntries - 1, lh.readLastConfirmed());
                    //V3 protocol not support batch read, it will throw UnsupportedOperationException.
                    try {
                        lh.batchReadEntries(0, numEntries, 5 * 1024 * 1024);
                        fail("Should throw UnsupportedOperationException.");
                    } catch (UnsupportedOperationException e) {
                        assertEquals("Unsupported batch read entry operation for v3 protocol.", e.getMessage());
                    }
                }
            }
        }

        conf.setBatchReadEnabled(false);
        try (BookKeeper bkc = new BookKeeper(conf)) {
            // basic read/write
            {
                long ledgerId;
                try (LedgerHandle lh = bkc.createLedger(2, 2, 2,
                        digestType, "testPasswd".getBytes())) {
                    ledgerId = lh.getId();
                    for (int i = 0; i < numEntries; i++) {
                        lh.addEntry(data);
                    }
                }
                try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                    assertEquals(numEntries - 1, lh.readLastConfirmed());
                    //We config disable the batch read, so failback to single read, it also can
                    //read data successfully.
                    for (Enumeration<LedgerEntry> readEntries = lh.batchReadEntries(0, numEntries, 5 * 1024 * 1024);
                            readEntries.hasMoreElements();) {
                        LedgerEntry entry = readEntries.nextElement();
                        assertArrayEquals(data, entry.getEntry());
                    }
                }
            }
        }
    }

    @Test
    public void testSanityCheckBatchReadEntriesV2() {
        ClientConfiguration conf = new ClientConfiguration().setUseV2WireProtocol(true);
        conf.setBatchReadEnabled(true);
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        int numEntries = 100;
        byte[] data = "foobar".getBytes();
        try (BookKeeper bkc = new BookKeeper(conf)) {
            long ledgerId;
            try (LedgerHandle lh = bkc.createLedger(2, 2, digestType, "testPasswd".getBytes())) {
                ledgerId = lh.getId();
                for (int i = 0; i < numEntries; i++) {
                    lh.addEntry(data);
                }
            } catch (BKException | InterruptedException e) {
                fail("LedgerHandle inti failed: " + e.getMessage());
                return;
            }

            // startEntry < 0
            try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                assertEquals(numEntries - 1, lh.readLastConfirmed());
                Enumeration<LedgerEntry> entries = lh.batchReadEntries(-1, numEntries, 5 * 1024 * 1024);
            } catch (BKException | InterruptedException e) {
                LOG.info(e.getMessage(), e); // It should raise IncorrectParameterException
            }

            // startEntry > lastAddConfirmed
            try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                Enumeration<LedgerEntry> entries = lh.batchReadEntries(numEntries, numEntries, 5 * 1024 * 1024);
            } catch (BKException | InterruptedException e) {
                LOG.info(e.getMessage(), e); // It should raise IncorrectParameterException
            }
        } catch (BKException | InterruptedException | IOException e) {
            fail("BookKeeper client init failed: " + e.getMessage());
        }
    }

    @Test
    public void testBatchReadWithV2Protocol() throws Exception {
        ClientConfiguration conf = new ClientConfiguration().setUseV2WireProtocol(true);
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        int numEntries = 100;
        byte[] data = "foobar".getBytes();
        try (BookKeeper bkc = new BookKeeper(conf)) {
            // basic read/write
            {
                long ledgerId;
                try (LedgerHandle lh = bkc.createLedger(2, 2, 2, digestType, "testPasswd".getBytes())) {
                    ledgerId = lh.getId();
                    for (int i = 0; i < numEntries; i++) {
                        lh.addEntry(data);
                    }
                }
                try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                    assertEquals(numEntries - 1, lh.readLastConfirmed());
                    int entries = 0;
                    for (Enumeration<LedgerEntry> readEntries = lh.batchReadEntries(0, numEntries, 5 * 1024 * 1024);
                            readEntries.hasMoreElements();) {
                        LedgerEntry entry = readEntries.nextElement();
                        assertArrayEquals(data, entry.getEntry());
                        entries++;
                    }
                    assertEquals(numEntries, entries);

                    //The maxCount is 0, the result is only limited by maxSize.
                    entries = 0;
                    for (Enumeration<LedgerEntry> readEntries = lh.batchReadEntries(0, 0, 5 * 1024 * 1024);
                            readEntries.hasMoreElements();) {
                        LedgerEntry entry = readEntries.nextElement();
                        assertArrayEquals(data, entry.getEntry());
                        entries++;
                    }
                    assertEquals(numEntries, entries);

                    // one entry size = 8(ledgerId) + 8(entryId) + 8(lac) + 8(length) + 8(digest) + payload size
                    long entrySize = 8 + 8 + 8 + 8 + 8 + data.length;
                    //response header size.
                    int headerSize = 24 + 8 + 4;
                    //The maxCount is 0, the result is only limited by maxSize.
                    entries = 0;
                    int expectEntriesNum = 5;
                    for (Enumeration<LedgerEntry> readEntries = lh.batchReadEntries(0, 0,
                            expectEntriesNum * entrySize + headerSize + (expectEntriesNum * 4));
                            readEntries.hasMoreElements();) {
                        LedgerEntry entry = readEntries.nextElement();
                        assertArrayEquals(data, entry.getEntry());
                        entries++;
                    }
                    assertEquals(expectEntriesNum, entries);

                    //The maxCount is 100, the result entries reach maxSize limit.
                    entries = 0;
                    for (Enumeration<LedgerEntry> readEntries = lh.batchReadEntries(0, 20,
                            expectEntriesNum * entrySize + headerSize + (expectEntriesNum * 4));
                            readEntries.hasMoreElements();) {
                        LedgerEntry entry = readEntries.nextElement();
                        assertArrayEquals(data, entry.getEntry());
                        entries++;
                    }
                    assertEquals(expectEntriesNum, entries);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testReadEntryReleaseByteBufs() throws Exception {
        ClientConfiguration confWriter = new ClientConfiguration();
        confWriter.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        int numEntries = 10;
        byte[] data = "foobar".getBytes();
        long ledgerId;
        try (BookKeeper bkc = new BookKeeper(confWriter)) {
            try (LedgerHandle lh = bkc.createLedger(digestType, "testPasswd".getBytes())) {
                ledgerId = lh.getId();
                for (int i = 0; i < numEntries; i++) {
                    lh.addEntry(data);
                }
            }
        }

        // v2 protocol, using pooled buffers
        ClientConfiguration confReader1 = new ClientConfiguration()
            .setUseV2WireProtocol(true)
            .setNettyUsePooledBuffers(true)
            .setMetadataServiceUri(zkUtil.getMetadataServiceUri());

        try (BookKeeper bkc = new BookKeeper(confReader1)) {
            try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                assertEquals(numEntries - 1, lh.readLastConfirmed());
                for (Enumeration<LedgerEntry> readEntries = lh.readEntries(0, numEntries - 1);
                    readEntries.hasMoreElements();) {
                    LedgerEntry entry = readEntries.nextElement();
                    try {
                        entry.data.release();
                    } catch (IllegalReferenceCountException ok) {
                        fail("ByteBuf already released");
                    }
                }
            }
        }

        // v2 protocol, not using pooled buffers
        ClientConfiguration confReader2 = new ClientConfiguration()
            .setUseV2WireProtocol(true)
            .setNettyUsePooledBuffers(false);
        confReader2.setMetadataServiceUri(zkUtil.getMetadataServiceUri());

        try (BookKeeper bkc = new BookKeeper(confReader2)) {
            try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                assertEquals(numEntries - 1, lh.readLastConfirmed());
                for (Enumeration<LedgerEntry> readEntries = lh.readEntries(0, numEntries - 1);
                    readEntries.hasMoreElements();) {
                    LedgerEntry entry = readEntries.nextElement();
                    try {
                        entry.data.release();
                    } catch (IllegalReferenceCountException e) {
                        fail("ByteBuf already released");
                    }
                }
            }
        }

        // v3 protocol, not using pooled buffers
        ClientConfiguration confReader3 = new ClientConfiguration()
            .setUseV2WireProtocol(false)
            .setNettyUsePooledBuffers(false)
            .setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        try (BookKeeper bkc = new BookKeeper(confReader3)) {
            try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                assertEquals(numEntries - 1, lh.readLastConfirmed());
                for (Enumeration<LedgerEntry> readEntries = lh.readEntries(0, numEntries - 1);
                    readEntries.hasMoreElements();) {
                    LedgerEntry entry = readEntries.nextElement();
                    assertTrue(entry.data.release(),
                            "Can't release entry " + entry.getEntryId() + ": ref = " + entry.data.refCnt());
                    try {
                        assertFalse(entry.data.release());
                        fail("ByteBuf already released");
                    } catch (IllegalReferenceCountException ok) {
                    }
                }
            }
        }

        // v3 protocol, using pooled buffers
        // v3 protocol from 4.5 always "wraps" buffers returned by protobuf
        ClientConfiguration confReader4 = new ClientConfiguration()
            .setUseV2WireProtocol(false)
            .setNettyUsePooledBuffers(true)
            .setMetadataServiceUri(zkUtil.getMetadataServiceUri());

        try (BookKeeper bkc = new BookKeeper(confReader4)) {
            try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                assertEquals(numEntries - 1, lh.readLastConfirmed());
                for (Enumeration<LedgerEntry> readEntries = lh.readEntries(0, numEntries - 1);
                    readEntries.hasMoreElements();) {
                    LedgerEntry entry = readEntries.nextElement();
                    // ButeBufs not reference counter
                    assertTrue(entry.data.release(),
                            "Can't release entry " + entry.getEntryId() + ": ref = " + entry.data.refCnt());
                    try {
                        assertFalse(entry.data.release());
                        fail("ByteBuf already released");
                    } catch (IllegalReferenceCountException ok) {
                    }
                }
            }
        }

        // cannot read twice an entry
        ClientConfiguration confReader5 = new ClientConfiguration();
        confReader5.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        try (BookKeeper bkc = new BookKeeper(confReader5)) {
            try (LedgerHandle lh = bkc.openLedger(ledgerId, digestType, "testPasswd".getBytes())) {
                assertEquals(numEntries - 1, lh.readLastConfirmed());
                for (Enumeration<LedgerEntry> readEntries = lh.readEntries(0, numEntries - 1);
                    readEntries.hasMoreElements();) {
                    LedgerEntry entry = readEntries.nextElement();
                    entry.getEntry();
                    try {
                        entry.getEntry();
                        fail("entry data accessed twice");
                    } catch (IllegalStateException ok){
                    }
                    try {
                        entry.getEntryInputStream();
                        fail("entry data accessed twice");
                    } catch (IllegalStateException ok){
                    }
                }
            }
        }
    }

    /**
     * Tests that issuing multiple reads for the same entry at the same time works as expected.
     *
     * @throws Exception
     */
    @Test
    public void testDoubleRead() throws Exception {
        LedgerHandle lh = bkc.createLedger(digestType, "".getBytes());

        lh.addEntry("test".getBytes());

        // Read the same entry more times asynchronously
        final int n = 10;
        final CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            lh.asyncReadEntries(0, 0, new ReadCallback() {
                public void readComplete(int rc, LedgerHandle lh,
                                         Enumeration<LedgerEntry> seq, Object ctx) {
                    if (rc == BKException.Code.OK) {
                        latch.countDown();
                    } else {
                        fail("Read fail");
                    }
                }
            }, null);
        }

        latch.await();
    }

    /**
     * Tests that issuing multiple reads for the same entry at the same time works as expected.
     *
     * @throws Exception
     */
    @Test
    public void testDoubleReadWithV2Protocol() throws Exception {
        ClientConfiguration conf = new ClientConfiguration(baseClientConf);
        conf.setUseV2WireProtocol(true);
        BookKeeperTestClient bkc = new BookKeeperTestClient(conf);
        LedgerHandle lh = bkc.createLedger(digestType, "".getBytes());

        lh.addEntry("test".getBytes());

        // Read the same entry more times asynchronously
        final int n = 10;
        final CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            lh.asyncReadEntries(0, 0, new ReadCallback() {
                public void readComplete(int rc, LedgerHandle lh,
                                         Enumeration<LedgerEntry> seq, Object ctx) {
                    if (rc == BKException.Code.OK) {
                        latch.countDown();
                    } else {
                        fail("Read fail");
                    }
                }
            }, null);
        }

        latch.await();
        bkc.close();
    }

    @Test
    public void testCannotUseWriteFlagsOnV2Protocol() throws Exception {
        ClientConfiguration conf = new ClientConfiguration(baseClientConf);
        conf.setUseV2WireProtocol(true);
        try (BookKeeperTestClient bkc = new BookKeeperTestClient(conf)) {
            try (WriteHandle wh = result(bkc.newCreateLedgerOp()
                    .withEnsembleSize(3)
                    .withWriteQuorumSize(3)
                    .withAckQuorumSize(2)
                    .withPassword("".getBytes())
                    .withWriteFlags(WriteFlag.DEFERRED_SYNC)
                    .execute())) {
                Assertions.assertThrows(BKException.BKIllegalOpException.class,
                        () -> result(wh.appendAsync("test".getBytes())));
            }
        }
    }

    @Test
    public void testCannotUseForceOnV2Protocol() throws Exception {
        ClientConfiguration conf = new ClientConfiguration(baseClientConf);
        conf.setUseV2WireProtocol(true);
        try (BookKeeperTestClient bkc = new BookKeeperTestClient(conf)) {
            try (WriteHandle wh = result(bkc.newCreateLedgerOp()
                    .withEnsembleSize(3)
                    .withWriteQuorumSize(3)
                    .withAckQuorumSize(2)
                    .withPassword("".getBytes())
                    .withWriteFlags(WriteFlag.NONE)
                    .execute())) {
                result(wh.appendAsync("".getBytes()));
                Assertions.assertThrows(BKException.BKIllegalOpException.class,
                        () -> result(wh.force()));
            }
        }
    }

    class MockZooKeeperClient extends ZooKeeperClient {
        class MockZooKeeper extends ZooKeeper {
            public MockZooKeeper(String connectString, int sessionTimeout, Watcher watcher, boolean canBeReadOnly)
                    throws IOException {
                super(connectString, sessionTimeout, watcher, canBeReadOnly);
            }

            @Override
            public void create(final String path, byte[] data, List<ACL> acl, CreateMode createMode, StringCallback cb,
                    Object ctx) {
                StringCallback injectedCallback = new StringCallback() {
                    @Override
                    public void processResult(int rc, String path, Object ctx, String name) {
                        /**
                         * if ledgerIdToInjectFailure matches with the path of
                         * the node, then throw CONNECTIONLOSS error and then
                         * reset it to INVALID_LEDGERID.
                         */
                        if (path.contains(ledgerIdToInjectFailure.toString())) {
                            ledgerIdToInjectFailure.set(INVALID_LEDGERID);
                            cb.processResult(KeeperException.Code.CONNECTIONLOSS.intValue(), path, ctx, name);
                        } else {
                            cb.processResult(rc, path, ctx, name);
                        }
                    }
                };
                super.create(path, data, acl, createMode, injectedCallback, ctx);
            }
        }

        private final String connectString;
        private final int sessionTimeoutMs;
        private final ZooKeeperWatcherBase watcherManager;
        private final AtomicLong ledgerIdToInjectFailure;

        MockZooKeeperClient(String connectString, int sessionTimeoutMs, ZooKeeperWatcherBase watcher,
                AtomicLong ledgerIdToInjectFailure) throws IOException {
            /*
             * in OperationalRetryPolicy maxRetries is > 0. So in case of any
             * RecoverableException scenario, it will retry.
             */
            super(connectString, sessionTimeoutMs, watcher,
                    new BoundExponentialBackoffRetryPolicy(sessionTimeoutMs, sessionTimeoutMs, Integer.MAX_VALUE),
                    new BoundExponentialBackoffRetryPolicy(sessionTimeoutMs, sessionTimeoutMs, 3),
                    NullStatsLogger.INSTANCE, 1, 0, false);
            this.connectString = connectString;
            this.sessionTimeoutMs = sessionTimeoutMs;
            this.watcherManager = watcher;
            this.ledgerIdToInjectFailure = ledgerIdToInjectFailure;
        }

        @Override
        protected ZooKeeper createZooKeeper() throws IOException {
            return new MockZooKeeper(this.connectString, this.sessionTimeoutMs, this.watcherManager, false);
        }
    }

    @Test
    public void testZKConnectionLossForLedgerCreation() throws Exception {
        int zkSessionTimeOut = 10000;
        AtomicLong ledgerIdToInjectFailure = new AtomicLong(INVALID_LEDGERID);
        ZooKeeperWatcherBase zooKeeperWatcherBase = new ZooKeeperWatcherBase(zkSessionTimeOut, false,
                NullStatsLogger.INSTANCE);
        MockZooKeeperClient zkFaultInjectionWrapper = new MockZooKeeperClient(zkUtil.getZooKeeperConnectString(),
                zkSessionTimeOut, zooKeeperWatcherBase, ledgerIdToInjectFailure);
        zkFaultInjectionWrapper.waitForConnection();
        assertEquals(States.CONNECTED, zkFaultInjectionWrapper.getState(),
                "zkFaultInjectionWrapper should be in connected state");
        BookKeeper bk = new BookKeeper(baseClientConf, zkFaultInjectionWrapper);
        long oldZkInstanceSessionId = zkFaultInjectionWrapper.getSessionId();
        long ledgerId = 567L;
        LedgerHandle lh = bk.createLedgerAdv(ledgerId, 1, 1, 1, DigestType.CRC32, "".getBytes(), null);
        lh.close();

        /*
         * trigger Expired event so that MockZooKeeperClient would run
         * 'clientCreator' and create new zk handle. In this case it would
         * create MockZooKeeper.
         */
        zooKeeperWatcherBase.process(new WatchedEvent(EventType.None, KeeperState.Expired, ""));
        zkFaultInjectionWrapper.waitForConnection();
        for (int i = 0; i < 10; i++) {
            if (zkFaultInjectionWrapper.getState() == States.CONNECTED) {
                break;
            }
            Thread.sleep(200);
        }
        assertEquals(States.CONNECTED, zkFaultInjectionWrapper.getState(),
                "zkFaultInjectionWrapper should be in connected state");
        assertNotEquals(oldZkInstanceSessionId, zkFaultInjectionWrapper.getSessionId(),
                "Session Id of old and new ZK instance should be different");
        ledgerId++;
        ledgerIdToInjectFailure.set(ledgerId);
        /**
         * ledgerIdToInjectFailure is set to 'ledgerId', so zookeeper.create
         * would return CONNECTIONLOSS error for the first time and when it is
         * retried, as expected it would return NODEEXISTS error.
         *
         * AbstractZkLedgerManager.createLedgerMetadata should deal with this
         * scenario appropriately.
         */
        lh = bk.createLedgerAdv(ledgerId, 1, 1, 1, DigestType.CRC32, "".getBytes(), null);
        lh.close();
        assertEquals(INVALID_LEDGERID, ledgerIdToInjectFailure.get(),
                "injectZnodeCreationNoNodeFailure should have been reset it to INVALID_LEDGERID");
        lh = bk.openLedger(ledgerId, DigestType.CRC32, "".getBytes());
        lh.close();
        ledgerId++;
        lh = bk.createLedgerAdv(ledgerId, 1, 1, 1, DigestType.CRC32, "".getBytes(), null);
        lh.close();
        bk.close();
    }

    @Test
    public void testLedgerDeletionIdempotency() throws Exception {
        BookKeeper bk = new BookKeeper(baseClientConf);
        long ledgerId = 789L;
        LedgerHandle lh = bk.createLedgerAdv(ledgerId, 1, 1, 1, DigestType.CRC32, "".getBytes(), null);
        lh.close();
        bk.deleteLedger(ledgerId);
        bk.deleteLedger(ledgerId);
        bk.close();
    }

    /**
     * Mock of RackawareEnsemblePlacementPolicy. Overrides areAckedBookiesAdheringToPlacementPolicy to only return true
     * when ackedBookies consists of writeQuorumSizeToUseForTesting bookies.
     */
    public static class MockRackawareEnsemblePlacementPolicy extends RackawareEnsemblePlacementPolicy {
        private int writeQuorumSizeToUseForTesting;
        private CountDownLatch conditionFirstInvocationLatch;

        void setWriteQuorumSizeToUseForTesting(int writeQuorumSizeToUseForTesting) {
            this.writeQuorumSizeToUseForTesting = writeQuorumSizeToUseForTesting;
        }

        void setConditionFirstInvocationLatch(CountDownLatch conditionFirstInvocationLatch) {
            this.conditionFirstInvocationLatch = conditionFirstInvocationLatch;
        }

        @Override
        public boolean areAckedBookiesAdheringToPlacementPolicy(Set<BookieId> ackedBookies,
                                                                int writeQuorumSize,
                                                                int ackQuorumSize) {
            conditionFirstInvocationLatch.countDown();
            return ackedBookies.size() == writeQuorumSizeToUseForTesting;
        }
    }

    /**
     * Test to verify that PendingAddOp waits for success condition from areAckedBookiesAdheringToPlacementPolicy
     * before returning success to client. Also tests working of WRITE_DELAYED_DUE_TO_NOT_ENOUGH_FAULT_DOMAINS and
     * WRITE_TIMED_OUT_DUE_TO_NOT_ENOUGH_FAULT_DOMAINS counters.
     */
    @Test
    public void testEnforceMinNumFaultDomainsForWrite() throws Exception {
        byte[] data = "foobar".getBytes();
        byte[] password = "testPasswd".getBytes();

        startNewBookie();
        startNewBookie();

        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());
        conf.setEnsemblePlacementPolicy(MockRackawareEnsemblePlacementPolicy.class);

        conf.setAddEntryTimeout(2);
        conf.setAddEntryQuorumTimeout(4);
        conf.setEnforceMinNumFaultDomainsForWrite(true);

        TestStatsProvider statsProvider = new TestStatsProvider();

        // Abnormal values for testing to prevent timeouts
        BookKeeperTestClient bk = new BookKeeperTestClient(conf, statsProvider);
        StatsLogger statsLogger = bk.getStatsLogger();

        int ensembleSize = 3;
        int writeQuorumSize = 3;
        int ackQuorumSize = 2;

        CountDownLatch countDownLatch = new CountDownLatch(1);
        MockRackawareEnsemblePlacementPolicy currPlacementPolicy =
                (MockRackawareEnsemblePlacementPolicy) bk.getPlacementPolicy();
        currPlacementPolicy.setConditionFirstInvocationLatch(countDownLatch);
        currPlacementPolicy.setWriteQuorumSizeToUseForTesting(writeQuorumSize);

        BookieId bookieToSleep;

        try (LedgerHandle lh = bk.createLedger(ensembleSize, writeQuorumSize, ackQuorumSize, digestType, password)) {
            CountDownLatch sleepLatchCase1 = new CountDownLatch(1);
            CountDownLatch sleepLatchCase2 = new CountDownLatch(1);

            // Put all non ensemble bookies to sleep
            LOG.info("Putting all non ensemble bookies to sleep.");
            for (BookieId addr : bookieAddresses()) {
                try {
                    if (!lh.getCurrentEnsemble().contains(addr)) {
                        sleepBookie(addr, sleepLatchCase2);
                    }
                } catch (UnknownHostException ignored) {}
            }

            Thread writeToLedger = new Thread(() -> {
                try {
                    LOG.info("Initiating write for entry");
                    long entryId = lh.addEntry(data);
                    LOG.info("Wrote entry with entryId = {}", entryId);
                } catch (InterruptedException | BKException ignored) {
                }
            });

            bookieToSleep = lh.getCurrentEnsemble().get(0);

            LOG.info("Putting picked bookie to sleep");
            sleepBookie(bookieToSleep, sleepLatchCase1);

            assertEquals(statsLogger
                           .getCounter(WRITE_DELAYED_DUE_TO_NOT_ENOUGH_FAULT_DOMAINS)
                           .get()
                           .longValue(), 0);

            // Trying to write entry
            writeToLedger.start();

            // Waiting and checking to make sure that write has not succeeded
            countDownLatch.await(conf.getAddEntryTimeout(), TimeUnit.SECONDS);
            assertEquals(-1, lh.lastAddConfirmed, "Write succeeded but should not have");

            // Wake the bookie
            sleepLatchCase1.countDown();

            // Waiting and checking to make sure that write has succeeded
            writeToLedger.join(conf.getAddEntryTimeout() * 1000);
            assertEquals(0, lh.lastAddConfirmed, "Write did not succeed but should have");

            assertEquals(statsLogger
                           .getCounter(WRITE_DELAYED_DUE_TO_NOT_ENOUGH_FAULT_DOMAINS)
                           .get()
                           .longValue(), 1);

            // AddEntry thread for second scenario
            Thread writeToLedger2 = new Thread(() -> {
                try {
                    LOG.info("Initiating write for entry");
                    long entryId = lh.addEntry(data);
                    LOG.info("Wrote entry with entryId = {}", entryId);
                } catch (InterruptedException | BKException ignored) {
                }
            });

            bookieToSleep = lh.getCurrentEnsemble().get(1);

            LOG.info("Putting picked bookie to sleep");
            sleepBookie(bookieToSleep, sleepLatchCase2);

            // Trying to write entry
            writeToLedger2.start();

            // Waiting and checking to make sure that write has failed
            writeToLedger2.join((conf.getAddEntryQuorumTimeout() + 2) * 1000);
            assertEquals(0, lh.lastAddConfirmed, "Write succeeded but should not have");

            sleepLatchCase2.countDown();

            assertEquals(statsLogger.getCounter(WRITE_DELAYED_DUE_TO_NOT_ENOUGH_FAULT_DOMAINS).get().longValue(),
                         2);

            assertEquals(statsLogger.getCounter(WRITE_TIMED_OUT_DUE_TO_NOT_ENOUGH_FAULT_DOMAINS).get().longValue(),
                         1);
        }
    }

    @Test
    public void testBookieAddressResolverPassedToDNSToSwitchMapping() throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());

        StaticDNSResolver tested = new StaticDNSResolver();
        try (BookKeeper bkc = BookKeeper
                        .forConfig(conf)
                        .dnsResolver(tested)
                        .build()) {
            bkc.createLedger(digestType, "testPasswd".getBytes()).close();
            assertSame(bkc.getBookieAddressResolver(), tested.getBookieAddressResolver());
        }
    }

    @Test
    public void testBookieWatcher() throws Exception {
        ClientConfiguration conf = new ClientConfiguration();
        conf.setMetadataServiceUri(zkUtil.getMetadataServiceUri());

        StaticDNSResolver tested = new StaticDNSResolver();
        try (BookKeeper bkc = BookKeeper
                .forConfig(conf)
                .dnsResolver(tested)
                .build()) {
            final Map<BookieId, BookieInfoReader.BookieInfo> bookieInfo = bkc.getBookieInfo();

            // 1. check all bookies in client cache successfully.
            bookieInfo.forEach((bookieId, info) -> {
                final CompletableFuture<Versioned<BookieServiceInfo>> bookieServiceInfo = bkc.getMetadataClientDriver()
                        .getRegistrationClient().getBookieServiceInfo(bookieId);
                assertTrue(bookieServiceInfo.isDone());
                assertFalse(bookieServiceInfo.isCompletedExceptionally());
            });

            // 2. add a task to scheduler, blocking zk watch for bookies cache
            bkc.getClientCtx().getScheduler().schedule(() -> {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }, 0, TimeUnit.MILLISECONDS);

            // 3. restart one bookie, so the client should update cache by WatchTask
            restartBookie(bookieInfo.keySet().iterator().next());

            // 4. after restart bookie, check again for the client cache
            final CompletableFuture<Versioned<BookieServiceInfo>> bookieServiceInfo =
                    bkc.getMetadataClientDriver().getRegistrationClient()
                            .getBookieServiceInfo(bookieInfo.keySet().iterator().next());
            assertTrue(bookieServiceInfo.isDone());
            // 5. Previously, we used scheduler, and here getting bookie from client cache would fail.
            // 6. After this PR, we introduced independent internal thread pool watchTaskScheduler,
            // and here it will succeed.
            assertFalse(bookieServiceInfo.isCompletedExceptionally());
        }
    }

}
