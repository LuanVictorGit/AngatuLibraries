package br.com.angatusistemas.lib.task;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import br.com.angatusistemas.lib.console.Console;

/**
 * Classe utilitária para execução de tarefas assíncronas, sequenciais, com
 * delay e repetição.
 *
 * <p><strong>Propósito:</strong> gerenciar execução concorrente sem que o
 * consumidor crie threads manualmente. Mantém três grupos de threads, cada um com um papel:</p>
 * <ul>
 * <li><b>Trabalhadores</b> ({@code Angatu-Async-N}) — executam {@link #runAsync} e as tarefas de
 * {@link #runLater} quando o prazo delas vence. São {@code ANGATU_TASK_THREADS} threads (padrão
 * 16), com fila sem limite.</li>
 * <li><b>Relógio</b> ({@code Angatu-Timer-N}) — dispara os prazos e executa as tarefas
 * periódicas ({@link #runTimer}, {@link #runTimerWithFixedDelay}). Não recebe trabalho avulso:
 * uma rajada de mil tarefas avulsas não atrasa a limpeza periódica do servidor.</li>
 * <li><b>Fila única</b> ({@code Angatu-Sync-N}) — {@link #runSync}, uma tarefa por vez, em
 * ordem FIFO, sem bloquear o chamador.</li>
 * </ul>
 * <p>Cada tarefa recebe um ID positivo para cancelamento posterior ({@link #cancelTask(int)}),
 * que nunca é entregue a outra enquanto ela estiver registrada — nem quando o contador dá a
 * volta.</p>
 *
 * <h2>Por que o relógio é separado</h2>
 * <p>Antes, um único pool de 4 threads fazia tudo. Quatro tarefas lentas — chamadas de rede de
 * um processamento em massa, por exemplo — ocupavam as quatro threads, e a varredura periódica do
 * rate limit parava de rodar enquanto a fila não esvaziasse (medido: um timer de 100 ms rodou
 * zero vezes em 2,9 s). Sem a varredura, a memória do rate limit cresce sem recuar.</p>
 *
 * <p><strong>Quando usar:</strong> qualquer operação que não deva bloquear a
 * thread atual (envios, persistência assíncrona, timers) e operações que
 * precisam de serialização (fila FIFO via {@link #runSync}).</p>
 *
 * <p><strong>Quando NÃO usar:</strong> para tarefas críticas que exigem
 * garantia de execução imediata (o agendamento é best-effort); para tarefas periódicas lentas
 * — elas ocupam uma thread do relógio enquanto rodam; dentro delas, mande o trabalho pesado para
 * {@link #runAsync}. {@code runSync} NÃO bloqueia o chamador — apenas serializa a execução em
 * thread única.</p>
 *
 * <p><strong>Integração:</strong> usado internamente pela limpeza periódica do
 * {@code JavalinAPI}. E-mail e Web Push têm fila própria: um servidor lento do outro lado não
 * ocupa estas threads.</p>
 *
 * <p><strong>Fluxo de utilização:</strong></p>
 * <ol>
 *   <li>Chame o método adequado ({@link #runAsync}, {@link #runLater},
 *       {@link #runTimer}, {@link #runTimerWithFixedDelay}, {@link #runSync});</li>
 *   <li>Guarde o ID retornado se precisar cancelar;</li>
 *   <li>Ao encerrar a aplicação, chame {@link #drain(long)} (espera o que está na fila) ou
 *       {@link #shutdown()} (cancela o que está pendente). Com o {@code AngatuLib}, o gancho de
 *       desligamento da biblioteca já chama {@link #drain(long)}.</li>
 * </ol>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * Task.runAsync(() -&gt; System.out.println("assíncrono"));
 * int id = Task.runLater(() -&gt; System.out.println("daqui a 5s"), 5000);
 * Task.runTimerWithFixedDelay(() -&gt; System.out.println("a cada hora"), 0, 3600_000);
 * Task.cancelTask(id);
 * </pre>
 *
 * <p><strong>Boas práticas:</strong> qualquer falha lançada dentro de uma tarefa — inclusive
 * {@link Error}, como {@code StackOverflowError} — é capturada e registrada no log, e uma tarefa
 * periódica continua agendada depois dela. Antes, só {@link Exception} era capturada: um
 * {@code Error} matava o timer em silêncio, sem uma linha no log.</p>
 *
 * <p><strong>Limitações:</strong> a fila dos trabalhadores não tem limite — sob sobrecarga, as
 * tarefas esperam, não são recusadas; após {@link #shutdown()} ou {@link #drain(long)} nenhuma
 * nova tarefa pode ser submetida ({@code RejectedExecutionException}). As threads não são
 * daemon: uma aplicação que só usa {@code Task} continua viva enquanto houver tarefa.</p>
 *
 * @author Angatu Sistemas
 * @see ScheduledExecutorService
 * @see ExecutorService
 */
