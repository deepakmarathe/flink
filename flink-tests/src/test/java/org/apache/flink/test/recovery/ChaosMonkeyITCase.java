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

package org.apache.flink.test.recovery;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import org.apache.commons.io.FileUtils;
import org.apache.flink.api.common.JobID;
import org.apache.flink.configuration.AkkaOptions;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.runtime.akka.AkkaUtils;
import org.apache.flink.runtime.akka.ListeningBehaviour;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.highavailability.HighAvailabilityServicesUtils;
import org.apache.flink.runtime.instance.AkkaActorGateway;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobStatus;
import org.apache.flink.runtime.leaderelection.TestingListener;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalService;
import org.apache.flink.runtime.messages.JobManagerMessages;
import org.apache.flink.runtime.state.filesystem.FsStateBackendFactory;
import org.apache.flink.runtime.testingUtils.TestingUtils;
import org.apache.flink.runtime.testutils.CommonTestUtils;
import org.apache.flink.runtime.testutils.JobManagerActorTestUtils;
import org.apache.flink.runtime.testutils.JobManagerProcess;
import org.apache.flink.runtime.testutils.TaskManagerProcess;
import org.apache.flink.runtime.testutils.TestJvmProcess;
import org.apache.flink.runtime.testutils.ZooKeeperTestUtils;
import org.apache.flink.runtime.zookeeper.ZooKeeperTestEnvironment;
import org.apache.flink.runtime.state.CheckpointListener;
import org.apache.flink.streaming.api.checkpoint.ListCheckpointed;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.util.TestLogger;
import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Deadline;
import scala.concurrent.duration.FiniteDuration;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@Ignore
public class ChaosMonkeyITCase extends TestLogger {

	private static final Logger LOG = LoggerFactory.getLogger(ChaosMonkeyITCase.class);

	private final static ZooKeeperTestEnvironment ZooKeeper = new ZooKeeperTestEnvironment(1);

	private final static File FileStateBackendBasePath;

	private final static File CheckpointCompletedCoordination;

	private final static File ProceedCoordination;

	private final static String COMPLETED_PREFIX = "completed_";

	private final static long LastElement = -1;

	private final Random rand = new Random();

	private int jobManagerPid;
	private int taskManagerPid;

	static {
		try {
			FileStateBackendBasePath = CommonTestUtils.createTempDirectory();
			CheckpointCompletedCoordination = new File(FileStateBackendBasePath, COMPLETED_PREFIX);
			ProceedCoordination = new File(FileStateBackendBasePath, "proceed");
		}
		catch (IOException e) {
			throw new RuntimeException("Error in test setup. Could not create directory.", e);
		}
	}

	@AfterClass
	public static void tearDown() throws Exception {
		if (ZooKeeper != null) {
			ZooKeeper.shutdown();
		}

		if (FileStateBackendBasePath != null) {
			FileUtils.deleteDirectory(FileStateBackendBasePath);
		}
	}

