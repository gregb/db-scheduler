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
package com.github.kagkarlsson.scheduler;

import static java.util.Optional.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kagkarlsson.jdbc.JdbcRunner;
import com.github.kagkarlsson.jdbc.Mappers;
import com.github.kagkarlsson.jdbc.ResultSetMapper;
import com.github.kagkarlsson.jdbc.SQLRuntimeException;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;

public class JdbcTaskRepository implements TaskRepository {
	private static final Logger LOG = LoggerFactory.getLogger(JdbcTaskRepository.class);
	private static final int MAX_DUE_RESULTS = 10_000;
	private final TaskResolver taskResolver;
	private final SchedulerName schedulerSchedulerName;
	private final JdbcRunner jdbcRunner;

	public JdbcTaskRepository(final DataSource dataSource, final TaskResolver taskResolver, final SchedulerName schedulerSchedulerName) {
		this.taskResolver = taskResolver;
		this.schedulerSchedulerName = schedulerSchedulerName;
		this.jdbcRunner = new JdbcRunner(dataSource);
	}

	@Override
	public boolean createIfNotExists(final Execution execution) {
		try {
			jdbcRunner.execute(
					"insert into scheduled_tasks(task_name, task_instance, execution_time, picked, version) values(?, ?, ?, ?, ?)",
					(final PreparedStatement p) -> {
						p.setString(1, execution.taskInstance.getTask().getName());
						p.setString(2, execution.taskInstance.getId());
						p.setTimestamp(3, Timestamp.valueOf(execution.executionTime));
						p.setBoolean(4, false);
						p.setLong(5, 1L);
					});
			return true;

		} catch (final SQLRuntimeException e) {
			LOG.debug("Exception when inserting execution. Assuming it to be a constraint violation.", e);
			final Boolean exists = jdbcRunner.query("select 1 from scheduled_tasks where task_name = ? and task_instance = ?",
					ps -> {
						ps.setString(1, execution.taskInstance.getTaskName());
						ps.setString(2, execution.taskInstance.getId());
					},
					Mappers.NON_EMPTY_RESULTSET);
			if (!exists) {
				throw new RuntimeException("Failed to add new execution.", e);
			}
			LOG.debug("Exception was due to a constraint violation. Another thread inserted the execution.");
			return false;
		}
	}

	@Override
	public List<Execution> getDue(final LocalDateTime now) {
		return getDue(now, MAX_DUE_RESULTS);
	}

	public List<Execution> getDue(final LocalDateTime now, final int limit) {
		return jdbcRunner.query(
				"select * from scheduled_tasks where picked = ? and execution_time <= ? and complete = ? order by execution_time asc",
				(final PreparedStatement p) -> {
					p.setBoolean(1, false);
					p.setTimestamp(2, Timestamp.valueOf(now));
					p.setBoolean(3, false);
					p.setMaxRows(limit);
				},
				new ExecutionResultSetMapper()
				);
	}

	@Override
	public void remove(final Execution execution) {

		final int removed = jdbcRunner.execute("delete from scheduled_tasks where task_name = ? and task_instance = ? and version = ?",
				ps -> {
					ps.setString(1, execution.taskInstance.getTaskName());
					ps.setString(2, execution.taskInstance.getId());
					ps.setLong(3, execution.version);
				}
				);

		if (removed != 1) {
			throw new RuntimeException("Expected one execution to be removed, but removed " + removed + ". Indicates a bug.");
		}
	}

	@Override
	public void markComplete(final Execution execution) {
		final int updated = jdbcRunner.execute("update scheduled_tasks set complete = ? where task_name = ? and task_instance = ? and version = ?", ps -> {
			ps.setBoolean(1, true);
			ps.setString(2, execution.taskInstance.getTaskName());
			ps.setString(3, execution.taskInstance.getId());
			ps.setLong(4, execution.version);
		});

		if (updated != 1) {
			throw new RuntimeException("Expected one execution to be updated, but updated " + updated + ". Indicates a bug.");
		}
	}

	@Override
	public void reschedule(final Execution execution, final LocalDateTime nextExecutionTime, final LocalDateTime lastSuccess, final LocalDateTime lastFailure) {
		final int updated = jdbcRunner.execute(
				"update scheduled_tasks set " +
						"picked = ?, " +
						"picked_by = ?, " +
						"last_heartbeat = ?, " +
						"last_success = ?, " +
						"last_failure = ?, " +
						"execution_time = ?, " +
						"version = version + 1" +
						"where task_name = ? " +
						"and task_instance = ? " +
						"and version = ?",
						ps -> {
							ps.setBoolean(1, false);
							ps.setString(2, null);
							ps.setTimestamp(3, null);
							ps.setTimestamp(4, ofNullable(lastSuccess).map(Timestamp::valueOf).orElse(null));
							ps.setTimestamp(5, ofNullable(lastFailure).map(Timestamp::valueOf).orElse(null));
							ps.setTimestamp(6, Timestamp.valueOf(nextExecutionTime));
							ps.setString(7, execution.taskInstance.getTaskName());
							ps.setString(8, execution.taskInstance.getId());
							ps.setLong(9, execution.version);
						});

		if (updated != 1) {
			throw new RuntimeException("Expected one execution to be updated, but updated " + updated + ". Indicates a bug.");
		}
	}