public final class Task {

	/** Trabalhadores: {@code ANGATU_TASK_THREADS} (1 a 256), padrão 16. */
	private static final int WORKER_THREADS = intFromEnvironment("ANGATU_TASK_THREADS", 16, 1, 256);

	/** Threads do relógio: poucas bastam, porque só disparam prazos e rodam as periódicas. */
	private static final int TIMER_THREADS = 4;

	/** Relógio: dispara os prazos e executa as tarefas periódicas. */
	private static final ScheduledThreadPoolExecutor TIMERS = newTimers();

	/** Trabalhadores: {@link #runAsync} e as tarefas de {@link #runLater} já vencidas. */
	private static final ThreadPoolExecutor WORKERS = newWorkers();

	/** Fila única (ordem FIFO) de {@link #runSync}. */
	private static final ExecutorService SYNC_EXECUTOR =
			Executors.newSingleThreadExecutor(namedThreadFactory("Angatu-Sync-%d"));

	/** Contador dos IDs das tarefas; o ID de cada uma sai de {@link #reserveId()}. */
	private static final AtomicInteger TASK_ID_COUNTER = new AtomicInteger(0);

	/**
	 * ID → tarefa, para cancelar e contar. A tarefa entra aqui <strong>antes</strong> de ser
	 * agendada: registrada depois, uma tarefa rápida podia terminar — e se remover do mapa —
	 * antes do registro, e a entrada ficava para sempre (medido: ~0,1% de todas as tarefas). A de
	 * uma execução sai sozinha ao terminar ou ser cancelada (ver {@link OneShot#done()}).
	 */
	private static final Map<Integer, Future<?>> TASKS = new ConcurrentHashMap<>();

	/** Ocupa o ID no mapa enquanto a tarefa dele é criada (ver {@link #reserveId()}). */
	private static final Future<?> RESERVED = CompletableFuture.completedFuture(null);

	private Task() {
		throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
	}

	// ==================== MÉTODOS PÚBLICOS ====================

	/**
	 * Executa uma tarefa imediatamente, de forma assíncrona, numa thread trabalhadora.
	 *
	 * <p>A tarefa não bloqueia a thread chamadora. O ID retornado pode ser usado para cancelar a
	 * tarefa se ela ainda não tiver começado.</p>
	 *
	 * @param runnable Tarefa a ser executada
	 * @return ID único da tarefa (pode ser usado em {@link #cancelTask(int)})
	 */
	public static int runAsync(Runnable runnable) {
		return runLater(runnable, 0);
	}

	/**
	 * Executa uma tarefa de forma "síncrona": sequencial, em thread única.
	 *
	 * <p>As tarefas enviadas a este método são executadas uma após a outra, em ordem FIFO, numa
	 * thread dedicada. Útil para operações que devem ser serializadas sem bloquear a thread
	 * principal (escrita em arquivo, atualização de recurso compartilhado). O ID pode ser usado
	 * em {@link #cancelTask(int)} enquanto a tarefa ainda não começou.</p>
	 *
	 * @param runnable Tarefa a ser executada
	 * @return ID único da tarefa
	 */
	public static int runSync(Runnable runnable) {
		int taskId = reserveId();
		OneShot task = new OneShot(taskId, guarded("tarefa síncrona", taskId, runnable));
		start(task, () -> SYNC_EXECUTOR.execute(task));
		Console.debug("Tarefa síncrona enfileirada. ID=%d", taskId);
		return taskId;
	}

