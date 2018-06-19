/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.workflow;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.internal.metrics.MetricsTag;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestEnvironmentOptions.Builder;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.workflow.interceptors.SignalWorkflowInterceptor;
import com.uber.cadence.workflow.workflows.ReceiveSignalObject_ChildWorkflowImpl;
import com.uber.cadence.workflow.workflows.SendSignalObject_Workflow;
import com.uber.cadence.workflow.workflows.SendSignalObject_WorkflowImpl;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.ArgumentCaptor;

public class MetricsTest {

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          if (testEnvironment != null) {
            System.err.println("HISTORIES:\n" + testEnvironment.getDiagnostics());
          }
        }
      };

  private static final String taskList = "metrics-test";
  private TestWorkflowEnvironment testEnvironment;
  private StatsReporter reporter;

  public interface TestWorkflow {

    @WorkflowMethod
    void execute();
  }

  public static class TestMetricsInWorkflow implements TestWorkflow {

    @Override
    public void execute() {
      Workflow.getMetricsScope().counter("test-started").inc(1);

      ChildWorkflowOptions options =
          new ChildWorkflowOptions.Builder().setTaskList(taskList).build();
      TestChildWorkflow workflow = Workflow.newChildWorkflowStub(TestChildWorkflow.class, options);
      workflow.executeChild();

      Workflow.getMetricsScope().counter("test-done").inc(1);
    }
  }

  public interface TestChildWorkflow {

    @WorkflowMethod
    void executeChild();
  }

  public static class TestMetricsInChildWorkflow implements TestChildWorkflow {

    @Override
    public void executeChild() {
      Workflow.getMetricsScope().counter("test-child-started").inc(1);

      Stopwatch sw = Workflow.getMetricsScope().timer("test-timer").start();
      Workflow.sleep(3000);
      sw.stop();

      Workflow.getMetricsScope().counter("test-child-done").inc(1);
    }
  }

  @Before
  public void setUp() {
    reporter = mock(StatsReporter.class);
    Scope scope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(300));

    TestEnvironmentOptions testOptions =
        new Builder().setDomain(WorkflowTest.DOMAIN).setMetricsScope(scope).build();
    testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
  }

  @Test
  public void testWorkflowMetrics() throws InterruptedException {

    Worker worker = testEnvironment.newWorker(taskList);
    worker.registerWorkflowImplementationTypes(
        TestMetricsInWorkflow.class, TestMetricsInChildWorkflow.class);
    worker.start();

    WorkflowClient workflowClient = testEnvironment.newWorkflowClient();
    WorkflowOptions options =
        new WorkflowOptions.Builder()
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(1000))
            .setTaskList(taskList)
            .build();
    TestWorkflow workflow = workflowClient.newWorkflowStub(TestWorkflow.class, options);
    workflow.execute();

    Thread.sleep(200);

    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, WorkflowTest.DOMAIN)
            .put(MetricsTag.TASK_LIST, taskList)
            .build();

    verify(reporter, times(1)).reportCounter("test-started", tags, 1);
    verify(reporter, times(1)).reportCounter("test-done", tags, 1);
    verify(reporter, times(1)).reportCounter("test-child-started", tags, 1);
    verify(reporter, times(1)).reportCounter("test-child-done", tags, 1);

    ArgumentCaptor<com.uber.m3.util.Duration> sleepDurationCaptor =
        ArgumentCaptor.forClass(com.uber.m3.util.Duration.class);
    verify(reporter, times(1)).reportTimer(eq("test-timer"), any(), sleepDurationCaptor.capture());

    com.uber.m3.util.Duration sleepDuration = sleepDurationCaptor.getValue();
    assertTrue(
        sleepDuration.toString(),
        sleepDuration.compareTo(com.uber.m3.util.Duration.ofSeconds(3)) > 0);
    assertTrue(
        sleepDuration.toString(),
        sleepDuration.compareTo(com.uber.m3.util.Duration.ofMillis(3100)) < 0);
  }

  @Test
  public void testCorruptedSignalMetrics() throws InterruptedException {
    Worker worker = testEnvironment.newWorker(taskList, builder ->
            builder.setInterceptorFactory(new CorruptedSignalWorkflowInterceptorFactory()));

    worker.registerWorkflowImplementationTypes(
            SendSignalObject_WorkflowImpl.class, ReceiveSignalObject_ChildWorkflowImpl.class);
    worker.start();

    WorkflowOptions options =
            new WorkflowOptions.Builder()
                    .setExecutionStartToCloseTimeout(Duration.ofSeconds(1000))
                    .setTaskList(taskList)
                    .build();

    WorkflowClient workflowClient = testEnvironment.newWorkflowClient();
    SendSignalObject_Workflow workflow = workflowClient.newWorkflowStub(SendSignalObject_Workflow.class, options);
    workflow.execute();

    //Wait for reporter
    Thread.sleep(500);

    Map<String, String> tags =
            new ImmutableMap.Builder<String, String>(2)
                    .put(MetricsTag.DOMAIN, WorkflowTest.DOMAIN)
                    .put(MetricsTag.TASK_LIST, taskList)
                    .build();
    verify(reporter, times(1)).reportCounter(MetricsType.CORRUPTED_SIGNALS_COUNTER, tags, 2);
  }

  private static class CorruptedSignalWorkflowInterceptorFactory
          implements Function<WorkflowInterceptor, WorkflowInterceptor> {

      @Override
      public WorkflowInterceptor apply(WorkflowInterceptor next) {
          return new SignalWorkflowInterceptor(args -> {
                      if(args != null && args.length > 0){
                          return new Object [] {"Corrupted Signal"};
                      }
                      return args;
                  }, sig->sig, next);
      }
  }
}
