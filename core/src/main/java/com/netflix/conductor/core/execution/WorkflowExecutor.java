/**
 * Copyright 2016 Netflix, Inc.
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
/**
 * 
 */
package com.netflix.conductor.core.execution;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.netflix.conductor.annotations.Trace;
import com.netflix.conductor.common.metadata.tasks.PollData;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.Task.Status;
import com.netflix.conductor.common.metadata.tasks.TaskExecLog;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.metadata.workflow.RerunWorkflowRequest;
import com.netflix.conductor.common.metadata.workflow.SkipTaskRequest;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.run.Workflow.WorkflowStatus;
import com.netflix.conductor.core.WorkflowContext;
import com.netflix.conductor.core.config.Configuration;
import com.netflix.conductor.core.execution.ApplicationException.Code;
import com.netflix.conductor.core.execution.DeciderService.DeciderOutcome;
import com.netflix.conductor.core.execution.tasks.WorkflowSystemTask;
import com.netflix.conductor.core.utils.IDGenerator;
import com.netflix.conductor.core.utils.QueueUtils;
import com.netflix.conductor.dao.ExecutionDAO;
import com.netflix.conductor.dao.MetadataDAO;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;

/**
 * @author Viren Workflow services provider interface
 */
@Trace
public class WorkflowExecutor {

	private static Logger logger = LoggerFactory.getLogger(WorkflowExecutor.class);

	private MetadataDAO metadata;

	private ExecutionDAO edao;

	private QueueDAO queue;
	
	private DeciderService decider;
	
	private Configuration config;
	
	public static final String deciderQueue = "_deciderQueue";

	private int activeWorkerLastPollnSecs;
	
	@Inject
	public WorkflowExecutor(MetadataDAO metadata, ExecutionDAO edao, QueueDAO queue, ObjectMapper om, Configuration config) {
		this.metadata = metadata;
		this.edao = edao;
		this.queue = queue;
		this.config = config;
		activeWorkerLastPollnSecs = config.getIntProperty("tasks.active.worker.lastpoll", 10);
		this.decider = new DeciderService(metadata, om);
	}

	public String startWorkflow(String name, int version, String correlationId, Map<String, Object> input) throws Exception {
		return startWorkflow(name, version, correlationId, input, null);
	}
	
	public String startWorkflow(String name, int version, String correlationId, Map<String, Object> input, String event) throws Exception {
		return startWorkflow(name, version, input, correlationId, null, null, event);
	}

	public String startWorkflow(String name, int version, String correlationId, Map<String, Object> input, String event, Map<String, String> taskToDomain) throws Exception {
		return startWorkflow(name, version, input, correlationId, null, null, event, taskToDomain);
	}
	
	public String startWorkflow(String name, int version, Map<String, Object> input, String correlationId, String parentWorkflowId, String parentWorkflowTaskId, String event) throws Exception {
		return startWorkflow(name, version, input, correlationId, parentWorkflowId, parentWorkflowTaskId, event, null);
	}
	
	public String startWorkflow(String name, int version, Map<String, Object> input, String correlationId, String parentWorkflowId, String parentWorkflowTaskId, String event, Map<String, String> taskToDomain) throws Exception {
		
		try {
			
			if(input == null){
				throw new ApplicationException(Code.INVALID_INPUT, "NULL input passed when starting workflow");
			}
			
			WorkflowDef exists = metadata.get(name, version);
			if (exists == null) {
				throw new ApplicationException(Code.NOT_FOUND, "No such workflow defined. name=" + name + ", version=" + version);
			}
			Set<String> missingTaskDefs = exists.all().stream()
													.filter(wft -> wft.getType().equals(WorkflowTask.Type.SIMPLE.name()))
													.map(wft2 -> wft2.getName()).filter(task -> metadata.getTaskDef(task) == null)
													.collect(Collectors.toSet());
			if(!missingTaskDefs.isEmpty()) {
				throw new ApplicationException(Code.INVALID_INPUT, "Cannot find the task definitions for the following tasks used in workflow: " + missingTaskDefs);
			}
			String workflowId = IDGenerator.generate();
	
			// Persist the Workflow
			Workflow wf = new Workflow();
			wf.setWorkflowId(workflowId);
			wf.setCorrelationId(correlationId);
			wf.setWorkflowType(name);
			wf.setVersion(version);
			wf.setInput(input);
			wf.setStatus(WorkflowStatus.RUNNING);
			wf.setParentWorkflowId(parentWorkflowId);
			wf.setParentWorkflowTaskId(parentWorkflowTaskId);
			wf.setOwnerApp(WorkflowContext.get().getClientApp());
			wf.setCreateTime(System.currentTimeMillis());
			wf.setUpdatedBy(null);
			wf.setUpdateTime(null);
			wf.setEvent(event);
			wf.setTaskToDomain(taskToDomain);
			edao.createWorkflow(wf);
			decide(workflowId);
			return workflowId;
			
		}catch (Exception e) {
			Monitors.recordWorkflowStartError(name);
			throw e;
		}
	}

