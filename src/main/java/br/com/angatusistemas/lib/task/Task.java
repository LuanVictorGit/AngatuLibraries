package br.com.angatusistemas.lib.task;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.angatusistemas.lib.console.Console;

/**
 * [PT] Classe utilitária para execução de tarefas assíncronas, síncronas, com
 * delay e repetição.
 * <p>
 * A classe mantém dois pools de threads:
 * <ul>
 * <li><b>ASYNC_EXECUTOR</b> – {@link ScheduledExecutorService} com 4 threads
 * para tarefas assíncronas, atrasadas ou repetitivas.</li>
 * <li><b>SYNC_EXECUTOR</b> – {@link ExecutorService} com uma única thread
 * (FIFO) para simular execução síncrona/sequencial sem bloquear a thread
 * chamadora.</li>
 * </ul>
 * Cada tarefa registrada recebe um ID único, que pode ser usado para
 * cancelamento posterior.
 * </p>
 * <p>
 * <b>Importante:</b> Ao final da aplicação, invoque {@link #shutdown()} para
 * encerrar os pools e evitar vazamento de recursos.
 * </p>
 *
 * [EN] Utility class for executing asynchronous, synchronous, delayed and
 * recurring tasks.
 * <p>
 * The class maintains two thread pools:
 * <ul>
 * <li><b>ASYNC_EXECUTOR</b> – {@link ScheduledExecutorService} with 4 threads
 * for asynchronous, delayed or recurring tasks.</li>
 * <li><b>SYNC_EXECUTOR</b> – {@link ExecutorService} with a single thread
 * (FIFO) to simulate synchronous/sequential execution without blocking the
 * caller thread.</li>
 * </ul>
 * Each registered task receives a unique ID that can be used for later
 * cancellation.
 * </p>
 * <p>
 * <b>Important:</b> At application shutdown, call {@link #shutdown()} to
 * terminate the pools and avoid resource leaks.
 * </p>
 *
 * @author [Sua equipe]
 * @see ScheduledExecutorService
 * @see ExecutorService
 */
public final class Task {

	// Pool para tarefas assíncronas com agendamento (delay, repetição)
	// Pool for asynchronous scheduled tasks (delay, repetition)
	private static final ScheduledExecutorService ASYNC_EXECUTOR = Executors.newScheduledThreadPool(4);

	// Pool para tarefas síncronas (única thread, ordem FIFO)
	// Pool for synchronous tasks (single thread, FIFO order)
	private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor();

	// Gerador de IDs únicos para cada tarefa
	// Unique ID generator for each task
	private static final AtomicInteger TASK_ID_COUNTER = new AtomicInteger(0);

	// Mapa que associa cada ID à sua Future correspondente (permite cancelamento)
	// Map that associates each ID to its corresponding Future (allows cancellation)
	private static final Map<Integer, ScheduledFuture<?>> TASKS = new ConcurrentHashMap<>();

	private Task() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	// ==================== MÉTODOS PÚBLICOS ====================
	// ==================== PUBLIC METHODS ====================

	/**
	 * [PT] Executa uma tarefa imediatamente de forma assíncrona (em uma das threads
	 * do pool).
	 * <p>
	 * A tarefa não bloqueia a thread chamadora. O ID retornado pode ser usado para
	 * cancelar a tarefa se ela ainda não tiver sido executada.
	 * </p>
	 *
	 * [EN] Executes a task immediately asynchronously (in one of the pool threads).
	 * <p>
	 * The task does not block the calling thread. The returned ID can be used to
	 * cancel the task if it hasn't run yet.
	 * </p>
	 *
	 * @param runnable [PT] tarefa a ser executada [EN] task to be executed
	 * @return [PT] ID único da tarefa (pode ser usado em {@link #cancelTask(int)})
	 *         [EN] unique task ID (can be used in {@link #cancelTask(int)})
	 */
	public static int runAsync(Runnable runnable) {
		int taskId = TASK_ID_COUNTER.incrementAndGet();

		ScheduledFuture<?> future = ASYNC_EXECUTOR.schedule(() -> {
			try {
				runnable.run();
			} catch (Exception e) {
				Console.error("Erro na tarefa assíncrona ID=%d", e, taskId);
			} finally {
				TASKS.remove(taskId);
			}
		}, 0, TimeUnit.MILLISECONDS);

		TASKS.put(taskId, future);
		Console.debug("Tarefa assíncrona iniciada. ID=%d", taskId);
		return taskId;
	}

