/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.exporter.local;

import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.indices.recovery.RecoveryResponse;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.marvel.agent.collector.cluster.ClusterStateCollector;
import org.elasticsearch.marvel.agent.collector.cluster.ClusterStateMarvelDoc;
import org.elasticsearch.marvel.agent.collector.indices.IndexRecoveryCollector;
import org.elasticsearch.marvel.agent.collector.indices.IndexRecoveryMarvelDoc;
import org.elasticsearch.marvel.agent.exporter.Exporter;
import org.elasticsearch.marvel.agent.exporter.Exporters;
import org.elasticsearch.marvel.agent.exporter.MarvelDoc;
import org.elasticsearch.marvel.agent.renderer.RendererRegistry;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.marvel.test.MarvelIntegTestCase;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.joda.time.format.DateTimeFormat;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.elasticsearch.marvel.agent.exporter.Exporter.MIN_SUPPORTED_TEMPLATE_VERSION;
import static org.elasticsearch.marvel.agent.exporter.http.HttpExporterUtils.MARVEL_VERSION_FIELD;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

@ClusterScope(scope = Scope.TEST, numDataNodes = 0, numClientNodes = 0, transportClientRatio = 0.0)
public class LocalExporterTests extends MarvelIntegTestCase {

    private final static AtomicLong timeStampGenerator = new AtomicLong();

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .build();
    }

    @Test
    public void testSimpleExport() throws Exception {
        internalCluster().startNode(Settings.builder()
                .put("marvel.agent.exporters._local.type", LocalExporter.TYPE)
                .put("marvel.agent.exporters._local.enabled", true)
                .build());
        ensureGreen();

        Exporter exporter = getLocalExporter("_local");

        logger.debug("--> exporting a single marvel doc");
        exporter.export(Collections.singletonList(newRandomMarvelDoc()));
        awaitMarvelDocsCount(is(1L));

        deleteMarvelIndices();

        final List<MarvelDoc> marvelDocs = new ArrayList<>();
        for (int i=0; i < randomIntBetween(2, 50); i++) {
            marvelDocs.add(newRandomMarvelDoc());
        }

        logger.debug("--> exporting {} marvel docs", marvelDocs.size());
        exporter.export(marvelDocs);
        awaitMarvelDocsCount(is((long) marvelDocs.size()));

        SearchResponse response = client().prepareSearch(MarvelSettings.MARVEL_INDICES_PREFIX + "*").get();
        for (SearchHit hit : response.getHits().hits()) {
            Map<String, Object> source = hit.sourceAsMap();
            assertNotNull(source.get("cluster_uuid"));
            assertNotNull(source.get("timestamp"));
        }
    }

    @Test
    public void testTemplateCreation() throws Exception {
        internalCluster().startNode(Settings.builder()
                .put("marvel.agent.exporters._local.type", LocalExporter.TYPE)
                .build());
        ensureGreen();

        LocalExporter exporter = getLocalExporter("_local");
        assertTrue(exporter.installedTemplateVersionMandatesAnUpdate(Version.CURRENT, null));

        // lets wait until the marvel template will be installed
        awaitMarvelTemplateInstalled();

        awaitMarvelDocsCount(greaterThan(0L));

        assertThat(getCurrentlyInstalledTemplateVersion(), is(Version.CURRENT));
    }

    @Test
    public void testTemplateUpdate() throws Exception {
        internalCluster().startNode(Settings.builder()
                .put("marvel.agent.exporters._local.type", LocalExporter.TYPE)
                .build());
        ensureGreen();

        LocalExporter exporter = getLocalExporter("_local");
        Version fakeVersion = MIN_SUPPORTED_TEMPLATE_VERSION;
        assertThat(exporter.installedTemplateVersionMandatesAnUpdate(Version.CURRENT, fakeVersion), is(true));

        // first, lets wait for the marvel template to be installed
        awaitMarvelTemplateInstalled();

        // now lets update the template with an old one and then restart the cluster
        exporter.putTemplate(Settings.builder().put(MARVEL_VERSION_FIELD, fakeVersion.toString()).build());
        logger.debug("full cluster restart");
        final CountDownLatch latch = new CountDownLatch(1);
        internalCluster().fullRestart(new InternalTestCluster.RestartCallback() {
            @Override
            public void doAfterNodes(int n, Client client) throws Exception {
                latch.countDown();
            }
        });
        if (!latch.await(30, TimeUnit.SECONDS)) {
            fail("waited too long (at least 30 seconds) for the cluster to restart");
        }

        // now that the cluster is restarting, lets wait for the new template version to be installed
        awaitMarvelTemplateInstalled(Version.CURRENT);
    }

    @Test
    public void testUnsupportedTemplateVersion() throws Exception {

        Exporter.Config config = new Exporter.Config("_name", Settings.EMPTY, Settings.builder()
                .put("type", "local").build());
        Client client = mock(Client.class);

        ClusterService clusterService = mock(ClusterService.class);
        boolean master = randomBoolean();
        DiscoveryNode localNode = mock(DiscoveryNode.class);
        when(localNode.masterNode()).thenReturn(master);
        when(clusterService.localNode()).thenReturn(localNode);

        RendererRegistry renderers = mock(RendererRegistry.class);

        LocalExporter exporter = spy(new LocalExporter(config, client, clusterService, renderers));

        // creating a cluster state mock that holds unsupported template version
        Version unsupportedVersion = randomFrom(Version.V_0_18_0, Version.V_1_0_0, Version.V_1_4_0);
        IndexTemplateMetaData template = mock(IndexTemplateMetaData.class);
        when(template.settings()).thenReturn(Settings.builder().put("index.marvel_version", unsupportedVersion.toString()).build());
        MetaData metaData = mock(MetaData.class);
        when(metaData.getTemplates()).thenReturn(ImmutableOpenMap.<String, IndexTemplateMetaData>builder().fPut(Exporter.INDEX_TEMPLATE_NAME, template).build());
        ClusterBlocks blocks = mock(ClusterBlocks.class);
        when(blocks.hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)).thenReturn(false);
        ClusterState clusterState = mock(ClusterState.class);
        when(clusterState.getMetaData()).thenReturn(metaData);
        when(clusterState.blocks()).thenReturn(blocks);
        when(clusterService.state()).thenReturn(clusterState);

        assertThat(exporter.start(clusterState), nullValue());
        verifyZeroInteractions(client);
        if (master) {
            verify(exporter, times(1)).installedTemplateVersionMandatesAnUpdate(Version.CURRENT, unsupportedVersion);
        }
        verify(exporter, times(1)).installedTemplateVersionIsSufficient(Version.CURRENT, unsupportedVersion);
    }

    @Test @TestLogging("marvel.agent:trace")
    public void testIndexTimestampFormat() throws Exception {
        long time = System.currentTimeMillis();
        String timeFormat = randomFrom("YY", "YYYY", "YYYY.MM", "YYYY-MM", "MM.YYYY", "MM");

        internalCluster().startNode(Settings.builder()
                .put("marvel.agent.exporters._local.type", LocalExporter.TYPE)
                .put("marvel.agent.exporters._local." + LocalExporter.INDEX_NAME_TIME_FORMAT_SETTING, timeFormat)
                .build());
        ensureGreen();

        LocalExporter exporter = getLocalExporter("_local");

        // first lets test that the index resolver works with time
        String indexName = MarvelSettings.MARVEL_INDICES_PREFIX + DateTimeFormat.forPattern(timeFormat).withZoneUTC().print(time);
        assertThat(exporter.indexNameResolver().resolve(time), equalTo(indexName));

        // now lets test that the index name resolver works with a doc
        MarvelDoc doc = newRandomMarvelDoc();
        indexName = MarvelSettings.MARVEL_INDICES_PREFIX + DateTimeFormat.forPattern(timeFormat).withZoneUTC().print(doc.timestamp());
        assertThat(exporter.indexNameResolver().resolve(doc), equalTo(indexName));

        logger.debug("--> exporting a random marvel document");
        exporter.export(Collections.singletonList(doc));
        awaitIndexExists(indexName);

        logger.debug("--> updates the timestamp");
        timeFormat = randomFrom("dd", "dd.MM.YYYY", "dd.MM");
        updateClusterSettings(Settings.builder().put("marvel.agent.exporters._local.index.name.time_format", timeFormat));
        exporter = getLocalExporter("_local"); // we need to get it again.. as it was rebuilt
        indexName = MarvelSettings.MARVEL_INDICES_PREFIX + DateTimeFormat.forPattern(timeFormat).withZoneUTC().print(doc.timestamp());
        assertThat(exporter.indexNameResolver().resolve(doc), equalTo(indexName));

        logger.debug("--> exporting the document again (this time with the the new index name time format [{}], expecting index name [{}]", timeFormat, indexName);
        exporter.export(Collections.singletonList(doc));
        awaitIndexExists(indexName);
    }

    private LocalExporter getLocalExporter(String name) throws Exception {
        final Exporter exporter = internalCluster().getInstance(Exporters.class).getExporter(name);
        assertThat(exporter, notNullValue());
        assertThat(exporter, instanceOf(LocalExporter.class));
        return (LocalExporter) exporter;
    }

    private MarvelDoc newRandomMarvelDoc() {
        if (randomBoolean()) {
            return new IndexRecoveryMarvelDoc(internalCluster().getClusterName(),
                    IndexRecoveryCollector.TYPE, timeStampGenerator.incrementAndGet(), new RecoveryResponse());
        } else {
            return new ClusterStateMarvelDoc(internalCluster().getClusterName(),
                    ClusterStateCollector.TYPE, timeStampGenerator.incrementAndGet(), ClusterState.PROTO, ClusterHealthStatus.GREEN);
        }
    }

    private void awaitMarvelTemplateInstalled() throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertMarvelTemplateInstalled();
            }
        }, 30, TimeUnit.SECONDS);
    }

    private void awaitMarvelTemplateInstalled(Version version) throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertMarvelTemplateInstalled(version);
            }
        }, 30, TimeUnit.SECONDS);
    }

    protected void assertMarvelTemplateInstalled(Version version) {
        for (IndexTemplateMetaData template : client().admin().indices().prepareGetTemplates(Exporter.INDEX_TEMPLATE_NAME).get().getIndexTemplates()) {
            if (template.getName().equals(Exporter.INDEX_TEMPLATE_NAME)) {
                Version templateVersion = LocalExporter.templateVersion(template);
                if (templateVersion != null && templateVersion.id == version.id) {
                    return;
                }
                fail("did not find marvel template with expected version [" + version + "]. found version [" + templateVersion + "]");
            }
        }
        fail("marvel template could not be found");
    }

    private Version getCurrentlyInstalledTemplateVersion() {
        GetIndexTemplatesResponse response = client().admin().indices().prepareGetTemplates(Exporter.INDEX_TEMPLATE_NAME).get();
        assertThat(response, notNullValue());
        assertThat(response.getIndexTemplates(), notNullValue());
        assertThat(response.getIndexTemplates(), hasSize(1));
        assertThat(response.getIndexTemplates().get(0), notNullValue());
        return LocalExporter.templateVersion(response.getIndexTemplates().get(0));
    }
}