	@Test
	public void testChaosMonkey() throws Exception {
		// Test config
		final int numberOfJobManagers = 3;
		final int numberOfTaskManagers = 3;
		final int numberOfSlotsPerTaskManager = 2;

		// The final count each source is counting to: 1...n
		final int n = 5000;

		// Parallelism for the program
		final int parallelism = numberOfTaskManagers * numberOfSlotsPerTaskManager;

		// The test should not run longer than this
		final FiniteDuration testDuration = new FiniteDuration(10, TimeUnit.MINUTES);

		// Every x seconds a random job or task manager is killed
		//
		// The job will will be running for $killEvery seconds and then a random Job/TaskManager
		// will be killed. On recovery (which takes some time to bring up the new process etc.),
		// this test will wait for task managers to reconnect before starting the next count down.
		// Therefore the delay between retries is not important in this setup.
		final FiniteDuration killEvery = new FiniteDuration(5, TimeUnit.SECONDS);

		// Trigger a checkpoint every
		final int checkpointingIntervalMs = 1000;

		// Total number of kills
		final int totalNumberOfKills = 10;

		// -----------------------------------------------------------------------------------------

		// Setup
		Configuration config = ZooKeeperTestUtils.createZooKeeperHAConfig(
				ZooKeeper.getConnectString(), FileStateBackendBasePath.toURI().toString());

		// Akka and restart timeouts
		config.setString(AkkaOptions.WATCH_HEARTBEAT_INTERVAL, "1000 ms");
		config.setString(AkkaOptions.WATCH_HEARTBEAT_PAUSE, "6 s");
		config.setInteger(AkkaOptions.WATCH_THRESHOLD, 9);

		if (checkpointingIntervalMs >= killEvery.toMillis()) {
			throw new IllegalArgumentException("Relax! You want to kill processes every " +
					killEvery + ", but the checkpointing interval is " +
					checkpointingIntervalMs / 1000 + " seconds. Either decrease the interval or " +
					"increase the kill interval. Otherwise, the program will not complete any " +
					"checkpoint.");
		}

		// Task manager
		config.setInteger(ConfigConstants.TASK_MANAGER_NUM_TASK_SLOTS, numberOfSlotsPerTaskManager);

		final HighAvailabilityServices highAvailabilityServices = HighAvailabilityServicesUtils.createHighAvailabilityServices(
			config,
			TestingUtils.defaultExecutor(),
			HighAvailabilityServicesUtils.AddressResolution.NO_ADDRESS_RESOLUTION);

		ActorSystem testActorSystem = null;
		LeaderRetrievalService leaderRetrievalService = null;
		List<JobManagerProcess> jobManagerProcesses = new ArrayList<>();
		List<TaskManagerProcess> taskManagerProcesses = new ArrayList<>();

		try {
			// Initial state
			for (int i = 0; i < numberOfJobManagers; i++) {
				jobManagerProcesses.add(createAndStartJobManagerProcess(config));
			}

			for (int i = 0; i < numberOfTaskManagers; i++) {
				taskManagerProcesses.add(createAndStartTaskManagerProcess(config));
			}

			testActorSystem = AkkaUtils.createDefaultActorSystem();

			// Leader listener
			leaderRetrievalService = highAvailabilityServices.getJobManagerLeaderRetriever(HighAvailabilityServices.DEFAULT_JOB_ID);
			TestingListener leaderListener = new TestingListener();
			leaderRetrievalService.start(leaderListener);

			Deadline deadline = testDuration.fromNow();

			// Wait for the new leader
			int leaderIndex = waitForNewLeader(
					leaderListener, jobManagerProcesses, deadline.timeLeft());

			// Wait for the task managers to connect
			waitForTaskManagers(
					numberOfTaskManagers,
					jobManagerProcesses.get(leaderIndex),
					testActorSystem,
					deadline.timeLeft());

			// The job
			JobGraph jobGraph = createJobGraph(n, CheckpointCompletedCoordination.getPath(),
					ProceedCoordination.getPath(), parallelism, checkpointingIntervalMs);

			LOG.info("Submitting job {}", jobGraph.getJobID());
			submitJobGraph(jobGraph, jobManagerProcesses.get(leaderIndex), leaderListener,
					testActorSystem, deadline.timeLeft());

			LOG.info("Waiting for a checkpoint to complete before kicking off chaos");

			// Wait for a checkpoint to complete
			TestJvmProcess.waitForMarkerFiles(FileStateBackendBasePath, COMPLETED_PREFIX,
					parallelism, deadline.timeLeft().toMillis());

			LOG.info("Checkpoint completed... ready for chaos");

			int currentKillNumber = 1;
			int currentJobManagerKills = 0;
			int currentTaskManagerKills = 0;

			for (int i = 0; i < totalNumberOfKills; i++) {
				LOG.info("Waiting for {} before next kill ({}/{})", killEvery, currentKillNumber++, totalNumberOfKills);
				Thread.sleep(killEvery.toMillis());

				LOG.info("Checking job status...");

				JobStatus jobStatus = requestJobStatus(jobGraph.getJobID(),
						jobManagerProcesses.get(leaderIndex), testActorSystem, deadline.timeLeft());

				if (jobStatus != JobStatus.RUNNING && jobStatus != JobStatus.FINISHED) {
					// Wait for it to run
					LOG.info("Waiting for job status {}", JobStatus.RUNNING);
					waitForJobRunning(jobGraph.getJobID(), jobManagerProcesses.get(leaderIndex),
							testActorSystem, deadline.timeLeft());
				}
				else if (jobStatus == JobStatus.FINISHED) {
					// Early finish
					LOG.info("Job finished");
					return;
				}
				else {
					LOG.info("Job status is {}", jobStatus);
				}

				if (rand.nextBoolean()) {
					LOG.info("Killing the leading JobManager");

					JobManagerProcess newJobManager = createAndStartJobManagerProcess(config);

					JobManagerProcess leader = jobManagerProcesses.remove(leaderIndex);
					leader.destroy();
					currentJobManagerKills++;

					LOG.info("Killed {}", leader);

					// Make sure to add the new job manager before looking for a new leader
					jobManagerProcesses.add(newJobManager);

					// Wait for the new leader
					leaderIndex = waitForNewLeader(
							leaderListener, jobManagerProcesses, deadline.timeLeft());

					// Wait for the task managers to connect
					waitForTaskManagers(
							numberOfTaskManagers,
							jobManagerProcesses.get(leaderIndex),
							testActorSystem,
							deadline.timeLeft());
				}
				else {
					LOG.info("Killing a random TaskManager");
					TaskManagerProcess newTaskManager = createAndStartTaskManagerProcess(config);

					// Wait for this new task manager to be connected
					waitForTaskManagers(
							numberOfTaskManagers + 1,
							jobManagerProcesses.get(leaderIndex),
							testActorSystem,
							deadline.timeLeft());

					// Now it's safe to kill a process
					int next = rand.nextInt(numberOfTaskManagers);
					TaskManagerProcess taskManager = taskManagerProcesses.remove(next);

					LOG.info("{} has been chosen. Killing process...", taskManager);

					taskManager.destroy();
					currentTaskManagerKills++;

					// Add the new task manager after killing an old one
					taskManagerProcesses.add(newTaskManager);
				}
			}

			LOG.info("Chaos is over. Total kills: {} ({} job manager + {} task managers). " +
							"Checking job status...",
					totalNumberOfKills, currentJobManagerKills, currentTaskManagerKills);

			// Signal the job to speed up (if it is not done yet)
			TestJvmProcess.touchFile(ProceedCoordination);

			// Wait for the job to finish
			LOG.info("Waiting for job status {}", JobStatus.FINISHED);
			waitForJobFinished(jobGraph.getJobID(), jobManagerProcesses.get(leaderIndex),
					testActorSystem, deadline.timeLeft());

			LOG.info("Job finished");

			LOG.info("Waiting for job removal");
			waitForJobRemoved(jobGraph.getJobID(), jobManagerProcesses.get(leaderIndex),
					testActorSystem, deadline.timeLeft());
			LOG.info("Job removed");

			LOG.info("Checking clean recovery state...");
			checkCleanRecoveryState(config);
			LOG.info("Recovery state clean");
		}
		catch (Throwable t) {
			// Print early (in some situations the process logs get too big
			// for Travis and the root problem is not shown)
			t.printStackTrace();

			System.out.println("#################################################");
			System.out.println(" TASK MANAGERS");
			System.out.println("#################################################");

			for (TaskManagerProcess taskManagerProcess : taskManagerProcesses) {
				taskManagerProcess.printProcessLog();
			}

			System.out.println("#################################################");
			System.out.println(" JOB MANAGERS");
			System.out.println("#################################################");

			for (JobManagerProcess jobManagerProcess : jobManagerProcesses) {
				jobManagerProcess.printProcessLog();
			}

			throw t;
		}
		finally {
			for (JobManagerProcess jobManagerProcess : jobManagerProcesses) {
				if (jobManagerProcess != null) {
					jobManagerProcess.destroy();
				}
			}

			if (leaderRetrievalService != null) {
				leaderRetrievalService.stop();
			}

			if (testActorSystem != null) {
				testActorSystem.shutdown();
			}

			highAvailabilityServices.closeAndCleanupAllData();
		}
	}

