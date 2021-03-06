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

public abstract class OneTimeTask extends Task {

	public OneTimeTask(final String name) {
		super(name, new CompletionHandler.OnCompleteRemove(), new DeadExecutionHandler.RescheduleDeadExecution());
	}

	public OneTimeTask(final String name, final CompletionHandler completionHandler) {
		super(name, completionHandler, new DeadExecutionHandler.RescheduleDeadExecution());
	}

}