	/**
	 * Executa uma tarefa uma única vez, depois de um atraso em milissegundos.
	 *
	 * <p>O relógio só dispara o prazo; a tarefa roda numa thread trabalhadora.</p>
	 *
	 * @param runnable    Tarefa a ser executada
	 * @param delayMillis Atraso em milissegundos antes da execução ({@code 0} ou menos = já)
	 * @return ID único da tarefa
	 */
	public static int runLater(Runnable runnable, long delayMillis) {
		int taskId = reserveId();
		OneShot task = new OneShot(taskId, guarded("tarefa assíncrona", taskId, runnable));
		start(task, () -> {
			if (delayMillis <= 0) {
				WORKERS.execute(task);
			} else {
				task.trigger = TIMERS.schedule(() -> dispatch(task), delayMillis, TimeUnit.MILLISECONDS);
			}
		});
		Console.debug("Tarefa agendada. ID=%d, delay=%dms", taskId, delayMillis);
		return taskId;
	}

	/**
	 * Executa uma tarefa repetidamente, a cada período fixo, numa thread do relógio.
	 *
	 * <p>A primeira execução ocorre após {@code delayMillis}, e depois a cada
	 * {@code periodMillis}, medidos entre os inícios. Duas execuções da mesma tarefa nunca rodam
	 * ao mesmo tempo: se uma demorar mais que o período, a seguinte começa atrasada.</p>
	 *
	 * @param runnable     Tarefa a ser executada
	 * @param delayMillis  Atraso inicial antes da primeira execução
	 * @param periodMillis Intervalo entre o início de cada execução
	 * @return ID único da tarefa
	 */
	public static int runTimer(Runnable runnable, long delayMillis, long periodMillis) {
		int taskId = reserveId();
		// Periódica nunca sai do mapa sozinha, então registrar depois de agendar não tem corrida.
		startPeriodic(taskId, () -> TIMERS.scheduleAtFixedRate(guarded("tarefa periódica", taskId, runnable),
				delayMillis, periodMillis, TimeUnit.MILLISECONDS));
		Console.debug("Timer (fixed rate) agendado. ID=%d, delay=%dms, period=%dms", taskId, delayMillis, periodMillis);
		return taskId;
	}

	/**
	 * Executa uma tarefa repetidamente, com atraso fixo entre o fim de uma execução e o início
	 * da próxima, numa thread do relógio.
	 *
	 * <p>Útil quando a tarefa pode ter duração variável e você quer garantir um intervalo entre
	 * execuções.</p>
	 *
	 * @param runnable       Tarefa a ser executada
	 * @param initialDelayMs Atraso inicial antes da primeira execução
	 * @param delayBetweenMs Atraso entre o fim de uma execução e o início da próxima
	 * @return ID único da tarefa
	 */
	public static int runTimerWithFixedDelay(Runnable runnable, long initialDelayMs, long delayBetweenMs) {
		int taskId = reserveId();
		startPeriodic(taskId, () -> TIMERS.scheduleWithFixedDelay(guarded("tarefa periódica", taskId, runnable),
				initialDelayMs, delayBetweenMs, TimeUnit.MILLISECONDS));
		Console.debug("Timer (fixed delay) agendado. ID=%d, initialDelay=%dms, delayBetween=%dms", taskId,
				initialDelayMs, delayBetweenMs);
		return taskId;
	}

	/**
	 * Cancela uma tarefa específica pelo seu ID.
	 *
	 * <p>Tarefa que ainda não começou não roda mais; tarefa em execução recebe interrupção;
	 * tarefa periódica não volta a ser agendada. Tarefa já terminada não é afetada.</p>
	 *
	 * @param taskId ID da tarefa retornado por um dos métodos de criação
	 */
	public static void cancelTask(int taskId) {
		Future<?> future = TASKS.remove(taskId);
		if (future != null) {
			boolean cancelled = future.cancel(true);
			Console.debug("Cancelamento da tarefa ID=%d: %s", taskId,
					cancelled ? "sucesso" : "falha (já executada ou inexistente)");
		} else {
			Console.debug("Tarefa ID=%d não encontrada para cancelamento", taskId);
		}
	}

	/**
	 * Cancela todas as tarefas atualmente registradas.
	 *
	 * <p>Cada tarefa sai do mapa e é cancelada individualmente: esvaziar o mapa inteiro depois do
	 * laço tirava dele, sem cancelar, a tarefa registrada no meio do caminho.</p>
	 */
	public static void cancelAll() {
		cancelRegistered(true);
		Console.log("Todas as tarefas foram canceladas.");
	}

