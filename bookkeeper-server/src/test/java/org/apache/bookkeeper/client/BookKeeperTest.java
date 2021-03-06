package org.apache.bookkeeper.client;

import java.util.Enumeration;

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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.AsyncCallback.ReadCallback;
import org.apache.bookkeeper.client.BKException.BKBookieHandleNotAvailableException;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.bookkeeper.test.BaseTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.KeeperException;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;

/**
 * Tests of the main BookKeeper client
 */
public class BookKeeperTest extends BaseTestCase {
    private final static Logger LOG = LoggerFactory.getLogger(BookKeeperTest.class);

    DigestType digestType;

    public BookKeeperTest(DigestType digestType) {
        super(4);

        this.digestType = digestType;
    }

    @Test
    public void testConstructionZkDelay() throws Exception {
        ClientConfiguration conf = new ClientConfiguration()
            .setZkServers(zkUtil.getZooKeeperConnectString())
            .setZkTimeout(20000);

        CountDownLatch l = new CountDownLatch(1);
        zkUtil.sleepServer(5, l);
        l.await();

        BookKeeper bkc = new BookKeeper(conf);
        bkc.createLedger(digestType, "testPasswd".getBytes()).close();
        bkc.close();
    }

    @Test
    public void testConstructionNotConnectedExplicitZk() throws Exception {
        ClientConfiguration conf = new ClientConfiguration()
            .setZkServers(zkUtil.getZooKeeperConnectString())
            .setZkTimeout(20000);

        CountDownLatch l = new CountDownLatch(1);
        zkUtil.sleepServer(5, l);
        l.await();

        ZooKeeper zk = new ZooKeeper(zkUtil.getZooKeeperConnectString(), 10000,
                            new Watcher() {
                                @Override
                                public void process(WatchedEvent event) {
                                }
                            });
        assertFalse("ZK shouldn't have connected yet", zk.getState().isConnected());
        try {
            BookKeeper bkc = new BookKeeper(conf, zk);
            fail("Shouldn't be able to construct with unconnected zk");
        } catch (KeeperException.ConnectionLossException cle) {
            // correct behaviour
        }
    }

    /**
     * Test that bookkeeper is not able to open ledgers if
     * it provides the wrong password or wrong digest
     */
    @Test(timeout=60000)
    public void testBookkeeperPassword() throws Exception {
        ClientConfiguration conf = new ClientConfiguration()
            .setZkServers(zkUtil.getZooKeeperConnectString());
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
                fail("Shouldn't be able to open with bad digest");
            } catch (BKException.BKDigestMatchException bke) {
                // correct behaviour
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
    @Test(timeout=60000)
    public void testAsyncReadWithError() throws Exception {
        LedgerHandle lh = bkc.createLedger(3, 3, DigestType.CRC32, "testPasswd".getBytes());
        bkc.close();

        final AtomicInteger result = new AtomicInteger(0);
        final CountDownLatch counter = new CountDownLatch(1);

        // Try to write, we shoud get and error callback but not an exception
        lh.asyncAddEntry("test".getBytes(), new AddCallback() {
            public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
                result.set(rc);
                counter.countDown();
            }
        }, null);

        counter.await();

        Assert.assertTrue(result.get() != 0);
    }

    /**
     * Test that bookkeeper will close cleanly if close is issued
     * while another operation is in progress.
     */
    @Test(timeout=60000)
    public void testCloseDuringOp() throws Exception {
        ClientConfiguration conf = new ClientConfiguration()
            .setZkServers(zkUtil.getZooKeeperConnectString());
        for (int i = 0; i < 100; i++) {
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
            assertTrue("Close never completed", l.await(10, TimeUnit.SECONDS));
            assertTrue("Close was not successful", success.get());
        }
    }