	// - The test program --------------------------------------------------------------------------

	private JobGraph createJobGraph(
			int n,
			String completedCheckpointMarker,
			String proceedMarker,
			int parallelism,
			int checkpointingIntervalMs) {

		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setParallelism(parallelism);
		env.enableCheckpointing(checkpointingIntervalMs);

		int expectedResult = parallelism * n * (n + 1) / 2;

		env.addSource(new CheckpointedSequenceSource(n, completedCheckpointMarker, proceedMarker))
				.addSink(new CountingSink(parallelism, expectedResult))
				.setParallelism(1);

		return env.getStreamGraph().getJobGraph();
	}

	public static class CheckpointedSequenceSource extends RichParallelSourceFunction<Long>
			implements ListCheckpointed<Long>, CheckpointListener {

		private static final long serialVersionUID = 0L;

		private final long end;

		private final String completedCheckpointMarkerFilePath;

		private final File proceedFile;

		private long current = 0;

		private volatile boolean isRunning = true;

		public CheckpointedSequenceSource(long end, String completedCheckpointMarkerFilePath, String proceedMarkerFilePath) {
			checkArgument(end >= 0, "Negative final count");

			this.end = end;
			this.completedCheckpointMarkerFilePath = completedCheckpointMarkerFilePath;
			this.proceedFile = new File(proceedMarkerFilePath);
		}

		@Override
		public void run(SourceContext<Long> ctx) throws Exception {
			while (isRunning) {

				if (!proceedFile.exists()) {
					Thread.sleep(50);
				}

				synchronized (ctx.getCheckpointLock()) {
					if (current <= end) {
						ctx.collect(current++);
					}
					else {
						ctx.collect(LastElement);
						return;
					}
				}
			}
		}

		@Override
		public List<Long> snapshotState(long checkpointId, long timestamp) throws Exception {
			LOG.info("Snapshotting state {} @ ID {}.", current, checkpointId);
			return Collections.singletonList(this.current);
		}

		@Override
		public void restoreState(List<Long> state) throws Exception {
			if (state.isEmpty() || state.size() > 1) {
				throw new RuntimeException("Test failed due to unexpected recovered state size " + state.size());
			}
			LOG.info("Restoring state {}/{}", state.get(0), end);
			this.current = state.get(0);
		}

		@Override
		public void cancel() {
			isRunning = false;
		}

		@Override
		public void notifyCheckpointComplete(long checkpointId) throws Exception {
			LOG.info("Checkpoint {} completed.", checkpointId);

			int taskIndex = getRuntimeContext().getIndexOfThisSubtask();
			TestJvmProcess.touchFile(new File(completedCheckpointMarkerFilePath + taskIndex));
		}
	}

