/**
 * Copyright (C) Gustav Karlsson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.kagkarlsson.scheduler.task;

import java.time.LocalDateTime;

import com.github.kagkarlsson.scheduler.TaskRepository;

public class ExecutionOperations {

	private final TaskRepository taskRepository;
	private final Execution execution;

	public ExecutionOperations(final TaskRepository taskRepository, final Execution execution) {
		this.taskRepository = taskRepository;
		this.execution = execution;
	}

	public void remove() {
		taskRepository.remove(execution);
	}

	public void reschedule(final ExecutionComplete completed, final LocalDateTime nextExecutionTime) {
		if (completed.getResult() == ExecutionComplete.Result.OK) {
			taskRepository.reschedule(execution, nextExecutionTime, completed.getTimeDone(), execution.lastFailure);
		} else {
			taskRepository.reschedule(execution, nextExecutionTime, execution.lastSuccess, completed.getTimeDone());
		}

	}

	public void markComplete() {
		taskRepository.markComplete(execution);
	}

}