    @Test(timeout=60000)
    public void testIsClosed() throws Exception {
        ClientConfiguration conf = new ClientConfiguration()
        .setZkServers(zkUtil.getZooKeeperConnectString());

        BookKeeper bkc = new BookKeeper(conf);
        LedgerHandle lh = bkc.createLedger(digestType, "testPasswd".getBytes());
        Long lId = lh.getId();

        lh.addEntry("000".getBytes());
        boolean result = bkc.isClosed(lId);
        Assert.assertTrue("Ledger shouldn't be flagged as closed!",!result);

        lh.close();
        result = bkc.isClosed(lId);
        Assert.assertTrue("Ledger should be flagged as closed!",result);

        bkc.close();
    }

    @Test(timeout = 60000)
    public void testReadFailureCallback() throws Exception {
        ClientConfiguration conf = new ClientConfiguration().setZkServers(zkUtil.getZooKeeperConnectString());

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

        startBKCluster();
    }

    @Test(timeout = 60000)
    public void testAutoCloseableBookKeeper() throws Exception {
        ClientConfiguration conf = new ClientConfiguration()
                .setZkServers(zkUtil.getZooKeeperConnectString());
        BookKeeper _bkc;
        try (BookKeeper bkc = new BookKeeper(conf);) {
            _bkc = bkc;
            long ledgerId;
            try (LedgerHandle lh = bkc.createLedger(digestType, "testPasswd".getBytes());) {
                ledgerId = lh.getId();
                for (int i = 0; i < 100; i++) {
                    lh.addEntry("foobar".getBytes());
                }
            }
            Assert.assertTrue("Ledger should be closed!", bkc.isClosed(ledgerId));
        }
        Assert.assertTrue("BookKeeper should be closed!", _bkc.closed);
    }

    @Test(timeout = 60000)
    public void testReadHandleWithNoExplicitLAC() throws Exception {
        ClientConfiguration confWithNoExplicitLAC = new ClientConfiguration()
                .setZkServers(zkUtil.getZooKeeperConnectString());
        confWithNoExplicitLAC.setExplictLacInterval(0);

        BookKeeper bkcWithNoExplicitLAC = new BookKeeper(confWithNoExplicitLAC);

        LedgerHandle wlh = bkcWithNoExplicitLAC.createLedger(digestType, "testPasswd".getBytes());
        long ledgerId = wlh.getId();
        int numOfEntries = 5;
        for (int i = 0; i < numOfEntries; i++) {
            wlh.addEntry(("foobar" + i).getBytes());
        }

        LedgerHandle rlh = bkcWithNoExplicitLAC.openLedgerNoRecovery(ledgerId, digestType, "testPasswd".getBytes());
        Assert.assertTrue(
                "Expected LAC of rlh: " + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed(),
                (rlh.getLastAddConfirmed() == (numOfEntries - 2)));

        Enumeration<LedgerEntry> entries = rlh.readEntries(0, numOfEntries - 2);
        int entryId = 0;
        while (entries.hasMoreElements()) {
            LedgerEntry entry = entries.nextElement();
            String entryString = new String(entry.getEntry());
            Assert.assertTrue("Expected entry String: " + ("foobar" + entryId) + " actual entry String: " + entryString,
                    entryString.equals("foobar" + entryId));
            entryId++;
        }

        for (int i = numOfEntries; i < 2 * numOfEntries; i++) {
            wlh.addEntry(("foobar" + i).getBytes());
        }

        Thread.sleep(3000);
        // since explicitlacflush policy is not enabled for writeledgerhandle, when we try
        // to read explicitlac for rlh, it will be LedgerHandle.INVALID_ENTRY_ID. But it
        // wont throw some exception.
        long explicitlac = rlh.readExplicitLastConfirmed();
        Assert.assertTrue(
                "Expected Explicit LAC of rlh: " + LedgerHandle.INVALID_ENTRY_ID + " actual ExplicitLAC of rlh: " + explicitlac,
                (explicitlac == LedgerHandle.INVALID_ENTRY_ID));
        Assert.assertTrue(
                "Expected LAC of wlh: " + (2 * numOfEntries - 1) + " actual LAC of rlh: " + wlh.getLastAddConfirmed(),
                (wlh.getLastAddConfirmed() == (2 * numOfEntries - 1)));
        Assert.assertTrue(
                "Expected LAC of rlh: " + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed(),
                (rlh.getLastAddConfirmed() == (numOfEntries - 2)));

        try {
            rlh.readEntries(numOfEntries - 1, numOfEntries - 1);
            fail("rlh readEntries beyond " + (numOfEntries - 2) + " should fail with ReadException");
        } catch (BKException.BKReadException readException) {
        }

        rlh.close();
        wlh.close();
        bkcWithNoExplicitLAC.close();
    }