	/**
	 * [PT] Executa uma tarefa de forma "síncrona" (sequencial, em thread única).
	 * <p>
	 * As tarefas enviadas a este método são executadas uma após a outra, em ordem
	 * FIFO, em uma thread dedicada. Isso é útil para operações que devem ser
	 * serializadas, mas sem bloquear a thread principal (ex: escrita em arquivo,
	 * atualização de recurso compartilhado).
	 * </p>
	 *
	 * [EN] Executes a task in a "synchronous" manner (sequential, single thread).
	 * <p>
	 * Tasks submitted to this method run one after another, in FIFO order, on a
	 * dedicated thread. Useful for operations that must be serialized without
	 * blocking the main thread (e.g., file writing, shared resource update).
	 * </p>
	 *
	 * @param runnable [PT] tarefa a ser executada [EN] task to be executed
	 * @return [PT] ID único da tarefa [EN] unique task ID
	 */
	public static int runSync(Runnable runnable) {
		int taskId = TASK_ID_COUNTER.incrementAndGet();

		SYNC_EXECUTOR.submit(() -> {
			try {
				runnable.run();
			} catch (Exception e) {
				Console.error("Erro na tarefa síncrona ID=%d", e, taskId);
			} finally {
				TASKS.remove(taskId);
			}
		});

		Console.debug("Tarefa síncrona enfileirada. ID=%d", taskId);
		return taskId;
	}

	/**
	 * [PT] Executa uma tarefa após um atraso (delay) em milissegundos.
	 * <p>
	 * A tarefa será executada uma única vez, após o tempo especificado.
	 * </p>
	 *
	 * [EN] Executes a task after a delay (in milliseconds).
	 * <p>
	 * The task will be executed once, after the specified delay.
	 * </p>
	 *
	 * @param runnable    [PT] tarefa a ser executada [EN] task to be executed
	 * @param delayMillis [PT] atraso em milissegundos antes da execução [EN] delay
	 *                    in milliseconds before execution
	 * @return [PT] ID único da tarefa [EN] unique task ID
	 */
	public static int runLater(Runnable runnable, long delayMillis) {
		int taskId = TASK_ID_COUNTER.incrementAndGet();

		ScheduledFuture<?> future = ASYNC_EXECUTOR.schedule(() -> {
			try {
				runnable.run();
			} catch (Exception e) {
				Console.error("Erro na tarefa com delay ID=%d", e, taskId);
			} finally {
				TASKS.remove(taskId);
			}
		}, delayMillis, TimeUnit.MILLISECONDS);

		TASKS.put(taskId, future);
		Console.debug("Tarefa com delay agendada. ID=%d, delay=%dms", taskId, delayMillis);
		return taskId;
	}

	/**
	 * [PT] Executa uma tarefa repetidamente a cada período fixo.
	 * <p>
	 * A primeira execução ocorre após {@code delayMillis}, e depois repetidamente a
	 * cada {@code periodMillis}. O intervalo é medido entre o início de cada
	 * execução.
	 * </p>
	 * <p>
	 * <b>Cuidado:</b> Se a tarefa demorar mais que o período, as execuções podem se
	 * sobrepor. Para evitar isso, considere usar
	 * {@link #runTimerWithFixedDelay(Runnable, long, long)}.
	 * </p>
	 *
	 * [EN] Executes a task repeatedly at a fixed rate.
	 * <p>
	 * The first execution occurs after {@code delayMillis}, then repeatedly every
	 * {@code periodMillis}. The interval is measured between the start of each
	 * execution.
	 * </p>
	 * <p>
	 * <b>Caution:</b> If the task takes longer than the period, executions may
	 * overlap. To avoid that, consider using
	 * {@link #runTimerWithFixedDelay(Runnable, long, long)}.
	 * </p>
	 *
	 * @param runnable     [PT] tarefa a ser executada [EN] task to be executed
	 * @param delayMillis  [PT] atraso inicial antes da primeira execução [EN]
	 *                     initial delay before first execution
	 * @param periodMillis [PT] intervalo entre o início de cada execução [EN]
	 *                     interval between the start of each execution
	 * @return [PT] ID único da tarefa [EN] unique task ID
	 */
	public static int runTimer(Runnable runnable, long delayMillis, long periodMillis) {
		int taskId = TASK_ID_COUNTER.incrementAndGet();

		ScheduledFuture<?> future = ASYNC_EXECUTOR.scheduleAtFixedRate(() -> {
			try {
				runnable.run();
			} catch (Exception e) {
				Console.error("Erro na tarefa periódica (fixed rate) ID=%d", e, taskId);
			}
		}, delayMillis, periodMillis, TimeUnit.MILLISECONDS);

		TASKS.put(taskId, future);
		Console.debug("Timer (fixed rate) agendado. ID=%d, delay=%dms, period=%dms", taskId, delayMillis, periodMillis);
		return taskId;
	}

