/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.routing.allocation.decider;

import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.cluster.ClusterInfo;
import org.elasticsearch.cluster.ClusterInfoService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.DiskUsage;
import org.elasticsearch.cluster.MockInternalClusterInfoService;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.allocation.DiskThresholdSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertBlocked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchHits;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0)
public class MockDiskUsagesIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        // Use the mock internal cluster info service, which has fake-able disk usages
        return Collections.singletonList(MockInternalClusterInfoService.TestPlugin.class);
    }

    public void testRerouteOccursOnDiskPassingHighWatermark() throws Exception {
        List<String> nodes = internalCluster().startNodes(3);

        // Start with all nodes at 50% usage
        final MockInternalClusterInfoService cis = (MockInternalClusterInfoService)
            internalCluster().getInstance(ClusterInfoService.class, internalCluster().getMasterName());
        cis.setUpdateFrequency(TimeValue.timeValueMillis(200));
        cis.onMaster();
        cis.setN1Usage(nodes.get(0), new DiskUsage(nodes.get(0), "n1", "/dev/null", 100, 50));
        cis.setN2Usage(nodes.get(1), new DiskUsage(nodes.get(1), "n2", "/dev/null", 100, 50));
        cis.setN3Usage(nodes.get(2), new DiskUsage(nodes.get(2), "n3", "/dev/null", 100, 50));

        final boolean watermarkBytes = randomBoolean(); // we have to consistently use bytes or percentage for the disk watermark settings
        client().admin().cluster().prepareUpdateSettings().setTransientSettings(Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), watermarkBytes ? "20b" : "80%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), watermarkBytes ? "10b" : "90%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(),
                watermarkBytes ? "0b" : "100%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.getKey(), "1ms")).get();
        // Create an index with 10 shards so we can check allocation for it
        prepareCreate("test").setSettings(Settings.builder()
            .put("number_of_shards", 10)
            .put("number_of_replicas", 0)).get();
        ensureGreen("test");

        // Block until the "fake" cluster info is retrieved at least once
        assertBusy(() -> {
            final ClusterInfo info = cis.getClusterInfo();
            logger.info("--> got: {} nodes", info.getNodeLeastAvailableDiskUsages().size());
            assertThat(info.getNodeLeastAvailableDiskUsages().size(), greaterThan(0));
        });

        final List<String> realNodeNames = new ArrayList<>();
        {
            final ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
            for (final RoutingNode node : clusterState.getRoutingNodes()) {
                realNodeNames.add(node.nodeId());
                logger.info("--> node {} has {} shards",
                    node.nodeId(), clusterState.getRoutingNodes().node(node.nodeId()).numberOfOwningShards());
            }
        }

        // Update the disk usages so one node has now passed the high watermark
        cis.setN1Usage(realNodeNames.get(0), new DiskUsage(nodes.get(0), "n1", "_na_", 100, 50));
        cis.setN2Usage(realNodeNames.get(1), new DiskUsage(nodes.get(1), "n2", "_na_", 100, 50));
        cis.setN3Usage(realNodeNames.get(2), new DiskUsage(nodes.get(2), "n3", "_na_", 100, 0)); // nothing free on node3

        logger.info("--> waiting for shards to relocate off node [{}]", realNodeNames.get(2));

        assertBusy(() -> {
            final ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
            final Map<String, Integer> nodesToShardCount = new HashMap<>();
            for (final RoutingNode node : clusterState.getRoutingNodes()) {
                logger.info("--> node {} has {} shards",
                    node.nodeId(), clusterState.getRoutingNodes().node(node.nodeId()).numberOfOwningShards());
                nodesToShardCount.put(node.nodeId(), clusterState.getRoutingNodes().node(node.nodeId()).numberOfOwningShards());
            }
            assertThat("node1 has 5 shards", nodesToShardCount.get(realNodeNames.get(0)), equalTo(5));
            assertThat("node2 has 5 shards", nodesToShardCount.get(realNodeNames.get(1)), equalTo(5));
            assertThat("node3 has 0 shards", nodesToShardCount.get(realNodeNames.get(2)), equalTo(0));
        });

        // Update the disk usages so one node is now back under the high watermark
        cis.setN1Usage(realNodeNames.get(0), new DiskUsage(nodes.get(0), "n1", "_na_", 100, 50));
        cis.setN2Usage(realNodeNames.get(1), new DiskUsage(nodes.get(1), "n2", "_na_", 100, 50));
        cis.setN3Usage(realNodeNames.get(2), new DiskUsage(nodes.get(2), "n3", "_na_", 100, 50)); // node3 has free space now

        logger.info("--> waiting for shards to rebalance back onto node [{}]", realNodeNames.get(2));

        assertBusy(() -> {
            final Map<String, Integer> nodesToShardCount = new HashMap<>();
            final ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
            for (final RoutingNode node : clusterState.getRoutingNodes()) {
                logger.info("--> node {} has {} shards",
                    node.nodeId(), clusterState.getRoutingNodes().node(node.nodeId()).numberOfOwningShards());
                nodesToShardCount.put(node.nodeId(), clusterState.getRoutingNodes().node(node.nodeId()).numberOfOwningShards());
            }
            assertThat("node1 has at least 3 shards", nodesToShardCount.get(realNodeNames.get(0)), greaterThanOrEqualTo(3));
            assertThat("node2 has at least 3 shards", nodesToShardCount.get(realNodeNames.get(1)), greaterThanOrEqualTo(3));
            assertThat("node3 has at least 3 shards", nodesToShardCount.get(realNodeNames.get(2)), greaterThanOrEqualTo(3));
        });
    }

    public void testAutomaticReleaseOfIndexBlock() throws Exception {
        List<String> nodes = internalCluster().startNodes(3);

        // Wait for all 3 nodes to be up
        assertBusy(() -> {
            NodesStatsResponse resp = client().admin().cluster().prepareNodesStats().get();
            assertThat(resp.getNodes().size(), equalTo(3));
        });

        // Start with all nodes at 50% usage
        final MockInternalClusterInfoService cis = (MockInternalClusterInfoService)
            internalCluster().getInstance(ClusterInfoService.class, internalCluster().getMasterName());
        cis.setUpdateFrequency(TimeValue.timeValueMillis(100));
        cis.onMaster();
        cis.setN1Usage(nodes.get(0), new DiskUsage(nodes.get(0), "n1", "/dev/null", 100, 50));
        cis.setN2Usage(nodes.get(1), new DiskUsage(nodes.get(1), "n2", "/dev/null", 100, 50));
        cis.setN3Usage(nodes.get(2), new DiskUsage(nodes.get(2), "n3", "/dev/null", 100, 50));

        final boolean watermarkBytes = randomBoolean(); // we have to consistently use bytes or percentage for the disk watermark settings
        client().admin().cluster().prepareUpdateSettings().setTransientSettings(Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), watermarkBytes ? "15b" : "85%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), watermarkBytes ? "10b" : "90%")
            .put(
                DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(),
                watermarkBytes ? "5b" : "95%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.getKey(), "150ms")).get();
        // Create an index with 6 shards so we can check allocation for it
        prepareCreate("test").setSettings(Settings.builder()
            .put("number_of_shards", 6)
            .put("number_of_replicas", 0)).get();
        ensureGreen("test");

        // Block until the "fake" cluster info is retrieved at least once
        assertBusy(() -> {
            ClusterInfo info = cis.getClusterInfo();
            logger.info("--> got: {} nodes", info.getNodeLeastAvailableDiskUsages().size());
            assertThat(info.getNodeLeastAvailableDiskUsages().size(), greaterThan(0));
        });

        final List<String> realNodeNames = new ArrayList<>();
        ClusterStateResponse resp = client().admin().cluster().prepareState().get();
        Iterator<RoutingNode> iter = resp.getState().getRoutingNodes().iterator();
        while (iter.hasNext()) {
            RoutingNode node = iter.next();
            realNodeNames.add(node.nodeId());
            logger.info("--> node {} has {} shards",
                node.nodeId(), resp.getState().getRoutingNodes().node(node.nodeId()).numberOfOwningShards());
        }

        client().prepareIndex("test", "doc", "1").setSource("{\"foo\": \"bar\"}", XContentType.JSON)
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
        assertSearchHits(client().prepareSearch().get(), "1");

        // Block all nodes so that re-balancing does not occur (BalancedShardsAllocator)
        cis.setN1Usage(realNodeNames.get(0), new DiskUsage(nodes.get(0), "n1", "_na_", 100, 3));
        cis.setN2Usage(realNodeNames.get(1), new DiskUsage(nodes.get(1), "n2", "_na_", 100, 3));
        cis.setN3Usage(realNodeNames.get(2), new DiskUsage(nodes.get(2), "n3", "_na_", 100, 3));

        // Wait until index "test" is blocked
        assertBusy(() -> {
            assertBlocked(client().prepareIndex().setIndex("test").setType("doc").setId("1").setSource("foo", "bar"),
                IndexMetaData.INDEX_READ_ONLY_ALLOW_DELETE_BLOCK);
        });

        // Cannot add further documents
        assertBlocked(client().prepareIndex().setIndex("test").setType("doc").setId("2").setSource("foo", "bar"),
            IndexMetaData.INDEX_READ_ONLY_ALLOW_DELETE_BLOCK);
        assertSearchHits(client().prepareSearch().get(), "1");

        // Update the disk usages so all nodes are back under the high and flood watermarks
        cis.setN1Usage(realNodeNames.get(0), new DiskUsage(nodes.get(0), "n1", "_na_", 100, 11));
        cis.setN2Usage(realNodeNames.get(1), new DiskUsage(nodes.get(1), "n2", "_na_", 100, 11));
        cis.setN3Usage(realNodeNames.get(2), new DiskUsage(nodes.get(2), "n3", "_na_", 100, 11));

        // Attempt to create a new document until DiskUsageMonitor unblocks the index
        assertBusy(() -> {
            try {
                client().prepareIndex("test", "doc", "3").setSource("{\"foo\": \"bar\"}", XContentType.JSON)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
            } catch (ClusterBlockException e) {
                throw new AssertionError("retrying", e);
            }
        });
        assertSearchHits(client().prepareSearch().get(), "1", "3");
    }

}
