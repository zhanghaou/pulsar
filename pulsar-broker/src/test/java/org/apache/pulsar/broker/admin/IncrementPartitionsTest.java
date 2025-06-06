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
package org.apache.pulsar.broker.admin;

import static org.testng.Assert.assertEquals;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Cleanup;
import org.apache.pulsar.broker.BrokerTestUtil;
import org.apache.pulsar.broker.admin.AdminApiTest.MockedPulsarService;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.awaitility.Awaitility;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test(groups = "broker-admin")
public class IncrementPartitionsTest extends MockedPulsarServiceBaseTest {

    private MockedPulsarService mockPulsarSetup;

    @BeforeMethod
    @Override
    public void setup() throws Exception {
        conf.setLoadBalancerEnabled(true);
        super.internalSetup();

        // create otherbroker to test redirect on calls that need
        // namespace ownership
        mockPulsarSetup = new MockedPulsarService(this.conf);
        mockPulsarSetup.setup();

        // Setup namespaces
        admin.clusters().createCluster("use", ClusterData.builder().serviceUrl(pulsar.getWebServiceAddress()).build());
        TenantInfoImpl tenantInfo = new TenantInfoImpl(Set.of("role1", "role2"), Set.of("use"));
        admin.tenants().createTenant("prop-xyz", tenantInfo);
        admin.namespaces().createNamespace("prop-xyz/use/ns1");

        // Setup v2 namespaces
        setupDefaultTenantAndNamespace();
    }

    @AfterMethod(alwaysRun = true)
    @Override
    public void cleanup() throws Exception {
        super.internalCleanup();
        mockPulsarSetup.cleanup();
    }

    @Test
    public void testIncrementPartitionsOfTopicOnUnusedTopic() throws Exception {
        final String partitionedTopicName = "persistent://prop-xyz/use/ns1/test-topic";

        admin.topics().createPartitionedTopic(partitionedTopicName, 10);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 10);

        admin.topics().updatePartitionedTopic(partitionedTopicName, 20);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 20);
    }

    @Test
    public void testIncrementPartitionsOfTopic() throws Exception {
        final String partitionedTopicName = "persistent://prop-xyz/use/ns1/test-topic-2";

        admin.topics().createPartitionedTopic(partitionedTopicName, 1);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 1);

        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(partitionedTopicName).subscriptionName("sub-1")
                    .subscribe();

        admin.topics().updatePartitionedTopic(partitionedTopicName, 2);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 2);

        admin.topics().updatePartitionedTopic(partitionedTopicName, 10);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 10);

        admin.topics().updatePartitionedTopic(partitionedTopicName, 20);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 20);

        AtomicReference<Map<String, ? extends TopicStats>> partitionsStats = new AtomicReference<>();
        Awaitility.await().until(() -> {
            partitionsStats.set(admin.topics().getPartitionedStats(partitionedTopicName, true).getPartitions());
            return partitionsStats.get().size() == 20;
        });
        for (TopicStats topic : partitionsStats.get().values()) {
                assertEquals(topic.getSubscriptions().keySet(), List.of("sub-1"));
        }

        consumer.close();
    }

    @Test
    public void testIncrementPartitionsOfTopicWithSubscriptionProperties() throws Exception {
        final String partitionedTopicName = UUID.randomUUID()
                + "-testIncrementPartitionsOfTopicWithSubscriptionProperties";

        admin.topics().createPartitionedTopic(partitionedTopicName, 1);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 1);

        Map<String, String> properties = new HashMap<>();
        properties.put("method", "testIncrementPartitionsOfTopic");

        @Cleanup
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(partitionedTopicName)
                .subscriptionName("sub-1")
                .subscriptionProperties(properties)
                .subscribe();

        admin.topics().updatePartitionedTopic(partitionedTopicName, 20);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 20);

        assertEquals(admin.topics().getSubscriptions(
                TopicName.get(partitionedTopicName).getPartition(15).toString()), List.of("sub-1"));
        TopicStats stats = admin.topics()
                .getStats(TopicName.get(partitionedTopicName).getPartition(15).toString());
        Map<String, String> subscriptionProperties = stats.getSubscriptions()
                .get("sub-1").getSubscriptionProperties();
        Assert.assertEquals(properties, subscriptionProperties);
    }

    @Test
    public void testIncrementPartitionsWithNoSubscriptions() throws Exception {
        final String partitionedTopicName =
                BrokerTestUtil.newUniqueName("persistent://prop-xyz/use/ns1/test-topic");

        admin.topics().createPartitionedTopic(partitionedTopicName, 1);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 1);

        @Cleanup
        Producer<String> consumer = pulsarClient.newProducer(Schema.STRING)
                .topic(partitionedTopicName)
                .create();

        admin.topics().updatePartitionedTopic(partitionedTopicName, 2);
        //zk update takes some time
        Awaitility.await().untilAsserted(() ->
                assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 2));

        admin.topics().updatePartitionedTopic(partitionedTopicName, 10);
        Awaitility.await().untilAsserted(() ->
                assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 10));

        admin.topics().updatePartitionedTopic(partitionedTopicName, 20);
        Awaitility.await().untilAsserted(() ->
                assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 20));
    }

    @Test
    public void testIncrementPartitionsWithReaders() throws Exception {
        TopicName partitionedTopicName = TopicName.get(
                BrokerTestUtil.newUniqueName("persistent://prop-xyz/use/ns1/test-topic"));

        admin.topics().createPartitionedTopic(partitionedTopicName.toString(), 1);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName.toString()).partitions, 1);

        @Cleanup
        Producer<String> consumer = pulsarClient.newProducer(Schema.STRING)
                .topic(partitionedTopicName.toString())
                .create();

        @Cleanup
        Reader<String> reader = pulsarClient.newReader(Schema.STRING)
                .topic(partitionedTopicName.getPartition(0).toString())
                .startMessageId(MessageId.earliest)
            .create();

        admin.topics().updatePartitionedTopic(partitionedTopicName.toString(), 2);
        assertEquals(admin.topics().getPartitionedTopicMetadata(partitionedTopicName.toString()).partitions, 2);

        assertEquals(admin.topics().getSubscriptions(partitionedTopicName.getPartition(0).toString()).size(), 1);

        // Partition-1 should not have subscriptions
        assertEquals(admin.topics().getSubscriptions(partitionedTopicName.getPartition(1).toString()),
                Collections.emptyList());
    }
}
