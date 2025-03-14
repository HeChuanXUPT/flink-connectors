/**
 * Copyright Pravega Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.pravega.connectors.flink.dynamic.table;

import io.pravega.client.stream.Stream;
import io.pravega.client.stream.StreamCut;
import io.pravega.connectors.flink.FlinkPravegaInputFormat;
import io.pravega.connectors.flink.FlinkPravegaReader;
import io.pravega.connectors.flink.FlinkPravegaWriter;
import io.pravega.connectors.flink.PravegaConfig;
import io.pravega.connectors.flink.PravegaWriterMode;
import io.pravega.connectors.flink.util.FlinkPravegaUtils;
import io.pravega.connectors.flink.util.StreamWithBoundaries;
import org.apache.flink.api.common.io.InputFormat;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.CatalogTableImpl;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.ResolvedCatalogTable;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.catalog.WatermarkSpec;
import org.apache.flink.table.connector.format.DecodingFormat;
import org.apache.flink.table.connector.format.EncodingFormat;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.sink.SinkFunctionProvider;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.InputFormatProvider;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceFunctionProvider;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.expressions.utils.ResolvedExpressionMock;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.factories.TestFormatFactory;
import org.apache.flink.table.runtime.connector.sink.SinkRuntimeProviderContext;
import org.apache.flink.table.runtime.connector.source.ScanRuntimeProviderContext;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.utils.TableSchemaUtils;
import org.apache.flink.util.TestLogger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.apache.flink.core.testutils.FlinkMatchers.containsCause;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FlinkPravegaDynamicTableFactoryTest extends TestLogger {
    private static final String SCOPE = "scope";
    private static final String CONTROLLER_URI = "dummy";
    private static final String AUTH_TYPE = "basic";
    private static final String AUTH_TOKEN = "token";

    private static final String READER_GROUP = "group";
    private static final String STREAM1 = "stream1";
    private static final String STREAM2 = "stream2";
    private static final String STREAM3 = "stream3";

    private static final String NAME = "name";
    private static final String COUNT = "count";
    private static final String TIME = "time";
    private static final String METADATA = "event_pointer";
    private static final String WATERMARK_EXPRESSION = TIME + " - INTERVAL '5' SECOND";
    private static final DataType WATERMARK_DATATYPE = DataTypes.TIMESTAMP(3);
    private static final String COMPUTED_COLUMN_NAME = "computed-column";
    private static final String COMPUTED_COLUMN_EXPRESSION = COUNT + " + 1.0";
    private static final DataType COMPUTED_COLUMN_DATATYPE = DataTypes.DECIMAL(10, 3);
    private static final String EXACTLY_ONCE = "exactly-once";
    private static final String TIMEOUT_INTERVAL = "2000 ms";
    private static final String LEASE_RENEWAL_INTERVAL = "1 min";
    private static final long TIMEOUT_MILLIS = 2000L;
    private static final long LEASE_MILLIS = 60000L;

    private static final ResolvedSchema SOURCE_SCHEMA = new ResolvedSchema(
            Arrays.asList(
                    Column.physical(NAME, DataTypes.STRING()),
                    Column.physical(COUNT, DataTypes.DECIMAL(38, 18)),
                    Column.physical(TIME, DataTypes.TIMESTAMP(3)),
                    Column.computed(
                            COMPUTED_COLUMN_NAME,
                            ResolvedExpressionMock.of(
                                    COMPUTED_COLUMN_DATATYPE, COMPUTED_COLUMN_EXPRESSION))),
            Collections.singletonList(
                    WatermarkSpec.of(
                            TIME,
                            ResolvedExpressionMock.of(
                                    WATERMARK_DATATYPE, WATERMARK_EXPRESSION))),
            null);

    private static final ResolvedSchema SOURCE_SCHEMA_WITH_METADATA = new ResolvedSchema(
            Arrays.asList(
                    Column.physical(NAME, DataTypes.STRING()),
                    Column.physical(COUNT, DataTypes.DECIMAL(38, 18)),
                    Column.physical(TIME, DataTypes.TIMESTAMP(3)),
                    Column.metadata(METADATA, DataTypes.BYTES(), null, true),
                    Column.computed(
                            COMPUTED_COLUMN_NAME,
                            ResolvedExpressionMock.of(
                                    COMPUTED_COLUMN_DATATYPE, COMPUTED_COLUMN_EXPRESSION))),
            Collections.singletonList(
                    WatermarkSpec.of(
                            TIME,
                            ResolvedExpressionMock.of(
                                    WATERMARK_DATATYPE, WATERMARK_EXPRESSION))),
            null);

    private static final ResolvedSchema SINK_SCHEMA = new ResolvedSchema(
            Arrays.asList(
                    Column.physical(NAME, DataTypes.STRING()),
                    Column.physical(COUNT, DataTypes.DECIMAL(38, 18)),
                    Column.physical(TIME, DataTypes.TIMESTAMP(3))),
            Collections.emptyList(),
            null);

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testStreamingTableSource() {
        // prepare parameters for Pravega table source
        final DataType physicalDataType = SOURCE_SCHEMA.toPhysicalRowDataType();
        DecodingFormat<DeserializationSchema<RowData>> decodingFormat =
                new TestFormatFactory.DecodingFormatMock(",", true);

        // Construct table source using options and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "scanTable");
        CatalogTable catalogTable = createPravegaStreamingSourceCatalogTable();
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SOURCE_SCHEMA);
        final DynamicTableSource actualSource = FactoryUtil.createTableSource(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);

        // Test scan source equals
        final FlinkPravegaDynamicTableSource expectedPravegaSource = new FlinkPravegaDynamicTableSource(
                physicalDataType,
                decodingFormat,
                null,
                getTestPravegaConfig(),
                getTestScanStreamList(),
                3000L,
                5000L,
                TIMEOUT_MILLIS,
                3,
                null,
                true,
                false);

        // expect the source to be constructed successfully
        final FlinkPravegaDynamicTableSource actualPravegaSource = (FlinkPravegaDynamicTableSource) actualSource;
        assertEquals(actualPravegaSource, expectedPravegaSource);
    }

    @Test
    public void testStreamingTableSourceWithMetadata() {
        // prepare parameters for Pravega table source
        final DataType producedDataType = SOURCE_SCHEMA_WITH_METADATA.toPhysicalRowDataType();
        DecodingFormat<DeserializationSchema<RowData>> decodingFormat =
                new TestFormatFactory.DecodingFormatMock(",", true);

        // Construct table source using options and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "scanTable");
        CatalogTable catalogTable = createPravegaStreamingSourceCatalogTable();
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SOURCE_SCHEMA_WITH_METADATA);
        final DynamicTableSource actualSource = FactoryUtil.createTableSource(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);
        final FlinkPravegaDynamicTableSource actualPravegaSource = (FlinkPravegaDynamicTableSource) actualSource;
        actualPravegaSource.applyReadableMetadata(Collections.singletonList("event_pointer"), producedDataType);

        // Test scan source equals
        final DataType physicalDataType = SOURCE_SCHEMA_WITH_METADATA.toPhysicalRowDataType();
        final FlinkPravegaDynamicTableSource expectedPravegaSource = new FlinkPravegaDynamicTableSource(
                physicalDataType,
                producedDataType,
                Collections.singletonList(FlinkPravegaDynamicTableSource.ReadableMetadata.EVENT_POINTER.key),
                decodingFormat,
                null,
                getTestPravegaConfig(),
                getTestScanStreamList(),
                3000L,
                5000L,
                TIMEOUT_MILLIS,
                3,
                null,
                true,
                false);

        // expect the source to be constructed successfully
        assertEquals(actualPravegaSource, expectedPravegaSource);
    }

    @Test
    public void testStreamingTableSourceWithOptionalReaderGroupName() {
        // prepare parameters for Pravega table source
        final DataType physicalDataType = SOURCE_SCHEMA.toPhysicalRowDataType();
        DecodingFormat<DeserializationSchema<RowData>> decodingFormat =
                new TestFormatFactory.DecodingFormatMock(",", true);

        // Construct table source using DDL and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "scanTable");
        final Map<String, String> modifiedOptions = getModifiedOptions(
                getFullStreamingSourceOptions(),
                options -> options.put("scan.reader-group.name", READER_GROUP));
        CatalogTable catalogTable = createPravegaSourceCatalogTable(modifiedOptions);
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SOURCE_SCHEMA);
        final DynamicTableSource actualSource = FactoryUtil.createTableSource(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);

        // Test scan source equals
        final FlinkPravegaDynamicTableSource expectedPravegaSource = new FlinkPravegaDynamicTableSource(
                physicalDataType,
                decodingFormat,
                READER_GROUP,
                getTestPravegaConfig(),
                getTestScanStreamList(),
                3000L,
                5000L,
                TIMEOUT_MILLIS,
                3,
                null,
                true,
                false);

        final FlinkPravegaDynamicTableSource actualPravegaSource = (FlinkPravegaDynamicTableSource) actualSource;
        assertEquals(actualPravegaSource, expectedPravegaSource);
    }

    @Test
    public void testStreamingTableSourceProvider() {
        final DataType physicalDataType = SOURCE_SCHEMA.toPhysicalRowDataType();
        DecodingFormat<DeserializationSchema<RowData>> decodingFormat =
                new TestPravegaDecodingFormat(",", true);

        final FlinkPravegaDynamicTableSource source = new FlinkPravegaDynamicTableSource(
                physicalDataType,
                decodingFormat,
                READER_GROUP,
                getTestPravegaConfig(),
                getTestScanStreamList(),
                3000L,
                5000L,
                TIMEOUT_MILLIS,
                3,
                null,
                true,
                false);

        ScanTableSource.ScanRuntimeProvider provider =
                source.getScanRuntimeProvider(ScanRuntimeProviderContext.INSTANCE);
        assertTrue(provider instanceof SourceFunctionProvider);
        final SourceFunctionProvider sourceFunctionProvider = (SourceFunctionProvider) provider;
        final SourceFunction<RowData> sourceFunction = sourceFunctionProvider.createSourceFunction();
        assertTrue(sourceFunction instanceof FlinkPravegaReader);
    }

    @Test
    public void testBatchTableSource() {
        // prepare parameters for Pravega table source
        final DataType physicalDataType = SOURCE_SCHEMA.toPhysicalRowDataType();
        DecodingFormat<DeserializationSchema<RowData>> decodingFormat =
                new TestFormatFactory.DecodingFormatMock(",", true);

        // Construct table source using options and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "scanTable");
        CatalogTable catalogTable = createPravegaBatchSourceCatalogTable();
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SOURCE_SCHEMA);
        final DynamicTableSource actualSource = FactoryUtil.createTableSource(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);

        // Test scan source equals
        final FlinkPravegaDynamicTableSource expectedPravegaSource = new FlinkPravegaDynamicTableSource(
                physicalDataType,
                decodingFormat,
                null,
                getTestPravegaConfig(),
                getTestScanStreamList(),
                3000L,
                5000L,
                TIMEOUT_MILLIS,
                3,
                null,
                false,
                false);

        final FlinkPravegaDynamicTableSource actualPravegaSource = (FlinkPravegaDynamicTableSource) actualSource;
        assertEquals(actualPravegaSource, expectedPravegaSource);
    }

    @Test
    public void testBatchTableSourceProvider() {
        final DataType physicalDataType = SOURCE_SCHEMA.toPhysicalRowDataType();

        DecodingFormat<DeserializationSchema<RowData>> decodingFormat =
                new TestPravegaDecodingFormat(",", true);

        final FlinkPravegaDynamicTableSource source = new FlinkPravegaDynamicTableSource(
                physicalDataType,
                decodingFormat,
                READER_GROUP,
                getTestPravegaConfig(),
                getTestScanStreamList(),
                3000L,
                5000L,
                TIMEOUT_MILLIS,
                3,
                null,
                false,
                false);

        ScanTableSource.ScanRuntimeProvider provider =
                source.getScanRuntimeProvider(ScanRuntimeProviderContext.INSTANCE);
        assertTrue(provider instanceof InputFormatProvider);
        final InputFormatProvider inputFormatProvider = (InputFormatProvider) provider;
        final InputFormat<RowData, ?> sourceFunction = inputFormatProvider.createInputFormat();
        assertTrue(sourceFunction instanceof FlinkPravegaInputFormat);
    }

    @Test
    public void testTableSink() {
        final DataType consumedDataType = SINK_SCHEMA.toPhysicalRowDataType();
        EncodingFormat<SerializationSchema<RowData>> encodingFormat =
                new TestFormatFactory.EncodingFormatMock(",");

        // Construct table sink using options and table sink factory.
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "sinkTable");
        final CatalogTable sinkTable = createPravegaSinkCatalogTable();
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(sinkTable, SINK_SCHEMA);
        final DynamicTableSink actualSink = FactoryUtil.createTableSink(
                null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);

        final FlinkPravegaDynamicTableSink expectedSink = new FlinkPravegaDynamicTableSink(
                TableSchemaUtils.getPhysicalSchema(TableSchema.fromResolvedSchema(SINK_SCHEMA)),
                encodingFormat,
                getTestPravegaConfig(),
                Stream.of(SCOPE, STREAM3),
                PravegaWriterMode.EXACTLY_ONCE,
                LEASE_MILLIS,
                false,
                NAME
        );
        assertEquals(expectedSink, actualSink);
    }

    @Test
    public void testTableSinkProvider() {
        final DataType consumedDataType = SINK_SCHEMA.toPhysicalRowDataType();
        EncodingFormat<SerializationSchema<RowData>> encodingFormat =
                new TestPravegaEncodingFormat(",");

        // Construct table sink using options and table sink factory.
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "sinkTable");
        final CatalogTable sinkTable = createPravegaSinkCatalogTable();
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(sinkTable, SINK_SCHEMA);
        final DynamicTableSink actualSink = FactoryUtil.createTableSink(
                null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);

        final FlinkPravegaDynamicTableSink sink = new FlinkPravegaDynamicTableSink(
                TableSchemaUtils.getPhysicalSchema(TableSchema.fromResolvedSchema(SINK_SCHEMA)),
                encodingFormat,
                getTestPravegaConfig(),
                Stream.of(SCOPE, STREAM3),
                PravegaWriterMode.EXACTLY_ONCE,
                LEASE_MILLIS,
                false,
                NAME
        );

        DynamicTableSink.SinkRuntimeProvider provider =
                sink.getSinkRuntimeProvider(new SinkRuntimeProviderContext(false));
        assertTrue(provider instanceof SinkFunctionProvider);
        final SinkFunctionProvider sinkFunctionProvider = (SinkFunctionProvider) provider;
        final SinkFunction<RowData> sinkFunction = sinkFunctionProvider.createSinkFunction();
        assertTrue(sinkFunction instanceof FlinkPravegaWriter);
    }

    // --------------------------------------------------------------------------------------------
    // Negative tests
    // --------------------------------------------------------------------------------------------
    @Test
    public void testInvalidScanExecutionType() {
        // Construct table source using DDL and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "scanTable");
        final Map<String, String> modifiedOptions = getModifiedOptions(
                getFullStreamingSourceOptions(),
                options -> {
                    options.put("scan.execution.type", "abc");
                });
        CatalogTable catalogTable = createPravegaSourceCatalogTable(modifiedOptions);
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SOURCE_SCHEMA);

        thrown.expect(ValidationException.class);
        thrown.expect(containsCause(new ValidationException("Unsupported value 'abc' for 'scan.execution.type'. "
                + "Supported values are ['streaming', 'batch'].")));
        FactoryUtil.createTableSource(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);
    }

    @Test
    public void testNegativeMaxCheckpointRequest() {
        // Construct table source using DDL and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "scanTable");
        final Map<String, String> modifiedOptions = getModifiedOptions(
                getFullStreamingSourceOptions(),
                options -> {
                    options.put("scan.reader-group.max-outstanding-checkpoint-request", "-1");
                });
        CatalogTable catalogTable = createPravegaSourceCatalogTable(modifiedOptions);
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SOURCE_SCHEMA);

        thrown.expect(ValidationException.class);
        thrown.expect(containsCause(new ValidationException("'scan.reader-group.max-outstanding-checkpoint-request'" +
                " requires a positive integer, received -1")));
        FactoryUtil.createTableSource(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);
    }

    @Test
    public void testMissingSourceStream() {
        // Construct table source using DDL and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "scanTable");
        final Map<String, String> modifiedOptions = getModifiedOptions(
                getFullStreamingSourceOptions(),
                options -> {
                    options.remove("scan.streams");
                });
        CatalogTable catalogTable = createPravegaSourceCatalogTable(modifiedOptions);
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SOURCE_SCHEMA);

        thrown.expect(ValidationException.class);
        thrown.expect(containsCause(new ValidationException("'scan.streams' is required but missing")));
        FactoryUtil.createTableSource(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);
    }

    @Test
    public void testInvalidStartStreamCuts() {
        // Construct table source using DDL and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "scanTable");
        final Map<String, String> modifiedOptions = getModifiedOptions(
                getFullStreamingSourceOptions(),
                options -> {
                    options.put("scan.start-streamcuts", "abc");
                    options.put("scan.end-streamcuts", "abc;def");
                });
        CatalogTable catalogTable = createPravegaSourceCatalogTable(modifiedOptions);
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SOURCE_SCHEMA);

        thrown.expect(ValidationException.class);
        thrown.expect(containsCause(new ValidationException("Start stream cuts are not matching the number of streams," +
                " having 1, expected 2")));
        FactoryUtil.createTableSource(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);
    }

    @Test
    public void testInvalidEndStreamCuts() {
        // Construct table source using DDL and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "scanTable");
        final Map<String, String> modifiedOptions = getModifiedOptions(
                getFullStreamingSourceOptions(),
                options -> {
                    options.put("scan.start-streamcuts", "abc;def");
                    options.put("scan.end-streamcuts", "abc");
                });
        CatalogTable catalogTable = createPravegaSourceCatalogTable(modifiedOptions);
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SOURCE_SCHEMA);

        thrown.expect(ValidationException.class);
        thrown.expect(containsCause(new ValidationException("End stream cuts are not matching the number of streams," +
                " having 1, expected 2")));
        FactoryUtil.createTableSource(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);
    }

    @Test
    public void testInvalidSinkSemantic() {
        // Construct table source using DDL and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "sinkTable");
        final Map<String, String> modifiedOptions = getModifiedOptions(
                getFullSinkOptions(),
                options -> {
                    options.put("sink.semantic", "abc");
                });
        CatalogTable catalogTable = createPravegaSinkCatalogTable(modifiedOptions);
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SINK_SCHEMA);

        thrown.expect(ValidationException.class);
        thrown.expect(containsCause(new ValidationException("Unsupported value 'abc' for 'sink.semantic'. "
                + "Supported values are ['at-least-once', 'exactly-once', 'best-effort'].")));
        FactoryUtil.createTableSink(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);
    }

    @Test
    public void testMissingSinkStream() {
        // Construct table source using DDL and table source factory
        ObjectIdentifier objectIdentifier = ObjectIdentifier.of(
                "default",
                "default",
                "sinkTable");
        final Map<String, String> modifiedOptions = getModifiedOptions(
                getFullSinkOptions(),
                options -> {
                    options.remove("sink.stream");
                });
        CatalogTable catalogTable = createPravegaSinkCatalogTable(modifiedOptions);
        ResolvedCatalogTable resolvedCatalogTable = new ResolvedCatalogTable(catalogTable, SINK_SCHEMA);

        thrown.expect(ValidationException.class);
        thrown.expect(containsCause(new ValidationException("'sink.stream' is required but missing")));
        FactoryUtil.createTableSink(null,
                objectIdentifier,
                resolvedCatalogTable,
                new Configuration(),
                Thread.currentThread().getContextClassLoader(),
                false);
    }

    // --------------------------------------------------------------------------------------------
    // Utilities
    // --------------------------------------------------------------------------------------------

    private CatalogTable createPravegaStreamingSourceCatalogTable() {
        return createPravegaSourceCatalogTable(getFullStreamingSourceOptions());
    }

    private CatalogTable createPravegaBatchSourceCatalogTable() {
        return createPravegaSourceCatalogTable(getFullBatchSourceOptions());
    }

    private CatalogTable createPravegaSourceCatalogTable(Map<String, String> options) {
        return new CatalogTableImpl(TableSchema.fromResolvedSchema(SOURCE_SCHEMA), options, "scanTable");
    }

    private CatalogTable createPravegaSinkCatalogTable() {
        return createPravegaSinkCatalogTable(getFullSinkOptions());
    }

    private CatalogTable createPravegaSinkCatalogTable(Map<String, String> options) {
        return new CatalogTableImpl(TableSchema.fromResolvedSchema(SINK_SCHEMA), options, "sinkTable");
    }

    private static Map<String, String> getModifiedOptions(
            Map<String, String> options,
            Consumer<Map<String, String>> optionModifier) {
        optionModifier.accept(options);
        return options;
    }

    private Map<String, String> getFullStreamingSourceOptions() {
        Map<String, String> tableOptions = new HashMap<>();
        // Pravega connection options.
        tableOptions.put("connector", "pravega");
        tableOptions.put("controller-uri", CONTROLLER_URI);
        tableOptions.put("scope", SCOPE);
        tableOptions.put("security.auth-type", AUTH_TYPE);
        tableOptions.put("security.auth-token", AUTH_TOKEN);
        tableOptions.put("security.validate-hostname", "true");

        tableOptions.put("scan.execution.type", "streaming");
        tableOptions.put("scan.streams", String.format("%s;%s", STREAM1, STREAM2));
        tableOptions.put("scan.event-read.timeout.interval", TIMEOUT_INTERVAL);

        // Format options.
        tableOptions.put("format", TestFormatFactory.IDENTIFIER);
        final String formatDelimiterKey = String.format("%s.%s",
                TestFormatFactory.IDENTIFIER, TestFormatFactory.DELIMITER.key());
        final String failOnMissingKey = String.format("%s.%s",
                TestFormatFactory.IDENTIFIER, TestFormatFactory.FAIL_ON_MISSING.key());
        tableOptions.put(formatDelimiterKey, ",");
        tableOptions.put(failOnMissingKey, "true");
        return tableOptions;
    }

    private Map<String, String> getFullBatchSourceOptions() {
        return getModifiedOptions(
                getFullStreamingSourceOptions(),
                options -> {
                    options.put("scan.execution.type", "batch");
                }
        );
    }

    private Map<String, String> getFullSinkOptions() {
        Map<String, String> tableOptions = new HashMap<>();
        // Pravega connection options.
        tableOptions.put("connector", "pravega");
        tableOptions.put("controller-uri", CONTROLLER_URI);
        tableOptions.put("scope", SCOPE);
        tableOptions.put("security.auth-type", AUTH_TYPE);
        tableOptions.put("security.auth-token", AUTH_TOKEN);
        tableOptions.put("security.validate-hostname", "true");

        tableOptions.put("sink.stream", STREAM3);
        tableOptions.put("sink.semantic", EXACTLY_ONCE);
        tableOptions.put("sink.txn-lease-renewal.interval", LEASE_RENEWAL_INTERVAL);
        tableOptions.put("sink.routing-key.field.name", NAME);

        // Format options.
        tableOptions.put("format", TestFormatFactory.IDENTIFIER);
        final String formatDelimiterKey = String.format("%s.%s",
                TestFormatFactory.IDENTIFIER, TestFormatFactory.DELIMITER.key());
        tableOptions.put(formatDelimiterKey, ",");
        return tableOptions;
    }

    private PravegaConfig getTestPravegaConfig() {
        return PravegaConfig
                .fromDefaults()
                .withControllerURI(URI.create(CONTROLLER_URI))
                .withDefaultScope(SCOPE)
                .withHostnameValidation(true)
                .withCredentials(new FlinkPravegaUtils.SimpleCredentials(AUTH_TYPE, AUTH_TOKEN));
    }

    private List<StreamWithBoundaries> getTestScanStreamList() {
        Stream stream1 = Stream.of(SCOPE, STREAM1);
        Stream stream2 = Stream.of(SCOPE, STREAM2);
        return Arrays.asList(
                StreamWithBoundaries.of(stream1, StreamCut.UNBOUNDED, StreamCut.UNBOUNDED),
                StreamWithBoundaries.of(stream2, StreamCut.UNBOUNDED, StreamCut.UNBOUNDED)
        );
    }

    private static class DummySerializationSchema implements SerializationSchema<RowData> {

        private static final DummySerializationSchema INSTANCE = new DummySerializationSchema();

        @Override
        public byte[] serialize(RowData element) {
            return new byte[0];
        }
    }

    private static class DummyDeserializationSchema implements DeserializationSchema<RowData> {

        private static final DummyDeserializationSchema INSTANCE = new DummyDeserializationSchema();

        @Override
        public RowData deserialize(byte[] message) throws IOException {
            return null;
        }

        @Override
        public boolean isEndOfStream(RowData nextElement) {
            return false;
        }

        @Override
        public TypeInformation<RowData> getProducedType() {
            return null;
        }
    }

    private static class TestPravegaEncodingFormat extends TestFormatFactory.EncodingFormatMock {

        private TestPravegaEncodingFormat(String delimiter) {
            super(delimiter);
        }

        @Override
        public SerializationSchema<RowData> createRuntimeEncoder(
                DynamicTableSink.Context context,
                DataType consumeDataType) {
            return DummySerializationSchema.INSTANCE;
        }
    }

    private static class TestPravegaDecodingFormat extends TestFormatFactory.DecodingFormatMock {

        private TestPravegaDecodingFormat(String delimiter, Boolean failOnMissing) {
            super(delimiter, failOnMissing);
        }

        @Override
        public DeserializationSchema<RowData> createRuntimeDecoder(
                DynamicTableSource.Context context,
                DataType producedDataType) {
            return DummyDeserializationSchema.INSTANCE;
        }
    }
}