	public String rerun(RerunWorkflowRequest request) throws Exception {

		Workflow reRunFromWorkflow = edao.getWorkflow(request.getReRunFromWorkflowId());

		String workflowId = IDGenerator.generate();

		// Persist the workflow and task First
		Workflow wf = new Workflow();
		wf.setWorkflowId(workflowId);
		wf.setCorrelationId((request.getCorrelationId() == null) ? reRunFromWorkflow.getCorrelationId() : request.getCorrelationId());
		wf.setWorkflowType(reRunFromWorkflow.getWorkflowType());
		wf.setVersion(reRunFromWorkflow.getVersion());
		wf.setInput((request.getWorkflowInput() == null) ? reRunFromWorkflow.getInput() : request.getWorkflowInput());
		wf.setReRunFromWorkflowId(request.getReRunFromWorkflowId());
		wf.setStatus(WorkflowStatus.RUNNING);
		wf.setOwnerApp(WorkflowContext.get().getClientApp());
		wf.setCreateTime(System.currentTimeMillis());
		wf.setUpdatedBy(null);
		wf.setUpdateTime(null);

		// If the "reRunFromTaskId" is not given in the RerunWorkflowRequest,
		// then the whole
		// workflow has to rerun
		if (request.getReRunFromTaskId() != null) {
			// We need to go thru the workflowDef and create tasks for
			// all tasks before request.getReRunFromTaskId() and marked them
			// skipped
			List<Task> newTasks = new LinkedList<>();
			Map<String, Task> refNameToTask = new HashMap<String, Task>();
			reRunFromWorkflow.getTasks().forEach(task -> refNameToTask.put(task.getReferenceTaskName(), task));
			WorkflowDef wd = metadata.get(reRunFromWorkflow.getWorkflowType(), reRunFromWorkflow.getVersion());
			Iterator<WorkflowTask> it = wd.getTasks().iterator();
			int seq = wf.getTasks().size();
			while (it.hasNext()) {
				WorkflowTask wt = it.next();
				Task previousTask = refNameToTask.get(wt.getTaskReferenceName());
				if (previousTask.getTaskId().equals(request.getReRunFromTaskId())) {
					Task theTask = new Task();
					theTask.setTaskId(IDGenerator.generate());
					theTask.setReferenceTaskName(previousTask.getReferenceTaskName());
					theTask.setInputData((request.getTaskInput() == null) ? previousTask.getInputData() : request.getTaskInput());
					theTask.setWorkflowInstanceId(workflowId);
					theTask.setStatus(Status.READY_FOR_RERUN);
					theTask.setTaskType(previousTask.getTaskType());
					theTask.setCorrelationId(wf.getCorrelationId());
					theTask.setSeq(seq++);
					theTask.setRetryCount(previousTask.getRetryCount() + 1);
					newTasks.add(theTask);
					break;
				} else { // Create with Skipped status
					Task theTask = new Task();
					theTask.setTaskId(IDGenerator.generate());
					theTask.setReferenceTaskName(previousTask.getReferenceTaskName());
					theTask.setWorkflowInstanceId(workflowId);
					theTask.setStatus(Status.SKIPPED);
					theTask.setTaskType(previousTask.getTaskType());
					theTask.setCorrelationId(wf.getCorrelationId());
					theTask.setInputData(previousTask.getInputData());
					theTask.setOutputData(previousTask.getOutputData());
					theTask.setRetryCount(previousTask.getRetryCount() + 1);
					theTask.setSeq(seq++);
					newTasks.add(theTask);
				}
			}

			edao.createTasks(newTasks);
		}

		edao.createWorkflow(wf);
		decide(workflowId);
		return workflowId;
	}