	public static class CountingSink extends RichSinkFunction<Long>
			implements ListCheckpointed<CountingSink>, CheckpointListener {

		private static final Logger LOG = LoggerFactory.getLogger(CountingSink.class);

		private static final long serialVersionUID = 0L;

		private final int parallelism;

		private final long expectedFinalCount;

		private long current;

		private int numberOfReceivedLastElements;

		public CountingSink(int parallelism, long expectedFinalCount) {
			this.expectedFinalCount = expectedFinalCount;
			this.parallelism = parallelism;
		}

		@Override
		public void invoke(Long value) throws Exception {
			if (value == LastElement) {
				numberOfReceivedLastElements++;

				if (numberOfReceivedLastElements == parallelism) {
					if (current != expectedFinalCount) {
						throw new Exception("Unexpected final result " + current);
					}
					else {
						LOG.info("Final result " + current);
					}
				}
				else if (numberOfReceivedLastElements > parallelism) {
					throw new IllegalStateException("Received more elements than parallelism.");
				}
			}
			else {
				current += value;
			}
		}

		@Override
		public List<CountingSink> snapshotState(long checkpointId, long timestamp) throws Exception {
			LOG.info("Snapshotting state {}:{} @ ID {}.", current, numberOfReceivedLastElements, checkpointId);
			return Collections.singletonList(this);
		}

		@Override
		public void restoreState(List<CountingSink> state) throws Exception {
			if (state.isEmpty() || state.size() > 1) {
				throw new RuntimeException("Test failed due to unexpected recovered state size " + state.size());
			}
			CountingSink sink = state.get(0);
			this.current = sink.current;
			this.numberOfReceivedLastElements = sink.numberOfReceivedLastElements;
			LOG.info("Restoring state {}:{}", sink.current, sink.numberOfReceivedLastElements);
		}

		@Override
		public void notifyCheckpointComplete(long checkpointId) throws Exception {
			LOG.info("Checkpoint {} completed.", checkpointId);
		}
	}

