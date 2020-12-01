package io.pravega.connectors.flink;

import io.pravega.connectors.flink.table.catalog.pravega.PravegaCatalog;
import io.pravega.connectors.flink.utils.EnvironmentFileUtil;
import io.pravega.connectors.flink.utils.SetupUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.Options;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.client.cli.DefaultCLI;
import org.apache.flink.client.deployment.ClusterClientServiceLoader;
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.client.gateway.SessionContext;
import org.apache.flink.table.client.gateway.local.ExecutionContext;
import org.apache.flink.table.client.config.Environment;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.types.Row;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@Slf4j
public class CatalogITest {
    private static final String CATALOGS_ENVIRONMENT_FILE = "test-sql-client-pravega-catalog.yaml";

    private static final String CONTROLLER_URI = "tcp://localhost:9090";

    private EventTimeOrderingOperator<String, Tuple2<String, Long>> operator;
    private KeyedOneInputStreamOperatorTestHarness<String, Tuple2<String, Long>, Tuple2<String, Long>> testHarness;

    /** Setup utility */
    private static final SetupUtils SETUP_UTILS = new SetupUtils();

    @Rule
    public final Timeout globalTimeout = new Timeout(120, TimeUnit.SECONDS);

    // ------------------------------------------------------------------------

    @BeforeClass
    public static void setupPravega() throws Exception {
        SETUP_UTILS.startAllServices();
    }

    @AfterClass
    public static void tearDownPravega() throws Exception {
        SETUP_UTILS.stopAllServices();
    }

    @Before
    public void before() throws Exception {
        operator = new EventTimeOrderingOperator<>();
        operator.setInputType(TypeInformation.of(new TypeHint<Tuple2<String, Long>>() {
        }), new ExecutionConfig());
        testHarness = new KeyedOneInputStreamOperatorTestHarness<>(
                operator, in -> in.f0, TypeInformation.of(String.class));
        testHarness.setTimeCharacteristic(TimeCharacteristic.EventTime);
        testHarness.open();
    }

    @After
    public void after() throws Exception {
        testHarness.close();
        operator.close();
    }

    @Test
    public void testCatalogs() throws Exception {
        String inmemoryCatalog = "inmemorycatalog";
        String pravegaCatalog1 = "pravegacatalog1";
        String pravegaCatalog2 = "pravegacatalog2";

        ExecutionContext context = createExecutionContext(CATALOGS_ENVIRONMENT_FILE, getStreamingConfs());
        TableEnvironment tableEnv = context.getTableEnvironment();

        assertEquals(tableEnv.getCurrentCatalog(), inmemoryCatalog);
        assertEquals(tableEnv.getCurrentDatabase(), "mydatabase");

        Catalog catalog = tableEnv.getCatalog(pravegaCatalog1).orElse(null);
        assertNotNull(catalog);
        assertTrue(catalog instanceof PravegaCatalog);
        tableEnv.useCatalog(pravegaCatalog1);
        assertEquals(tableEnv.getCurrentDatabase(), "default-scope");

        catalog = tableEnv.getCatalog(pravegaCatalog2).orElse(null);
        assertNotNull(catalog);
        assertTrue(catalog instanceof PravegaCatalog);
        tableEnv.useCatalog(pravegaCatalog2);
        assertEquals(tableEnv.getCurrentDatabase(), "tn/ns");
    }

    @Test
    public void testTableReadStartFromLatestByDefault() throws Exception {
        String catalog1 = "pravegacatalog1";

        String tableName = "test";
        String scopeName = "scope";

        ExecutionContext context = createExecutionContext(CATALOGS_ENVIRONMENT_FILE, getStreamingConfs());
        TableEnvironment tableEnv = context.getTableEnvironment();

        tableEnv.useCatalog(catalog1);

        tableEnv.useDatabase(scopeName);

        Table t = tableEnv.from(tableName).select("value");
        DataStream stream = ((StreamTableEnvironment) tableEnv).toAppendStream(t, t.getSchema().toRowType());
    }

    private ExecutionContext createExecutionContext(String file, Map<String, String> replaceVars) throws Exception {
        final Environment env = EnvironmentFileUtil.parseModified(
                file,
                replaceVars
        );
        final Configuration flinkConfig = new Configuration();
        return ExecutionContext.builder(
                env,
                new SessionContext("test-session", new Environment()),
                Collections.emptyList(),
                flinkConfig,
                new DefaultClusterClientServiceLoader(),
                new Options(),
                Collections.singletonList(new DefaultCLI(flinkConfig))
        ).build();
    }

    public String getControllerUri() {
        return CONTROLLER_URI;
    }

    private Map<String, String> getStreamingConfs() {
        Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_CONTROLLERURI", getControllerUri());
        return replaceVars;
    }
}