	/**
	 * Encerra os pools de threads e cancela todas as tarefas pendentes.
	 *
	 * <p>O que está na fila é descartado. Para deixar terminar o que já foi pedido, use
	 * {@link #drain(long)}. Depois do encerramento, nenhuma nova
	 * tarefa pode ser submetida.</p>
	 */
	public static void shutdown() {
		Console.log("Iniciando shutdown do Task...");
		cancelAll();
		stopAccepting();
		awaitOrForce(5_000);
		cancelRegistered(false);
		Console.log("Task finalizado.");
	}

	/**
	 * Encerra os pools <strong>deixando terminar</strong> o trabalho já pedido, até o prazo.
	 *
	 * <p>Para de aceitar tarefas, cancela as periódicas (elas nunca terminam) e os prazos ainda
	 * não vencidos, e espera as tarefas em execução ou na fila dos trabalhadores e da fila única —
	 * inclusive a de prazo já vencido que o relógio, ocupado, ainda não tinha disparado (ver
	 * {@link #dispatch(OneShot)}). Passado o prazo, interrompe o que sobrou. É o que o gancho de
	 * desligamento do {@code AngatuLib} chama: num redeploy, o {@link #shutdown()} descartava os
	 * envios que estavam na fila.</p>
	 *
	 * @param timeoutMillis Prazo total de espera
	 * @return {@code true} se tudo terminou dentro do prazo
	 */
	public static boolean drain(long timeoutMillis) {
		stopAccepting();
		boolean finished = awaitOrForce(timeoutMillis);
		cancelRegistered(false); // o que sobrou no mapa não roda mais: periódicas e prazos cancelados
		return finished;
	}

	/**
	 * Retorna o número de tarefas atualmente ativas (agendadas, na fila ou em execução).
	 *
	 * @return Quantidade de tarefas registradas
	 */
	public static int activeTaskCount() {
		return TASKS.size();
	}

	// ==================== INFRAESTRUTURA ====================

	/**
	 * Tarefa de uma execução: a mesma instância é agendada no relógio e executada pelos
	 * trabalhadores (ou pela fila única). Cancelar cancela os dois — o prazo e a execução.
	 */
	private static final class OneShot extends FutureTask<Void> {

		/** ID da tarefa no mapa. */
		final int id;

		/** Prazo no relógio, quando houver atraso. */
		volatile ScheduledFuture<?> trigger;

		OneShot(int id, Runnable work) {
			super(work, null);
			this.id = id;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			ScheduledFuture<?> scheduled = trigger;
			if (scheduled != null) scheduled.cancel(false);
			return super.cancel(mayInterruptIfRunning);
		}

		/**
		 * Terminou, falhou ou foi cancelada: sai do mapa. Só esta instância — o ID já pode ter
		 * sido reservado para outra tarefa, que não pode sair junto.
		 */
		@Override
		protected void done() {
			TASKS.remove(id, this);
		}
	}

	/**
	 * Reserva um ID livre e positivo para uma tarefa nova.
	 *
	 * <p>O contador dá a volta: depois de 2^31 tarefas ele recomeça do 1 — numa aplicação que
	 * agenda mil tarefas por segundo, menos de um mês no ar. O ID de uma tarefa ainda registrada
	 * é pulado: entregue a outra, ele sobrescrevia o registro de um timer criado na subida, a
	 * tarefa nova o apagava ao terminar, e o {@link #cancelTask(int)} daquele timer deixava de
	 * pará-lo.</p>
	 */
	private static int reserveId() {
		while (true) {
			int id = TASK_ID_COUNTER.updateAndGet(last -> last <= 0 || last == Integer.MAX_VALUE ? 1 : last + 1);
			if (TASKS.putIfAbsent(id, RESERVED) == null) return id;
		}
	}

	/**
	 * Registra a tarefa de uma execução e a entrega ao executor. Recusada — depois do
	 * desligamento —, ela sai do mapa e a recusa sobe: não fica contada como ativa para sempre.
	 */
	private static void start(OneShot task, Runnable schedule) {
		TASKS.put(task.id, task);
		try {
			schedule.run();
		} catch (RuntimeException refused) {
			TASKS.remove(task.id, task);
			throw refused;
		}
	}

	/** Agenda a periódica e a registra no ID reservado; recusada, libera o ID e a recusa sobe. */
	private static void startPeriodic(int taskId, Supplier<ScheduledFuture<?>> schedule) {
		try {
			TASKS.put(taskId, schedule.get());
		} catch (RuntimeException refused) {
			TASKS.remove(taskId, RESERVED);
			throw refused;
		}
	}