    @Test(timeout = 60000)
    public void testReadHandleWithExplicitLAC() throws Exception {
        ClientConfiguration confWithExplicitLAC = new ClientConfiguration()
                .setZkServers(zkUtil.getZooKeeperConnectString());
        int explictLacInterval = 1;
        confWithExplicitLAC.setExplictLacInterval(explictLacInterval);

        BookKeeper bkcWithExplicitLAC = new BookKeeper(confWithExplicitLAC);

        LedgerHandle wlh = bkcWithExplicitLAC.createLedger(digestType, "testPasswd".getBytes());
        long ledgerId = wlh.getId();
        int numOfEntries = 5;
        for (int i = 0; i < numOfEntries; i++) {
            wlh.addEntry(("foobar" + i).getBytes());
        }

        LedgerHandle rlh = bkcWithExplicitLAC.openLedgerNoRecovery(ledgerId, digestType, "testPasswd".getBytes());

        Assert.assertTrue(
                "Expected LAC of rlh: " + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed(),
                (rlh.getLastAddConfirmed() == (numOfEntries - 2)));

        for (int i = numOfEntries; i < 2 * numOfEntries; i++) {
            wlh.addEntry(("foobar" + i).getBytes());
        }

        // we need to wait for atleast 2 explicitlacintervals,
        // since in writehandle for the first call
        // lh.getExplicitLastAddConfirmed() will be <
        // lh.getPiggyBackedLastAddConfirmed(),
        // so it wont make explicit writelac in the first run
        Thread.sleep((2 * explictLacInterval + 1) * 1000);
        Assert.assertTrue(
                "Expected LAC of wlh: " + (2 * numOfEntries - 1) + " actual LAC of wlh: " + wlh.getLastAddConfirmed(),
                (wlh.getLastAddConfirmed() == (2 * numOfEntries - 1)));
        // readhandle's lastaddconfirmed wont be updated until readExplicitLastConfirmed call is made   
        Assert.assertTrue(
                "Expected LAC of rlh: " + (numOfEntries - 2) + " actual LAC of rlh: " + rlh.getLastAddConfirmed(),
                (rlh.getLastAddConfirmed() == (numOfEntries - 2)));
        
        long explicitlac = rlh.readExplicitLastConfirmed();
        Assert.assertTrue(
                "Expected Explicit LAC of rlh: " + (2 * numOfEntries - 1) + " actual ExplicitLAC of rlh: " + explicitlac,
                (explicitlac == (2 * numOfEntries - 1)));
        // readExplicitLastConfirmed updates the lac of rlh.
        Assert.assertTrue(
                "Expected LAC of rlh: " + (2 * numOfEntries - 1) + " actual LAC of rlh: " + rlh.getLastAddConfirmed(),
                (rlh.getLastAddConfirmed() == (2 * numOfEntries - 1)));
        
        Enumeration<LedgerEntry> entries = rlh.readEntries(numOfEntries, 2 * numOfEntries - 1);
        int entryId = numOfEntries;
        while (entries.hasMoreElements()) {
            LedgerEntry entry = entries.nextElement();
            String entryString = new String(entry.getEntry());
            Assert.assertTrue("Expected entry String: " + ("foobar" + entryId) + " actual entry String: " + entryString,
                    entryString.equals("foobar" + entryId));
            entryId++;
        }

        rlh.close();
        wlh.close();
        bkcWithExplicitLAC.close();
    }	
}
