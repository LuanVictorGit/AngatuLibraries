package br.com.angatusistemas.lib;

import java.io.File;
import java.io.PrintStream;

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
 *   <li>Detecta modo localhost (sem certificados) ou produção (Let's Encrypt);</li>
 *   <li>Inicializa o {@link JavalinAPI#setup} com SSL, rate limiting e headers de segurança;</li>
 *   <li>Registra automaticamente as páginas HTML de {@code /public} via
 *       {@link HtmlRouteAPI}.</li>
 * </ol>
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
 *   <li>Construa {@code new AngatuLib(dominio, porta, bloqPorMaxRequisicoes)} no início do main;</li>
 *   <li>Configure rate limits e paths especiais via {@link JavalinAPI};</li>
 *   <li>Use as demais funcionalidades (rotas automáticas, Saveable, etc.).</li>
 * </ol>
 *
 * <p><strong>Exemplo:</strong>
 * <pre>
 * public class Main {
 *     public static void main(String[] args) {
 *         // Local: http://localhost:80
 *         new AngatuLib("localhost", 80, true);
 *
 *         // Produção: https://meusite.com.br:443 (HTTP redireciona para HTTPS)
 *         // new AngatuLib("meusite.com.br", 443, false);
 *     }
 * }
 * </pre>
 * </p>
 *
 * <p><strong>Modo localhost vs produção:</strong> se a pasta
 * {@code /etc/letsencrypt/live/<dominio>} existir, a biblioteca configura
 * HTTPS na porta informada (HTTP na porta + 1 com redirecionamento). Caso
 * contrário, ativa o modo localhost (HTTP na porta 80).</p>
 *
 * <p><strong>Boas práticas:</strong> use {@code localhost} como domínio e
 * {@code true} em desenvolvimento; em produção, o domínio real e
 * {@code bloqPorMaxRequisicoes} conforme a política de segurança desejada.</p>
 *
 * <p><strong>Limitações:</strong> requer a dependência
 * {@code io.javalin:javalin:7.2.2} (verificada no construtor); o
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

    /** Caminho da pasta de certificados Let's Encrypt do domínio. */
    private final String PATH_FOLDER_CERTS;
    /** Instância única da biblioteca (singleton). */
    @Getter private static AngatuLib instance;
    /** Habilita bloqueio por excesso de requisições (rate limiting). */
    private final boolean bloqByMaxRequisitions;
    /** Pasta de certificados (pode não existir → modo localhost). */
    private final File folderCerts;
    /** Porta principal do servidor. */
    private final int port;
    /** Stream original preservado antes do redirecionamento do System.out. */
    private final PrintStream originalOut = System.out;
    /** Instância do servidor Javalin configurado. */
    private final Javalin javalin;
    /** Domínio do certificado (ex: {@code "meusite.com.br"}). */
    private final String addressCertificate;
    /** {@code true} quando opera sem certificados (HTTP). */
    private boolean localhost = false;
    /** Host de origem capturado da primeira requisição. */
    private String originHost;

    /**
     * Inicializa a biblioteca: servidor web, log e infraestrutura de segurança.
     *
     * <p><strong>Pré-condições:</strong> dependência Javalin presente no
     * classpath (verificada no início); pasta {@code /public} em
     * {@code resources} com {@code index.html} para o registro de páginas.</p>
     *
     * <p><strong>Pós-condições:</strong> {@code instance} definida (singleton);
     * {@code System.out} redirecionado para o log colorido; servidor Javalin
     * iniciado e registrado em {@link #getJavalin()}; rotas de páginas HTML
     * registradas; banner de inicialização exibido.</p>
     *
     * <p><strong>Efeitos colaterais:</strong> redireciona o {@code System.out}
     * do processo (global); inicia threads do servidor e do pool de tarefas;
     * cria/abre o banco SQLite se módulos de persistência forem usados.</p>
     *
     * @param addressCertificate   Domínio (ex: {@code "localhost"},
     *                             {@code "meusite.com.br"}) — determina a pasta
     *                             de certificados
     * @param port                 Porta principal (HTTPS usa esta porta; em
     *                             localhost a porta 80 é usada)
     * @param bloqByMaxRequisitions {@code true} habilita rate limiting por
     *                             excesso de requisições
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência Javalin não estiver no classpath (a mensagem
     *         contém as instruções de instalação Maven/Gradle)
     */
    public AngatuLib(String addressCertificate, int port, boolean bloqByMaxRequisitions) {
        // Guard de dependência: verifica o Javalin ANTES de qualquer referência
        // ao servidor, exibindo instruções de instalação se ausente
        Dependencies.require("io.javalin.Javalin", "io.javalin:javalin:7.2.2", "Web Server (Javalin)");
        instance = this;
        this.bloqByMaxRequisitions = bloqByMaxRequisitions;
        this.port = port;
        this.addressCertificate = addressCertificate.toLowerCase();
        System.setOut(new PrintStream(new InterceptorOutputStream(), true));
        this.PATH_FOLDER_CERTS = "/etc/letsencrypt/live/" + addressCertificate;

        folderCerts = new File(PATH_FOLDER_CERTS);
        if (!folderCerts.exists()) {
            localhost = true;
            System.out.println("&eModo localhost ativado com sucesso.");
        } else {
            System.out.println("pasta dos certificados configurados com sucesso.");
        }

        javalin = JavalinAPI.setup(folderCerts, port, localhost, bloqByMaxRequisitions);
        if (javalin != null) {
            HtmlRouteAPI.registerAllRoutes(javalin);
            System.out.println("Javalin configurado com sucesso! -> " + getOriginHost());
            this.printBanner(addressCertificate);
        } else {
            Console.error("Para inicializar o javalin você precisa criar a pasta /public dentro de resources e adicionar o index.html");
        }
    }

    /**
     * Retorna o host de origem da aplicação.
     *
     * <p><strong>Objetivo:</strong> expor a URL base (esquema + host) usada pela
     * aplicação. Em modo localhost retorna {@code http://localhost}; em
     * produção, {@code https://<domínio>}. Se a primeira requisição HTTP já
     * chegou, o host real capturado é retornado (considerando proxies).</p>
     *
     * @return Host de origem (ex: {@code "https://meusite.com.br"})
     */
    public String getOriginHost() {
        return originHost == null
                ? (!localhost ? "https://" + addressCertificate : "http://localhost")
                : originHost;
    }

    /**
     * Exibe o banner de inicialização da biblioteca no console.
     *
     * @param addressCertificate Domínio usado na exibição do banner
     */
    private void printBanner(String addressCertificate) {
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
        Console.log("&6║&r        &fCertificado: &3%s&r", addressCertificate + "          ");
        Console.log("&6║&r        &fBanco: &aSQLite &7(WAL + Pool HikariCP)             ");
        Console.log("&6║&r                                                              ");
        Console.log("&6╚══════════════════════════════════════════════════════════════╝");
        Console.log("&7AngatuLib inicializado com sucesso. &2✔");
    }

}