	// - Utilities ---------------------------------------------------------------------------------

	private void submitJobGraph(
			JobGraph jobGraph,
			JobManagerProcess jobManager,
			TestingListener leaderListener,
			ActorSystem actorSystem,
			FiniteDuration timeout) throws Exception {

		ActorRef jobManagerRef = jobManager.getActorRef(actorSystem, timeout);
		UUID jobManagerLeaderId = leaderListener.getLeaderSessionID();
		AkkaActorGateway jobManagerGateway = new AkkaActorGateway(jobManagerRef, jobManagerLeaderId);

		jobManagerGateway.tell(new JobManagerMessages.SubmitJob(jobGraph, ListeningBehaviour.DETACHED));
	}

	private void checkCleanRecoveryState(Configuration config) throws Exception {
		LOG.info("Checking " + ZooKeeper.getClientNamespace() +
				ConfigConstants.DEFAULT_ZOOKEEPER_JOBGRAPHS_PATH);
		List<String> jobGraphs = ZooKeeper.getChildren(ConfigConstants.DEFAULT_ZOOKEEPER_JOBGRAPHS_PATH);
		assertEquals("Unclean job graphs: " + jobGraphs, 0, jobGraphs.size());

		LOG.info("Checking " + ZooKeeper.getClientNamespace() +
				ConfigConstants.DEFAULT_ZOOKEEPER_CHECKPOINTS_PATH);

		for (int i = 0; i < 10; i++) {
			List<String> checkpoints = ZooKeeper.getChildren(ConfigConstants.DEFAULT_ZOOKEEPER_CHECKPOINTS_PATH);
			assertEquals("Unclean checkpoints: " + checkpoints, 0, checkpoints.size());

			LOG.info("Unclean... retrying in 2s.");
			Thread.sleep(2000);
		}

		LOG.info("Checking " + ZooKeeper.getClientNamespace() +
				ConfigConstants.DEFAULT_ZOOKEEPER_CHECKPOINT_COUNTER_PATH);
		List<String> checkpointCounter = ZooKeeper.getChildren(ConfigConstants.DEFAULT_ZOOKEEPER_CHECKPOINT_COUNTER_PATH);
		assertEquals("Unclean checkpoint counter: " + checkpointCounter, 0, checkpointCounter.size());

		LOG.info("ZooKeeper state is clean");

		LOG.info("Checking file system backend state...");

		File fsCheckpoints = new File(new URI(config.getString(FsStateBackendFactory.CHECKPOINT_DIRECTORY_URI_CONF_KEY, "")).getPath());

		LOG.info("Checking " + fsCheckpoints);

		File[] files = fsCheckpoints.listFiles();
		if (files == null) {
			fail(fsCheckpoints + " does not exist: " + Arrays.toString(FileStateBackendBasePath.listFiles()));
		}

		File fsRecovery = new File(new URI(config.getString(HighAvailabilityOptions.HA_STORAGE_PATH)).getPath());

		LOG.info("Checking " + fsRecovery);

		files = fsRecovery.listFiles();
		if (files == null) {
			fail(fsRecovery + " does not exist: " + Arrays.toString(FileStateBackendBasePath.listFiles()));
		}
	}

	private void waitForJobRemoved(
			JobID jobId, JobManagerProcess jobManager, ActorSystem actorSystem, FiniteDuration timeout)
			throws Exception {

		ActorRef jobManagerRef = jobManager.getActorRef(actorSystem, timeout);
		AkkaActorGateway jobManagerGateway = new AkkaActorGateway(jobManagerRef, null);

		Future<Object> archiveFuture = jobManagerGateway.ask(JobManagerMessages.getRequestArchive(), timeout);

		ActorRef archive = ((JobManagerMessages.ResponseArchive) Await.result(archiveFuture, timeout)).actor();

		AkkaActorGateway archiveGateway = new AkkaActorGateway(archive, null);

		Deadline deadline = timeout.fromNow();

		while (deadline.hasTimeLeft()) {
			JobManagerMessages.JobStatusResponse resp = JobManagerActorTestUtils
					.requestJobStatus(jobId, archiveGateway, deadline.timeLeft());

			if (resp instanceof JobManagerMessages.JobNotFound) {
				Thread.sleep(100);
			}
			else {
				return;
			}
		}
	}