	public void rewind(String workflowId) throws Exception {
		Workflow workflow = edao.getWorkflow(workflowId, true);
		if (!workflow.getStatus().isTerminal()) {
			throw new ApplicationException(Code.CONFLICT, "Workflow is still running.  status=" + workflow.getStatus());
		}

		// Remove all the tasks...
		workflow.getTasks().forEach(t -> edao.removeTask(t.getTaskId()));
		workflow.getTasks().clear();
		workflow.setReasonForIncompletion(null);
		workflow.setStartTime(System.currentTimeMillis());
		workflow.setEndTime(0);
		// Change the status to running
		workflow.setStatus(WorkflowStatus.RUNNING);
		edao.updateWorkflow(workflow);
		decide(workflowId);
	}

	public void retry(String workflowId) throws Exception {
		Workflow workflow = edao.getWorkflow(workflowId, true);
		if (!workflow.getStatus().isTerminal()) {
			throw new ApplicationException(Code.CONFLICT, "Workflow is still running.  status=" + workflow.getStatus());
		}
		if (workflow.getTasks().isEmpty()) {
			throw new ApplicationException(Code.CONFLICT, "Workflow has not started yet");
		}
		int lastIndex = workflow.getTasks().size() - 1;
		Task last = workflow.getTasks().get(lastIndex);
		if (!last.getStatus().isTerminal()) {
			throw new ApplicationException(Code.CONFLICT,
					"The last task is still not completed!  I can only retry the last failed task.  Use restart if you want to attempt entire workflow execution again.");
		}
		if (last.getStatus().isSuccessful()) {
			throw new ApplicationException(Code.CONFLICT,
					"The last task has not failed!  I can only retry the last failed task.  Use restart if you want to attempt entire workflow execution again.");
		}

		// Below is the situation where currently when the task failure causes
		// workflow to fail, the task's retried flag is not updated. This is to
		// update for these old tasks.
		List<Task> update = workflow.getTasks().stream().filter(task -> !task.isRetried()).collect(Collectors.toList());
		update.forEach(task -> task.setRetried(true));
		edao.updateTasks(update);

		Task retried = last.copy();
		retried.setTaskId(IDGenerator.generate());
		retried.setRetriedTaskId(last.getTaskId());
		retried.setStatus(Status.SCHEDULED);
		retried.setRetryCount(last.getRetryCount() + 1);
		scheduleTask(workflow, Arrays.asList(retried));

		workflow.setStatus(WorkflowStatus.RUNNING);
		edao.updateWorkflow(workflow);

		decide(workflowId);

	}

	public List<Workflow> getStatusByCorrelationId(String workflowName, String correlationId, boolean includeClosed) throws Exception {
		Preconditions.checkNotNull(correlationId, "correlation id is missing");
		Preconditions.checkNotNull(workflowName, "workflow name is missing");
		List<Workflow> workflows = edao.getWorkflowsByCorrelationId(correlationId);
		List<Workflow> result = new LinkedList<>();
		for (Workflow wf : workflows) {
			if (wf.getWorkflowType().equals(workflowName) && (includeClosed || wf.getStatus().equals(WorkflowStatus.RUNNING))) {
				result.add(wf);
			}
		}

		return result;
	}

	public Task getPendingTaskByWorkflow(String taskReferenceName, String workflowId) {
		List<Task> tasks = edao.getTasksForWorkflow(workflowId).stream()
				.filter(task -> !task.getStatus().isTerminal() && task.getReferenceTaskName().equals(taskReferenceName)).collect(Collectors.toList());
		if (!tasks.isEmpty()) {
			return tasks.get(0); // There can only be one task by a given
									// reference name running at a time.
		}
		return null;
	}