	@Override
	public Optional<Execution> pick(final Execution e, final LocalDateTime timePicked) {
		final int updated = jdbcRunner.execute(
				"update scheduled_tasks set picked = ?, picked_by = ?, last_heartbeat = ?, version = version + 1 " +
						"where picked = ? " +
						"and task_name = ? " +
						"and task_instance = ?" +
						"and version = ?",
						ps -> {
							ps.setBoolean(1, true);
							ps.setString(2, schedulerSchedulerName.getName());
							ps.setTimestamp(3, Timestamp.valueOf(timePicked));
							ps.setBoolean(4, false);
							ps.setString(5, e.taskInstance.getTaskName());
							ps.setString(6, e.taskInstance.getId());
							ps.setLong(7, e.version);
						});

		if (updated == 0) {
			LOG.trace("Failed to pick execution. It must have been picked by another scheduler.", e);
			return Optional.empty();
		} else if (updated == 1) {
			final Optional<Execution> pickedExecution = getExecution(e.taskInstance);
			if (!pickedExecution.isPresent()) {
				throw new IllegalStateException("Unable to find picked execution. Must have been deleted by another thread. Indicates a bug.");
			} else if (!pickedExecution.get().isPicked()) {
				throw new IllegalStateException("Picked execution does not have expected state in database: " + pickedExecution.get());
			}
			return pickedExecution;
		} else {
			throw new IllegalStateException("Updated multiple rows when picking single execution. Should never happen since name and id is primary key. Execution: " + e);
		}
	}

	@Override
	public List<Execution> getOldExecutions(final LocalDateTime olderThan) {
		return jdbcRunner.query(
				"select * from scheduled_tasks where picked = ? and last_heartbeat <= ? order by last_heartbeat asc",
				(final PreparedStatement p) -> {
					p.setBoolean(1, true);
					p.setTimestamp(2, Timestamp.valueOf(olderThan));
				},
				new ExecutionResultSetMapper()
				);
	}

	@Override
	public void updateHeartbeat(final Execution e, final LocalDateTime newHeartbeat) {

		final int updated = jdbcRunner.execute(
				"update scheduled_tasks set last_heartbeat = ? " +
						"where task_name = ? " +
						"and task_instance = ? " +
						"and version = ?" + "and complete = false",
						ps -> {
							ps.setTimestamp(1, Timestamp.valueOf(newHeartbeat));
							ps.setString(2, e.taskInstance.getTaskName());
							ps.setString(3, e.taskInstance.getId());
							ps.setLong(4, e.version);
						});

		if (updated == 0) {
			LOG.trace("Did not update heartbeat. Execution must have been removed or rescheduled.", e);
		} else {
			if (updated > 1) {
				throw new IllegalStateException("Updated multiple rows updating heartbeat for execution. Should never happen since name and id is primary key. Execution: " + e);
			}
			LOG.debug("Updated heartbeat for execution: " + e);
		}
	}

	@Override
	public List<Execution> getExecutionsFailingLongerThan(final Duration interval) {
		return jdbcRunner.query(
				"select * from scheduled_tasks where " +
						"	(last_success is null and last_failure is not null)" +
						"	or (last_failure is not null and last_success < ?)",
						(final PreparedStatement p) -> {
							p.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now().minus(interval)));
						},
						new ExecutionResultSetMapper()
				);
	}

	public Optional<Execution> getExecution(final TaskInstance taskInstance) {
		final List<Execution> executions = jdbcRunner.query(
				"select * from scheduled_tasks where task_name = ? and task_instance = ?",
				(final PreparedStatement p) -> {
					p.setString(1, taskInstance.getTaskName());
					p.setString(2, taskInstance.getId());
				},
				new ExecutionResultSetMapper()
				);
		if (executions.size() > 1) {
			throw new RuntimeException("Found more than one matching execution for taskInstance: " + taskInstance);
		}

		return executions.size() == 1 ? ofNullable(executions.get(0)) : Optional.<Execution>empty();
	}

	private class ExecutionResultSetMapper implements ResultSetMapper<List<Execution>> {
		@Override
		public List<Execution> map(final ResultSet rs) throws SQLException {

			final List<Execution> executions = new ArrayList<>();
			while (rs.next()) {
				final String taskName = rs.getString("task_name");
				final Task task = taskResolver.resolve(taskName);

				if (task == null) {
					continue;
				}

				final String taskInstance = rs.getString("task_instance");

				final LocalDateTime executionTime = rs.getTimestamp("execution_time").toLocalDateTime();

				final boolean picked = rs.getBoolean("picked");
				final String pickedBy = rs.getString("picked_by");
				final LocalDateTime lastSuccess = ofNullable(rs.getTimestamp("last_success"))
						.map(Timestamp::toLocalDateTime).orElse(null);
				final LocalDateTime lastFailure = ofNullable(rs.getTimestamp("last_failure"))
						.map(Timestamp::toLocalDateTime).orElse(null);
				final LocalDateTime lastHeartbeat = ofNullable(rs.getTimestamp("last_heartbeat"))
						.map(Timestamp::toLocalDateTime).orElse(null);
				final long version = rs.getLong("version");
				executions.add(new Execution(executionTime, new TaskInstance(task, taskInstance), picked, pickedBy, lastSuccess, lastFailure, lastHeartbeat, version));
			}
			return executions;
		}
	}

	@Override
	public void registerTask(final Task task) {
		taskResolver.addTask(task);
	}
}
