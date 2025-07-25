/*
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
 */
package org.apache.pulsar.client.api;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.google.common.util.concurrent.RateLimiter;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Cleanup;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.stats.NamespaceStats;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@Test(groups = "broker-api")
public class SimpleProducerConsumerStatTest extends ProducerConsumerBase {
    private static final Logger log = LoggerFactory.getLogger(SimpleProducerConsumerStatTest.class);

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        super.internalSetupForStatsTest();
        super.producerBaseSetup();
    }

    @Override
    protected ServiceConfiguration getDefaultConf() {
        ServiceConfiguration conf = super.getDefaultConf();
        // wait for shutdown of the broker, this prevents flakiness which could be caused by metrics being
        // unregistered asynchronously. This impacts the execution of the next test method if this would be happening.
        conf.setBrokerShutdownTimeoutMs(5000L);
        return conf;
    }

    @AfterMethod(alwaysRun = true)
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @DataProvider(name = "batch")
    public Object[][] batchMessageDelayMsProvider() {
        return new Object[][] { { 0 }, { 1000 } };
    }

    @DataProvider(name = "batch_with_timeout")
    public Object[][] ackTimeoutSecProvider() {
        return new Object[][] { { 0, 0 }, { 0, 2 }, { 1000, 0 }, { 1000, 2 } };
    }

    @DataProvider(name = "batchingEnabled")
    public Object[][] batchingEnabled() {
        return new Object[][] { { true }, { false } };
    }

    @Test(dataProvider = "batch_with_timeout")
    public void testSyncProducerAndConsumer(int batchMessageDelayMs, int ackTimeoutSec) throws Exception {
        log.info("-- Starting {} test --", methodName);
        ConsumerBuilder<byte[]> consumerBuilder = pulsarClient.newConsumer()
                .topic("persistent://my-property/tp1/my-ns/my-topic1").subscriptionName("my-subscriber-name");

        // Cumulative Ack-counter works if ackTimeOutTimer-task is enabled
        boolean isAckTimeoutTaskEnabledForCumulativeAck = ackTimeoutSec > 0;
        if (ackTimeoutSec > 0) {
            consumerBuilder.ackTimeout(ackTimeoutSec, TimeUnit.SECONDS);
        }

        Consumer<byte[]> consumer = consumerBuilder.subscribe();

        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer()
                .topic("persistent://my-property/tp1/my-ns/my-topic1");
        if (batchMessageDelayMs != 0) {
            producerBuilder.enableBatching(true).batchingMaxPublishDelay(batchMessageDelayMs, TimeUnit.MILLISECONDS)
                    .batchingMaxMessages(5);
        }

        Producer<byte[]> producer = producerBuilder.create();

        int numMessages = 11;
        for (int i = 0; i < numMessages; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        Message<byte[]> msg = null;
        Set<String> messageSet = new HashSet<>();
        for (int i = 0; i < numMessages; i++) {
            msg = consumer.receive(5, TimeUnit.SECONDS);
            String receivedMessage = new String(msg.getData());
            log.info("Received message: [{}]", receivedMessage);
            String expectedMessage = "my-message-" + i;
            testMessageOrderAndDuplicates(messageSet, receivedMessage, expectedMessage);
        }
        // Acknowledge the consumption of all messages at once
        consumer.acknowledgeCumulative(msg);
        Thread.sleep(2000);
        consumer.close();
        producer.close();
        validatingLogInfo(consumer, producer, isAckTimeoutTaskEnabledForCumulativeAck);
        log.info("-- Exiting {} test --", methodName);
    }

    @Test(dataProvider = "batch_with_timeout")
    public void testAsyncProducerAndAsyncAck(int batchMessageDelayMs, int ackTimeoutSec) throws Exception {
        log.info("-- Starting {} test --", methodName);
        ConsumerBuilder<byte[]> consumerBuilder = pulsarClient.newConsumer()
                .topic("persistent://my-property/tp1/my-ns/my-topic2").subscriptionName("my-subscriber-name");
        if (ackTimeoutSec > 0) {
            consumerBuilder.ackTimeout(ackTimeoutSec, TimeUnit.SECONDS);
        }

        Consumer<byte[]> consumer = consumerBuilder.subscribe();

        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer()
                .topic("persistent://my-property/tp1/my-ns/my-topic2")
                .messageRoutingMode(MessageRoutingMode.SinglePartition);
        if (batchMessageDelayMs != 0) {
            producerBuilder.enableBatching(true).batchingMaxPublishDelay(batchMessageDelayMs, TimeUnit.MILLISECONDS)
                    .batchingMaxMessages(5);
        } else {
            producerBuilder.enableBatching(false);
        }

        Producer<byte[]> producer = producerBuilder.create();
        List<Future<MessageId>> futures = new ArrayList<>();

        int numMessages = 50;
        // Asynchronously produce messages
        for (int i = 0; i < numMessages; i++) {
            final String message = "my-message-" + i;
            Future<MessageId> future = producer.sendAsync(message.getBytes());
            futures.add(future);
        }

        log.info("Waiting for async publish to complete");
        for (Future<MessageId> future : futures) {
            future.get();
        }

        Message<byte[]> msg = null;
        Set<String> messageSet = new HashSet<>();
        for (int i = 0; i < numMessages; i++) {
            msg = consumer.receive(5, TimeUnit.SECONDS);
            String receivedMessage = new String(msg.getData());
            String expectedMessage = "my-message-" + i;
            testMessageOrderAndDuplicates(messageSet, receivedMessage, expectedMessage);
        }

        // Asynchronously acknowledge upto and including the last message
        Future<Void> ackFuture = consumer.acknowledgeCumulativeAsync(msg);
        log.info("Waiting for async ack to complete");
        ackFuture.get();
        Thread.sleep(2000);
        consumer.close();
        producer.close();
        validatingLogInfo(consumer, producer, batchMessageDelayMs == 0 && ackTimeoutSec > 0);
        log.info("-- Exiting {} test --", methodName);
    }

    @Test(dataProvider = "batch_with_timeout")
    public void testAsyncProducerAndReceiveAsyncAndAsyncAck(int batchMessageDelayMs, int ackTimeoutSec)
            throws Exception {
        log.info("-- Starting {} test --", methodName);
        ConsumerBuilder<byte[]> consumerBuilder = pulsarClient.newConsumer()
                .topic("persistent://my-property/tp1/my-ns/my-topic2").subscriptionName("my-subscriber-name");
        if (ackTimeoutSec > 0) {
            consumerBuilder.ackTimeout(ackTimeoutSec, TimeUnit.SECONDS);
        }

        Consumer<byte[]> consumer = consumerBuilder.subscribe();

        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer()
                .topic("persistent://my-property/tp1/my-ns/my-topic2")
                .messageRoutingMode(MessageRoutingMode.SinglePartition);
        if (batchMessageDelayMs != 0) {
            producerBuilder.enableBatching(true).batchingMaxPublishDelay(batchMessageDelayMs, TimeUnit.MILLISECONDS)
                    .batchingMaxMessages(5);
        } else {
            producerBuilder.enableBatching(false);
        }

        Producer<byte[]> producer = producerBuilder.create();
        List<Future<MessageId>> futures = new ArrayList<>();

        int numMessages = 101;
        // Asynchronously produce messages
        for (int i = 0; i < numMessages; i++) {
            final String message = "my-message-" + i;
            Future<MessageId> future = producer.sendAsync(message.getBytes());
            futures.add(future);
        }

        log.info("Waiting for async publish to complete");
        for (Future<MessageId> future : futures) {
            future.get();
        }
        Message<byte[]> msg = null;
        CompletableFuture<Message<byte[]>> futureMsg = null;
        Set<String> messageSet = new HashSet<>();
        for (int i = 0; i < numMessages; i++) {
            futureMsg = consumer.receiveAsync();
            Thread.sleep(10);
            msg = futureMsg.get();
            String receivedMessage = new String(msg.getData());
            log.info("Received message: [{}]", receivedMessage);
            String expectedMessage = "my-message-" + i;
            testMessageOrderAndDuplicates(messageSet, receivedMessage, expectedMessage);
        }

        // Asynchronously acknowledge upto and including the last message
        Future<Void> ackFuture = consumer.acknowledgeCumulativeAsync(msg);
        log.info("Waiting for async ack to complete");
        ackFuture.get();
        Thread.sleep(5000);
        consumer.close();
        producer.close();
        validatingLogInfo(consumer, producer, batchMessageDelayMs == 0 && ackTimeoutSec > 0);
        log.info("-- Exiting {} test --", methodName);
    }

    @Test(dataProvider = "batch", timeOut = 100000)
    public void testMessageListener(int batchMessageDelayMs) throws Exception {
        log.info("-- Starting {} test --", methodName);

        int numMessages = 100;
        final CountDownLatch latch = new CountDownLatch(numMessages);

        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic("persistent://my-property/tp1/my-ns/my-topic3")
                .subscriptionName("my-subscriber-name").ackTimeout(100, TimeUnit.SECONDS)
                .messageListener((consumer1, msg) -> {
                    assertNotNull(msg, "Message cannot be null");
                    String receivedMessage = new String(msg.getData());
                    log.debug("Received message [{}] in the listener", receivedMessage);
                    consumer1.acknowledgeAsync(msg);
                    latch.countDown();
                }).subscribe();

        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer()
                .topic("persistent://my-property/tp1/my-ns/my-topic3");
        if (batchMessageDelayMs != 0) {
            producerBuilder.enableBatching(true).batchingMaxPublishDelay(batchMessageDelayMs, TimeUnit.MILLISECONDS)
                    .batchingMaxMessages(5);
        }

        Producer<byte[]> producer = producerBuilder.create();
        List<Future<MessageId>> futures = new ArrayList<>();

        // Asynchronously produce messages
        for (int i = 0; i < numMessages; i++) {
            final String message = "my-message-" + i;
            Future<MessageId> future = producer.sendAsync(message.getBytes());
            futures.add(future);
        }

        log.info("Waiting for async publish to complete");
        for (Future<MessageId> future : futures) {
            future.get();
        }
        Thread.sleep(5000);
        log.info("Waiting for message listener to ack all messages");
        assertTrue(latch.await(numMessages, TimeUnit.SECONDS), "Timed out waiting for message listener acks");
        consumer.close();
        producer.close();
        validatingLogInfo(consumer, producer, true);
        log.info("-- Exiting {} test --", methodName);
    }

    @Test(dataProvider = "batch")
    public void testSendTimeout(int batchMessageDelayMs) throws Exception {
        log.info("-- Starting {} test --", methodName);

        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic("persistent://my-property/tp1/my-ns/my-topic5")
                .subscriptionName("my-subscriber-name").subscribe();

        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer()
                .topic("persistent://my-property/tp1/my-ns/my-topic5").sendTimeout(1, TimeUnit.SECONDS);
        if (batchMessageDelayMs != 0) {
            producerBuilder.enableBatching(true)
                    .batchingMaxPublishDelay(2L * batchMessageDelayMs, TimeUnit.MILLISECONDS)
                    .batchingMaxMessages(5);
        }

        Producer<byte[]> producer = producerBuilder.create();

        final String message = "my-message";

        // Trigger the send timeout
        stopBroker();

        Future<MessageId> future = producer.sendAsync(message.getBytes());

        try {
            future.get();
            fail("Send operation should have failed");
        } catch (ExecutionException e) {
            // Expected
        }

        startBroker();

        // We should not have received any message
        Message<byte[]> msg = consumer.receive(3, TimeUnit.SECONDS);
        assertNull(msg);
        consumer.close();
        producer.close();
        Thread.sleep(1000);
        ConsumerStats cStat = consumer.getStats();
        ProducerStats pStat = producer.getStats();
        assertEquals(pStat.getTotalMsgsSent(), 0);
        assertEquals(pStat.getTotalSendFailed(), 1);
        assertEquals(cStat.getTotalMsgsReceived(), 0);
        assertEquals(cStat.getTotalMsgsReceived(), cStat.getTotalAcksSent());
        log.info("-- Exiting {} test --", methodName);
    }

    @Test
    public void testBatchMessagesRateOut() throws PulsarClientException, InterruptedException, PulsarAdminException {
        log.info("-- Starting {} test --", methodName);
        String topicName = "persistent://my-property/cluster/my-ns/testBatchMessagesRateOut";
        double produceRate = 17;
        int batchSize = 5;
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName).subscriptionName("my-subscriber-name")
                .subscribe();
        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName).batchingMaxMessages(batchSize)
                .enableBatching(true).batchingMaxPublishDelay(2, TimeUnit.SECONDS).create();
        AtomicBoolean runTest = new AtomicBoolean(true);
        Thread t1 = new Thread(() -> {
            RateLimiter r = RateLimiter.create(produceRate);
            while (runTest.get()) {
                r.acquire();
                producer.sendAsync("Hello World".getBytes());
                consumer.receiveAsync().thenAccept(consumer::acknowledgeAsync);
            }
        });
        t1.start();
        Thread.sleep(2000); // Two seconds sleep
        runTest.set(false);
        pulsar.getBrokerService().updateRates();
        double actualRate = admin.topics().getStats(topicName).getMsgRateOut();
        assertTrue(actualRate > (produceRate / batchSize));
        consumer.unsubscribe();
        log.info("-- Exiting {} test --", methodName);
    }

    private void validatingLogInfo(Consumer<?> consumer, Producer<?> producer, boolean verifyAckCount)
            throws InterruptedException {
        // Waiting for recording last stat info
        Thread.sleep(1000);
        ConsumerStats cStat = consumer.getStats();
        ProducerStats pStat = producer.getStats();
        assertEquals(pStat.getTotalMsgsSent(), cStat.getTotalMsgsReceived());
        assertEquals(pStat.getTotalBytesSent(), cStat.getTotalBytesReceived());
        assertEquals(pStat.getTotalMsgsSent(), pStat.getTotalAcksReceived());
        if (verifyAckCount) {
            assertEquals(cStat.getTotalMsgsReceived(), cStat.getTotalAcksSent());
        }
    }

    @Test
    public void testAddBrokerLatencyStats() throws Exception {

        log.info("-- Starting {} test --", methodName);

        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer()
                .topic("persistent://my-property/tp1/my-ns/my-topic1");

        Producer<byte[]> producer = producerBuilder.create();

        int numMessages = 120;
        for (int i = 0; i < numMessages; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        pulsar.getBrokerService().updateRates();

        String json = admin.brokerStats().getMetrics();
        JsonArray metrics = new Gson().fromJson(json, JsonArray.class);

        boolean latencyCaptured = false;
        for (int i = 0; i < metrics.size(); i++) {
            try {
                String data = metrics.get(i).getAsJsonObject().get("metrics").toString();
                if (data.contains(NamespaceStats.BRK_ADD_ENTRY_LATENCY_PREFIX)) {
                    JsonObject stat = metrics.get(i).getAsJsonObject().get("metrics").getAsJsonObject();
                    for (String key : stat.keySet()) {
                        if (key.startsWith(NamespaceStats.BRK_ADD_ENTRY_LATENCY_PREFIX)) {
                            double val = stat.get(key).getAsDouble();
                            if (val > 0.0) {
                                latencyCaptured = true;
                            }
                        }
                    }
                    System.out.println(stat.toString());
                }
            } catch (Exception e) {
                //Ok
            }
        }

        assertTrue(latencyCaptured);
        producer.close();
        log.info("-- Exiting {} test --", methodName);
    }

    @Test(dataProvider =  "batchingEnabled")
    public void testProducerPendingQueueSizeStats(boolean batchingEnabled) throws Exception {
        log.info("-- Starting {} test --", methodName);
        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer()
                .topic("persistent://my-property/tp1/my-ns/my-topic1");

        @Cleanup
        Producer<byte[]> producer = producerBuilder.enableBatching(batchingEnabled).create();

        stopBroker();

        int numMessages = 120;
        for (int i = 0; i < numMessages; i++) {
            String message = "my-message-" + i;
            producer.sendAsync(message.getBytes());
        }
        Awaitility.await().timeout(2, TimeUnit.MINUTES)
                .until(() -> producer.getStats().getPendingQueueSize() == numMessages);
        assertEquals(producer.getStats().getPendingQueueSize(), numMessages);
    }

    /**
     * This test verifies partitioned topic stats for producer and consumer.
     * @throws Exception
     */
    @Test
    public void testPartitionTopicStats() throws Exception {
        log.info("-- Starting {} test --", methodName);

        String topicName = "persistent://my-property/my-ns/testPartitionTopicStats";
        int numPartitions = 10;
        admin.topics().createPartitionedTopic(topicName, numPartitions);

        ConsumerBuilder<byte[]> consumerBuilder = pulsarClient.newConsumer().topic(topicName)
                .subscriptionName("my-subscriber-name");

        Consumer<byte[]> consumer = consumerBuilder.subscribe();

        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer().enableBatching(false).topic(topicName);

        Producer<byte[]> producer = producerBuilder.create();

        int numMessages = 20;
        for (int i = 0; i < numMessages; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        Message<byte[]> msg = null;
        Set<String> messageSet = new HashSet<>();
        for (int i = 0; i < numMessages; i++) {
            msg = consumer.receive(5, TimeUnit.SECONDS);
            String receivedMessage = new String(msg.getData());
            log.info("Received message: [{}]", receivedMessage);
            Assert.assertTrue(messageSet.add(receivedMessage), "Received duplicate message " + receivedMessage);
        }
        Assert.assertEquals(messageSet.size(), numMessages);

        // Acknowledge the consumption of all messages at once
        consumer.acknowledgeCumulative(msg);

        ConsumerStats cStat = consumer.getStats();
        ProducerStats pStat = producer.getStats();
        retryStrategically((test) -> !pStat.getPartitionStats().isEmpty(), 5, 100);
        retryStrategically((test) -> !cStat.getPartitionStats().isEmpty(), 5, 100);
        Map<String, ProducerStats> prodStatsMap = pStat.getPartitionStats();
        Map<String, ConsumerStats> consStatsMap = cStat.getPartitionStats();
        assertFalse(prodStatsMap.isEmpty());
        assertFalse(consStatsMap.isEmpty());
        for (int i = 0; i < numPartitions; i++) {
            String topic = topicName + "-partition-" + i;
            assertTrue(prodStatsMap.containsKey(topic));
            assertTrue(consStatsMap.containsKey(topic));
        }

        consumer.close();
        producer.close();

        log.info("-- Exiting {} test --", methodName);
    }

    @Test
    public void testMsgRateExpired() throws Exception {
        log.info("-- Starting {} test --", methodName);

        String topicName = "persistent://my-property/tp1/my-ns/" + methodName;
        String subName = "my-sub";
        admin.topics().createSubscription(topicName, subName, MessageId.latest);

        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topicName)
                .enableBatching(false)
                .create();

        int numMessages = 100;
        for (int i = 0; i < numMessages; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        Thread.sleep(2000);
        admin.topics().expireMessages(topicName, subName, 1);
        pulsar.getBrokerService().updateRates();

        Awaitility.await().ignoreExceptions().timeout(10, TimeUnit.SECONDS)
                .until(() -> pulsar.getBrokerService().getTopicStats().get(topicName)
                        .getSubscriptions().get(subName).getTotalMsgExpired() > 0);

        Awaitility.await().ignoreExceptions().timeout(10, TimeUnit.SECONDS).until(() -> {
            pulsar.getBrokerService().updateRates();
            return pulsar.getBrokerService().getTopicStats().get(topicName)
                    .getSubscriptions().get(subName).getMsgRateExpired() < 0.001;
        });

        assertEquals(pulsar.getBrokerService().getTopicStats().get(topicName)
                        .getSubscriptions().get(subName).getMsgRateExpired(), 0.0, 0.001);
        assertEquals(pulsar.getBrokerService().getTopicStats().get(topicName)
                        .getSubscriptions().get(subName).getTotalMsgExpired(), numMessages);

        log.info("-- Exiting {} test --", methodName);
    }

    @Test
    public void testRetryLetterAndDeadLetterStats() throws PulsarClientException, InterruptedException {
        final String topicName = "persistent://my-property/my-ns/testRetryLetterAndDeadLetterStats";

        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(topicName)
                .subscriptionType(SubscriptionType.Shared)
                .negativeAckRedeliveryDelay(100, TimeUnit.MILLISECONDS)
                .enableRetry(true)
                .deadLetterPolicy(DeadLetterPolicy.builder()
                        .maxRedeliverCount(3)
                        .retryLetterTopic("persistent://my-property/my-ns/retry-topic")
                        .deadLetterTopic("persistent://my-property/my-ns/dlq-topic")
                        .build())
                .subscriptionName("sub")
                .subscribe();

        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topicName)
                .create();

        final int messages = 1;
        for (int i = 0; i < messages; i++) {
            producer.send(("message-" + i).getBytes());
        }

        for (int i = 0; i < messages * 4; i++) {
            // nack and reconsumeLater
            Message msg = consumer.receive(1, TimeUnit.SECONDS);
            if (msg != null) {
                consumer.reconsumeLater(msg, 100, TimeUnit.MILLISECONDS);
            }
        }

        Awaitility.await().untilAsserted(() -> {
            ConsumerStats stats = consumer.getStats();
            ProducerStats retryStats = stats.getRetryLetterProducerStats();
            ProducerStats deadLetterStats = stats.getDeadLetterProducerStats();
            assertNotNull(retryStats);
            assertNotNull(deadLetterStats);
            assertEquals(retryStats.getTotalMsgsSent(), 3);
            assertEquals(deadLetterStats.getTotalMsgsSent(), 1);
        });
    }
    @Test
    public void testDeadLetterStats() throws PulsarClientException, InterruptedException {
        final String topicName = "persistent://my-property/my-ns/testDeadLetterStats";

        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(topicName)
                .subscriptionType(SubscriptionType.Shared)
                .negativeAckRedeliveryDelay(100, TimeUnit.MILLISECONDS)
                .deadLetterPolicy(DeadLetterPolicy.builder()
                        .maxRedeliverCount(1)
                        .deadLetterTopic("persistent://my-property/my-ns/dlq-topic")
                        .build())
                .subscriptionName("sub")
                .subscribe();

        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topicName)
                .create();

        final int messages = 1;
        for (int i = 0; i < messages; i++) {
            producer.send(("message-" + i).getBytes());
        }

        for (int i = 0; i < messages * 2; i++) {
            // nack and reconsumeLater
            Message msg = consumer.receive(1, TimeUnit.SECONDS);
            if (msg != null) {
                consumer.negativeAcknowledge(msg);
            }
        }

        Awaitility.await().untilAsserted(() -> {
            ConsumerStats stats = consumer.getStats();
            ProducerStats dlqStats = stats.getDeadLetterProducerStats();
            assertNotNull(dlqStats);
            assertEquals(dlqStats.getTotalMsgsSent(), 1);
        });
    }

    @Test
    public void testPartitionedRetryLetterAndDeadLetterStats()
            throws PulsarClientException, InterruptedException, PulsarAdminException {
        final String topicName = "persistent://my-property/my-ns/testPartitionedRetryLetterAndDeadLetterStats";

        admin.topics().createPartitionedTopic(topicName, 10);
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(topicName)
                .subscriptionType(SubscriptionType.Shared)
                .negativeAckRedeliveryDelay(100, TimeUnit.MILLISECONDS)
                .enableRetry(true)
                .deadLetterPolicy(DeadLetterPolicy.builder()
                        .maxRedeliverCount(3)
                        .retryLetterTopic("persistent://my-property/my-ns/retry-topic")
                        .deadLetterTopic("persistent://my-property/my-ns/dlq-topic")
                        .build())
                .subscriptionName("sub")
                .subscribe();

        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topicName)
                .messageRoutingMode(MessageRoutingMode.RoundRobinPartition)
                .create();

        final int messages = 30;
        for (int i = 0; i < messages; i++) {
            producer.send(("message-" + i).getBytes());
        }

        for (int i = 0; i < messages * 4; i++) {
            // nack and reconsumeLater
            Message msg = consumer.receive(1, TimeUnit.SECONDS);
            if (msg != null) {
                consumer.reconsumeLater(msg, 100, TimeUnit.MILLISECONDS);
            }
        }

        Awaitility.await().untilAsserted(() -> {
            ConsumerStats stats = consumer.getStats();
            ProducerStats retryStats = stats.getRetryLetterProducerStats();
            ProducerStats deadLetterStats = stats.getDeadLetterProducerStats();
            assertNotNull(retryStats);
            assertNotNull(deadLetterStats);
            assertEquals(retryStats.getTotalMsgsSent(), 3 * messages);
            assertEquals(deadLetterStats.getTotalMsgsSent(), messages);
        });
    }
}