	/**
	 * [PT] Executa uma tarefa repetidamente com atraso fixo entre o término de uma
	 * execução e o início da próxima.
	 * <p>
	 * Útil quando a tarefa pode ter duração variável e você quer garantir um
	 * intervalo entre execuções.
	 * </p>
	 *
	 * [EN] Executes a task repeatedly with a fixed delay between the end of one
	 * execution and the start of the next.
	 * <p>
	 * Useful when the task may have variable duration and you want to guarantee a
	 * pause between executions.
	 * </p>
	 *
	 * @param runnable       [PT] tarefa a ser executada [EN] task to be executed
	 * @param initialDelayMs [PT] atraso inicial antes da primeira execução [EN]
	 *                       initial delay before first execution
	 * @param delayBetweenMs [PT] atraso entre o fim de uma execução e o início da
	 *                       próxima [EN] delay between the end of one execution and
	 *                       the start of the next
	 * @return [PT] ID único da tarefa [EN] unique task ID
	 */
	public static int runTimerWithFixedDelay(Runnable runnable, long initialDelayMs, long delayBetweenMs) {
		int taskId = TASK_ID_COUNTER.incrementAndGet();

		ScheduledFuture<?> future = ASYNC_EXECUTOR.scheduleWithFixedDelay(() -> {
			try {
				runnable.run();
			} catch (Exception e) {
				Console.error("Erro na tarefa periódica (fixed delay) ID=%d", e, taskId);
			}
		}, initialDelayMs, delayBetweenMs, TimeUnit.MILLISECONDS);

		TASKS.put(taskId, future);
		Console.debug("Timer (fixed delay) agendado. ID=%d, initialDelay=%dms, delayBetween=%dms", taskId,
				initialDelayMs, delayBetweenMs);
		return taskId;
	}

	/**
	 * [PT] Cancela uma tarefa específica pelo seu ID.
	 * <p>
	 * Se a tarefa já tiver sido executada ou estiver em andamento, o cancelamento
	 * pode não ter efeito (depende do estado). Tarefas periódicas são
	 * interrompidas.
	 * </p>
	 *
	 * [EN] Cancels a specific task by its ID.
	 * <p>
	 * If the task has already been executed or is in progress, cancellation may
	 * have no effect (depends on state). Periodic tasks are interrupted.
	 * </p>
	 *
	 * @param taskId [PT] ID da tarefa retornado por um dos métodos de criação [EN]
	 *               task ID returned by one of the creation methods
	 */
	public static void cancelTask(int taskId) {
		ScheduledFuture<?> future = TASKS.remove(taskId);
		if (future != null) {
			boolean cancelled = future.cancel(true);
			Console.debug("Cancelamento da tarefa ID=%d: %s", taskId,
					cancelled ? "sucesso" : "falha (já executada ou inexistente)");
		} else {
			Console.debug("Tarefa ID=%d não encontrada para cancelamento", taskId);
		}
	}

	/**
	 * [PT] Cancela todas as tarefas atualmente registradas.
	 * <p>
	 * Útil durante o desligamento da aplicação ou para limpeza forçada.
	 * </p>
	 *
	 * [EN] Cancels all currently registered tasks.
	 * <p>
	 * Useful during application shutdown or for forced cleanup.
	 * </p>
	 */
	public static void cancelAll() {
		for (Map.Entry<Integer, ScheduledFuture<?>> entry : TASKS.entrySet()) {
			entry.getValue().cancel(true);
		}
		TASKS.clear();
		Console.log("Todas as tarefas foram canceladas.");
	}

	/**
	 * [PT] Encerra os pools de threads e cancela todas as tarefas pendentes.
	 * <p>
	 * Este método deve ser chamado ao final da aplicação para liberar recursos.
	 * Após o shutdown, nenhuma nova tarefa pode ser submetida.
	 * </p>
	 *
	 * [EN] Shuts down the thread pools and cancels all pending tasks.
	 * <p>
	 * This method should be called at application termination to release resources.
	 * After shutdown, no new tasks can be submitted.
	 * </p>
	 */
	public static void shutdown() {
		Console.log("Iniciando shutdown do Task...");
		cancelAll();
		ASYNC_EXECUTOR.shutdown();
		SYNC_EXECUTOR.shutdown();
		try {
			// Aguarda até 5 segundos para as tarefas terminarem
			if (!ASYNC_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
				ASYNC_EXECUTOR.shutdownNow();
			}
			if (!SYNC_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
				SYNC_EXECUTOR.shutdownNow();
			}
		} catch (InterruptedException e) {
			ASYNC_EXECUTOR.shutdownNow();
			SYNC_EXECUTOR.shutdownNow();
			Thread.currentThread().interrupt();
		}
		Console.log("Task finalizado.");
	}

	/**
	 * [PT] Retorna o número de tarefas atualmente ativas (agendadas ou em
	 * execução). [EN] Returns the number of currently active tasks (scheduled or
	 * running).
	 *
	 * @return [PT] quantidade de tarefas no mapa [EN] number of tasks in the map
	 */
	public static int activeTaskCount() {
		return TASKS.size();
	}
}