	public void completeWorkflow(Workflow wf) throws Exception {
		Workflow workflow = edao.getWorkflow(wf.getWorkflowId(), false);

		if (workflow.getStatus().equals(WorkflowStatus.COMPLETED)) {
			logger.info("Workflow has already been completed.  Current status=" + workflow.getStatus() + ", workflowId=" + wf.getWorkflowId());
			return;
		}

		if (workflow.getStatus().isTerminal()) {
			String msg = "Workflow has already been completed.  Current status " + workflow.getStatus();
			throw new ApplicationException(Code.CONFLICT, msg);
		}

		workflow.setStatus(WorkflowStatus.COMPLETED);
		workflow.setOutput(wf.getOutput());
		edao.updateWorkflow(workflow);

		// If the following task, for some reason fails, the sweep will take
		// care of this again!
		if (workflow.getParentWorkflowId() != null) {
			Workflow parent = edao.getWorkflow(workflow.getParentWorkflowId(), false);
			decide(parent.getWorkflowId());
		}
		Monitors.recordWorkflowCompletion(workflow.getWorkflowType(), workflow.getEndTime() - workflow.getStartTime());
		queue.remove(deciderQueue, workflow.getWorkflowId());	//remove from the sweep queue
	}

	public void terminateWorkflow(String workflowId, String reason) throws Exception {
		Workflow workflow = edao.getWorkflow(workflowId, true);
		workflow.setStatus(WorkflowStatus.TERMINATED);
		terminateWorkflow(workflow, reason, null);
	}
	
	public void terminateWorkflow(Workflow workflow, String reason, String failureWorkflow) throws Exception {

		if (!workflow.getStatus().isTerminal()) {
			workflow.setStatus(WorkflowStatus.TERMINATED);
		}
		
		String workflowId = workflow.getWorkflowId();
		workflow.setReasonForIncompletion(reason);
		edao.updateWorkflow(workflow);

		List<Task> tasks = workflow.getTasks();
		for (Task task : tasks) {
			if (!task.getStatus().isTerminal()) {
				// Cancel the ones which are not completed yet....
				task.setStatus(Status.CANCELED);
				if (SystemTaskType.is(task.getTaskType())) {
					WorkflowSystemTask stt = WorkflowSystemTask.get(task.getTaskType());
					stt.cancel(workflow, task, this);
					//SystemTaskType.valueOf(task.getTaskType()).cancel(workflow, task, this);
				}
				edao.updateTask(task);
			}
			// And remove from the task queue if they were there
			queue.remove(QueueUtils.getQueueName(task), task.getTaskId());
		}

		// If the following lines, for some reason fails, the sweep will take
		// care of this again!
		if (workflow.getParentWorkflowId() != null) {
			Workflow parent = edao.getWorkflow(workflow.getParentWorkflowId(), false);
			decide(parent.getWorkflowId());
		}

		if (!StringUtils.isBlank(failureWorkflow)) {
			Map<String, Object> input = new HashMap<>();
			input.putAll(workflow.getInput());
			input.put("workflowId", workflowId);
			input.put("reason", reason);
			input.put("failureStatus", workflow.getStatus().toString());

			try {
				
				WorkflowDef latestFailureWorkflow = metadata.getLatest(failureWorkflow);
				String failureWFId = startWorkflow(failureWorkflow, latestFailureWorkflow.getVersion(), input, workflowId, null, null, null);
				workflow.getOutput().put("conductor.failure_workflow", failureWFId);
				
			} catch (Exception e) {
				logger.error("Failed to start error workflow", e);
				workflow.getOutput().put("conductor.failure_workflow", "Error workflow " + failureWorkflow + " failed to start.  reason: " + e.getMessage());
				Monitors.recordWorkflowStartError(failureWorkflow);
			}
		}
		
		queue.remove(deciderQueue, workflow.getWorkflowId());	//remove from the sweep queue
		
		// Send to atlas
		Monitors.recordWorkflowTermination(workflow.getWorkflowType(), workflow.getStatus());
	}	