	private JobStatus requestJobStatus(
			JobID jobId, JobManagerProcess jobManager, ActorSystem actorSystem, FiniteDuration timeout)
			throws Exception {

		ActorRef jobManagerRef = jobManager.getActorRef(actorSystem, timeout);
		AkkaActorGateway jobManagerGateway = new AkkaActorGateway(jobManagerRef, null);

		JobManagerMessages.JobStatusResponse resp = JobManagerActorTestUtils
				.requestJobStatus(jobId, jobManagerGateway, timeout);

		if (resp instanceof JobManagerMessages.CurrentJobStatus) {
			JobManagerMessages.CurrentJobStatus jobStatusResponse = (JobManagerMessages
					.CurrentJobStatus) resp;

			return jobStatusResponse.status();
		}
		else if (resp instanceof JobManagerMessages.JobNotFound) {
			return JobStatus.RESTARTING;
		}

		throw new IllegalStateException("Unexpected response from JobManager");
	}

	private void waitForJobRunning(
			JobID jobId, JobManagerProcess jobManager, ActorSystem actorSystem, FiniteDuration timeout)
			throws Exception {

		ActorRef jobManagerRef = jobManager.getActorRef(actorSystem, timeout);
		AkkaActorGateway jobManagerGateway = new AkkaActorGateway(jobManagerRef, null);

		JobManagerActorTestUtils.waitForJobStatus(jobId, JobStatus.RUNNING, jobManagerGateway, timeout);
	}

	private void waitForJobFinished(
			JobID jobId, JobManagerProcess jobManager, ActorSystem actorSystem, FiniteDuration timeout)
			throws Exception {

		ActorRef jobManagerRef = jobManager.getActorRef(actorSystem, timeout);
		AkkaActorGateway jobManagerGateway = new AkkaActorGateway(jobManagerRef, null);

		JobManagerActorTestUtils.waitForJobStatus(jobId, JobStatus.FINISHED, jobManagerGateway, timeout);
	}

	private void waitForTaskManagers(
			int minimumNumberOfTaskManagers,
			JobManagerProcess jobManager,
			ActorSystem actorSystem,
			FiniteDuration timeout) throws Exception {

		LOG.info("Waiting for {} task managers to connect to leading {}",
				minimumNumberOfTaskManagers, jobManager);

		ActorRef jobManagerRef = jobManager.getActorRef(actorSystem, timeout);
		AkkaActorGateway jobManagerGateway = new AkkaActorGateway(jobManagerRef, null);

		JobManagerActorTestUtils.waitForTaskManagers(
				minimumNumberOfTaskManagers, jobManagerGateway, timeout);

		LOG.info("All task managers connected");
	}

	private int waitForNewLeader(
			TestingListener leaderListener,
			List<JobManagerProcess> jobManagerProcesses,
			FiniteDuration timeout) throws Exception {

		LOG.info("Waiting for new leader notification");
		leaderListener.waitForNewLeader(timeout.toMillis());

		LOG.info("Leader: {}:{}", leaderListener.getAddress(), leaderListener.getLeaderSessionID());

		String currentLeader = leaderListener.getAddress();

		int leaderIndex = -1;

		for (int i = 0; i < jobManagerProcesses.size(); i++) {
			JobManagerProcess jobManager = jobManagerProcesses.get(i);
			if (jobManager.getJobManagerAkkaURL(timeout).equals(currentLeader)) {
				leaderIndex = i;
				break;
			}
		}

		if (leaderIndex == -1) {
			throw new IllegalStateException("Failed to determine which process is leader");
		}

		return leaderIndex;
	}

	private JobManagerProcess createAndStartJobManagerProcess(Configuration config)
			throws Exception {

		JobManagerProcess jobManager = new JobManagerProcess(jobManagerPid++, config);
		jobManager.startProcess();
		LOG.info("Created and started {}.", jobManager);

		return jobManager;
	}

	private TaskManagerProcess createAndStartTaskManagerProcess(Configuration config)
			throws Exception {

		TaskManagerProcess taskManager = new TaskManagerProcess(taskManagerPid++, config);
		taskManager.startProcess();
		LOG.info("Created and started {}.", taskManager);

		return taskManager;
	}

}
