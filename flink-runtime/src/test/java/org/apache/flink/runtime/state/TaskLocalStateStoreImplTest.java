/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.state;

import org.apache.flink.api.common.JobID;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.checkpoint.TaskStateSnapshot;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.concurrent.Executors;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.OperatorID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.powermock.api.mockito.PowerMockito.spy;

public class TaskLocalStateStoreImplTest {

	private TemporaryFolder temporaryFolder;
	private JobID jobID;
	private AllocationID allocationID;
	private JobVertexID jobVertexID;
	private int subtaskIdx;
	private File[] allocationBaseDirs;
	private TaskLocalStateStoreImpl taskLocalStateStore;

	@Before
	public void before() throws Exception {
		this.temporaryFolder = new TemporaryFolder();
		this.temporaryFolder.create();
		this.jobID = new JobID();
		this.allocationID = new AllocationID();
		this.jobVertexID = new JobVertexID();
		this.subtaskIdx = 0;
		this.allocationBaseDirs = new File[]{temporaryFolder.newFolder(), temporaryFolder.newFolder()};

		LocalRecoveryDirectoryProviderImpl directoryProvider =
			new LocalRecoveryDirectoryProviderImpl(allocationBaseDirs, jobID, jobVertexID, subtaskIdx);

		LocalRecoveryConfig localRecoveryConfig =
			new LocalRecoveryConfig(LocalRecoveryConfig.LocalRecoveryMode.DISABLED, directoryProvider);

		this.taskLocalStateStore = new TaskLocalStateStoreImpl(
			jobID,
			allocationID,
			jobVertexID,
			subtaskIdx,
			localRecoveryConfig,
			Executors.directExecutor());
	}

	@After
	public void after() {
		this.temporaryFolder.delete();
	}

	/**
	 * Test that the instance delivers a correctly configured LocalRecoveryDirectoryProvider.
	 */
	@Test
	public void getLocalRecoveryRootDirectoryProvider() {

		LocalRecoveryConfig directoryProvider = taskLocalStateStore.getLocalRecoveryConfig();
		Assert.assertEquals(
			allocationBaseDirs.length,
			directoryProvider.getLocalStateDirectoryProvider().allocationBaseDirsCount());

		for (int i = 0; i < allocationBaseDirs.length; ++i) {
			Assert.assertEquals(
				allocationBaseDirs[i],
				directoryProvider.getLocalStateDirectoryProvider().selectAllocationBaseDirectory(i));
		}
	}

	/**
	 * Tests basic store/retrieve of local state.
	 */
	@Test
	public void storeAndRetrieve() throws Exception {

		final int chkCount = 3;

		for (int i = 0; i < chkCount; ++i) {
			Assert.assertNull(taskLocalStateStore.retrieveLocalState(i));
		}

		List<TaskStateSnapshot> taskStateSnapshots = storeStates(chkCount);

		checkStoredAsExpected(taskStateSnapshots, 0, chkCount);

		Assert.assertNull(taskLocalStateStore.retrieveLocalState(chkCount + 1));
	}

	/**
	 * Tests pruning of previous checkpoints if a new checkpoint is confirmed.
	 */
	@Test
	public void confirmCheckpoint() throws Exception {

		final int chkCount = 3;
		final int confirmed = chkCount - 1;
		List<TaskStateSnapshot> taskStateSnapshots = storeStates(chkCount);
		taskLocalStateStore.confirmCheckpoint(confirmed);
		checkPrunedAndDiscarded(taskStateSnapshots, 0, confirmed);
		checkStoredAsExpected(taskStateSnapshots, confirmed, chkCount);
	}

	/**
	 * Tests that disposal of a {@link TaskLocalStateStoreImpl} works and discards all local states.
	 */
	@Test
	public void dispose() throws Exception {
		final int chkCount = 3;
		final int confirmed = chkCount - 1;
		List<TaskStateSnapshot> taskStateSnapshots = storeStates(chkCount);
		taskLocalStateStore.confirmCheckpoint(confirmed);
		taskLocalStateStore.dispose();

		checkPrunedAndDiscarded(taskStateSnapshots, 0, chkCount);
	}

	private void checkStoredAsExpected(List<TaskStateSnapshot> history, int off, int len) throws Exception {
		for (int i = off; i < len; ++i) {
			TaskStateSnapshot expected = history.get(i);
			Assert.assertTrue(expected == taskLocalStateStore.retrieveLocalState(i));
			Mockito.verify(expected, Mockito.never()).discardState();
		}
	}

	private void checkPrunedAndDiscarded(List<TaskStateSnapshot> history, int off, int len) throws Exception {
		for (int i = off; i < len; ++i) {
			Assert.assertNull(taskLocalStateStore.retrieveLocalState(i));
			Mockito.verify(history.get(i)).discardState();
		}
	}

	private List<TaskStateSnapshot> storeStates(int count) {
		List<TaskStateSnapshot> taskStateSnapshots = new ArrayList<>(count);
		for (int i = 0; i < count; ++i) {
			OperatorID operatorID = new OperatorID();
			TaskStateSnapshot taskStateSnapshot = spy(new TaskStateSnapshot());
			OperatorSubtaskState operatorSubtaskState = new OperatorSubtaskState();
			taskStateSnapshot.putSubtaskStateByOperatorID(operatorID, operatorSubtaskState);
			taskLocalStateStore.storeLocalState(i, taskStateSnapshot);
			taskStateSnapshots.add(taskStateSnapshot);
		}
		return taskStateSnapshots;
	}
}
