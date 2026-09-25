package br.com.angatusistemas.lib;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.console.InterceptorOutputStream;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.javalin.html.HtmlRouteAPI;
import br.com.angatusistemas.lib.task.Task;
import io.javalin.Javalin;
import lombok.Getter;
import lombok.Setter;

/**
 * Classe de entrada da biblioteca: inicializa o servidor web (Javalin), o log
 * colorido e a infraestrutura de segurança.
 *
 * <p><strong>Propósito:</strong> bootstrap da aplicação. Ao construir uma
 * instância, a biblioteca:</p>
 * <ol>
 *   <li>Verifica a presença da dependência do Javalin (mensagem clara se ausente);</li>
 *   <li>Redireciona {@code System.out} para o log colorido do {@link Console}
 *       (preservando o stream original);</li>
 *   <li>Sobe o servidor em <strong>HTTP na porta informada</strong>;</li>
 *   <li>Inicializa o {@link JavalinAPI#setup} com rate limiting, headers de segurança e o log de
 *       requisições (uma linha no terminal para cada uma);</li>
 *   <li>Registra automaticamente as páginas HTML de {@code /public} via
 *       {@link HtmlRouteAPI}.</li>
 * </ol>
 *
 * <p><strong>Só HTTP — o TLS é do Coolify:</strong> a biblioteca roda atrás do
 * <strong>Coolify</strong>, que termina o TLS no proxy de borda e entrega a requisição
 * em HTTP para o contêiner. Certificado, renovação e redirecionamento para HTTPS são da
 * hospedagem; a biblioteca não tem modo HTTPS próprio nem pasta de certificados.</p>
 *
 * <p><strong>Quando usar:</strong> uma única vez no {@code main} da aplicação.
 * É o ponto de entrada obrigatório para as funcionalidades web.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> se a aplicação não usa o servidor web,
 * não instancie — as funcionalidades independentes ({@code Saveable},
 * {@code Task}, {@code StringAPI}, etc.) funcionam sem ela. Também não instancie
 * mais de uma vez no mesmo processo (padrão singleton).</p>
 *
 * <p><strong>Integração:</strong> o {@link Console} passa a usar o stream
 * original preservado ({@code getOriginalOut()}); o {@link JavalinAPI} expõe a
 * instância do servidor ({@code getJavalin()}) e rotas podem ser adicionadas
 * após a inicialização.</p>
 *
 * <p><strong>Fluxo de utilização:</strong></p>
 * <ol>
 *   <li>Configure o arquivo {@code .env} (credenciais dos módulos usados);</li>
 *   <li>Adicione as dependências dos módulos usados ao seu build;</li>
 *   <li>Construa {@code new AngatuLib(host, porta, bloqPorMaxRequisicoes)} no início do main;</li>
 *   <li>Configure rate limits e paths especiais via {@link JavalinAPI};</li>
 *   <li>Use as demais funcionalidades (rotas automáticas, Saveable, etc.).</li>
 * </ol>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * public class Main {
 *     public static void main(String[] args) {
 *         // Coolify: HTTP na porta que vem do ambiente
 *         int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
 *         new AngatuLib("meusite.com.br", port, true);
 *
 *         // Desenvolvimento local
 *         // new AngatuLib("localhost", 8080, true);
 *     }
 * }
 * </pre>
 *
 * <p><strong>Ambiente (produção x desenvolvimento):</strong> resolvido nesta ordem:</p>
 * <ol>
 *   <li>propriedade {@code -Dangatu.env=} ou variável {@code ANGATU_ENV} /
 *       {@code ENVIRONMENT}: valor começando por {@code prod} é produção; começando por
 *       {@code dev}, ou {@code local}/{@code test}, é desenvolvimento;</li>
 *   <li>host local ({@code localhost}, {@code 127.0.0.1}, {@code 0.0.0.0}, {@code ::1},
 *       {@code [::1]}, {@code host.docker.internal}, {@code *.local}) → desenvolvimento;</li>
 *   <li>qualquer outro host → <strong>produção</strong> (regra conservadora: na
 *       dúvida, cookie {@code Secure} e nada de atalho de desenvolvimento).</li>
 * </ol>
 * <p>O resultado é lido por {@code isLocalhost()} e pode ser forçado com
 * {@code setLocalhost(boolean)} logo após o construtor. {@code ANGATU_ENV} e
 * {@code ENVIRONMENT} são lidos das variáveis de ambiente do processo — declarados só no
 * arquivo {@code .env}, não têm efeito aqui.</p>
 *
 * <p><strong>Boas práticas:</strong> em desenvolvimento use {@code "localhost"}
 * como host; no Coolify, o domínio real, {@code PORT} vindo do ambiente e o TLS
 * a cargo da hospedagem; declare {@code JavalinAPI.setTrustedProxyHops(1)}
 * quando houver proxy reverso na frente (é o caso do Coolify).</p>
 *
 * <p><strong>Limitações:</strong> requer a dependência
 * {@code io.javalin:javalin:7.2.3} (verificada no construtor); o
 * redirecionamento de {@code System.out} é global ao processo — preserve o
 * stream original se precisar restaurar a saída padrão.</p>
 *
 * <p><strong>Extensões futuras:</strong> a classe não é {@code final} e nem
 * {@code sealed} por compatibilidade — consumidores podem estendê-la para
 * customizar o bootstrap. Novos módulos de inicialização devem ser adicionados
 * ao construtor com o mesmo padrão de verificação de dependências.</p>
 *
 * @author Angatu Sistemas
 * @see JavalinAPI
 * @see Console
 * @see br.com.angatusistemas.lib.dependencies.Dependencies
 */