	/**
	 * Entrega aos trabalhadores a tarefa cujo prazo venceu.
	 *
	 * <p>No {@link #drain(long)}, os trabalhadores param de aceitar tarefa antes de o relógio
	 * terminar. Um prazo que já tinha vencido, mas que o relógio ainda não disparara — as threads
	 * dele ocupadas com uma periódica lenta —, era recusado ao disparar, e a tarefa sumia sem
	 * rodar: pedida antes do desligamento, perdida nele. Recusada, ela roda aqui mesmo, na thread
	 * do relógio, e o {@code drain} espera por ela. Tarefa cancelada não roda: o {@code run} de
	 * um {@link FutureTask} cancelado não faz nada.</p>
	 */
	private static void dispatch(OneShot task) {
		try {
			WORKERS.execute(task);
		} catch (RejectedExecutionException closed) {
			task.run();
		}
	}

	/** Para de aceitar tarefas: prazos ainda não vencidos e periódicas são cancelados no relógio. */
	private static void stopAccepting() {
		TIMERS.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		TIMERS.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		TIMERS.shutdown();
		WORKERS.shutdown();
		SYNC_EXECUTOR.shutdown();
	}

	/**
	 * Tira cada tarefa do mapa e a cancela, uma a uma: esvaziar o mapa inteiro depois do laço
	 * tirava dele, sem cancelar, a tarefa registrada no meio do caminho.
	 */
	private static void cancelRegistered(boolean interrupt) {
		for (Integer taskId : TASKS.keySet()) {
			Future<?> future = TASKS.remove(taskId);
			if (future != null) future.cancel(interrupt);
		}
	}

	/** Envolve a tarefa: registra no log qualquer falha — {@link Throwable}, não só {@link Exception}. */
	private static Runnable guarded(String kind, int taskId, Runnable runnable) {
		return () -> {
			try {
				runnable.run();
			} catch (Throwable t) {
				Console.error("Erro na " + kind + " ID=" + taskId, t);
			}
		};
	}

	/** Espera os três pools terminarem dentro do prazo; o que sobrar é interrompido. */
	private static boolean awaitOrForce(long timeoutMillis) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
		boolean finished = true;
		try {
			for (ExecutorService pool : new ExecutorService[] {WORKERS, SYNC_EXECUTOR, TIMERS}) {
				long remaining = Math.max(0, deadline - System.nanoTime());
				if (!pool.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
					pool.shutdownNow();
					finished = false;
				}
			}
		} catch (InterruptedException e) {
			WORKERS.shutdownNow();
			SYNC_EXECUTOR.shutdownNow();
			TIMERS.shutdownNow();
			Thread.currentThread().interrupt();
			return false;
		}
		return finished;
	}

	private static ScheduledThreadPoolExecutor newTimers() {
		ScheduledThreadPoolExecutor timers =
				new ScheduledThreadPoolExecutor(TIMER_THREADS, namedThreadFactory("Angatu-Timer-%d"));
		// Tarefa cancelada sai da fila na hora, em vez de esperar o prazo dela vencer.
		timers.setRemoveOnCancelPolicy(true);
		return timers;
	}

	private static ThreadPoolExecutor newWorkers() {
		ThreadPoolExecutor workers = new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), namedThreadFactory("Angatu-Async-%d"));
		// Sem trabalho, as threads encerram depois de um minuto: nada fica parado ocupando memória.
		workers.allowCoreThreadTimeOut(true);
		return workers;
	}

	/**
	 * Lê um inteiro do ambiente, preso entre um mínimo e um máximo; valor ausente ou ilegível
	 * volta ao padrão.
	 */
	private static int intFromEnvironment(String key, int fallback, int min, int max) {
		try {
			String value = System.getenv(key);
			if (value == null || value.isBlank()) return fallback;
			return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/**
	 * Cria uma {@link ThreadFactory} com nomes descritivos para as threads dos pools.
	 *
	 * @param pattern Padrão de nome com um placeholder {@code %d} (ex: {@code "Angatu-Async-%d"})
	 * @return Factory que gera threads nomeadas e não-daemon
	 */
	private static ThreadFactory namedThreadFactory(String pattern) {
		AtomicInteger counter = new AtomicInteger(1);
		return runnable -> {
			Thread thread = new Thread(runnable, String.format(pattern, counter.getAndIncrement()));
			thread.setDaemon(false);
			return thread;
		};
	}
}