	public void updateTask(TaskResult result) throws Exception {
		if (result == null) {
			logger.info("null task given for update..." + result);
			throw new ApplicationException(Code.INVALID_INPUT, "Task object is null");
		}
		String workflowId = result.getWorkflowInstanceId();
		Workflow wf = edao.getWorkflow(workflowId);
		Task task = edao.getTask(result.getTaskId());
		
		if (wf.getStatus().isTerminal()) {
			// Workflow is in terminal state
			queue.remove(QueueUtils.getQueueName(task), result.getTaskId());
			if(!task.getStatus().isTerminal()) {
				task.setStatus(Status.COMPLETED);
			}
			task.setOutputData(result.getOutputData());
			task.setReasonForIncompletion(result.getReasonForIncompletion());
			task.setWorkerId(result.getWorkerId());
			edao.updateTask(task);
			String msg = "Workflow " + wf.getWorkflowId() + " is already completed as " + wf.getStatus() + ", task=" + task.getTaskType() + ", reason=" + wf.getReasonForIncompletion();
			logger.info(msg);
			Monitors.recordUpdateConflict(task.getTaskType(), wf.getWorkflowType(), wf.getStatus());
			return;
		}

		if (task.getStatus().isTerminal()) {
			// Task was already updated....
			queue.remove(QueueUtils.getQueueName(task), result.getTaskId());
			String msg = "Task is already completed as " + task.getStatus() + "@" + task.getEndTime() + ", workflow status=" + wf.getStatus() + ", workflowId=" + wf.getWorkflowId() + ", taskId=" + task.getTaskId();
			logger.info(msg);
			Monitors.recordUpdateConflict(task.getTaskType(), wf.getWorkflowType(), task.getStatus());
			return;
		}

		task.setStatus(Status.valueOf(result.getStatus().name()));
		task.setOutputData(result.getOutputData());
		task.setReasonForIncompletion(result.getReasonForIncompletion());
		task.setWorkerId(result.getWorkerId());
		task.setCallbackAfterSeconds(result.getCallbackAfterSeconds());

		if (task.getStatus().isTerminal()) {
			task.setEndTime(System.currentTimeMillis());
		}
		edao.updateTask(task);

		TaskExecLog tlog = result.getLog();
		tlog.setTaskId(task.getTaskId());
		edao.addTaskExecLog(tlog);

		switch (task.getStatus()) {

		case COMPLETED:
			queue.remove(QueueUtils.getQueueName(task), result.getTaskId());
			break;

		case CANCELED:
			queue.remove(QueueUtils.getQueueName(task), result.getTaskId());
			break;
		case FAILED:
			queue.remove(QueueUtils.getQueueName(task), result.getTaskId());
			break;
		case IN_PROGRESS:
			// put it back in queue based in callbackAfterSeconds
			long callBack = result.getCallbackAfterSeconds();
			queue.remove(QueueUtils.getQueueName(task), task.getTaskId());			
			queue.push(QueueUtils.getQueueName(task), task.getTaskId(), callBack); // Milliseconds
			break;
		default:
			break;
		}
		
		decide(workflowId);

		if (task.getStatus().isTerminal()) {
			long duration = getTaskDuration(0, task);
			long lastDuration = task.getEndTime() - task.getStartTime();
			Monitors.recordTaskExecutionTime(task.getTaskDefName(), duration, true, task.getStatus());
			Monitors.recordTaskExecutionTime(task.getTaskDefName(), lastDuration, false, task.getStatus());
		}

	}

	public List<Task> getTasks(String taskType, String startKey, int count) throws Exception {
		return edao.getTasks(taskType, startKey, count);
	}

	public List<Workflow> getRunningWorkflows(String workflowName) throws Exception {
		List<Workflow> allwf = edao.getPendingWorkflowsByType(workflowName);
		return allwf;
	}

	public List<String> getWorkflows(String name, Integer version, Long startTime, Long endTime) {
		List<Workflow> allwf = edao.getWorkflowsByType(name, startTime, endTime);
		List<String> workflows = allwf.stream().filter(wf -> wf.getVersion() == version).map(wf -> wf.getWorkflowId()).collect(Collectors.toList());
		return workflows;
	}