@Getter
@Setter
public class AngatuLib {

    /** Hosts que caracterizam execução local (desenvolvimento). */
    private static final Set<String> LOCAL_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]", "host.docker.internal");

    /**
     * Instância única da biblioteca (singleton). Volátil: é publicada no meio do construtor e
     * lida pelas threads do servidor e do log.
     */
    @Getter private static volatile AngatuLib instance;

    /** O gancho de desligamento é registrado uma vez por processo. */
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean();

    /**
     * O interceptador posto no {@code System.out}. O gancho de desligamento entrega por ele a
     * última linha, se ela não terminou com {@code '\n'}.
     */
    private static volatile InterceptorOutputStream logInterceptor;

    /** Host/domínio da aplicação (ex: {@code "loja.angatusistemas.com.br"}). */
    private final String host;
    /** Porta HTTP em que o servidor escuta. */
    private final int port;
    /** Habilita bloqueio por excesso de requisições (rate limiting). */
    private final boolean bloqByMaxRequisitions;
    /**
     * Primeiro {@code System.out} visto pelo processo, capturado no carregamento
     * da classe — antes, portanto, de qualquer interceptação. Guardar isto em
     * campo de instância deixaria uma segunda construção capturando o próprio
     * interceptador, e o log passaria a chamar a si mesmo até estourar a pilha.
     */
    private static final PrintStream PROCESS_OUT = System.out;

    /** Stream original preservado antes do redirecionamento do System.out. */
    private final PrintStream originalOut = PROCESS_OUT;
    /** Instância do servidor Javalin configurado. */
    private final Javalin javalin;
    /**
     * {@code true} quando a aplicação roda em ambiente de desenvolvimento. Volátil: pode ser
     * trocado depois do construtor, com as threads do servidor já rodando.
     */
    private volatile boolean localhost;
    /** Origem pública da aplicação; sobrescreve a origem derivada do host. Volátil, pelo mesmo motivo. */
    private volatile String originHost;

    /**
     * Inicializa a biblioteca em <strong>HTTP</strong>: servidor web, log e infraestrutura de
     * segurança.
     *
     * <p>O TLS é do Coolify: o proxy de borda termina o HTTPS e encaminha a requisição em HTTP
     * para a porta informada.</p>
     *
     * <p><strong>Pré-condições:</strong> dependência Javalin presente no
     * classpath (verificada no início); pasta {@code /public} em
     * {@code resources} com {@code index.html} para o registro de páginas.</p>
     *
     * <p><strong>Pós-condições:</strong> {@code instance} definida (singleton);
     * {@code System.out} redirecionado para o log colorido; servidor Javalin
     * escutando em {@code 0.0.0.0:port}; rotas de páginas HTML registradas;
     * banner de inicialização exibido.</p>
     *
     * <p><strong>Efeitos colaterais:</strong> redireciona o {@code System.out}
     * do processo (global); inicia threads do servidor e do pool de tarefas;
     * cria/abre o banco SQLite se módulos de persistência forem usados; registra, uma vez, o
     * gancho de desligamento que para o servidor, deixa as tarefas enfileiradas terminarem e
     * fecha o banco.</p>
     *
     * @param host                 Host/domínio da aplicação (ex: {@code "localhost"},
     *                             {@code "loja.angatusistemas.com.br"})
     * @param port                 Porta HTTP em que o servidor escuta
     * @param bloqByMaxRequisitions {@code true} habilita rate limiting por
     *                             excesso de requisições
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência Javalin não estiver no classpath (a mensagem
     *         contém as instruções de instalação Maven/Gradle)
     * @throws IllegalStateException se o servidor web não subir — porta em uso ou pasta
     *         {@code public} ausente; o motivo fica no log e o {@code System.out} volta ao original
     */
    public AngatuLib(String host, int port, boolean bloqByMaxRequisitions) {
        // Guard de dependência: verifica o Javalin ANTES de qualquer referência
        // ao servidor, exibindo instruções de instalação se ausente
        Dependencies.require("io.javalin.Javalin", "io.javalin:javalin:7.2.3", "Web Server (Javalin)");
        this.host = host == null || host.isBlank() ? "localhost" : host.trim().toLowerCase(Locale.ROOT);
        this.port = port;
        this.bloqByMaxRequisitions = bloqByMaxRequisitions;

        instance = this;
        // Sem autoflush e em UTF-8: cada println vira uma linha de log (com autoflush, um print
        // seguido de println virava duas) e acento não depende do file.encoding da máquina.
        InterceptorOutputStream interceptor = new InterceptorOutputStream();
        logInterceptor = interceptor;
        System.setOut(new PrintStream(interceptor, false, StandardCharsets.UTF_8));

        this.localhost = resolveLocalEnvironment();

        // As páginas entram antes de o servidor aceitar conexões, junto com o filtro e as rotas.
        javalin = JavalinAPI.setup(this.port, bloqByMaxRequisitions, HtmlRouteAPI::registerAllRoutes);
        if (javalin == null) {
            /* Falhar alto. Antes, o construtor voltava normalmente com o servidor fora do ar: o
               processo seguia vivo, sem nenhuma porta ouvindo, e o contêiner nunca era
               reiniciado. O motivo já foi registrado no log pelo JavalinAPI. */
            instance = null;
            System.setOut(PROCESS_OUT);
            throw new IllegalStateException("O servidor web não iniciou — veja o erro acima. Causa comum: "
                    + "falta a pasta src/main/resources/public (com o index.html), ou a porta " + port + " está em uso.");
        }
        Console.log("Javalin configurado com sucesso! -> %s", getOriginHost());
        registerShutdownHook(javalin);
        this.printBanner();
    }

    /**
     * Como a biblioteca subia com HTTPS gerenciado pelo próprio servidor.
     *
     * <p>Com {@code manageSsl = false}, é igual a {@link #AngatuLib(String, int, boolean)}.</p>
     *
     * @param host                 Host/domínio da aplicação
     * @param port                 Porta HTTP em que o servidor escuta
     * @param bloqByMaxRequisitions {@code true} habilita rate limiting por excesso de requisições
     * @param manageSsl            Só {@code false} é aceito
     * @throws UnsupportedOperationException se {@code manageSsl} for {@code true} — antes de
     *         qualquer efeito global: o {@code System.out} não é tocado
     * @deprecated A biblioteca roda só atrás do Coolify, que termina o TLS: não existe mais HTTPS
     *             gerenciado nem pasta de certificados. Use {@link #AngatuLib(String, int, boolean)}.
     */
    @Deprecated(forRemoval = true)
    public AngatuLib(String host, int port, boolean bloqByMaxRequisitions, boolean manageSsl) {
        this(host, httpOnly(port, manageSsl), bloqByMaxRequisitions);
    }

    /** Recusa o HTTPS gerenciado antes de o construtor principal rodar; devolve a porta. */
    private static int httpOnly(int port, boolean manageSsl) {
        if (manageSsl) {
            throw new UnsupportedOperationException("HTTPS gerenciado saiu da biblioteca: ela roda só atrás do "
                    + "Coolify, que termina o TLS. Use new AngatuLib(host, porta, rateLimit).");
        }
        return port;
    }

    /**
     * O HTTPS era gerenciado pelo próprio servidor?
     *
     * @return Sempre {@code false}
     * @deprecated Não existe mais HTTPS gerenciado: o TLS é do Coolify.
     */
    @Deprecated(forRemoval = true)
    public boolean isManageSsl() {
        return false;
    }

    /**
     * Caminho dos certificados do HTTPS gerenciado.
     *
     * @return Sempre {@code null}
     * @deprecated Não existe mais pasta de certificados: o TLS é do Coolify.
     */
    @Deprecated(forRemoval = true)
    public String getCertsPath() {
        return null;
    }

    /**
     * Pasta dos certificados do HTTPS gerenciado.
     *
     * @return Sempre {@code null}
     * @deprecated Não existe mais pasta de certificados: o TLS é do Coolify.
     */
    @Deprecated(forRemoval = true)
    public File getFolderCerts() {
        return null;
    }

    /**
     * Registra, uma vez por processo, o desligamento ordenado: para de aceitar requisições e
     * termina as que estão em curso, deixa as tarefas enfileiradas terminarem (até 5 s), fecha o
     * banco com checkpoint e entrega ao log a última linha, se ela não terminou com
     * {@code '\n'}.
     *
     * <p>Sem isto, um redeploy (SIGTERM) cortava requisições no meio e descartava o trabalho que
     * estava na fila do {@code Task} — os e-mails enfileirados, por exemplo. O prazo cabe nos 10 s
     * que o Docker dá antes de matar o processo. E um {@code print} sem quebra de linha logo antes
     * de sair ("... concluído") nunca aparecia: o interceptador só entrega a linha no
     * {@code '\n'}.</p>
     */
    private static void registerShutdownHook(Javalin server) {
        if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
            } catch (Throwable t) {
                PROCESS_OUT.println("Falha ao parar o servidor web: " + t);
            }
            try {
                Task.drain(5_000);
            } catch (Throwable t) {
                PROCESS_OUT.println("Falha ao encerrar as tarefas: " + t);
            }
            try {
                Saveable.shutdown();
            } catch (Throwable t) {
                PROCESS_OUT.println("Falha ao fechar o banco: " + t);
            }
            InterceptorOutputStream interceptor = logInterceptor;
            if (interceptor != null) {
                System.out.flush();  // o PrintStream guarda bytes antes de passá-los adiante
                interceptor.close(); // entrega a linha sem '\n'; o System.out continua aberto
            }
        }, "angatu-shutdown"));
    }

    /**
     * Retorna o host de origem (URL base pública) da aplicação.
     *
     * <p><strong>Objetivo:</strong> montar links absolutos — e-mails, Web Push,
     * QR Codes, retornos de pagamento. A origem é derivada assim:</p>
     * <ul>
     *   <li>{@code setOriginHost(String)} definido → vence sempre;</li>
     *   <li>ambiente de desenvolvimento → {@code http://localhost:<porta>};</li>
     *   <li>produção → {@code https://<host>}, porque quem termina o TLS é o
     *       Coolify e o endereço público é HTTPS.</li>
     * </ul>
     *
     * @return Host de origem (ex: {@code "https://loja.angatusistemas.com.br"})
     */
    public String getOriginHost() {
        if (originHost != null) return originHost;
        if (localhost) return "http://localhost" + (port == 80 ? "" : ":" + port);
        return "https://" + host;
    }

    /**
     * Host/domínio informado na inicialização.
     *
     * @deprecated A biblioteca não tem mais certificado: o parâmetro é só o host público da
     *             aplicação. Use {@code getHost()}.
     * @return Host da aplicação
     */
    @Deprecated
    public String getAddressCertificate() {
        return host;
    }

    /**
     * Resolve se a aplicação está em ambiente de desenvolvimento.
     *
     * <p>A dedução por pasta de certificados não vale mais: dentro de um
     * contêiner do Coolify não existe {@code /etc/letsencrypt} e a aplicação
     * ainda assim está em produção. A ordem é: declaração explícita por
     * variável de ambiente, nome de host local e, por fim, a regra
     * conservadora — host real é produção.</p>
     *
     * @return {@code true} quando é desenvolvimento local
     */
    private boolean resolveLocalEnvironment() {
        String declared = declaredEnvironment();
        if (declared != null) {
            if (declared.startsWith("prod")) return false;
            if (declared.startsWith("dev") || declared.equals("local") || declared.equals("test")) return true;
        }
        return LOCAL_HOSTS.contains(host) || host.endsWith(".local");
    }

    /**
     * Lê o ambiente declarado por propriedade de sistema ou variável de ambiente.
     *
     * @return Nome do ambiente em minúsculas, ou {@code null} se não declarado
     */
    private static String declaredEnvironment() {
        String value = System.getProperty("angatu.env");
        if (value == null || value.isBlank()) value = System.getenv("ANGATU_ENV");
        if (value == null || value.isBlank()) value = System.getenv("ENVIRONMENT");
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Exibe o banner de inicialização da biblioteca no console.
     */
    private void printBanner() {
        String mode = "HTTP (TLS no Coolify)";
        Console.log("&6╔══════════════════════════════════════════════════════════════╗");
        Console.log("&6║&r                                                              ");
        Console.log("&6║&r        &b&l&oAngatuLibs | AngatuSistemas                     ");
        Console.log("&6║&r        &7Framework de utilidades para projetos Java          ");
        Console.log("&6║&r                                                              ");
        Console.log("&6║&r        &fMódulos incluídos:&r                                ");
        Console.log("&6║&r        &8• &7Persistência (SQLite + HikariCP)                ");
        Console.log("&6║&r        &8• &7Logging avançado                                ");
        Console.log("&6║&r        &8• &7Web/API (Javalin)                               ");
        Console.log("&6║&r        &8• &7Cliente HTTP (Request)                          ");
        Console.log("&6║&r        &8• &7Utilidades gerais (Strings, JSON, etc)          ");
        Console.log("&6║&r                                                              ");
        Console.log("&6║&r        &fVersão: &eLATEST&r                                  ");
        Console.log("&6║&r        &fHost: &3%s&r", host + "          ");
        Console.log("&6║&r        &fModo: &3%s&r", mode + "          ");
        Console.log("&6║&r        &fAmbiente: &3%s&r", (localhost ? "desenvolvimento" : "produção") + "          ");
        Console.log("&6║&r        &fBanco: &aSQLite &7(WAL + Pool HikariCP)             ");
        Console.log("&6║&r                                                              ");
        Console.log("&6╚══════════════════════════════════════════════════════════════╝");
        Console.log("&7AngatuLib inicializado com sucesso. &2✔");
    }

}
