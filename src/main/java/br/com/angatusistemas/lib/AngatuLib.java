package br.com.angatusistemas.lib;

import java.io.File;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Set;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.console.InterceptorOutputStream;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.javalin.html.HtmlRouteAPI;
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
 *   <li>Sobe o servidor em <strong>HTTP na porta informada</strong> — o padrão;</li>
 *   <li>Configura HTTPS <strong>somente</strong> quando isso é pedido
 *       explicitamente pelo parâmetro {@code manageSsl};</li>
 *   <li>Inicializa o {@link JavalinAPI#setup} com rate limiting e headers de segurança;</li>
 *   <li>Registra automaticamente as páginas HTML de {@code /public} via
 *       {@link HtmlRouteAPI}.</li>
 * </ol>
 *
 * <p><strong>HTTP por padrão, HTTPS só quando pedido:</strong> os projetos são
 * hospedados no <strong>Coolify</strong>, que termina o TLS no proxy de borda e
 * entrega a requisição em HTTP para o contêiner. Por isso a biblioteca
 * <strong>nunca</strong> configura HTTPS por conta própria: ela sobe em HTTP na
 * porta informada e deixa certificado, renovação e redirecionamento com a
 * hospedagem. Quem roda fora do Coolify — em uma VPS com Let's Encrypt, por
 * exemplo — pede o modo HTTPS pelo quarto parâmetro do construtor.</p>
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
 * original preservado ({@link #getOriginalOut()}); o {@link JavalinAPI} expõe a
 * instância do servidor ({@link #getJavalin()}) e rotas podem ser adicionadas
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
 * <p><strong>Exemplo:</strong>
 * <pre>
 * public class Main {
 *     public static void main(String[] args) {
 *         // Padrão (Coolify e desenvolvimento): HTTP na porta informada
 *         int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
 *         new AngatuLib("meusite.com.br", port, true);
 *
 *         // Desenvolvimento local
 *         // new AngatuLib("localhost", 8080, true);
 *
 *         // Fora do Coolify, com Let's Encrypt no próprio servidor:
 *         // new AngatuLib("meusite.com.br", 443, true, true);
 *     }
 * }
 * </pre>
 * </p>
 *
 * <p><strong>Ambiente (produção x desenvolvimento):</strong> a biblioteca não
 * deduz mais o ambiente pela existência de certificados — dentro de um
 * contêiner eles nunca existem. O ambiente é resolvido, nesta ordem, por:</p>
 * <ol>
 *   <li>propriedade {@code -Dangatu.env=} ou variável {@code ANGATU_ENV} /
 *       {@code ENVIRONMENT} ({@code production}/{@code prod} ou
 *       {@code development}/{@code dev}/{@code local});</li>
 *   <li>{@code manageSsl == true} → produção;</li>
 *   <li>host local ({@code localhost}, {@code 127.0.0.1}, {@code ::1},
 *       {@code 0.0.0.0}, {@code *.local}) → desenvolvimento;</li>
 *   <li>qualquer outro host → <strong>produção</strong> (regra conservadora: na
 *       dúvida, cookie {@code Secure} e nada de atalho de desenvolvimento).</li>
 * </ol>
 * <p>O resultado é lido por {@link #isLocalhost()} e pode ser forçado com
 * {@link #setLocalhost(boolean)} logo após o construtor.</p>
 *
 * <p><strong>Boas práticas:</strong> em desenvolvimento use {@code "localhost"}
 * como host; no Coolify, o domínio real, {@code PORT} vindo do ambiente e o TLS
 * a cargo da hospedagem; declare {@code JavalinAPI.setTrustedProxyHops(1)}
 * quando houver proxy reverso na frente (é o caso do Coolify).</p>
 *
 * <p><strong>Limitações:</strong> requer a dependência
 * {@code io.javalin:javalin:7.2.2} (verificada no construtor) e, apenas no modo
 * HTTPS, {@code io.javalin.community.ssl:javalin-ssl:7.2.2}; o
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

    /** Raiz dos certificados Let's Encrypt usada no modo HTTPS gerenciado. */
    private static final String LETS_ENCRYPT_ROOT = "/etc/letsencrypt/live/";

    /** Hosts que caracterizam execução local (desenvolvimento). */
    private static final Set<String> LOCAL_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]", "host.docker.internal");

    /** Instância única da biblioteca (singleton). */
    @Getter private static AngatuLib instance;

    /** Host/domínio da aplicação (ex: {@code "loja.angatusistemas.com.br"}). */
    private final String host;
    /** Porta em que o servidor escuta (HTTP) ou porta segura (HTTPS gerenciado). */
    private final int port;
    /** Habilita bloqueio por excesso de requisições (rate limiting). */
    private final boolean bloqByMaxRequisitions;
    /** {@code true} quando o próprio Javalin gerencia o certificado SSL. */
    private final boolean manageSsl;
    /** Caminho dos certificados no modo HTTPS gerenciado; {@code null} em HTTP. */
    private final String certsPath;
    /** Pasta dos certificados no modo HTTPS gerenciado; {@code null} em HTTP. */
    private final File folderCerts;
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
    /** {@code true} quando a aplicação roda em ambiente de desenvolvimento. */
    private boolean localhost;
    /** Origem pública da aplicação; sobrescreve a origem derivada do host. */
    private String originHost;

    /**
     * Inicializa a biblioteca em <strong>HTTP</strong> (padrão): servidor web,
     * log e infraestrutura de segurança.
     *
     * <p>Este é o construtor usado pelos projetos hospedados no Coolify. O TLS
     * fica a cargo da hospedagem: o proxy de borda termina o HTTPS e encaminha a
     * requisição em HTTP para a porta informada.</p>
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
     * @param host                 Host/domínio da aplicação (ex: {@code "localhost"},
     *                             {@code "loja.angatusistemas.com.br"})
     * @param port                 Porta HTTP em que o servidor escuta
     * @param bloqByMaxRequisitions {@code true} habilita rate limiting por
     *                             excesso de requisições
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência Javalin não estiver no classpath (a mensagem
     *         contém as instruções de instalação Maven/Gradle)
     */
    public AngatuLib(String host, int port, boolean bloqByMaxRequisitions) {
        this(host, port, bloqByMaxRequisitions, false);
    }

    /**
     * Inicializa a biblioteca escolhendo explicitamente quem gerencia o SSL.
     *
     * <p><strong>Efeitos colaterais:</strong> redireciona o {@code System.out}
     * do processo (global); inicia threads do servidor e do pool de tarefas;
     * cria/abre o banco SQLite se módulos de persistência forem usados.</p>
     *
     * @param host                 Host/domínio da aplicação — no modo HTTPS
     *                             define a pasta
     *                             {@code /etc/letsencrypt/live/<host>}
     * @param port                 Porta principal: porta HTTP no modo padrão;
     *                             porta HTTPS quando {@code manageSsl} é
     *                             {@code true} (o HTTP sobe em {@code port + 1}
     *                             apenas para redirecionar)
     * @param bloqByMaxRequisitions {@code true} habilita rate limiting por
     *                             excesso de requisições
     * @param manageSsl            {@code true} para o Javalin gerenciar o
     *                             certificado SSL (exige {@code fullchain.pem} e
     *                             {@code privkey.pem} em
     *                             {@code /etc/letsencrypt/live/<host>});
     *                             {@code false} (padrão) mantém HTTP e deixa o
     *                             HTTPS com a hospedagem — Coolify, nginx ou
     *                             outro proxy reverso
     * @throws IllegalStateException se {@code manageSsl} for {@code true} e os
     *         certificados não existirem — pedir HTTPS e servir HTTP em silêncio
     *         seria um rebaixamento de segurança
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência Javalin (ou javalin-ssl, no modo HTTPS) não
     *         estiver no classpath
     */
    public AngatuLib(String host, int port, boolean bloqByMaxRequisitions, boolean manageSsl) {
        // Guard de dependência: verifica o Javalin ANTES de qualquer referência
        // ao servidor, exibindo instruções de instalação se ausente
        Dependencies.require("io.javalin.Javalin", "io.javalin:javalin:7.2.2", "Web Server (Javalin)");
        this.host = host == null || host.isBlank() ? "localhost" : host.trim().toLowerCase(Locale.ROOT);
        this.port = port;
        this.bloqByMaxRequisitions = bloqByMaxRequisitions;
        this.manageSsl = manageSsl;

        if (manageSsl) {
            this.certsPath = LETS_ENCRYPT_ROOT + this.host;
            this.folderCerts = new File(certsPath);
            // Validação antes de qualquer efeito global: uma inicialização que
            // falha não deixa o processo com o System.out já redirecionado
            requireCertificates();
        } else {
            this.certsPath = null;
            this.folderCerts = null;
        }

        instance = this;
        System.setOut(new PrintStream(new InterceptorOutputStream(), true));

        if (manageSsl) {
            System.out.println("&eHTTPS gerenciado pelo Javalin: certificados em " + certsPath);
        } else {
            System.out.println("&eModo HTTP na porta " + port + ": o HTTPS é da hospedagem (Coolify/proxy reverso).");
        }

        this.localhost = resolveLocalEnvironment();

        javalin = JavalinAPI.setup(this.port, bloqByMaxRequisitions, manageSsl, folderCerts);
        if (javalin != null) {
            HtmlRouteAPI.registerAllRoutes(javalin);
            System.out.println("Javalin configurado com sucesso! -> " + getOriginHost());
            this.printBanner();
        } else {
            Console.error("Para inicializar o javalin você precisa criar a pasta /public dentro de resources e adicionar o index.html");
        }
    }

    /**
     * Retorna o host de origem (URL base pública) da aplicação.
     *
     * <p><strong>Objetivo:</strong> montar links absolutos — e-mails, Web Push,
     * QR Codes, retornos de pagamento. A origem é derivada assim:</p>
     * <ul>
     *   <li>{@link #setOriginHost(String)} definido → vence sempre;</li>
     *   <li>ambiente de desenvolvimento → {@code http://localhost:<porta>};</li>
     *   <li>HTTPS gerenciado pelo Javalin → {@code https://<host>} (com a porta
     *       quando não for 443);</li>
     *   <li>HTTP em produção → {@code https://<host>}, porque quem termina o TLS
     *       é a hospedagem e o endereço público continua sendo HTTPS.</li>
     * </ul>
     *
     * <p>Se a aplicação for publicada em HTTP puro, sem proxy com TLS, declare a
     * origem real com {@link #setOriginHost(String)} logo após o construtor.</p>
     *
     * @return Host de origem (ex: {@code "https://loja.angatusistemas.com.br"})
     */
    public String getOriginHost() {
        if (originHost != null) return originHost;
        if (localhost) return "http://localhost" + (port == 80 ? "" : ":" + port);
        if (manageSsl) return "https://" + host + (port == 443 ? "" : ":" + port);
        return "https://" + host;
    }

    /**
     * Host/domínio informado na inicialização.
     *
     * @deprecated O parâmetro deixou de significar apenas "domínio do
     *             certificado" — em HTTP ele é só o host público da aplicação.
     *             Use {@link #getHost()}.
     * @return Host da aplicação
     */
    @Deprecated
    public String getAddressCertificate() {
        return host;
    }

    /**
     * Verifica se os certificados exigidos pelo modo HTTPS existem.
     *
     * <p>Falha alto e cedo: HTTPS foi pedido explicitamente, então servir HTTP
     * em silêncio esconderia um rebaixamento de segurança justamente onde ele
     * importa.</p>
     *
     * @throws IllegalStateException se {@code fullchain.pem} ou {@code privkey.pem}
     *         não existirem na pasta do domínio
     */
    private void requireCertificates() {
        File fullchain = new File(folderCerts, "fullchain.pem");
        File privkey = new File(folderCerts, "privkey.pem");
        if (fullchain.isFile() && privkey.isFile()) return;
        throw new IllegalStateException(
                "HTTPS foi solicitado (manageSsl = true) mas os certificados não foram encontrados em "
                        + certsPath + " (esperado fullchain.pem e privkey.pem). "
                        + "Gere os certificados para o domínio " + host
                        + " ou use o construtor de três parâmetros, deixando o HTTPS com a hospedagem (Coolify).");
    }

    /**
     * Resolve se a aplicação está em ambiente de desenvolvimento.
     *
     * <p>A dedução por pasta de certificados não vale mais: dentro de um
     * contêiner do Coolify não existe {@code /etc/letsencrypt} e a aplicação
     * ainda assim está em produção. A ordem é: declaração explícita por
     * variável de ambiente, HTTPS gerenciado, nome de host local e, por fim, a
     * regra conservadora — host real é produção.</p>
     *
     * @return {@code true} quando é desenvolvimento local
     */
    private boolean resolveLocalEnvironment() {
        String declared = declaredEnvironment();
        if (declared != null) {
            if (declared.startsWith("prod")) return false;
            if (declared.startsWith("dev") || declared.equals("local") || declared.equals("test")) return true;
        }
        if (manageSsl) return false;
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
        String mode = manageSsl
                ? "HTTPS (certificado gerenciado pelo Javalin)"
                : "HTTP (TLS a cargo da hospedagem)";
        Console.log("&6╔══════════════════════════════════════════════════════════════╗");
        Console.log("&6║&r                                                              ");
        Console.log("&6║&r        &b&l&oAngatuLibs | AngatuSistemas                     ");
        Console.log("&6║&r        &7Framework de utilidades para projetos Java          ");
        Console.log("&6║&r                                                              ");
        Console.log("&6║&r        &fMódulos incluídos:&r                                ");
        Console.log("&6║&r        &8• &7Persistência (SQLite + HikariCP)                ");
        Console.log("&6║&r        &8• &7Logging avançado                                ");
        Console.log("&6║&r        &8• &7Web/API (Javalin)                               ");
        Console.log("&6║&r        &8• &7HTTP Client (OkHttp / Unirest)                  ");
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