	public List<String> getRunningWorkflowIds(String workflowName) throws Exception {
		return edao.getRunningWorkflowIds(workflowName);
	}

	/**
	 * 
	 * @param workflowId ID of the workflow to evaluate the state for
	 * @return true if the workflow has completed (success or failed), false otherwise.
	 * @throws Exception If there was an error - caller should retry in this case.
	 */
	public boolean decide(String workflowId) throws Exception {
		
		Workflow workflow = edao.getWorkflow(workflowId, true);
		WorkflowDef def = metadata.get(workflow.getWorkflowType(), workflow.getVersion());
		try {
			DeciderOutcome outcome = decider.decide(workflow, def);
			if(outcome.isComplete) {
				completeWorkflow(workflow);
				return true;
			}
			
			List<Task> tasksToBeScheduled = outcome.tasksToBeScheduled;
			setTaskDomains(tasksToBeScheduled, workflow);
			List<Task> tasksToBeUpdated = outcome.tasksToBeUpdated;
			boolean stateChanged = false;
			
			workflow.getTasks().addAll(tasksToBeScheduled);
			for(Task task : tasksToBeScheduled) {
				if (SystemTaskType.is(task.getTaskType()) && !task.getStatus().isTerminal()) {
					WorkflowSystemTask stt = WorkflowSystemTask.get(task.getTaskType());
					if (!stt.isAsync() && stt.execute(workflow, task, this)) {
						tasksToBeUpdated.add(task);
						stateChanged = true;
					}
				}
			}
			stateChanged = scheduleTask(workflow, tasksToBeScheduled) || stateChanged;
			
			if(!outcome.tasksToBeUpdated.isEmpty() || !outcome.tasksToBeScheduled.isEmpty()) {
				edao.updateTasks(tasksToBeUpdated);
				edao.updateWorkflow(workflow);
				queue.push(deciderQueue, workflow.getWorkflowId(), config.getSweepFrequency());	
			}

			if(stateChanged) {				
				decide(workflowId);
			}
			
		} catch (TerminateWorkflow tw) {
			logger.debug(tw.getMessage(), tw);
			terminate(def, workflow, tw);
			return true;
		}
		return false;
	}
	
	public void pauseWorkflow(String workflowId) throws Exception {
		WorkflowStatus status = WorkflowStatus.PAUSED;
		Workflow workflow = edao.getWorkflow(workflowId, false);
		if(workflow.getStatus().isTerminal()){
        	throw new ApplicationException(Code.CONFLICT, "Workflow id " + workflowId + " has ended, status cannot be updated.");
        }
		if (workflow.getStatus().equals(status)) {
        	return;		//Already paused!
        }
        workflow.setStatus(status);
        edao.updateWorkflow(workflow);
	}

	public void resumeWorkflow(String workflowId) throws Exception{
		Workflow workflow = edao.getWorkflow(workflowId, false);
		if(!workflow.getStatus().equals(WorkflowStatus.PAUSED)){
			throw new IllegalStateException("The workflow " + workflowId + " is not is not PAUSED so cannot resume");
		}
		workflow.setStatus(WorkflowStatus.RUNNING);
		edao.updateWorkflow(workflow);
		decide(workflowId);
	}
	
