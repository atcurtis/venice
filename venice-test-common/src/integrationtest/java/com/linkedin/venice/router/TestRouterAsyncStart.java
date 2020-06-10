package com.linkedin.venice.router;

import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.client.exceptions.VeniceClientException;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceServerWrapper;
import com.linkedin.venice.router.httpclient.StorageNodeClientType;
import com.linkedin.venice.utils.Utils;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import java.util.Properties;
import org.apache.commons.io.IOUtils;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


public class TestRouterAsyncStart {
  private VeniceClusterWrapper veniceCluster;

  @BeforeClass(alwaysRun = true)
  public void setUp() throws VeniceClientException {
    Utils.thisIsLocalhost();
    veniceCluster = ServiceFactory.getVeniceCluster(1, 1, 0, 2, 100, true, false);
  }

  @AfterClass(alwaysRun = true)
  public void cleanUp() {
    IOUtils.closeQuietly(veniceCluster);
  }

  /**
   * TODO: this test will take a couple of mins to finish:
   * 1. Internally {@link ServiceFactory#getService} would retry for 5 times in failure scenario;
   * 2. The client warming timeout is 1 min;
   *
   * If this runtime of this test is not acceptable, w e could tune the above behavior.
   */
  @Test
  public void testConnectionWarmingFailureDuringSyncStart() {
    // Setup Venice server in a way that it will take a very long time to response
    List<VeniceServerWrapper> servers = veniceCluster.getVeniceServers();
    Assert.assertEquals(servers.size(), 1, "There should be only one storage node in this cluster");
    VeniceServerWrapper serverWrapper = servers.get(0);
    serverWrapper.getVeniceServer().setRequestHandler((ChannelHandlerContext context, Object message) -> {
      Utils.sleep(Integer.MAX_VALUE); // never return;
      return true;
    });

    Properties routerProperties = new Properties();
    routerProperties.put(ConfigKeys.ROUTER_STORAGE_NODE_CLIENT_TYPE, StorageNodeClientType.APACHE_HTTP_ASYNC_CLIENT);
    routerProperties.put(ConfigKeys.ROUTER_PER_NODE_CLIENT_ENABLED, true);
    routerProperties.put(ConfigKeys.ROUTER_HTTPASYNCCLIENT_CONNECTION_WARMING_ENABLED, true);
    routerProperties.put(ConfigKeys.ROUTER_PER_NODE_CLIENT_THREAD_COUNT, 2);
    routerProperties.put(ConfigKeys.ROUTER_MAX_OUTGOING_CONNECTION_PER_ROUTE, 10);
    routerProperties.put(ConfigKeys.ROUTER_HTTPASYNCCLIENT_CONNECTION_WARMING_LOW_WATER_MARK, 5);
    routerProperties.put(ConfigKeys.ROUTER_HTTPASYNCCLIENT_CONNECTION_WARMING_SLEEP_INTERVAL_MS, 0);
    routerProperties.put(ConfigKeys.ROUTER_ASYNC_START_ENABLED, false);

    try {
      veniceCluster.addVeniceRouter(routerProperties);
      Assert.fail("Venice Router should fail to start because of connection warming  since storage node won't respond to any request");
    } catch (Exception e) {
    }
  }
}