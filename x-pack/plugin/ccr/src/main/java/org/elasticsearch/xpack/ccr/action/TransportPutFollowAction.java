/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ccr.action;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.ActiveShardsObserver;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.license.LicenseUtils;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.ccr.Ccr;
import org.elasticsearch.xpack.ccr.CcrLicenseChecker;
import org.elasticsearch.xpack.ccr.CcrSettings;
import org.elasticsearch.xpack.core.ccr.action.PutFollowAction;
import org.elasticsearch.xpack.core.ccr.action.ResumeFollowAction;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class TransportPutFollowAction
        extends TransportMasterNodeAction<PutFollowAction.Request, PutFollowAction.Response> {

    private final Client client;
    private final AllocationService allocationService;
    private final ActiveShardsObserver activeShardsObserver;
    private final CcrLicenseChecker ccrLicenseChecker;

    @Inject
    public TransportPutFollowAction(
            final Settings settings,
            final ThreadPool threadPool,
            final TransportService transportService,
            final ClusterService clusterService,
            final ActionFilters actionFilters,
            final IndexNameExpressionResolver indexNameExpressionResolver,
            final Client client,
            final AllocationService allocationService,
            final CcrLicenseChecker ccrLicenseChecker) {
        super(
                settings,
                PutFollowAction.NAME,
                transportService,
                clusterService,
                threadPool,
                actionFilters,
                indexNameExpressionResolver,
                PutFollowAction.Request::new);
        this.client = client;
        this.allocationService = allocationService;
        this.activeShardsObserver = new ActiveShardsObserver(settings, clusterService, threadPool);
        this.ccrLicenseChecker = Objects.requireNonNull(ccrLicenseChecker);
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected PutFollowAction.Response newResponse() {
        return new PutFollowAction.Response();
    }

    @Override
    protected void masterOperation(
            final PutFollowAction.Request request,
            final ClusterState state,
            final ActionListener<PutFollowAction.Response> listener) throws Exception {
        if (ccrLicenseChecker.isCcrAllowed() == false) {
            listener.onFailure(LicenseUtils.newComplianceException("ccr"));
            return;
        }
        String clusterAlias = request.getFollowRequest().getLeaderCluster();
        if (clusterAlias == null) {
            createFollowerIndexAndFollowLocalIndex(request, state, listener);
        } else {
            // In the case of following a local index there is no cluster alias:
            client.getRemoteClusterClient(clusterAlias);
            String leaderIndex = request.getFollowRequest().getLeaderIndex();
            createFollowerIndexAndFollowRemoteIndex(request, clusterAlias, leaderIndex, listener);
        }
    }

    private void createFollowerIndexAndFollowLocalIndex(
            final PutFollowAction.Request request,
            final ClusterState state,
            final ActionListener<PutFollowAction.Response> listener) {
        // following an index in local cluster, so use local cluster state to fetch leader index metadata
        final String leaderIndex = request.getFollowRequest().getLeaderIndex();
        final IndexMetaData leaderIndexMetadata = state.getMetaData().index(leaderIndex);
        if (leaderIndexMetadata == null) {
            listener.onFailure(new IndexNotFoundException(leaderIndex));
            return;
        }

        Consumer<String[]> historyUUIDhandler = historyUUIDs -> {
            createFollowerIndex(leaderIndexMetadata, historyUUIDs, request, listener);
        };
        ccrLicenseChecker.hasPrivilegesToFollowIndices(client, new String[] {leaderIndex}, e -> {
            if (e == null) {
                ccrLicenseChecker.fetchLeaderHistoryUUIDs(client, leaderIndexMetadata, listener::onFailure, historyUUIDhandler);
            } else {
                listener.onFailure(e);
            }
        });
    }

    private void createFollowerIndexAndFollowRemoteIndex(
            final PutFollowAction.Request request,
            final String clusterAlias,
            final String leaderIndex,
            final ActionListener<PutFollowAction.Response> listener) {
        ccrLicenseChecker.checkRemoteClusterLicenseAndFetchLeaderIndexMetadataAndHistoryUUIDs(
                client,
                clusterAlias,
                leaderIndex,
                listener::onFailure,
                (historyUUID, leaderIndexMetaData) -> createFollowerIndex(leaderIndexMetaData, historyUUID, request, listener));
    }

    private void createFollowerIndex(
            final IndexMetaData leaderIndexMetaData,
            final String[] historyUUIDs,
            final PutFollowAction.Request request,
            final ActionListener<PutFollowAction.Response> listener) {
        if (leaderIndexMetaData == null) {
            listener.onFailure(new IllegalArgumentException("leader index [" + request.getFollowRequest().getLeaderIndex() +
                    "] does not exist"));
            return;
        }

        ActionListener<Boolean> handler = ActionListener.wrap(
                result -> {
                    if (result) {
                        initiateFollowing(request, listener);
                    } else {
                        listener.onResponse(new PutFollowAction.Response(true, false, false));
                    }
                },
                listener::onFailure);
        // Can't use create index api here, because then index templates can alter the mappings / settings.
        // And index templates could introduce settings / mappings that are incompatible with the leader index.
        clusterService.submitStateUpdateTask("create_following_index", new AckedClusterStateUpdateTask<Boolean>(request, handler) {

            @Override
            protected Boolean newResponse(final boolean acknowledged) {
                return acknowledged;
            }

            @Override
            public ClusterState execute(final ClusterState currentState) throws Exception {
                String followIndex = request.getFollowRequest().getFollowerIndex();
                IndexMetaData currentIndex = currentState.metaData().index(followIndex);
                if (currentIndex != null) {
                    throw new ResourceAlreadyExistsException(currentIndex.getIndex());
                }

                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                IndexMetaData.Builder imdBuilder = IndexMetaData.builder(followIndex);

                // Adding the leader index uuid for each shard as custom metadata:
                Map<String, String> metadata = new HashMap<>();
                metadata.put(Ccr.CCR_CUSTOM_METADATA_LEADER_INDEX_SHARD_HISTORY_UUIDS, String.join(",", historyUUIDs));
                metadata.put(Ccr.CCR_CUSTOM_METADATA_LEADER_INDEX_UUID_KEY, leaderIndexMetaData.getIndexUUID());
                imdBuilder.putCustom(Ccr.CCR_CUSTOM_METADATA_KEY, metadata);

                // Copy all settings, but overwrite a few settings.
                Settings.Builder settingsBuilder = Settings.builder();
                settingsBuilder.put(leaderIndexMetaData.getSettings());
                // Overwriting UUID here, because otherwise we can't follow indices in the same cluster
                settingsBuilder.put(IndexMetaData.SETTING_INDEX_UUID, UUIDs.randomBase64UUID());
                settingsBuilder.put(IndexMetaData.SETTING_INDEX_PROVIDED_NAME, followIndex);
                settingsBuilder.put(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), true);
                imdBuilder.settings(settingsBuilder);

                // Copy mappings from leader IMD to follow IMD
                for (ObjectObjectCursor<String, MappingMetaData> cursor : leaderIndexMetaData.getMappings()) {
                    imdBuilder.putMapping(cursor.value);
                }
                imdBuilder.setRoutingNumShards(leaderIndexMetaData.getRoutingNumShards());
                IndexMetaData followIMD = imdBuilder.build();
                mdBuilder.put(followIMD, false);

                ClusterState.Builder builder = ClusterState.builder(currentState);
                builder.metaData(mdBuilder.build());
                ClusterState updatedState = builder.build();

                RoutingTable.Builder routingTableBuilder = RoutingTable.builder(updatedState.routingTable())
                        .addAsNew(updatedState.metaData().index(request.getFollowRequest().getFollowerIndex()));
                updatedState = allocationService.reroute(
                        ClusterState.builder(updatedState).routingTable(routingTableBuilder.build()).build(),
                        "follow index [" + request.getFollowRequest().getFollowerIndex() + "] created");

                logger.info("[{}] creating index, cause [ccr_create_and_follow], shards [{}]/[{}]",
                        followIndex, followIMD.getNumberOfShards(), followIMD.getNumberOfReplicas());

                return updatedState;
            }
        });
    }

    private void initiateFollowing(
            final PutFollowAction.Request request,
            final ActionListener<PutFollowAction.Response> listener) {
        activeShardsObserver.waitForActiveShards(new String[]{request.getFollowRequest().getFollowerIndex()},
                ActiveShardCount.DEFAULT, request.timeout(), result -> {
                    if (result) {
                        client.execute(ResumeFollowAction.INSTANCE, request.getFollowRequest(), ActionListener.wrap(
                                r -> listener.onResponse(new PutFollowAction.Response(true, true, r.isAcknowledged())),
                                listener::onFailure
                        ));
                    } else {
                        listener.onResponse(new PutFollowAction.Response(true, false, false));
                    }
                }, listener::onFailure);
    }

    @Override
    protected ClusterBlockException checkBlock(final PutFollowAction.Request request, final ClusterState state) {
        return state.blocks().indexBlockedException(ClusterBlockLevel.METADATA_WRITE, request.getFollowRequest().getFollowerIndex());
    }

}