	public void skipTaskFromWorkflow(String workflowId, String taskReferenceName, SkipTaskRequest skipTaskRequest)  throws Exception {
		
		Workflow wf = edao.getWorkflow(workflowId, true);
		
		// If the wf is not running then cannot skip any task
		if(!wf.getStatus().equals(WorkflowStatus.RUNNING)){
			String errorMsg = String.format("The workflow %s is not running so the task referenced by %s cannot be skipped", workflowId, taskReferenceName);
			throw new IllegalStateException(errorMsg);
		}
		// Check if the reference name is as per the workflowdef
		WorkflowDef wfd = metadata.get(wf.getWorkflowType(), wf.getVersion());
		WorkflowTask wft = wfd.getTaskByRefName(taskReferenceName);
		if(wft == null){
			String errorMsg = String.format("The task referenced by %s does not exist in the WorkflowDef %s", taskReferenceName, wf.getWorkflowType());
			throw new IllegalStateException(errorMsg);				
		}
		// If the task is already started the again it cannot be skipped
		wf.getTasks().forEach(task -> {
			if(task.getReferenceTaskName().equals(taskReferenceName)){
				String errorMsg = String.format("The task referenced %s has already been processed, cannot be skipped", taskReferenceName);
				throw new IllegalStateException(errorMsg);				
			}
		});
		// Now create a "SKIPPED" task for this workflow
		Task theTask = new Task();
	    theTask.setTaskId(IDGenerator.generate());
	    theTask.setReferenceTaskName(taskReferenceName);
	    theTask.setWorkflowInstanceId(workflowId);
	    theTask.setStatus(Status.SKIPPED);
	    theTask.setTaskType(wft.getName());
	    theTask.setCorrelationId(wf.getCorrelationId());
	    if(skipTaskRequest != null){
		    theTask.setInputData(skipTaskRequest.getTaskInput());
		    theTask.setOutputData(skipTaskRequest.getTaskOutput());
	    }
	    edao.createTasks(Arrays.asList(theTask));
	    decide(workflowId);
	}
	
	public Workflow getWorkflow(String workflowId, boolean includeTasks) {
		return edao.getWorkflow(workflowId, includeTasks);
	}
	
	public void addTaskToQueue(Task task) throws Exception {
		// put in queue
		queue.remove(QueueUtils.getQueueName(task), task.getTaskId());
		if (task.getCallbackAfterSeconds() > 0) {
			queue.push(QueueUtils.getQueueName(task), task.getTaskId(), task.getCallbackAfterSeconds());
		} else {
			queue.push(QueueUtils.getQueueName(task), task.getTaskId(), 0);
		}
	}	
	
