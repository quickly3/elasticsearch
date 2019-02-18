/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.delete.DeleteAction;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.ml.MlTasks;
import org.elasticsearch.xpack.core.ml.action.DeleteDataFrameAnalyticsAction;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsConfig;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsState;
import org.elasticsearch.xpack.core.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;

import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;

/**
 * The action is a master node action to ensure it reads an up-to-date cluster
 * state in order to determine whether there is a persistent task for the analytics
 * to delete.
 */
public class TransportDeleteDataFrameAnalyticsAction
    extends TransportMasterNodeAction<DeleteDataFrameAnalyticsAction.Request, AcknowledgedResponse> {

    private final Client client;

    @Inject
    public TransportDeleteDataFrameAnalyticsAction(TransportService transportService, ClusterService clusterService,
                                                      ThreadPool threadPool, ActionFilters actionFilters,
                                                      IndexNameExpressionResolver indexNameExpressionResolver, Client client) {
        super(DeleteDataFrameAnalyticsAction.NAME, transportService, clusterService, threadPool, actionFilters, indexNameExpressionResolver,
            DeleteDataFrameAnalyticsAction.Request::new);
        this.client = client;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected AcknowledgedResponse newResponse() {
        return new AcknowledgedResponse();
    }

    @Override
    protected void masterOperation(DeleteDataFrameAnalyticsAction.Request request, ClusterState state,
                                   ActionListener<AcknowledgedResponse> listener) {
        PersistentTasksCustomMetaData tasks = state.getMetaData().custom(PersistentTasksCustomMetaData.TYPE);
        DataFrameAnalyticsState taskState = MlTasks.getDataFrameAnalyticsState(request.getId(), tasks);
        if (taskState != DataFrameAnalyticsState.STOPPED) {
            listener.onFailure(ExceptionsHelper.conflictStatusException("Cannot delete data frame analytics [{}] while its status is [{}]",
                request.getId(), taskState));
            return;
        }

        DeleteRequest deleteRequest = new DeleteRequest(AnomalyDetectorsIndex.configIndexName());
        deleteRequest.id(DataFrameAnalyticsConfig.documentId(request.getId()));
        deleteRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        executeAsyncWithOrigin(client, ML_ORIGIN, DeleteAction.INSTANCE, deleteRequest, ActionListener.wrap(
            deleteResponse -> {
                if (deleteResponse.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                    listener.onFailure(ExceptionsHelper.missingDataFrameAnalytics(request.getId()));
                    return;
                }
                assert deleteResponse.getResult() == DocWriteResponse.Result.DELETED;
                listener.onResponse(new AcknowledgedResponse(true));
            },
            listener::onFailure
        ));
    }

    @Override
    protected ClusterBlockException checkBlock(DeleteDataFrameAnalyticsAction.Request request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }
}