/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.kinesis.source.reader;

import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsAddition;
import org.apache.flink.connector.kinesis.source.proxy.StreamProxy;
import org.apache.flink.connector.kinesis.source.split.KinesisShardSplit;
import org.apache.flink.connector.kinesis.source.util.KinesisStreamProxyProvider.TestKinesisStreamProxy;
import org.apache.flink.connector.kinesis.source.util.TestUtil;

import org.apache.flink.shaded.guava30.com.google.common.collect.ImmutableList;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kinesis.model.Record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.apache.flink.connector.kinesis.source.util.KinesisStreamProxyProvider.getTestStreamProxy;
import static org.apache.flink.connector.kinesis.source.util.TestUtil.generateShardId;
import static org.apache.flink.connector.kinesis.source.util.TestUtil.getTestRecord;
import static org.apache.flink.connector.kinesis.source.util.TestUtil.getTestSplit;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatNoException;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

class PollingKinesisShardSplitReaderTest {
    @Test
    void testNoAssignedSplitsHandledGracefully() throws Exception {
        StreamProxy testStreamProxy = getTestStreamProxy();
        PollingKinesisShardSplitReader splitReader =
                new PollingKinesisShardSplitReader(testStreamProxy);

        RecordsWithSplitIds<Record> retrievedRecords = splitReader.fetch();

        assertThat(retrievedRecords.nextRecordFromSplit()).isNull();
        assertThat(retrievedRecords.nextSplit()).isNull();
        assertThat(retrievedRecords.finishedSplits()).isEmpty();
    }

    @Test
    void testAssignedSplitHasNoRecordsHandledGracefully() throws Exception {
        TestKinesisStreamProxy testStreamProxy = getTestStreamProxy();
        PollingKinesisShardSplitReader splitReader =
                new PollingKinesisShardSplitReader(testStreamProxy);

        // Given assigned split with no records
        String shardId = generateShardId(1);
        testStreamProxy.addShards(shardId);
        splitReader.handleSplitsChanges(
                new SplitsAddition<>(ImmutableList.of(getTestSplit(shardId))));

        // When fetching records
        RecordsWithSplitIds<Record> retrievedRecords = splitReader.fetch();

        // Then retrieve no records
        assertThat(retrievedRecords.nextRecordFromSplit()).isNull();
        assertThat(retrievedRecords.nextSplit()).isNull();
        assertThat(retrievedRecords.finishedSplits()).isEmpty();
    }

    @Test
    void testSingleAssignedSplitAllConsumed() throws Exception {
        TestKinesisStreamProxy testStreamProxy = getTestStreamProxy();
        PollingKinesisShardSplitReader splitReader =
                new PollingKinesisShardSplitReader(testStreamProxy);

        // Given assigned split with records
        String shardId = generateShardId(1);
        testStreamProxy.addShards(shardId);
        List<Record> expectedRecords =
                ImmutableList.of(
                        getTestRecord("data-1"), getTestRecord("data-2"), getTestRecord("data-3"));
        testStreamProxy.addRecords(
                TestUtil.STREAM_ARN, shardId, ImmutableList.of(expectedRecords.get(0)));
        testStreamProxy.addRecords(
                TestUtil.STREAM_ARN, shardId, ImmutableList.of(expectedRecords.get(1)));
        testStreamProxy.addRecords(
                TestUtil.STREAM_ARN, shardId, ImmutableList.of(expectedRecords.get(2)));
        splitReader.handleSplitsChanges(
                new SplitsAddition<>(ImmutableList.of(getTestSplit(shardId))));

        // When fetching records
        List<Record> records = new ArrayList<>();
        for (int i = 0; i < expectedRecords.size(); i++) {
            RecordsWithSplitIds<Record> retrievedRecords = splitReader.fetch();
            records.addAll(readAllRecords(retrievedRecords));
        }

        assertThat(records).containsExactlyInAnyOrderElementsOf(expectedRecords);
    }

