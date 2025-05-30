/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearchmetrics.util;

import com.google.common.collect.Iterables;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.delete.DeleteResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.CreateIndexResponse;
import org.opensearch.action.support.master.AcknowledgedResponse;
import org.opensearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class OpenSearchUtil {
    private static final int NUM_REPLICAS = 2;
    private static final int NUM_THREADS = 8;
    private static final int OS_BULK_SIZE = 200;
    private static final int INDEX_THREAD_TIMEOUT_MINUTES = 10;

    private final RestHighLevelClient client;

    public OpenSearchUtil(RestHighLevelClient client) {
        this.client = client;
    }

    public void createIndexIfNotExists(String index, Optional<String> aliasName) {
        GetIndexRequest getIndexRequest = new GetIndexRequest(index);
        try {
            if (!client.indices().exists(getIndexRequest, RequestOptions.DEFAULT)) {
                CreateIndexRequest createIndexRequest = new CreateIndexRequest(index);
                createIndexRequest.settings(Settings.builder()
                        .put("index.number_of_replicas", NUM_REPLICAS)
                        .build());
                System.out.println("Creating index " + index);
                CreateIndexResponse createIndexResponse =
                        null;
                try {
                    createIndexResponse = client.indices().create(createIndexRequest, RequestOptions.DEFAULT);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                System.out.println("Create index " + createIndexResponse.index() + ", acknowledged = " + createIndexResponse.isAcknowledged() + ", shard acknowledged = " + createIndexResponse.isShardsAcknowledged());
                //Adds alias if requested to the index after the index is successfully created
                if(aliasName.isPresent()) {

                    IndicesAliasesRequest indicesAliasesRequest = new IndicesAliasesRequest()
                            .addAliasAction(
                                    IndicesAliasesRequest.AliasActions.add()
                                            .index(index)
                                            .alias(aliasName.get())
                            );
                    try {
                        AcknowledgedResponse indicesAliasesResponse = client.indices().updateAliases(indicesAliasesRequest, RequestOptions.DEFAULT);
                        System.out.println("Alias creation acknowledged " + indicesAliasesResponse.isAcknowledged() + "for the index-" + index);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

            } else {
                System.out.println("Index " + index + " already exists, skip creating index.");
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Bulk index json data into an OpenSearch index.
     *
     * @param index   name of the index
     * @param jsonMap key/value pair where key is the id of the doc and value is the json string
     * @throws Exception if indexing failed
     */
    public void bulkIndex(@NonNull String index, @NonNull Map<String, String> jsonMap) {
        if (jsonMap.isEmpty()) {
            System.out.println("Empty data received for indexing");
            return;
        }
        var forkJoinPool = new ForkJoinPool(NUM_THREADS);
        Set<Map.Entry<String, String>> entries = jsonMap.entrySet();
        var partitions = Iterables.partition(entries, Math.max(1, entries.size() / NUM_THREADS));
        try {
            forkJoinPool.submit(() -> partitions.forEach(p -> bulkIndexInBatches(index, p)))
                    .get(INDEX_THREAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private void bulkIndexInBatches(String index, List<Map.Entry<String, String>> partition) {
        System.out.println("Started bulk indexing...");
        BulkRequest bulkRequest = new BulkRequest();
        for (Map.Entry<String, String> entry : partition) {
            bulkRequest.add(new IndexRequest()
                    .index(index)
                    .id(entry.getKey())
                    .source(entry.getValue(), XContentType.JSON));
            if (bulkRequest.numberOfActions() >= OS_BULK_SIZE) {
                execBulkRequest(bulkRequest, client);
                bulkRequest = new BulkRequest();
            }
        }
        if (bulkRequest.numberOfActions() > 0) {
            execBulkRequest(bulkRequest, client);
        }
        System.out.println("Bulk indexing finished on thread");
    }

    private static void execBulkRequest(BulkRequest bulkRequest, RestHighLevelClient client) {
        try {
            BulkResponse response = client.bulk(bulkRequest, RequestOptions.DEFAULT);
            if (response.hasFailures()) {
                System.out.println("Bulk index has errors: " + response.buildFailureMessage());
            }
        } catch (IOException ioException) {
            System.out.println ("Error " + ioException);
        }
    }

    public void deleteDocument(String issueIndex, String docId) {
        DeleteRequest request = new DeleteRequest(
                issueIndex,
                docId);
        try {
            DeleteResponse response = client.delete(request, RequestOptions.DEFAULT);
            if (response.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                log.error("Index removal failed - document not found by id: {}", docId);
            }
        } catch (IOException e) {
            log.error(e.getMessage());
            throw new RuntimeException("Failed to remove the document:", e);
        }
    }

    public SearchResponse search(SearchRequest searchRequest) {
        try {
            return client.search(searchRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
