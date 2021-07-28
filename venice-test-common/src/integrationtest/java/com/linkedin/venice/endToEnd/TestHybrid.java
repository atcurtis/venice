package com.linkedin.venice.endToEnd;

import com.github.benmanes.caffeine.cache.Cache;
import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.controller.Admin;
import com.linkedin.venice.controller.VeniceHelixAdmin;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.ControllerResponse;
import com.linkedin.venice.controllerapi.JobStatusQueryResponse;
import com.linkedin.venice.controllerapi.StoreResponse;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.controllerapi.VersionCreationResponse;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.hadoop.VenicePushJob;
import com.linkedin.venice.helix.HelixBaseRoutingRepository;
import com.linkedin.venice.helix.HelixPartitionState;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceControllerWrapper;
import com.linkedin.venice.integration.utils.VeniceServerWrapper;
import com.linkedin.venice.integration.utils.ZkServerWrapper;
import com.linkedin.venice.kafka.TopicManager;
import com.linkedin.venice.kafka.protocol.GUID;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.kafka.protocol.LeaderMetadata;
import com.linkedin.venice.kafka.protocol.ProducerMetadata;
import com.linkedin.venice.kafka.protocol.Put;
import com.linkedin.venice.kafka.protocol.enums.MessageType;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.meta.BufferReplayPolicy;
import com.linkedin.venice.meta.DataReplicationPolicy;
import com.linkedin.venice.meta.HybridStoreConfig;
import com.linkedin.venice.meta.HybridStoreConfigImpl;
import com.linkedin.venice.meta.IngestionMode;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.InstanceStatus;
import com.linkedin.venice.meta.PersistenceType;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.StoreStatus;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.meta.ZKStore;
import com.linkedin.venice.systemstore.schemas.StoreProperties;
import com.linkedin.venice.replication.TopicReplicator;
import com.linkedin.venice.samza.SamzaExitMode;
import com.linkedin.venice.samza.VeniceSystemFactory;
import com.linkedin.venice.samza.VeniceSystemProducer;
import com.linkedin.venice.serializer.AvroGenericDeserializer;
import com.linkedin.venice.serializer.AvroSerializer;
import com.linkedin.venice.utils.DataProviderUtils;
import com.linkedin.venice.utils.Pair;
import com.linkedin.venice.utils.TestPushUtils;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.writer.VeniceWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.avro.Schema;
import org.apache.avro.util.Utf8;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.helix.HelixAdmin;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.model.CustomizedStateConfig;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.log4j.Logger;
import org.apache.samza.config.MapConfig;
import org.apache.samza.system.SystemProducer;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.linkedin.davinci.store.rocksdb.RocksDBServerConfig.*;
import static com.linkedin.venice.ConfigKeys.*;
import static com.linkedin.venice.integration.utils.VeniceClusterWrapper.*;
import static com.linkedin.venice.kafka.TopicManager.*;
import static com.linkedin.venice.meta.BufferReplayPolicy.*;
import static com.linkedin.venice.router.api.VenicePathParser.*;
import static com.linkedin.venice.utils.TestPushUtils.*;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class TestHybrid {
  private static final Logger logger = Logger.getLogger(TestHybrid.class);
  public static final int STREAMING_RECORD_SIZE = 1024;
  private static final long MIN_COMPACTION_LAG = 24 * Time.MS_PER_HOUR;

  /**
   * IMPORTANT NOTE: if you use this sharedVenice cluster, please do not close it. The {@link #tearDown()} function
   *                 will take care of it. Besides, if any backend component of the shared cluster is stopped in
   *                 the middle of the test, please restart them at the end of your test.
   */
  private VeniceClusterWrapper sharedVenice;
  private VeniceClusterWrapper ingestionIsolationEnabledSharedVenice;

  /**
   * This cluster is re-used by some of the tests, in order to speed up the suite. Some other tests require
   * certain specific characteristics which makes it awkward to re-use, though not necessarily impossible.
   * Further reuse of this shared cluster can be attempted later.
   */
  @BeforeClass(alwaysRun=true)
  public void setUp() {
    sharedVenice = setUpCluster(false);
    ingestionIsolationEnabledSharedVenice = setUpCluster(true);
  }

  @AfterClass(alwaysRun=true)
  public void tearDown() {
    Utils.closeQuietlyWithErrorLogged(sharedVenice);
    Utils.closeQuietlyWithErrorLogged(ingestionIsolationEnabledSharedVenice);
  }

  @DataProvider(name = "isLeaderFollowerModelEnabled", parallel = false)
  public static Object[][] isLeaderFollowerModelEnabled() {
    return new Object[][]{{false}, {true}};
  }

  @Test(dataProvider = "isLeaderFollowerModelEnabled", timeOut = 180 * Time.MS_PER_SECOND)
  public void testHybridInitializationOnMultiColo(boolean isLeaderFollowerModelEnabled) throws IOException {
    Properties extraProperties = new Properties();
    extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(3L));
    extraProperties.setProperty(ROCKSDB_PLAIN_TABLE_FORMAT_ENABLED, "false");
    extraProperties.setProperty(SERVER_DATABASE_CHECKSUM_VERIFICATION_ENABLED, "true");
    extraProperties.setProperty(SERVER_DATABASE_SYNC_BYTES_INTERNAL_FOR_DEFERRED_WRITE_MODE, "300");
    try (VeniceClusterWrapper venice = ServiceFactory.getVeniceCluster(1,2,1,1, 1000000, false, false, extraProperties);
        ZkServerWrapper parentZk = ServiceFactory.getZkServer();
        VeniceControllerWrapper parentController = ServiceFactory.getVeniceParentController(
            venice.getClusterName(), parentZk.getAddress(), venice.getKafka(), new VeniceControllerWrapper[]{venice.getMasterVeniceController()}, false);
        ControllerClient controllerClient = new ControllerClient(venice.getClusterName(), parentController.getControllerUrl());
        TopicManager topicManager = new TopicManager(DEFAULT_KAFKA_OPERATION_TIMEOUT_MS, 100, 0l, TestUtils.getVeniceConsumerFactory(venice.getKafka()))
    ) {
      long streamingRewindSeconds = 25L;
      long streamingMessageLag = 2L;
      final String storeName = TestUtils.getUniqueString("multi-colo-hybrid-store");

      //Create store at parent, make it a hybrid store
      controllerClient.createNewStore(storeName, "owner", STRING_SCHEMA, STRING_SCHEMA);
      controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
          .setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA)
          .setHybridRewindSeconds(streamingRewindSeconds)
          .setHybridOffsetLagThreshold(streamingMessageLag)
          .setLeaderFollowerModel(isLeaderFollowerModelEnabled)
      );

      HybridStoreConfig hybridStoreConfig = new HybridStoreConfigImpl(streamingRewindSeconds, streamingMessageLag,
          HybridStoreConfigImpl.DEFAULT_HYBRID_TIME_LAG_THRESHOLD, DataReplicationPolicy.NON_AGGREGATE,
          REWIND_FROM_EOP);
      // There should be no version on the store yet
      assertEquals(controllerClient.getStore(storeName).getStore().getCurrentVersion(),
          0, "The newly created store must have a current version of 0");

      // Create a new version, and do an empty push for that version
      VersionCreationResponse vcr = controllerClient.emptyPush(storeName, TestUtils.getUniqueString("empty-hybrid-push"), 1L);
      int versionNumber = vcr.getVersion();
      assertNotEquals(versionNumber, 0, "requesting a topic for a push should provide a non zero version number");

      TestUtils.waitForNonDeterministicAssertion(100, TimeUnit.SECONDS, true, () -> {
        // Now the store should have version 1
        JobStatusQueryResponse jobStatus = controllerClient.queryJobStatus(Version.composeKafkaTopic(storeName, versionNumber));
        Assert.assertFalse(jobStatus.isError(), "Error in getting JobStatusResponse: " + jobStatus.getError());
        assertEquals(jobStatus.getStatus(), "COMPLETED");
      });

      //And real-time topic should exist now.
      assertTrue(topicManager.containsTopicAndAllPartitionsAreOnline(Version.composeRealTimeTopic(storeName)));
      // Creating a store object with default values since we're not updating bootstrap to online timeout
      StoreProperties storeProperties = Store.prefillAvroRecordWithDefaultValue(new StoreProperties());
      storeProperties.name = storeName;
      storeProperties.owner = "owner";
      storeProperties.createdTime = System.currentTimeMillis();
      Store store = new ZKStore(storeProperties);
      assertEquals(topicManager.getTopicRetention(Version.composeRealTimeTopic(storeName)),
          TopicManager.getExpectedRetentionTimeInMs(store, hybridStoreConfig), "RT retention not configured properly");
      // Make sure RT retention is updated when the rewind time is updated
      long newStreamingRewindSeconds = 600;
      hybridStoreConfig.setRewindTimeInSeconds(newStreamingRewindSeconds);
      controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
          .setHybridRewindSeconds(newStreamingRewindSeconds));
      assertEquals(topicManager.getTopicRetention(Version.composeRealTimeTopic(storeName)),
          TopicManager.getExpectedRetentionTimeInMs(store, hybridStoreConfig), "RT retention not updated properly");
    }
  }

  /**
   * N.B.: Non-L/F does not support chunking, so this permutation is skipped.
   */
  @DataProvider(name = "testPermutations", parallel = false)
  public static Object[][] testPermutations() {
    return new Object[][]{
        {false, false, false, REWIND_FROM_EOP},
        {false, true, false, REWIND_FROM_EOP},
        {false, true, true, REWIND_FROM_EOP},
        {true, false, false, REWIND_FROM_EOP},
        {true, true, false, REWIND_FROM_EOP},
        {true, true, true, REWIND_FROM_EOP},
        {false, false, false, REWIND_FROM_SOP},
        {false, true, false, REWIND_FROM_SOP},
        {false, true, true, REWIND_FROM_SOP},
        {true, false, false, REWIND_FROM_SOP},
        {true, true, false, REWIND_FROM_SOP},
        {true, true, true, REWIND_FROM_SOP}
    };
  }

  /**
   * This test validates the hybrid batch + streaming semantics and verifies that configured rewind time works as expected.
   *
   * TODO: This test needs to be refactored in order to leverage {@link com.linkedin.venice.utils.MockTime},
   *       which would allow the test to run faster and more deterministically.

   * @param multiDivStream if false, rewind will happen in the middle of a DIV Segment, which was originally broken.
   *                       if true, two independent DIV Segments will be placed before and after the start of buffer replay.
   *
   *                       If this test succeeds with {@param multiDivStream} set to true, but fails with it set to false,
   *                       then there is a regression in the DIV partial segment tolerance after EOP.
   * @param isLeaderFollowerModelEnabled Whether to enable Leader/Follower state transition model.
   * @param chunkingEnabled Whether chunking should be enabled (only supported in {@param isLeaderFollowerModelEnabled} is true).
   */
  @Test(dataProvider = "testPermutations", timeOut = 180 * Time.MS_PER_SECOND, groups = {"flaky"})
  public void testHybridEndToEnd(boolean multiDivStream, boolean isLeaderFollowerModelEnabled, boolean chunkingEnabled, BufferReplayPolicy bufferReplayPolicy) throws Exception {
    logger.info("About to create VeniceClusterWrapper");
    Properties extraProperties = new Properties();
    if (isLeaderFollowerModelEnabled) {
      extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(3L));
    }
    if (chunkingEnabled) {
      // We exercise chunking by setting the servers' max size arbitrarily low. For now, since the RT topic
      // does not support chunking, and write compute is not merged yet, there is no other way to make the
      // store-version data bigger than the RT data and thus have chunked values produced.
      int maxMessageSizeInServer = STREAMING_RECORD_SIZE / 2;
      extraProperties.setProperty(VeniceWriter.MAX_SIZE_FOR_USER_PAYLOAD_PER_MESSAGE_IN_BYTES, Integer.toString(maxMessageSizeInServer));
    }

    SystemProducer veniceProducer = null;

    // N.B.: RF 2 with 2 servers is important, in order to test both the leader and follower code paths
    VeniceClusterWrapper venice = sharedVenice;
    try {
      logger.info("Finished creating VeniceClusterWrapper");

      long streamingRewindSeconds = 10L;
      long streamingMessageLag = 2L;

      String storeName = TestUtils.getUniqueString("hybrid-store");
      File inputDir = getTempDataDirectory();
      String inputDirPath = "file://" + inputDir.getAbsolutePath();
      Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir); // records 1-100
      Properties h2vProperties = defaultH2VProps(venice, inputDirPath, storeName);

      try (ControllerClient controllerClient = createStoreForJob(venice, recordSchema, h2vProperties);
           AvroGenericStoreClient client = ClientFactory.getAndStartGenericAvroClient(
               ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(venice.getRandomRouterURL()));
           TopicManager topicManager = new TopicManager(
               DEFAULT_KAFKA_OPERATION_TIMEOUT_MS,
               100,
               MIN_COMPACTION_LAG,
               TestUtils.getVeniceConsumerFactory(venice.getKafka()))) {

        Cache cacheNothingCache = mock(Cache.class);
        Mockito.when(cacheNothingCache.getIfPresent(Mockito.any())).thenReturn(null);
        topicManager.setTopicConfigCache(cacheNothingCache);

        ControllerResponse response = controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
                                                                                  .setHybridRewindSeconds(streamingRewindSeconds)
                                                                                  .setHybridOffsetLagThreshold(streamingMessageLag)
                                                                                  .setLeaderFollowerModel(isLeaderFollowerModelEnabled)
                                                                                  .setChunkingEnabled(chunkingEnabled)
                                                                                  .setHybridBufferReplayPolicy(bufferReplayPolicy)
        );

        Assert.assertFalse(response.isError());

        //Do an H2V push
        runH2V(h2vProperties, 1, controllerClient);

        // verify the topic compaction policy
        String topicForStoreVersion1 = Version.composeKafkaTopic(storeName, 1);
        Assert.assertTrue(topicManager.isTopicCompactionEnabled(topicForStoreVersion1), "topic: " + topicForStoreVersion1 + " should have compaction enabled");
        Assert.assertEquals(topicManager.getTopicMinLogCompactionLagMs(topicForStoreVersion1), MIN_COMPACTION_LAG, "topic:" + topicForStoreVersion1 + " should have min compaction lag config set to " + MIN_COMPACTION_LAG);

        //Verify some records (note, records 1-100 have been pushed)
        TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, true, () -> {
          try {
            for (int i = 1; i < 100; i++) {
              String key = Integer.toString(i);
              Object value = client.get(key).get();
              assertNotNull(value, "Key " + i + " should not be missing!");
              assertEquals(value.toString(), "test_name_" + key);
            }
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });

        //write streaming records
        veniceProducer = getSamzaProducer(venice, storeName, Version.PushType.STREAM);
        for (int i = 1; i <= 10; i++) {
          // The batch values are small, but the streaming records are "big" (i.e.: not that big, but bigger than
          // the server's max configured chunk size). In the scenario where chunking is disabled, the server's
          // max chunk size is not altered, and thus this will be under threshold.
          sendCustomSizeStreamingRecord(veniceProducer, storeName, i, STREAMING_RECORD_SIZE);
        }
        if (multiDivStream) {
          veniceProducer.stop(); //close out the DIV segment
        }

        TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
          try {
            checkLargeRecord(client, 2);
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });

        runH2V(h2vProperties, 2, controllerClient);
        // verify the topic compaction policy
        String topicForStoreVersion2 = Version.composeKafkaTopic(storeName, 2);
        Assert.assertTrue(topicManager.isTopicCompactionEnabled(topicForStoreVersion2), "topic: " + topicForStoreVersion2 + " should have compaction enabled");
        Assert.assertEquals(topicManager.getTopicMinLogCompactionLagMs(topicForStoreVersion2), MIN_COMPACTION_LAG, "topic:" + topicForStoreVersion2 + " should have min compaction lag config set to " + MIN_COMPACTION_LAG);

        // Verify streaming record in second version
        checkLargeRecord(client, 2);
        assertEquals(client.get("19").get().toString(), "test_name_19");

        // TODO: Would be great to eliminate this wait time...
        logger.info("***** Sleeping to get outside of rewind time: " + streamingRewindSeconds + " seconds");
        Utils.sleep(TimeUnit.MILLISECONDS.convert(streamingRewindSeconds, TimeUnit.SECONDS));

        // Write more streaming records
        if (multiDivStream) {
          veniceProducer = getSamzaProducer(venice, storeName, Version.PushType.STREAM); // new producer, new DIV segment.
        }
        for (int i = 10; i <= 20; i++) {
          sendCustomSizeStreamingRecord(veniceProducer, storeName, i, STREAMING_RECORD_SIZE);
        }
        TestUtils.waitForNonDeterministicAssertion(15, TimeUnit.SECONDS, () -> {
          try {
            checkLargeRecord(client, 19);
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });

        // Run H2V a third Time
        runH2V(h2vProperties, 3, controllerClient);
        // verify the topic compaction policy
        String topicForStoreVersion3 = Version.composeKafkaTopic(storeName, 3);
        Assert.assertTrue(topicManager.isTopicCompactionEnabled(topicForStoreVersion3), "topic: " + topicForStoreVersion3 + " should have compaction enabled");

        // Verify new streaming record in third version
        TestUtils.waitForNonDeterministicAssertion(15, TimeUnit.SECONDS, () -> {
          try {
            checkLargeRecord(client, 19);
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });
        // But not old streaming record (because we waited the rewind time)
        assertEquals(client.get("2").get().toString(), "test_name_2");

        TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, () -> {
          StoreResponse storeResponse = controllerClient.getStore(storeName);
          assertFalse(storeResponse.isError());
          List<Integer> versions =
              storeResponse.getStore().getVersions().stream().map(Version::getNumber).collect(Collectors.toList());
          assertFalse(versions.contains(1), "After version 3 comes online, version 1 should be retired");
          assertTrue(versions.contains(2));
          assertTrue(versions.contains(3));
        });

        /**
         * For L/F model, {@link com.linkedin.venice.replication.LeaderStorageNodeReplicator#doesReplicationExist(String, String)}
         * would always return false.
         */
        if (!isLeaderFollowerModelEnabled) {
          // Verify replication exists for versions 2 and 3, but not for version 1
          VeniceHelixAdmin veniceHelixAdmin = (VeniceHelixAdmin) venice.getMasterVeniceController().getVeniceAdmin();
          Field topicReplicatorField = veniceHelixAdmin.getClass().getDeclaredField("onlineOfflineTopicReplicator");
          topicReplicatorField.setAccessible(true);
          Optional<TopicReplicator> topicReplicatorOptional = (Optional<TopicReplicator>) topicReplicatorField.get(veniceHelixAdmin);
          if (topicReplicatorOptional.isPresent()) {
            TopicReplicator topicReplicator = topicReplicatorOptional.get();
            String realtimeTopic = Version.composeRealTimeTopic(storeName);
            String versionOneTopic = Version.composeKafkaTopic(storeName, 1);
            String versionTwoTopic = Version.composeKafkaTopic(storeName, 2);
            String versionThreeTopic = Version.composeKafkaTopic(storeName, 3);

            assertFalse(topicReplicator.doesReplicationExist(realtimeTopic, versionOneTopic), "Replication stream must not exist for retired version 1");
            assertTrue(topicReplicator.doesReplicationExist(realtimeTopic, versionTwoTopic), "Replication stream must still exist for backup version 2");
            assertTrue(topicReplicator.doesReplicationExist(realtimeTopic, versionThreeTopic), "Replication stream must still exist for current version 3");
          } else {
            fail("Venice cluster must have a topic replicator for hybrid to be operational"); //this shouldn't happen
          }
        }

        controllerClient.listInstancesStatuses().getInstancesStatusMap().keySet().stream()
            .forEach(s -> logger.info("Replicas for " + s + ": "
                                          + Arrays.toString(controllerClient.listStorageNodeReplicas(s).getReplicas())));

        // TODO will move this test case to a single fail-over integration test.
        //Stop one server
        int port = venice.getVeniceServers().get(0).getPort();
        venice.stopVeniceServer(port);
        TestUtils.waitForNonDeterministicAssertion(15, TimeUnit.SECONDS, true, () -> {
          // Make sure Helix knows the instance is shutdown
          Map<String, String> storeStatus = controllerClient.listStoresStatuses().getStoreStatusMap();
          Assert.assertTrue(storeStatus.get(storeName).equals(StoreStatus.UNDER_REPLICATED.toString()),
              "Should be UNDER_REPLICATED");

          Map<String, String> instanceStatus = controllerClient.listInstancesStatuses().getInstancesStatusMap();
          Assert.assertTrue(instanceStatus.entrySet().stream()
                                .filter(entry -> entry.getKey().contains(Integer.toString(port)))
                                .map(entry -> entry.getValue())
                                .allMatch(s -> s.equals(InstanceStatus.DISCONNECTED.toString())),
              "Storage Node on port " + port + " should be DISCONNECTED");
        });

        //Restart one server
        venice.restartVeniceServer(port);
        TestUtils.waitForNonDeterministicAssertion(15, TimeUnit.SECONDS, true, () -> {
          // Make sure Helix knows the instance has recovered
          Map<String, String> storeStatus = controllerClient.listStoresStatuses().getStoreStatusMap();
          Assert.assertTrue(storeStatus.get(storeName).equals(StoreStatus.FULLLY_REPLICATED.toString()),
              "Should be FULLLY_REPLICATED");
        });
      }
    } finally {
      if (null != veniceProducer) {
        veniceProducer.stop();
      }
    }
  }

  private void checkLargeRecord(AvroGenericStoreClient client, int index)
      throws ExecutionException, InterruptedException {
    String key = Integer.toString(index);
    String value = client.get(key).get().toString();
    assertEquals(value.length(), STREAMING_RECORD_SIZE);

    String expectedChar = Integer.toString(index).substring(0, 1);
    for (int j = 0; j < value.length(); j++) {
      assertEquals(value.substring(j, j + 1), expectedChar);
    }
  }

  /**
   * A comprehensive integration test for GF job. We set up RF to be 2 in the cluster and spin up 3 SNs nodes here.
   * 2 RF is required to be the correctness for both leader and follower's behavior. A spare SN is also added for
   * testing whether the flow can work while the original leader dies.
   *
   * @param isLeaderFollowerModelEnabled true if the resource is running in L/F model. Otherwise, the resource is running in O/O model.
   *                                     Pass-through mode is enabled during L/F model and we have extra check for it.
   */
  @Test(dataProvider = "isLeaderFollowerModelEnabled", timeOut = 180 * Time.MS_PER_SECOND)
  public void testSamzaBatchLoad(boolean isLeaderFollowerModelEnabled) throws Exception {
    Properties extraProperties = new Properties();
    extraProperties.setProperty(PERSISTENCE_TYPE, PersistenceType.ROCKS_DB.name());
    extraProperties.setProperty(ROCKSDB_PLAIN_TABLE_FORMAT_ENABLED, "false");
    extraProperties.setProperty(SERVER_AUTO_COMPACTION_FOR_SAMZA_REPROCESSING_JOB_ENABLED, "false");
    extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(1L));

    SystemProducer veniceBatchProducer = null;
    try (VeniceClusterWrapper veniceClusterWrapper = ServiceFactory.getVeniceCluster(1,3,
        1,2, 1000000, false, false, extraProperties)) {
      try {
        Admin admin = veniceClusterWrapper.getMasterVeniceController().getVeniceAdmin();
        String clusterName = veniceClusterWrapper.getClusterName();
        String storeName = TestUtils.getUniqueString("test-store");
        long streamingRewindSeconds = 25L;
        long streamingMessageLag = 2L;

        // Create empty store
        admin.addStore(clusterName, storeName, "tester", "\"string\"", "\"string\"");
        admin.updateStore(clusterName, storeName, new UpdateStoreQueryParams()
                                                      .setPartitionCount(1)
                                                      .setHybridRewindSeconds(streamingRewindSeconds)
                                                      .setHybridOffsetLagThreshold(streamingMessageLag)
                                                      .setLeaderFollowerModel(isLeaderFollowerModelEnabled)
        );
        TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
          Assert.assertFalse(admin.getStore(clusterName, storeName).containsVersion(1));
          Assert.assertEquals(admin.getStore(clusterName, storeName).getCurrentVersion(), 0);
        });

        // Batch load from Samza
        VeniceSystemFactory factory = new VeniceSystemFactory();
        Version.PushType pushType = isLeaderFollowerModelEnabled ? Version.PushType.STREAM_REPROCESSING : Version.PushType.BATCH;
        Map<String, String> samzaConfig = getSamzaProducerConfig(veniceClusterWrapper, storeName, pushType);
        veniceBatchProducer = factory.getProducer("venice", new MapConfig(samzaConfig), null);
        veniceBatchProducer.start();
        if (veniceBatchProducer instanceof VeniceSystemProducer) {
          // The default behavior would exit the process
          ((VeniceSystemProducer) veniceBatchProducer).setExitMode(SamzaExitMode.NO_OP);
        }

        // Purposefully out of order, because Samza batch jobs should be allowed to write out of order
        for (int i = 10; i >= 1; i--) {
          sendStreamingRecord(veniceBatchProducer, storeName, i);
        }

        TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
          Assert.assertTrue(admin.getStore(clusterName, storeName).containsVersion(1));
          Assert.assertEquals(admin.getStore(clusterName, storeName).getCurrentVersion(), 0);
        });

        //while running in L/F model, we try to stop the original SN; let Helix elect a new leader and push some extra
        //data here. This is for testing "pass-through" mode is working properly
        if (isLeaderFollowerModelEnabled) {
          //wait a little time to make sure the leader has re-produced all existing messages
          long waitTime = TimeUnit.SECONDS.toMillis(Integer
                                                        .parseInt(extraProperties.getProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS)) + 2);
          Utils.sleep(waitTime);

          String resourceName = Version.composeKafkaTopic(storeName, 1);
          HelixBaseRoutingRepository routingDataRepo =
              veniceClusterWrapper.getRandomVeniceRouter().getRoutingDataRepository();
          TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, false, true, () -> {
              Instance leaderNode = routingDataRepo.getLeaderInstance(resourceName, 0);
              Assert.assertNotNull(leaderNode);
          });
          Instance oldLeaderNode = routingDataRepo.getLeaderInstance(resourceName, 0);

          veniceClusterWrapper.stopVeniceServer(oldLeaderNode.getPort());
          TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, false, true, () -> {
            Instance newLeaderNode = routingDataRepo.getLeaderInstance(resourceName, 0);
            Assert.assertNotNull(newLeaderNode);
            Assert.assertNotEquals(oldLeaderNode.getPort(), newLeaderNode.getPort());
            Assert.assertTrue(routingDataRepo
                                  .getPartitionAssignments(resourceName).getPartition(0).getWorkingInstances().size() == 2);
          });
        }

        // Before EOP, the Samza batch producer should still be in active state
        Assert.assertEquals(factory.getNumberOfActiveSystemProducers(), 1);

        if (isLeaderFollowerModelEnabled) {
          for (int i = 31; i <= 40; i++) {
            sendStreamingRecord(veniceBatchProducer, storeName, i);
          }
        }

        /**
         * Use the same VeniceWriter to write END_OF_PUSH message, which will guarantee the message order in topic
         */
        ((VeniceSystemProducer)veniceBatchProducer).getInternalProducer().broadcastEndOfPush(new HashMap<>());

        TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, false, true, () -> {
          Assert.assertTrue(admin.getStore(clusterName, storeName).containsVersion(1));
          Assert.assertEquals(admin.getStore(clusterName, storeName).getCurrentVersion(), 1);
          if (isLeaderFollowerModelEnabled) {
            // After EOP, the push monitor inside the system producer would mark the producer as inactive in the factory
            Assert.assertEquals(factory.getNumberOfActiveSystemProducers(), 0);
          }
        });

        SystemProducer veniceStreamProducer = getSamzaProducer(veniceClusterWrapper, storeName, Version.PushType.STREAM);
        try (AvroGenericStoreClient client = ClientFactory.getAndStartGenericAvroClient(
            ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(veniceClusterWrapper.getRandomRouterURL()))) {
          TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, false, true, () -> {
            // Verify data, note only 1-10 have been pushed so far
            for (int i = 1; i <= 10; i++) {
              String key = Integer.toString(i);
              Object value = client.get(key).get();
              Assert.assertNotNull(value);
              Assert.assertEquals(value.toString(), "stream_" + key);
            }

            Assert.assertNull(client.get(Integer.toString(11)).get(), "This record should not be found");

            if (isLeaderFollowerModelEnabled) {
              for (int i = 31; i <= 40; i++) {
                String key = Integer.toString(i);
                Assert.assertEquals(client.get(key).get().toString(), "stream_" + key);
              }
            }

            // Switch to stream mode and push more data
            veniceStreamProducer.start();
            for (int i = 11; i <= 20; i++) {
              sendStreamingRecord(veniceStreamProducer, storeName, i);
            }
            Assert.assertTrue(admin.getStore(clusterName, storeName).containsVersion(1));
            Assert.assertFalse(admin.getStore(clusterName, storeName).containsVersion(2));
            Assert.assertEquals(admin.getStore(clusterName, storeName).getCurrentVersion(), 1);

            // Verify both batch and stream data
            /**
             * Leader would wait for 5 seconds before switching to real-time topic.
             */
            long extraWaitTime = isLeaderFollowerModelEnabled ? TimeUnit.SECONDS.toMillis(Long.valueOf(extraProperties.getProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS)))
                : 0L;
            long normalTimeForConsuming = TimeUnit.SECONDS.toMillis(3);
            logger.info("normalTimeForConsuming:" + normalTimeForConsuming + "; extraWaitTime:" + extraWaitTime);
            Utils.sleep(normalTimeForConsuming + extraWaitTime);
            for (int i = 1; i < 20; i++) {
              String key = Integer.toString(i);
              Assert.assertEquals(client.get(key).get().toString(), "stream_" + key);
            }
            Assert.assertNull(client.get(Integer.toString(21)).get(), "This record should not be found");
          });
        } finally {
          if (null != veniceStreamProducer) {
            veniceStreamProducer.stop();
          }
        }
      } finally {
        if (null != veniceBatchProducer) {
          veniceBatchProducer.stop();
        }
      }
    }
  }

  @Test(timeOut = 180 * Time.MS_PER_SECOND)
  public void testMultiStreamReprocessingSystemProducers() {
    SystemProducer veniceBatchProducer1 = null, veniceBatchProducer2 = null;
    try {
      VeniceClusterWrapper veniceClusterWrapper = sharedVenice;
      Admin admin = veniceClusterWrapper.getMasterVeniceController().getVeniceAdmin();
      String clusterName = veniceClusterWrapper.getClusterName();
      String storeName1 = TestUtils.getUniqueString("test-store1");
      String storeName2 = TestUtils.getUniqueString("test-store2");
      long streamingRewindSeconds = 25L;
      long streamingMessageLag = 2L;

      // create 2 stores
      // Create empty store
      admin.addStore(clusterName, storeName1, "tester", "\"string\"", "\"string\"");
      admin.addStore(clusterName, storeName2, "tester", "\"string\"", "\"string\"");
      UpdateStoreQueryParams storeSettings = new UpdateStoreQueryParams()
          .setHybridRewindSeconds(streamingRewindSeconds)
          .setHybridOffsetLagThreshold(streamingMessageLag)
          .setLeaderFollowerModel(true);
      admin.updateStore(clusterName, storeName1, storeSettings);
      admin.updateStore(clusterName, storeName2, storeSettings);
      Assert.assertFalse(admin.getStore(clusterName, storeName1).containsVersion(1));
      Assert.assertEquals(admin.getStore(clusterName, storeName1).getCurrentVersion(), 0);
      Assert.assertFalse(admin.getStore(clusterName, storeName2).containsVersion(1));
      Assert.assertEquals(admin.getStore(clusterName, storeName2).getCurrentVersion(), 0);

      // Batch load from Samza to both stores
      VeniceSystemFactory factory = new VeniceSystemFactory();
      Map<String, String> samzaConfig1 = getSamzaProducerConfig(veniceClusterWrapper, storeName1, Version.PushType.STREAM_REPROCESSING);
      veniceBatchProducer1 = factory.getProducer("venice", new MapConfig(samzaConfig1), null);
      veniceBatchProducer1.start();
      Map<String, String> samzaConfig2 = getSamzaProducerConfig(veniceClusterWrapper, storeName2, Version.PushType.STREAM_REPROCESSING);
      veniceBatchProducer2 = factory.getProducer("venice", new MapConfig(samzaConfig2), null);
      veniceBatchProducer2.start();
      if (veniceBatchProducer1 instanceof VeniceSystemProducer) {
        // The default behavior would exit the process
        ((VeniceSystemProducer) veniceBatchProducer1).setExitMode(SamzaExitMode.NO_OP);
      }
      if (veniceBatchProducer2 instanceof VeniceSystemProducer) {
        // The default behavior would exit the process
        ((VeniceSystemProducer) veniceBatchProducer2).setExitMode(SamzaExitMode.NO_OP);
      }

      for (int i = 10; i >= 1; i--) {
        sendStreamingRecord(veniceBatchProducer1, storeName1, i);
        sendStreamingRecord(veniceBatchProducer2, storeName2, i);
      }

      // Before EOP, there should be 2 active producers
      Assert.assertEquals(factory.getNumberOfActiveSystemProducers(), 2);
      /**
       * Send EOP to the first store, eventually the first SystemProducer will be marked as inactive
       * after push monitor poll the latest push job status from router.
       */
      Utils.sleep(500);
      veniceClusterWrapper.useControllerClient(c -> {
        c.writeEndOfPush(storeName1, 1);
        TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, () -> {
          Assert.assertTrue(admin.getStore(clusterName, storeName1).containsVersion(1));
          Assert.assertEquals(admin.getStore(clusterName, storeName1).getCurrentVersion(), 1);
          // The second SystemProducer should still be active
          Assert.assertEquals(factory.getNumberOfActiveSystemProducers(), 1);
        });

        c.writeEndOfPush(storeName2, 1);
        TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, () -> {
          Assert.assertTrue(admin.getStore(clusterName, storeName2).containsVersion(1));
          Assert.assertEquals(admin.getStore(clusterName, storeName2).getCurrentVersion(), 1);
          // There should be no active SystemProducer any more.
          Assert.assertEquals(factory.getNumberOfActiveSystemProducers(), 0);
        });
      });
    } finally {
      if (null != veniceBatchProducer1) {
        veniceBatchProducer1.stop();
      }
      if (null != veniceBatchProducer2) {
        veniceBatchProducer2.stop();
      }
    }
  }

  @Test(dataProvider = "isLeaderFollowerModelEnabled", timeOut = 180 * Time.MS_PER_SECOND, enabled = false)
  public void testHybridWithBufferReplayDisabled(boolean isLeaderFollowerModelEnabled) throws Exception {
    VeniceControllerWrapper addedControllerWrapper = null, originalControllerWrapper = null;
    try {
      VeniceClusterWrapper venice = sharedVenice;
      List<VeniceControllerWrapper> controllers = venice.getVeniceControllers();
      Assert.assertEquals(controllers.size(), 1, "There should only be one controller");
      originalControllerWrapper = controllers.get(0);

      // Create a controller with buffer replay disabled, and remove the previous controller
      Properties controllerProps = new Properties();
      controllerProps.put(CONTROLLER_SKIP_BUFFER_REPLAY_FOR_HYBRID, true);
      addedControllerWrapper = venice.addVeniceController(controllerProps);
      List<VeniceControllerWrapper> newControllers = venice.getVeniceControllers();
      Assert.assertEquals(newControllers.size(), 2, "There should be two controllers now");
      // Shutdown the original controller, now there is only one controller with config: buffer replay disabled.
      venice.stopVeniceController(originalControllerWrapper.getPort());

      long streamingRewindSeconds = 25L;
      long streamingMessageLag = 2L;

      String storeName = TestUtils.getUniqueString("hybrid-store");

      try (ControllerClient controllerClient = new ControllerClient(venice.getClusterName(), venice.getAllControllersURLs())) {
        //Create store , make it a hybrid store
        controllerClient.createNewStore(storeName, "owner", STRING_SCHEMA, STRING_SCHEMA);
        controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
            .setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA)
            .setHybridRewindSeconds(streamingRewindSeconds)
            .setHybridOffsetLagThreshold(streamingMessageLag)
            .setLeaderFollowerModel(isLeaderFollowerModelEnabled)
        );

        // Create a new version, and do an empty push for that version
        VersionCreationResponse vcr = controllerClient.emptyPush(storeName, TestUtils.getUniqueString("empty-hybrid-push"), 1L);
        assertFalse(vcr.isError(), "VersionCreationResponse error: " + vcr.getError());
        int versionNumber = vcr.getVersion();
        assertNotEquals(versionNumber, 0, "requesting a topic for a push should provide a non zero version number");
        int partitionCnt = vcr.getPartitions();

        // Continue to write more records to the version topic
        Properties veniceWriterProperties = new Properties();
        veniceWriterProperties.put(KAFKA_BOOTSTRAP_SERVERS, venice.getKafka().getAddress());
        try (VeniceWriter<byte[], byte[], byte[]> writer = TestUtils.getVeniceWriterFactory(veniceWriterProperties).createBasicVeniceWriter(Version.composeKafkaTopic(storeName, versionNumber))) {

          // Mock buffer replay message
          if (!isLeaderFollowerModelEnabled) {
            List<Long> bufferReplyOffsets = new ArrayList<>();
            for (int i = 0; i < partitionCnt; ++i) {
              bufferReplyOffsets.add(1L);
            }
            writer.broadcastStartOfBufferReplay(bufferReplyOffsets, "", "", new HashMap<>());
          }

          // Write 100 records
          AvroSerializer<String> stringSerializer = new AvroSerializer(Schema.parse(STRING_SCHEMA));
          for (int i = 1; i <= 100; ++i) {
            writer.put(stringSerializer.serialize("key_" + i), stringSerializer.serialize("value_" + i), 1);
          }
        }

        // Wait for up to 10 seconds
        TestUtils.waitForNonDeterministicAssertion(10 * 1000, TimeUnit.MILLISECONDS, () -> {
          StoreResponse store = controllerClient.getStore(storeName);
          Assert.assertEquals(store.getStore().getCurrentVersion(), 1);
        });

        //Verify some records (note, records 1-100 have been pushed)
        try (AvroGenericStoreClient client =
            ClientFactory.getAndStartGenericAvroClient(ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(venice.getRandomRouterURL()))) {
          for (int i = 1; i <= 10; i++){
            String key = "key_" + i;
            assertEquals(client.get(key).get().toString(), "value_" + i);
          }
        }

        // And real-time topic should not exist since buffer replay is skipped.
        try (TopicManager topicManager = new TopicManager(DEFAULT_KAFKA_OPERATION_TIMEOUT_MS, 100, 0l, TestUtils.getVeniceConsumerFactory(venice.getKafka()))) {
          assertFalse(topicManager.containsTopicAndAllPartitionsAreOnline(Version.composeRealTimeTopic(storeName)));
        }
      }
    } finally {
      // Restore the shared cluster to its original state...
      if (null != addedControllerWrapper) {
        sharedVenice.removeVeniceController(addedControllerWrapper.getPort());
      }
      if (!originalControllerWrapper.isRunning()) {
        sharedVenice.restartVeniceController(originalControllerWrapper.getPort());
      }
    }
  }

  @Test(timeOut = 180 * Time.MS_PER_SECOND)
  public void testLeaderHonorLastTopicSwitchMessage() throws Exception {
    Properties extraProperties = new Properties();
    extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(10L));
    try (VeniceClusterWrapper venice = ServiceFactory.getVeniceCluster(1,2,1,2, 1000000, false, false, extraProperties);
        ControllerClient controllerClient = new ControllerClient(venice.getClusterName(), venice.getAllControllersURLs())) {
      long streamingRewindSeconds = 25L;
      long streamingMessageLag = 2L;

      String storeName = TestUtils.getUniqueString("hybrid-store");

      //Create store , make it a hybrid store
      controllerClient.createNewStore(storeName, "owner", STRING_SCHEMA, STRING_SCHEMA);
      controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
          .setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA)
          .setHybridRewindSeconds(streamingRewindSeconds)
          .setHybridOffsetLagThreshold(streamingMessageLag)
          .setLeaderFollowerModel(true)
      );

      // Create a new version, and do an empty push for that version
      VersionCreationResponse vcr = TestUtils.assertCommand(controllerClient.emptyPush(storeName, TestUtils.getUniqueString("empty-hybrid-push"), 1L));
      int versionNumber = vcr.getVersion();
      Assert.assertEquals(versionNumber, 1, "Version number should become 1 after an empty-push");
      int partitionCnt = vcr.getPartitions();

      /**
       * Write 2 TopicSwitch messages into version topic:
       * TS1 (new topic: storeName_tmp1, startTime: {@link rewindStartTime})
       * TS2 (new topic: storeName_tmp2, startTime: {@link rewindStartTime})
       *
       * All messages in TS1 should not be replayed into VT and should not be queryable;
       * but messages in TS2 should be replayed and queryable.
       */
      String tmpTopic1 = storeName + "_tmp1_rt";
      String tmpTopic2 = storeName + "_tmp2_rt";
      TopicManager topicManager = venice.getMasterVeniceController().getVeniceAdmin().getTopicManager();
      topicManager.createTopic(tmpTopic1, partitionCnt, 1, true);
      topicManager.createTopic(tmpTopic2, partitionCnt, 1, true);

      /**
       *  Build a producer that writes to {@link tmpTopic1}
       */
      Properties veniceWriterProperties = new Properties();
      veniceWriterProperties.put(KAFKA_BOOTSTRAP_SERVERS, venice.getKafka().getAddress());
      AvroSerializer<String> stringSerializer = new AvroSerializer(Schema.parse(STRING_SCHEMA));
      try (VeniceWriter<byte[], byte[], byte[]> tmpWriter1 = TestUtils.getVeniceWriterFactory(veniceWriterProperties).createBasicVeniceWriter(tmpTopic1)) {
        // Write 10 records
        for (int i = 0; i < 10; ++i) {
          tmpWriter1.put(stringSerializer.serialize("key_" + i), stringSerializer.serialize("value_" + i), 1);
        }
      }

      /**
       *  Build a producer that writes to {@link tmpTopic2}
       */
      try (VeniceWriter<byte[], byte[], byte[]> tmpWriter2 = TestUtils.getVeniceWriterFactory(veniceWriterProperties).createBasicVeniceWriter(tmpTopic2)) {
        // Write 10 records
        for (int i = 10; i < 20; ++i) {
          tmpWriter2.put(stringSerializer.serialize("key_" + i), stringSerializer.serialize("value_" + i), 1);
        }
      }

      /**
       * Wait for leader to switch over to real-time topic
       */
      TestUtils.waitForNonDeterministicAssertion(20 * 1000, TimeUnit.MILLISECONDS, () -> {
        StoreResponse store = TestUtils.assertCommand(controllerClient.getStore(storeName));
        Assert.assertEquals(store.getStore().getCurrentVersion(), 1);
      });

      /**
       * Verify that all messages from {@link tmpTopic2} are in store and no message from {@link tmpTopic1} is in store.
       */
      try (AvroGenericStoreClient<String, Utf8> client =
          ClientFactory.getAndStartGenericAvroClient(ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(venice.getRandomRouterURL()));
          VeniceWriter<byte[], byte[], byte[]> realTimeTopicWriter = TestUtils.getVeniceWriterFactory(veniceWriterProperties).createBasicVeniceWriter(Version.composeRealTimeTopic(storeName));) {
        // Build a producer to produce 2 TS messages into RT
        realTimeTopicWriter.broadcastTopicSwitch(Collections.singletonList(venice.getKafka().getAddress()), tmpTopic1, -1L, null);
        realTimeTopicWriter.broadcastTopicSwitch(Collections.singletonList(venice.getKafka().getAddress()), tmpTopic2, -1L, null);

        TestUtils.waitForNonDeterministicAssertion(30 * 1000, TimeUnit.MILLISECONDS, () -> {
          // All messages from tmpTopic2 should exist
          try {
            for (int i = 10; i < 20; i++) {
              String key = "key_" + i;
              Assert.assertEquals(client.get(key).get(), new Utf8("value_" + i));
            }
          } catch (Exception e) {
            logger.error("Caught exception in client.get()", e);
            Assert.fail(e.getMessage());
          }

          // No message from tmpTopic1 should exist
          try {
            for (int i = 0; i < 10; i++) {
              String key = "key_" + i;
              Assert.assertNull(client.get(key).get());
            }
          } catch (Exception e) {
            logger.error("Caught exception in client.get()", e);
            Assert.fail(e.getMessage());
          }
        });
      }
    }
  }

  @Test(timeOut = 180 * Time.MS_PER_SECOND, dataProvider = "True-and-False", dataProviderClass = DataProviderUtils.class)
  public void testLeaderCanReleaseLatch(boolean isIngestionIsolationEnabled) {
    VeniceClusterWrapper veniceClusterWrapper = isIngestionIsolationEnabled ? ingestionIsolationEnabledSharedVenice : sharedVenice;
    Admin admin = veniceClusterWrapper.getMasterVeniceController().getVeniceAdmin();
    String clusterName = veniceClusterWrapper.getClusterName();
    String storeName = TestUtils.getUniqueString("test-store");

    SystemProducer producer = null;
    try (ControllerClient controllerClient = new ControllerClient(clusterName, veniceClusterWrapper.getAllControllersURLs())) {
      controllerClient.createNewStore(storeName, "owner", STRING_SCHEMA, STRING_SCHEMA);
      controllerClient.updateStore(storeName, new UpdateStoreQueryParams().setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA)
          .setHybridRewindSeconds(25L)
          .setHybridOffsetLagThreshold(1L)
          .setLeaderFollowerModel(true));

      // Create a new version, and do an empty push for that version
      controllerClient.emptyPush(storeName, TestUtils.getUniqueString("empty-hybrid-push"), 1L);

      //write a few of messages from the Samza
      producer = TestPushUtils.getSamzaProducer(veniceClusterWrapper, storeName, Version.PushType.STREAM);
      for (int i = 0; i < 10; i ++) {
        sendStreamingRecord(producer, storeName, i);
      }

      //make sure the v1 is online and all the writes have been consumed by the SN
      try (AvroGenericStoreClient client = ClientFactory.getAndStartGenericAvroClient(
          ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(veniceClusterWrapper.getRandomRouterURL()))) {
        TestUtils.waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, true, () ->
          Assert.assertEquals(admin.getStore(clusterName, storeName).getCurrentVersion(), 1));

        TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, true, () -> {
          try {
            for (int i = 0; i < 10; i++) {
              String key = Integer.toString(i);
              Object value = client.get(key).get();
              Assert.assertNotNull(value, "Did not find key " + i + " in store before restarting SN.");
              Assert.assertEquals(value.toString(), "stream_" + key);
            }
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });

        //stop the SN (leader) and write more messages
        VeniceServerWrapper serverWrapper = veniceClusterWrapper.getVeniceServers().get(0);
        veniceClusterWrapper.stopVeniceServer(serverWrapper.getPort());

        for (int i = 10; i < 20; i ++) {
          sendStreamingRecord(producer, storeName, i);
        }

        //restart the SN (leader). The node is supposed to be promoted to leader even with the offset lags.
        veniceClusterWrapper.restartVeniceServer(serverWrapper.getPort());

        String resourceName = Version.composeKafkaTopic(storeName, 1);
        HelixBaseRoutingRepository routingDataRepo = veniceClusterWrapper.getRandomVeniceRouter().getRoutingDataRepository();
        TestUtils.waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, true, () ->
          Assert.assertNotNull(routingDataRepo.getLeaderInstance(resourceName, 0)));

        TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, true, () -> {
          try {
            for (int i = 10; i < 20; i++) {
              String key = Integer.toString(i);
              Object value = client.get(key).get();
              Assert.assertNotNull(value, "Did not find key " + i + " in store after restarting SN.");
              Assert.assertEquals(value.toString(), "stream_" + key);
            }
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });
      }
    } finally {
      if (null != producer) {
        producer.stop();
      }
    }
  }

  @Test(timeOut = 180 * Time.MS_PER_SECOND)
  public void testHybridMultipleVersions() throws Exception {
    final Properties extraProperties = new Properties();
    extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(1L));
    final int partitionCount = 2;
    final int keyCount = 10;
    VeniceClusterWrapper cluster = sharedVenice;
    UpdateStoreQueryParams params = new UpdateStoreQueryParams()
        // set hybridRewindSecond to a big number so following versions won't ignore old records in RT
        .setHybridRewindSeconds(2000000)
        .setHybridOffsetLagThreshold(10)
        .setPartitionCount(partitionCount)
        .setLeaderFollowerModel(true);
    String storeName = TestUtils.getUniqueString("store");
    try (ControllerClient client = cluster.getControllerClient()) {
      client.createNewStore(storeName, "owner", DEFAULT_KEY_SCHEMA, DEFAULT_VALUE_SCHEMA);
      client.updateStore(storeName, params);
    }
    cluster.createVersion(storeName, DEFAULT_KEY_SCHEMA, DEFAULT_VALUE_SCHEMA, IntStream.range(0, keyCount).mapToObj(i -> new AbstractMap.SimpleEntry<>(i, i)));
    try (AvroGenericStoreClient client = ClientFactory.getAndStartGenericAvroClient(
        ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(cluster.getRandomRouterURL()))) {
      TestUtils.waitForNonDeterministicAssertion(20, TimeUnit.SECONDS, () -> {
        for (Integer i = 0; i < keyCount; i++) {
          assertEquals(client.get(i).get(), i);
        }
      });
      SystemProducer producer = TestPushUtils.getSamzaProducer(cluster, storeName, Version.PushType.STREAM);
      for (int i = 0; i < keyCount; i++) {
        TestPushUtils.sendStreamingRecord(producer, storeName, i, i + 1);
      }
      producer.stop();

      TestUtils.waitForNonDeterministicAssertion(20, TimeUnit.SECONDS, () -> {
        for (Integer i = 0; i < keyCount; i++) {
          assertEquals(client.get(i).get(), new Integer(i + 1));
        }
      });
      cluster.createVersion(storeName, DEFAULT_KEY_SCHEMA, DEFAULT_VALUE_SCHEMA, IntStream.range(0, keyCount).mapToObj(i -> new AbstractMap.SimpleEntry<>(i, i + 2)));
      TestUtils.waitForNonDeterministicAssertion(20, TimeUnit.SECONDS, () -> {
        for (Integer i = 0; i < keyCount; i++) {
          assertEquals(client.get(i).get(), new Integer(i + 1));
        }
      });
    }
  }

  @Test(timeOut = 180 * Time.MS_PER_SECOND, dataProvider = "True-and-False", dataProviderClass = DataProviderUtils.class)
  public void testHybridStoreTimeLagThresholdWithEmptyRT(boolean isRealTimeTopicEmpty) throws Exception {
    SystemProducer veniceProducer = null;

    VeniceClusterWrapper venice = sharedVenice;
    try {
      long streamingRewindSeconds = 10L;
      // Disable offset lag threshold
      long streamingMessageLag = -1L;
      // Time lag threshold is 30 seconds for the test case
      long streamingTimeLag = 30L;

      String storeName = TestUtils.getUniqueString("hybrid-store");
      File inputDir = getTempDataDirectory();
      String inputDirPath = "file://" + inputDir.getAbsolutePath();
      Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir); // records 1-100
      Properties h2vProperties = defaultH2VProps(venice, inputDirPath, storeName);

      try (ControllerClient controllerClient = createStoreForJob(venice, recordSchema, h2vProperties);
           AvroGenericStoreClient client = ClientFactory.getAndStartGenericAvroClient(
               ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(venice.getRandomRouterURL()));
           TopicManager topicManager = new TopicManager(
               DEFAULT_KAFKA_OPERATION_TIMEOUT_MS,
               100,
               MIN_COMPACTION_LAG,
               TestUtils.getVeniceConsumerFactory(venice.getKafka()))) {

        ControllerResponse response = controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
                                                                                  .setHybridRewindSeconds(streamingRewindSeconds)
                                                                                  .setHybridOffsetLagThreshold(streamingMessageLag)
                                                                                  .setHybridTimeLagThreshold(streamingTimeLag)
        );

        Assert.assertFalse(response.isError());

        //Do an H2V push with an empty RT
        runH2V(h2vProperties, 1, controllerClient);

        //Verify some records (note, records 1-100 have been pushed)
        TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, () -> {
          try {
            for (int i = 1; i < 100; i++) {
              String key = Integer.toString(i);
              Object value = client.get(key).get();
              assertNotNull(value, "Key " + i + " should not be missing!");
              assertEquals(value.toString(), "test_name_" + key);
            }
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });

        if (!isRealTimeTopicEmpty) {
          //write streaming records
          veniceProducer = getSamzaProducer(venice, storeName, Version.PushType.STREAM);
          for (int i = 1; i <= 10; i++) {
            // The batch values are small, but the streaming records are "big" (i.e.: not that big, but bigger than
            // the server's max configured chunk size). In the scenario where chunking is disabled, the server's
            // max chunk size is not altered, and thus this will be under threshold.
            sendCustomSizeStreamingRecord(veniceProducer, storeName, i, STREAMING_RECORD_SIZE);
          }

          TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, () -> {
            try {
              checkLargeRecord(client, 2);
            } catch (Exception e) {
              throw new VeniceException(e);
            }
          });
        }

        // bounce servers
        List<VeniceServerWrapper> servers = venice.getVeniceServers();
        for (VeniceServerWrapper server : servers) {
          venice.stopAndRestartVeniceServer(server.getPort());
        }
        // Without waiting after bouncing servers, it may cause this test flaky. It takes for a while for this
        // partition from BOOTSTRAP to ONLINE.
        Utils.sleep(5000);
        if (!isRealTimeTopicEmpty) {
          TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, () -> {
            try {
              checkLargeRecord(client, 2);
            } catch (Exception e) {
              throw new VeniceException(e);
            }
          });
        } else {
          //Verify some records (note, records 1-100 have been pushed)
          TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, () -> {
            try {
              for (int i = 1; i < 100; i++) {
                String key = Integer.toString(i);
                Object value = client.get(key).get();
                assertNotNull(value, "Key " + i + " should not be missing!");
                assertEquals(value.toString(), "test_name_" + key);
              }
            } catch (Exception e) {
              throw new VeniceException(e);
            }
          });
        }
      }
    } finally {
      if (null != veniceProducer) {
        veniceProducer.stop();
      }
    }
  }

  @Test(dataProvider = "Two-True-and-False", dataProviderClass = DataProviderUtils.class, timeOut = 180 * Time.MS_PER_SECOND)
  public void testDuplicatedMessagesWontBePersisted(boolean isLeaderFollowerModelEnabled, boolean isIngestionIsolationEnabled) throws Exception {
    Properties extraProperties = new Properties();
    if (isLeaderFollowerModelEnabled) {
      extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(3L));
    }

    SystemProducer veniceProducer = null;
    // N.B.: RF 2 with 2 servers is important, in order to test both the leader and follower code paths
    VeniceClusterWrapper venice = isIngestionIsolationEnabled? ingestionIsolationEnabledSharedVenice : sharedVenice;
    try {
      logger.info("Finished creating VeniceClusterWrapper");

      long streamingRewindSeconds = 10L;
      long streamingMessageLag = 2L;

      String storeName = TestUtils.getUniqueString("hybrid-store");
      File inputDir = getTempDataDirectory();
      String inputDirPath = "file://" + inputDir.getAbsolutePath();
      Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir); // records 1-100
      Properties h2vProperties = defaultH2VProps(venice, inputDirPath, storeName);

      try (ControllerClient controllerClient = createStoreForJob(venice, recordSchema, h2vProperties)) {
        // Have 1 partition only, so that all keys are produced to the same partition
        ControllerResponse response = controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
            .setHybridRewindSeconds(streamingRewindSeconds)
            .setHybridOffsetLagThreshold(streamingMessageLag)
            .setLeaderFollowerModel(isLeaderFollowerModelEnabled)
            .setPartitionCount(1)
        );

        Assert.assertFalse(response.isError());

        //Do an H2V push
        runH2V(h2vProperties, 1, controllerClient);

        /**
         * The following k/v pairs will be sent to RT, with the same producer GUID:
         * <key1, value1, Sequence number: 1>, <key1, value2, seq: 2>, <key1, value1, seq: 1 (Duplicated message)>, <key2, value1, seq: 3>
         * First check key2=value1, which confirms all messages above have been consumed by servers; then check key1=value2 to confirm
         * that duplicated message will not be persisted into disk
         */
        String key1 = "duplicated_message_test_key_1";
        String value1 = "duplicated_message_test_value_1";
        String value2 = "duplicated_message_test_value_2";
        String key2 = "duplicated_message_test_key_2";
        Properties veniceWriterProperties = new Properties();
        veniceWriterProperties.put(KAFKA_BOOTSTRAP_SERVERS, venice.getKafka().getAddress());
        AvroSerializer<String> stringSerializer = new AvroSerializer(Schema.parse(STRING_SCHEMA));
        AvroGenericDeserializer<String> stringDeserializer = new AvroGenericDeserializer<>(Schema.parse(STRING_SCHEMA), Schema.parse(STRING_SCHEMA));
        try (VeniceWriter<byte[], byte[], byte[]> realTimeTopicWriter = TestUtils.getVeniceWriterFactory(veniceWriterProperties).createBasicVeniceWriter(Version.composeRealTimeTopic(storeName))) {
          // Send <key1, value1, seq: 1>
          Pair<KafkaKey, KafkaMessageEnvelope> record = getKafkaKeyAndValueEnvelope(stringSerializer.serialize(key1),
              stringSerializer.serialize(value1), 1, realTimeTopicWriter.getProducerGUID(), 100, 1, -1);
          realTimeTopicWriter.put(record.getFirst(), record.getSecond(), new VeniceWriter.CompletableFutureCallback(
              new CompletableFuture<>()), 0, VeniceWriter.DEFAULT_LEADER_METADATA_WRAPPER);

          // Send <key1, value2, seq: 2>
          record = getKafkaKeyAndValueEnvelope(stringSerializer.serialize(key1),
              stringSerializer.serialize(value2), 1, realTimeTopicWriter.getProducerGUID(), 100, 2, -1);
          realTimeTopicWriter.put(record.getFirst(), record.getSecond(), new VeniceWriter.CompletableFutureCallback(
              new CompletableFuture<>()), 0, VeniceWriter.DEFAULT_LEADER_METADATA_WRAPPER);

          // Send <key1, value1, seq: 1 (Duplicated message)>
          record = getKafkaKeyAndValueEnvelope(stringSerializer.serialize(key1),
              stringSerializer.serialize(value1), 1, realTimeTopicWriter.getProducerGUID(), 100, 1, -1);
          realTimeTopicWriter.put(record.getFirst(), record.getSecond(), new VeniceWriter.CompletableFutureCallback(
              new CompletableFuture<>()), 0, VeniceWriter.DEFAULT_LEADER_METADATA_WRAPPER);

          // Send <key2, value1, seq: 3>
          record = getKafkaKeyAndValueEnvelope(stringSerializer.serialize(key2),
              stringSerializer.serialize(value1), 1, realTimeTopicWriter.getProducerGUID(), 100, 3, -1);
          realTimeTopicWriter.put(record.getFirst(), record.getSecond(), new VeniceWriter.CompletableFutureCallback(
              new CompletableFuture<>()), 0, VeniceWriter.DEFAULT_LEADER_METADATA_WRAPPER);
        }

        try (CloseableHttpAsyncClient storageNodeClient = HttpAsyncClients.createDefault()) {
          storageNodeClient.start();
          Base64.Encoder encoder = Base64.getUrlEncoder();
          // Check both leader and follower hosts
          TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, true, () -> {
            for (VeniceServerWrapper server : venice.getVeniceServers()) {
              /**
               * Check key2=value1 first, it means all messages sent to RT has been consumed already
               */
              StringBuilder sb = new StringBuilder().append("http://")
                  .append(server.getAddress())
                  .append("/")
                  .append(TYPE_STORAGE)
                  .append("/")
                  .append(Version.composeKafkaTopic(storeName, 1))
                  .append("/")
                  .append(0)
                  .append("/")
                  .append(encoder.encodeToString(stringSerializer.serialize(key2)))
                  .append("?f=b64");
              HttpGet getReq = new HttpGet(sb.toString());
              HttpResponse storageNodeResponse = storageNodeClient.execute(getReq, null).get();
              try (InputStream bodyStream = storageNodeClient.execute(getReq, null).get().getEntity().getContent()) {
                byte[] body = IOUtils.toByteArray(bodyStream);
                Assert.assertEquals(storageNodeResponse.getStatusLine().getStatusCode(), HttpStatus.SC_OK,
                    "Response did not return 200: " + new String(body));
                Object value = stringDeserializer.deserialize(null, body);
                Assert.assertEquals(value.toString(), value1);
              }

              /**
               * If key1=value1, it means duplicated message has been persisted, so key1 must equal to value2
               */
              sb = new StringBuilder().append("http://")
                  .append(server.getAddress())
                  .append("/")
                  .append(TYPE_STORAGE)
                  .append("/")
                  .append(Version.composeKafkaTopic(storeName, 1))
                  .append("/")
                  .append(0)
                  .append("/")
                  .append(encoder.encodeToString(stringSerializer.serialize(key1)))
                  .append("?f=b64");
              getReq = new HttpGet(sb.toString());
              storageNodeResponse = storageNodeClient.execute(getReq, null).get();
              try (InputStream bodyStream = storageNodeClient.execute(getReq, null).get().getEntity().getContent()) {
                byte[] body = IOUtils.toByteArray(bodyStream);
                Assert.assertEquals(storageNodeResponse.getStatusLine().getStatusCode(), HttpStatus.SC_OK,
                    "Response did not return 200: " + new String(body));
                Object value = stringDeserializer.deserialize(null, body);
                Assert.assertEquals(value.toString(), value2);
              }
            }
          });
        }
      }
    } finally {
      if (null != veniceProducer) {
        veniceProducer.stop();
      }
    }
  }

  @Test(dataProvider = "isLeaderFollowerModelEnabled", timeOut = 180 * Time.MS_PER_SECOND)
  public void testHybridDIVEnhancement(boolean isLeaderFollowerModelEnabled) throws Exception {
    Properties extraProperties = new Properties();
    if (isLeaderFollowerModelEnabled) {
      extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(3L));
    }

    // N.B.: RF 2 with 2 servers is important, in order to test both the leader and follower code paths
    VeniceClusterWrapper venice = sharedVenice;
    logger.info("Finished creating VeniceClusterWrapper");
    long streamingRewindSeconds = 10L;
    long streamingMessageLag = 2L;
    String storeName = TestUtils.getUniqueString("hybrid-store");
    File inputDir = getTempDataDirectory();
    String inputDirPath = "file://" + inputDir.getAbsolutePath();
    Schema recordSchema = writeSimpleAvroFileWithUserSchema(inputDir); // records 1-100
    Properties h2vProperties = defaultH2VProps(venice, inputDirPath, storeName);
    try (ControllerClient controllerClient = createStoreForJob(venice, recordSchema, h2vProperties);
        AvroGenericStoreClient client = ClientFactory.getAndStartGenericAvroClient(
            ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(venice.getRandomRouterURL()))) {
      // Have 1 partition only, so that all keys are produced to the same partition
      ControllerResponse response = controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
          .setHybridRewindSeconds(streamingRewindSeconds)
          .setHybridOffsetLagThreshold(streamingMessageLag)
          .setLeaderFollowerModel(isLeaderFollowerModelEnabled)
          .setPartitionCount(1)
      );
      Assert.assertFalse(response.isError());
      //Do an H2V push
      runH2V(h2vProperties, 1, controllerClient);
      Properties veniceWriterProperties = new Properties();
      veniceWriterProperties.put(KAFKA_BOOTSTRAP_SERVERS, venice.getKafka().getAddress());
      /**
       * Set max segment elapsed time to 0 to enforce creating small segments aggressively
       */
      veniceWriterProperties.put(VeniceWriter.MAX_ELAPSED_TIME_FOR_SEGMENT_IN_MS, "0");
      AvroSerializer<String> stringSerializer = new AvroSerializer(Schema.parse(STRING_SCHEMA));
      String prefix = "hybrid_DIV_enhancement_";

      //chunk the data into 2 parts and send each part by different producers. Also, close the producers
      //as soon as it finishes writing. This makes sure that closing or switching producers won't
      //impact the ingestion
      for (int i = 0; i < 2; i ++) {
        try (VeniceWriter<byte[], byte[], byte[]> realTimeTopicWriter = TestUtils.getVeniceWriterFactory(
            veniceWriterProperties).createBasicVeniceWriter(Version.composeRealTimeTopic(storeName))) {
          for (int j = i * 50 + 1; j <= i * 50 + 50; j++) {
            realTimeTopicWriter.put(stringSerializer.serialize(String.valueOf(j)),
                stringSerializer.serialize(prefix + j), 1);
          }
        }
      }

      // Check both leader and follower hosts
      TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, true, () -> {
        try {
          for (int i = 1; i <= 100; i++) {
            String key = Integer.toString(i);
            Object value = client.get(key).get();
            assertNotNull(value, "Key " + i + " should not be missing!");
            assertEquals(value.toString(), prefix + key);
          }
        } catch (Exception e) {
          throw new VeniceException(e);
        }
      });
    }
  }

  @Test(dataProvider = "Two-True-and-False", dataProviderClass = DataProviderUtils.class, timeOut = 120 * Time.MS_PER_SECOND)
  public void testHybridWithAmplificationFactor(boolean useCustomizedView, boolean useIngestionIsolation) throws Exception {
    final Properties extraProperties = new Properties();
    extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(1L));
    final int partitionCount = 2;
    final int keyCount = 20;
    VeniceClusterWrapper cluster;
    boolean usedSharedCluster = false;
    if (useCustomizedView) {
      cluster = ServiceFactory.getVeniceCluster(1, 0, 0, 1,
          100, false, false, extraProperties);
      Properties routerProperties = new Properties();
      routerProperties.put(ConfigKeys.HELIX_OFFLINE_PUSH_ENABLED, true);
      cluster.addVeniceRouter(routerProperties);
      Properties serverProperties = new Properties();
      serverProperties.put(ConfigKeys.HELIX_OFFLINE_PUSH_ENABLED, true);
      serverProperties.put(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(1L));
      if (useIngestionIsolation) {
        serverProperties.put(SERVER_INGESTION_MODE, IngestionMode.ISOLATED.toString());
      }
      // add two servers for enough SNs
      cluster.addVeniceServer(new Properties(), serverProperties);
      cluster.addVeniceServer(new Properties(), serverProperties);
      // Build customized state config and update to Zookeeper
      CustomizedStateConfig.Builder customizedStateConfigBuilder = new CustomizedStateConfig.Builder();
      List<String> aggregationEnabledTypes = new ArrayList<String>();
      aggregationEnabledTypes.add(HelixPartitionState.OFFLINE_PUSH.name());
      customizedStateConfigBuilder.setAggregationEnabledTypes(aggregationEnabledTypes);
      CustomizedStateConfig customizedStateConfig = customizedStateConfigBuilder.build();
      HelixAdmin admin = new ZKHelixAdmin(cluster.getZk().getAddress());
      admin.addCustomizedStateConfig(cluster.getClusterName(), customizedStateConfig);
    } else {
      usedSharedCluster = true;
      cluster = useIngestionIsolation ? ingestionIsolationEnabledSharedVenice : sharedVenice;
    }

    UpdateStoreQueryParams params = new UpdateStoreQueryParams()
        .setPartitionCount(partitionCount)
        .setReplicationFactor(2)
        .setAmplificationFactor(5)
        .setLeaderFollowerModel(true);
    String storeName = TestUtils.getUniqueString("store");
    try (ControllerClient controllerClient = cluster.getControllerClient()) {
      TestUtils.assertCommand(controllerClient.createNewStore(storeName, "owner", STRING_SCHEMA, STRING_SCHEMA));
      TestUtils.assertCommand(controllerClient.updateStore(storeName, params));
    }
    cluster.createVersion(storeName, STRING_SCHEMA, STRING_SCHEMA, IntStream.range(0, keyCount).mapToObj(i -> new AbstractMap.SimpleEntry<>(String.valueOf(i), String.valueOf(i))));
    try (AvroGenericStoreClient client = ClientFactory.getAndStartGenericAvroClient(
        ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(cluster.getRandomRouterURL()))) {
      for (int i = 0; i < keyCount; i++) {
        assertEquals(client.get(String.valueOf(i)).get().toString(), String.valueOf(i));
      }

      // Update Amp Factor and turn store into a hybrid store
      params = new UpdateStoreQueryParams()
          .setAmplificationFactor(3)
          // set hybridRewindSecond to a big number so following versions won't ignore old records in RT
          .setHybridRewindSeconds(2000000)
          .setHybridOffsetLagThreshold(10);
      try (ControllerClient controllerClient = cluster.getControllerClient()) {
        TestUtils.assertCommand(controllerClient.updateStore(storeName, params));
      }

      // Create a new version with updated amplification factor
      cluster.createVersion(storeName, STRING_SCHEMA, STRING_SCHEMA, IntStream.range(0, keyCount).mapToObj(i -> new AbstractMap.SimpleEntry<>(String.valueOf(i), String.valueOf(i))));
      TestUtils.waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, () -> {
          for (Integer i = 0; i < keyCount; i++) {
            assertEquals(client.get(String.valueOf(i)).get().toString(), String.valueOf(i));
          }
      });

      // Produce Large Streaming Record
      SystemProducer producer = TestPushUtils.getSamzaProducer(cluster, storeName, Version.PushType.STREAM);
      for (int i = 0; i < keyCount; i++) {
        TestPushUtils.sendCustomSizeStreamingRecord(producer, storeName, i, STREAMING_RECORD_SIZE);
      }
      producer.stop();

      TestUtils.waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, () -> {
        for (int i = 0; i < keyCount; i++) {
          checkLargeRecord(client, i);
        }
      });
    }
    if (!usedSharedCluster) {
      cluster.close();
    }
  }

  private static Pair<KafkaKey, KafkaMessageEnvelope> getKafkaKeyAndValueEnvelope(byte[] keyBytes, byte[] valueBytes,
      int valueSchemaId, GUID producerGUID, int segmentNumber, int sequenceNumber, long upstreamOffset) {
    KafkaKey kafkaKey = new KafkaKey(MessageType.PUT, keyBytes);
    Put putPayload = new Put();
    putPayload.putValue = ByteBuffer.wrap(valueBytes);
    putPayload.schemaId = valueSchemaId;
    putPayload.timestampMetadataVersionId = VeniceWriter.VENICE_DEFAULT_TIMESTAMP_METADATA_VERSION_ID;
    putPayload.timestampMetadataPayload = ByteBuffer.wrap(new byte[0]);

    KafkaMessageEnvelope kafkaValue = new KafkaMessageEnvelope();
    kafkaValue.messageType = MessageType.PUT.getValue();
    kafkaValue.payloadUnion = putPayload;

    ProducerMetadata producerMetadata = new ProducerMetadata();
    producerMetadata.producerGUID = producerGUID;
    producerMetadata.segmentNumber = segmentNumber;
    producerMetadata.messageSequenceNumber = sequenceNumber;
    producerMetadata.messageTimestamp = System.currentTimeMillis();
    kafkaValue.producerMetadata = producerMetadata;
    kafkaValue.leaderMetadataFooter = new LeaderMetadata();
    kafkaValue.leaderMetadataFooter.upstreamOffset = upstreamOffset;
    return Pair.create(kafkaKey, kafkaValue);
  }
  /**
   * Blocking, waits for new version to go online
   */
  public static void runH2V(Properties h2vProperties, int expectedVersionNumber, ControllerClient controllerClient) throws Exception {
    long h2vStart = System.currentTimeMillis();
    String jobName = TestUtils.getUniqueString("hybrid-job-" + expectedVersionNumber);
    try (VenicePushJob job = new VenicePushJob(jobName, h2vProperties)) {
      job.run();
      TestUtils.waitForNonDeterministicCompletion(5, TimeUnit.SECONDS,
          () -> controllerClient.getStore((String) h2vProperties.get(VenicePushJob.VENICE_STORE_NAME_PROP))
              .getStore().getCurrentVersion() == expectedVersionNumber);
      logger.info("**TIME** H2V" + expectedVersionNumber + " takes " + (System.currentTimeMillis() - h2vStart));
    }
  }

  private static VeniceClusterWrapper setUpCluster(boolean enabledIngestionIsolation) {
    Properties extraProperties = new Properties();
    extraProperties.setProperty(PERSISTENCE_TYPE, PersistenceType.ROCKS_DB.name());
    extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(3L));
    extraProperties.setProperty(ROCKSDB_PLAIN_TABLE_FORMAT_ENABLED, "false");
    extraProperties.setProperty(SERVER_DATABASE_CHECKSUM_VERIFICATION_ENABLED, "true");
    extraProperties.setProperty(SERVER_DATABASE_SYNC_BYTES_INTERNAL_FOR_DEFERRED_WRITE_MODE, "300");
    if (enabledIngestionIsolation) {
      extraProperties.setProperty(SERVER_INGESTION_MODE, IngestionMode.ISOLATED.toString());
    }
    VeniceClusterWrapper cluster = ServiceFactory.getVeniceCluster(1,1,1, 2, 1000000, false, false, extraProperties);
    Properties serverPropertiesWithSharedConsumer = new Properties();
    serverPropertiesWithSharedConsumer.setProperty(SSL_TO_KAFKA, "false");
    extraProperties.setProperty(SERVER_SHARED_CONSUMER_POOL_ENABLED, "true");
    extraProperties.setProperty(SERVER_CONSUMER_POOL_SIZE_PER_KAFKA_CLUSTER, "3");
    extraProperties.setProperty(SERVER_DEDICATED_DRAINER_FOR_SORTED_INPUT_ENABLED, "true");
    cluster.addVeniceServer(serverPropertiesWithSharedConsumer, extraProperties);

    return cluster;
  }
}