    @Test
    void testMultipleAssignedSplitsAllConsumed() throws Exception {
        TestKinesisStreamProxy testStreamProxy = getTestStreamProxy();
        PollingKinesisShardSplitReader splitReader =
                new PollingKinesisShardSplitReader(testStreamProxy);

        // Given assigned split with records
        String shardId = generateShardId(1);
        testStreamProxy.addShards(shardId);
        List<Record> expectedRecords =
                ImmutableList.of(
                        getTestRecord("data-1"), getTestRecord("data-2"), getTestRecord("data-3"));
        testStreamProxy.addRecords(
                TestUtil.STREAM_ARN, shardId, ImmutableList.of(expectedRecords.get(0)));
        testStreamProxy.addRecords(
                TestUtil.STREAM_ARN, shardId, ImmutableList.of(expectedRecords.get(1)));
        testStreamProxy.addRecords(
                TestUtil.STREAM_ARN, shardId, ImmutableList.of(expectedRecords.get(2)));
        splitReader.handleSplitsChanges(
                new SplitsAddition<>(ImmutableList.of(getTestSplit(shardId))));

        // When records are fetched
        List<Record> fetchedRecords = new ArrayList<>();
        for (int i = 0; i < expectedRecords.size(); i++) {
            RecordsWithSplitIds<Record> retrievedRecords = splitReader.fetch();
            fetchedRecords.addAll(readAllRecords(retrievedRecords));
        }

        // Then all records are fetched
        assertThat(fetchedRecords).containsExactlyInAnyOrderElementsOf(expectedRecords);
    }

    @Test
    void testHandleEmptyCompletedShard() throws Exception {
        TestKinesisStreamProxy testStreamProxy = getTestStreamProxy();
        PollingKinesisShardSplitReader splitReader =
                new PollingKinesisShardSplitReader(testStreamProxy);

        // Given assigned split with no records, and the shard is complete
        String shardId = generateShardId(1);
        testStreamProxy.addShards(shardId);
        testStreamProxy.addRecords(TestUtil.STREAM_ARN, shardId, Collections.emptyList());
        KinesisShardSplit split = getTestSplit(shardId);
        splitReader.handleSplitsChanges(new SplitsAddition<>(ImmutableList.of(split)));
        testStreamProxy.setShouldCompleteNextShard(true);

        // When fetching records
        RecordsWithSplitIds<Record> retrievedRecords = splitReader.fetch();

        // Returns completed split with no records
        assertThat(retrievedRecords.nextRecordFromSplit()).isNull();
        assertThat(retrievedRecords.nextSplit()).isNull();
        assertThat(retrievedRecords.finishedSplits()).contains(split.splitId());
    }

    @Test
    void testFinishedSplitsReturned() throws Exception {
        TestKinesisStreamProxy testStreamProxy = getTestStreamProxy();
        PollingKinesisShardSplitReader splitReader =
                new PollingKinesisShardSplitReader(testStreamProxy);

        // Given assigned split with records from completed shard
        String shardId = generateShardId(1);
        testStreamProxy.addShards(shardId);
        List<Record> expectedRecords =
                ImmutableList.of(
                        getTestRecord("data-1"), getTestRecord("data-2"), getTestRecord("data-3"));
        testStreamProxy.addRecords(TestUtil.STREAM_ARN, shardId, expectedRecords);
        KinesisShardSplit split = getTestSplit(shardId);
        splitReader.handleSplitsChanges(new SplitsAddition<>(ImmutableList.of(split)));

        // When fetching records
        List<Record> fetchedRecords = new ArrayList<>();
        testStreamProxy.setShouldCompleteNextShard(true);
        RecordsWithSplitIds<Record> retrievedRecords = splitReader.fetch();

        // Then records can be read successfully, with finishedSplit returned once all records are
        // completed
        for (int i = 0; i < expectedRecords.size(); i++) {
            assertThat(retrievedRecords.nextSplit()).isEqualTo(split.splitId());
            assertThat(retrievedRecords.finishedSplits()).isEmpty();
            fetchedRecords.add(retrievedRecords.nextRecordFromSplit());
        }
        assertThat(retrievedRecords.nextSplit()).isNull();
        assertThat(retrievedRecords.finishedSplits()).contains(split.splitId());
        assertThat(fetchedRecords).containsExactlyInAnyOrderElementsOf(expectedRecords);
    }

    @Test
    void testWakeUpIsNoOp() {
        TestKinesisStreamProxy testStreamProxy = getTestStreamProxy();
        PollingKinesisShardSplitReader splitReader =
                new PollingKinesisShardSplitReader(testStreamProxy);

        assertThatNoException().isThrownBy(splitReader::wakeUp);
    }

    @Test
    void testCloseClosesStreamProxy() {
        TestKinesisStreamProxy testStreamProxy = getTestStreamProxy();
        PollingKinesisShardSplitReader splitReader =
                new PollingKinesisShardSplitReader(testStreamProxy);

        assertThatNoException().isThrownBy(splitReader::close);
        assertThat(testStreamProxy.isClosed()).isTrue();
    }

    private List<Record> readAllRecords(RecordsWithSplitIds<Record> recordsWithSplitIds) {
        List<Record> outputRecords = new ArrayList<>();
        Record record;
        do {
            record = recordsWithSplitIds.nextRecordFromSplit();
            if (record != null) {
                outputRecords.add(record);
            }
        } while (record != null);

        return outputRecords;
    }
}