	//Executes the async system task 
	public void executeSystemTask(WorkflowSystemTask systemTask, String taskId, int unackTimeout) {
		
		
		try {
			
			Task task = edao.getTask(taskId);
			if(task.getStatus().isTerminal()) {
				//Tune the SystemTaskWorkerCoordinator's queues - if the queue size is very big this can happen!
				logger.info("Task {}/{} was already completed.", task.getTaskType(), task.getTaskId());
				queue.remove(QueueUtils.getQueueName(task), task.getTaskId());
				return;
			}
			
			String workflowId = task.getWorkflowInstanceId();			
			Workflow workflow = edao.getWorkflow(workflowId, true);			
			
			if (task.getStartTime() == 0) {
				task.setStartTime(System.currentTimeMillis());
				Monitors.recordQueueWaitTime(task.getTaskDefName(), task.getQueueWaitTime());
			}
			
			if(workflow.getStatus().isTerminal()) {
				logger.warn("Workflow {} has been completed for {}/{}", workflow.getWorkflowId(), systemTask.getName(), task.getTaskId());
				if(!task.getStatus().isTerminal()) {
					task.setStatus(Status.CANCELED);
				}
				edao.updateTask(task);
				queue.remove(QueueUtils.getQueueName(task), task.getTaskId());
				return;
			}
			
			if(task.getStatus().equals(Status.SCHEDULED)) {
				
				if(edao.exceedsInProgressLimit(task)) {
					logger.warn("Rate limited for {}", task.getTaskDefName());					
					return;
				}
			}
			
			logger.info("Executing {}/{}-{}", task.getTaskType(), task.getTaskId(), task.getStatus());
			
			queue.setUnackTimeout(QueueUtils.getQueueName(task), task.getTaskId(), systemTask.getRetryTimeInSecond() * 1000);
			task.setPollCount(task.getPollCount() + 1);
			edao.updateTask(task);

			switch (task.getStatus()) {
			
				case SCHEDULED:
					systemTask.start(workflow, task, this);					
					break;
					
				case IN_PROGRESS:
					systemTask.execute(workflow, task, this);
					break;
				default:
					break;
			}
			
			if(!task.getStatus().isTerminal()) {
				task.setCallbackAfterSeconds(unackTimeout);
			}
			
			updateTask(new TaskResult(task));
			logger.info("Done Executing {}/{}-{} op={}", task.getTaskType(), task.getTaskId(), task.getStatus(), task.getOutputData().toString());
			
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
	}

	public void setTaskDomains(List<Task> tasks, Workflow wf){
		Map<String, String> taskToDomain = wf.getTaskToDomain();
		if(taskToDomain != null){
			// Check if all tasks have the same domain "*"
			String domainstr = taskToDomain.get("*");
			if(domainstr != null){
				String[] domains = domainstr.split(",");
				tasks.forEach(task -> {
					// Filter out SystemTask
					if(!(task instanceof SystemTask)){
						// Check which domain worker is polling 
						// Set the task domain
						task.setDomain(getActiveDomain(task.getTaskType(), domains));
					}
				});
				
			} else {
				tasks.forEach(task -> {
					if(!(task instanceof SystemTask)){
						String taskDomainstr = taskToDomain.get(task.getTaskType());
						if(taskDomainstr != null){
							task.setDomain(getActiveDomain(task.getTaskType(), taskDomainstr.split(",")));
						}
					}					
				});				
			}
		}
	}
	
	private String getActiveDomain(String taskType, String[] domains){
		// The domain list has to be ordered.
		// In sequence check if any worker has polled for last 30 seconds, if so that is the Active domain
		String domain = null; // Default domain 
		for(String d: domains){
			PollData pd = edao.getPollData(taskType, d.trim());
			if(pd != null){
				if(pd.getLastPollTime() > System.currentTimeMillis() - (activeWorkerLastPollnSecs * 1000)){
					domain = d.trim();
					break;
				}
			}
		}
		return domain;
	}

	private long getTaskDuration(long s, Task task) {
		long duration = task.getEndTime() - task.getStartTime();
		s += duration;
		if (task.getRetriedTaskId() == null) {
			return s;
		}
		return s + getTaskDuration(s, edao.getTask(task.getRetriedTaskId()));
	}
	
	@VisibleForTesting
	boolean scheduleTask(Workflow workflow, List<Task> tasks) throws Exception {
		
		if (tasks == null || tasks.isEmpty()) {
			return false;
		}
		int count = workflow.getTasks().size();

		for (Task task : tasks) {
			task.setSeq(++count);
		}

		List<Task> created = edao.createTasks(tasks);
		List<Task> createdSystemTasks = created.stream().filter(task -> SystemTaskType.is(task.getTaskType())).collect(Collectors.toList());
		List<Task> toBeQueued = created.stream().filter(task -> !SystemTaskType.is(task.getTaskType())).collect(Collectors.toList());
		boolean startedSystemTasks = false;		
		for(Task task : createdSystemTasks) {

			WorkflowSystemTask stt = WorkflowSystemTask.get(task.getTaskType());
			if(stt == null) {
				throw new RuntimeException("No system task found by name " + task.getTaskType());
			}
			task.setStartTime(System.currentTimeMillis());
			if(!stt.isAsync()) {
				stt.start(workflow, task, this);
				startedSystemTasks = true;
				edao.updateTask(task);
			} else {
				toBeQueued.add(task);
			}
		}
		addTaskToQueue(toBeQueued);
		return startedSystemTasks;
	}

	private void addTaskToQueue(final List<Task> tasks) throws Exception {
		for (Task t : tasks) {
			addTaskToQueue(t);
		}
	}
	
	private void terminate(final WorkflowDef def, final Workflow workflow, TerminateWorkflow tw) throws Exception {
		
		if (!workflow.getStatus().isTerminal()) {
			workflow.setStatus(tw.workflowStatus);
		}

		String failureWorkflow = def.getFailureWorkflow();
		if (failureWorkflow != null) {
			if (failureWorkflow.startsWith("$")) {
				String[] paramPathComponents = failureWorkflow.split("\\.");
				String name = paramPathComponents[2]; // name of the input parameter
				failureWorkflow = (String) workflow.getInput().get(name);
			}
		}
		if(tw.task != null){
			edao.updateTask(tw.task);
		}
		terminateWorkflow(workflow, tw.getMessage(), failureWorkflow);
	}
	

}
