package br.com.angatusistemas.lib.database;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import br.com.angatusistemas.lib.console.Console;

/**
 * Utilitário de sincronização automática dos dados abertos de CNPJ da Receita Federal.
 *
 * <h2>Descrição</h2>
 * <p>
 * Ao ser inicializado via {@link #start()}, agenda uma tarefa que:
 * <ol>
 *   <li>Baixa os arquivos ZIP de empresas e estabelecimentos disponibilizados
 *       pela Receita Federal em {@code arquivos.receitafederal.gov.br};</li>
 *   <li>Descompacta e interpreta os CSVs (separador {@code ;}, charset ISO-8859-1);</li>
 *   <li>Persiste cada registro como uma instância de {@link Company}, que estende
 *       {@link Saveable}, usando {@code saveSync()} para garantia de escrita.</li>
 * </ol>
 * A tarefa é repetida a cada 30 dias. A data da última sincronização é registrada
 * em {@code cnpj_sync_state.properties} para que a aplicação não faça download
 * desnecessário ao reiniciar antes do prazo.
 * </p>
 *
 * <h2>Uso</h2>
 * <pre>
 * // Na inicialização da aplicação:
 * BrazilianCompanySync.start();
 *
 * // Para buscar uma empresa pelo CNPJ básico (8 dígitos):
 * Company c = Saveable.findById(Company.class, "12345678");
 * System.out.println(c.getRazaoSocial());
 *
 * // Para encerrar corretamente:
 * BrazilianCompanySync.shutdown();
 * Saveable.shutdown();
 * </pre>
 *
 * <h2>Avisos de volume</h2>
 * <p>
 * A base completa da Receita Federal contém aproximadamente <strong>60 milhões</strong>
 * de registros distribuídos em ~20 arquivos ZIP (total ~20 GB descompactados).
 * O download e a importação completa podem levar <strong>várias horas</strong>
 * dependendo da infraestrutura. Considere executar em janela de manutenção ou
 * limitar os arquivos via {@link Builder#maxFiles(int)}.
 * </p>
 *
 * @author Angatu Sistemas
 */
public final class CompanySyncs {

    // ==================== CONSTANTES ====================

    /**
     * Página oficial de dados abertos de CNPJ da Receita Federal.
     * Os arquivos estão listados em https://arquivos.receitafederal.gov.br/index.php/s/YggdBLfdninEJX9
     * O padrão de nome dos arquivos de empresa é Empresas[N].zip (0-9) e
     * de estabelecimento é Estabelecimentos[N].zip (0-9).
     */
    private static final String BASE_URL =
        "https://arquivos.receitafederal.gov.br/index.php/s/YggdBLfdninEJX9/download?path=%2F&files=";

    /** Arquivos de empresas (dados cadastrais da matriz). */
    private static final String[] EMPRESA_FILES = {
        "Empresas0.zip", "Empresas1.zip", "Empresas2.zip", "Empresas3.zip",
        "Empresas4.zip", "Empresas5.zip", "Empresas6.zip", "Empresas7.zip",
        "Empresas8.zip", "Empresas9.zip"
    };

    /** Arquivos de estabelecimentos (endereço, situação, CNAE, contato). */
    private static final String[] ESTABELECIMENTO_FILES = {
        "Estabelecimentos0.zip", "Estabelecimentos1.zip", "Estabelecimentos2.zip",
        "Estabelecimentos3.zip", "Estabelecimentos4.zip", "Estabelecimentos5.zip",
        "Estabelecimentos6.zip", "Estabelecimentos7.zip", "Estabelecimentos8.zip",
        "Estabelecimentos9.zip"
    };

    /** Arquivo de Simples Nacional / MEI. */
    private static final String SIMPLES_FILE = "Simples.zip";

    /** Charset dos arquivos CSV da Receita Federal. */
    private static final Charset RF_CHARSET = Charset.forName("ISO-8859-1");

    /** Separador de campos dos CSVs. */
    private static final char SEPARATOR = ';';

    /** Arquivo de controle para persistir a data da última sincronização. */
    private static final String STATE_FILE = "cnpj_sync_state.properties";

    /** Intervalo entre sincronizações (30 dias). */
    private static final long SYNC_INTERVAL_DAYS = 30;

    // ==================== ESTADO ====================

    private static volatile CompanySyncs INSTANCE;
    private static final Object INIT_LOCK = new Object();

    private final ScheduledExecutorService scheduler;
    private final int maxEmpresaFiles;
    private final int maxEstabFiles;
    private final boolean importSimples;
    private final Path tempDir;

    // ==================== CONSTRUTOR / BUILDER ====================

    private CompanySyncs(Builder builder) {
        this.maxEmpresaFiles  = builder.maxEmpresaFiles;
        this.maxEstabFiles    = builder.maxEstabFiles;
        this.importSimples    = builder.importSimples;
        this.tempDir          = builder.tempDir;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BrazilianCompanySync");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Builder para configurar a sincronização antes de iniciá-la.
     *
     * <pre>
     * BrazilianCompanySync.builder()
     *     .maxFiles(2)              // importa apenas os primeiros 2 arquivos de cada tipo (teste)
     *     .withSimples(false)       // ignora Simples/MEI
     *     .tempDir(Path.of("/tmp/cnpj"))
     *     .build()
     *     .start();
     * </pre>
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxEmpresaFiles  = EMPRESA_FILES.length;
        private int maxEstabFiles    = ESTABELECIMENTO_FILES.length;
        private boolean importSimples = true;
        private Path tempDir          = Path.of("cnpj_tmp");

        /**
         * Limita o número de arquivos de empresa e estabelecimento a importar.
         * Útil para testes (ex.: {@code maxFiles(1)} processa apenas
         * {@code Empresas0.zip} e {@code Estabelecimentos0.zip}).
         */
        public Builder maxFiles(int n) {
            this.maxEmpresaFiles = Math.min(n, EMPRESA_FILES.length);
            this.maxEstabFiles   = Math.min(n, ESTABELECIMENTO_FILES.length);
            return this;
        }

        /** Define se o arquivo Simples.zip (Simples Nacional / MEI) deve ser importado. */
        public Builder withSimples(boolean enabled) {
            this.importSimples = enabled;
            return this;
        }

        /** Diretório temporário onde os ZIPs serão baixados e extraídos. */
        public Builder tempDir(Path dir) {
            this.tempDir = dir;
            return this;
        }

        /** Constrói e retorna a instância configurada (sem iniciar). Use {@link CompanySyncs#start()} depois. */
        public CompanySyncs build() {
            synchronized (INIT_LOCK) {
                if (INSTANCE != null) {
                    Console.warn("[BrazilianCompanySync] Já existe uma instância em execução. Retornando a existente.");
                    return INSTANCE;
                }
                INSTANCE = new CompanySyncs(this);
                return INSTANCE;
            }
        }
    }

    // ==================== API PÚBLICA ====================

    /**
     * Inicializa a sincronização com configurações padrão (todos os arquivos, 30 dias).
     * Equivalente a {@code builder().build().start()}.
     */
    public static void init() {
        builder().build().start();
    }
    
    /**
     * Inicia o agendamento. Se já existe uma sincronização pendente/atrasada,
     * ela é disparada imediatamente. Caso contrário, aguarda o prazo.
     */
    public CompanySyncs start() {
        long delaySeconds = calcNextSyncDelay();
        Console.info("[BrazilianCompanySync] Próxima sincronização em %d segundo(s).", delaySeconds);
        scheduler.scheduleWithFixedDelay(
            this::runSync,
            delaySeconds,
            TimeUnit.DAYS.toSeconds(SYNC_INTERVAL_DAYS),
            TimeUnit.SECONDS
        );
        return this;
    }

    /**
     * Para o agendador e aguarda a conclusão de qualquer sincronização em andamento.
     */
    public static void shutdown() {
        synchronized (INIT_LOCK) {
            if (INSTANCE != null) {
                INSTANCE.scheduler.shutdown();
                try {
                    INSTANCE.scheduler.awaitTermination(60, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                INSTANCE = null;
                Console.info("[BrazilianCompanySync] Encerrado.");
            }
        }
    }

    /**
     * Força uma sincronização imediata (bloqueante) independente do agendamento.
     * Útil para primeiro uso ou testes.
     */
    public static void syncNow() {
        synchronized (INIT_LOCK) {
            if (INSTANCE == null) {
                Console.warn("[BrazilianCompanySync] Chame start() ou builder().build().start() antes de syncNow().");
                return;
            }
        }
        INSTANCE.runSync();
    }

    // ==================== SINCRONIZAÇÃO ====================

    private void runSync() {
        Console.info("[BrazilianCompanySync] Iniciando sincronização dos dados abertos de CNPJ...");
        long start = System.currentTimeMillis();

        try {
            Files.createDirectories(tempDir);

            // 1. Importa arquivos de Empresas
            for (int i = 0; i < maxEmpresaFiles; i++) {
                processZip(EMPRESA_FILES[i], this::parseEmpresaLine);
            }

            // 2. Importa arquivos de Estabelecimentos (mescla com empresa existente)
            for (int i = 0; i < maxEstabFiles; i++) {
                processZip(ESTABELECIMENTO_FILES[i], this::parseEstabelecimentoLine);
            }

            // 3. Simples Nacional / MEI (opcional)
            if (importSimples) {
                processZip(SIMPLES_FILE, this::parseSimplesLine);
            }

            saveLastSyncDate();
            long elapsed = (System.currentTimeMillis() - start) / 1000;
            Console.info("[BrazilianCompanySync] Sincronização concluída em %d segundo(s).", elapsed);

        } catch (Exception e) {
            Console.error("[BrazilianCompanySync] Erro durante a sincronização.", e);
        } finally {
            cleanTempDir();
        }
    }

    // ==================== DOWNLOAD E EXTRAÇÃO ====================

    @SuppressWarnings("resource")
	private void processZip(String fileName, LineParser parser) throws IOException {
        Console.info("[BrazilianCompanySync] Processando %s...", fileName);
        URL url = URI.create(BASE_URL + fileName).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "BrazilianCompanySync/1.0 (dados abertos CNPJ)");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(300_000);

        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            Console.warn("[BrazilianCompanySync] Arquivo %s retornou HTTP %d. Pulando.", fileName, status);
            return;
        }

        AtomicInteger count = new AtomicInteger();
        try (InputStream httpIn = conn.getInputStream();
             ZipInputStream zip  = new ZipInputStream(httpIn)) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Console.info("[BrazilianCompanySync]   Lendo entrada ZIP: %s", entry.getName());
                // Lê o CSV linha a linha sem descompactar para disco
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new NonClosingInputStream(zip), RF_CHARSET));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        parser.parse(line);
                        count.incrementAndGet();
                        if (count.get() % 100_000 == 0) {
                            Console.info("[BrazilianCompanySync]   %,d registros processados...", count.get());
                        }
                    } catch (Exception ex) {
                        Console.warn("[BrazilianCompanySync] Linha inválida em %s: %s", fileName, ex.getMessage());
                    }
                }
                zip.closeEntry();
            }
        } finally {
            conn.disconnect();
        }
        Console.info("[BrazilianCompanySync] %s concluído: %,d registros.", fileName, count.get());
    }

    @FunctionalInterface
    private interface LineParser {
        void parse(String line);
    }

    // ==================== PARSERS CSV ====================

    /**
     * Interpreta uma linha do arquivo de Empresas.
     * Layout: CNPJ_BASICO;RAZAO_SOCIAL;NATUREZA_JURIDICA;QUALIF_RESPONSAVEL;
     *         CAPITAL_SOCIAL;PORTE;ENTE_FEDERATIVO_RESPONSAVEL
     */
    private void parseEmpresaLine(String line) {
        String[] f = splitCsv(line);
        if (f.length < 7) return;

        String cnpjBasico = trim(f[0]);
        if (cnpjBasico.isEmpty()) return;

        Company company = getOrCreate(cnpjBasico);
        company.cnpjBasico              = cnpjBasico;
        company.razaoSocial             = trim(f[1]);
        company.naturezaJuridica        = trim(f[2]);
        company.qualificacaoResponsavel = trim(f[3]);
        company.capitalSocial           = parseDouble(f[4]);
        company.porte                   = trim(f[5]);
        company.enteFederativoResp      = trim(f[6]);
        company.saveSync();
    }

    /**
     * Interpreta uma linha do arquivo de Estabelecimentos.
     * Layout: CNPJ_BASICO;CNPJ_ORDEM;CNPJ_DV;MATRIZ_FILIAL;NOME_FANTASIA;
     *         SITUACAO_CADASTRAL;DATA_SITUACAO;MOTIVO_SITUACAO;
     *         NOME_CIDADE_EXTERIOR;PAIS;DATA_INICIO_ATIVIDADE;
     *         CNAE_PRINCIPAL;CNAE_SECUNDARIA;TIPO_LOGRADOURO;LOGRADOURO;
     *         NUMERO;COMPLEMENTO;BAIRRO;CEP;UF;MUNICIPIO;
     *         DDD1;TELEFONE1;DDD2;TELEFONE2;DDD_FAX;FAX;
     *         EMAIL;SITUACAO_ESPECIAL;DATA_SITUACAO_ESPECIAL
     */
    private void parseEstabelecimentoLine(String line) {
        String[] f = splitCsv(line);
        if (f.length < 29) return;

        String cnpjBasico = trim(f[0]);
        if (cnpjBasico.isEmpty()) return;

        // Busca empresa já existente ou cria nova entrada mínima
        Company company = getOrCreate(cnpjBasico);
        company.cnpjBasico        = cnpjBasico;
        company.cnpjOrdem         = trim(f[1]);
        company.cnpjDv            = trim(f[2]);
        company.matrizFilial      = trim(f[3]).equals("1") ? "MATRIZ" : "FILIAL";
        company.nomeFantasia      = trim(f[4]);
        company.situacaoCadastral = parseSituacao(trim(f[5]));
        company.dataSituacao      = trim(f[6]);
        company.motivoSituacao    = trim(f[7]);
        company.dataInicioAtiv    = trim(f[10]);
        company.cnaePrincipal     = trim(f[11]);
        company.cnaeSecundarias   = trim(f[12]);
        company.tipoLogradouro    = trim(f[13]);
        company.logradouro        = trim(f[14]);
        company.numero            = trim(f[15]);
        company.complemento       = trim(f[16]);
        company.bairro            = trim(f[17]);
        company.cep               = trim(f[18]);
        company.uf                = trim(f[19]);
        company.municipio         = trim(f[20]);
        company.telefone1         = buildPhone(f[21], f[22]);
        company.telefone2         = buildPhone(f[23], f[24]);
        company.fax               = buildPhone(f[25], f[26]);
        company.email             = trim(f[27]);
        company.saveSync();
    }

    /**
     * Interpreta uma linha do arquivo Simples Nacional / MEI.
     * Layout: CNPJ_BASICO;OPCAO_SIMPLES;DATA_OPCAO_SIMPLES;DATA_EXCLUSAO_SIMPLES;
     *         OPCAO_MEI;DATA_OPCAO_MEI;DATA_EXCLUSAO_MEI
     */
    private void parseSimplesLine(String line) {
        String[] f = splitCsv(line);
        if (f.length < 7) return;

        String cnpjBasico = trim(f[0]);
        if (cnpjBasico.isEmpty()) return;

        Company company = getOrCreate(cnpjBasico);
        company.cnpjBasico       = cnpjBasico;
        company.opcaoSimples      = trim(f[1]);
        company.dataOpcaoSimples  = trim(f[2]);
        company.dataExclusaoSimp  = trim(f[3]);
        company.opcaoMei          = trim(f[4]);
        company.dataOpcaoMei      = trim(f[5]);
        company.dataExclusaoMei   = trim(f[6]);
        company.saveSync();
    }

    // ==================== UTILITÁRIOS ====================

    /** Busca no IdentityMap/banco ou cria nova instância vazia com o ID dado. */
    private static Company getOrCreate(String cnpjBasico) {
        Company existing = Saveable.findById(Company.class, cnpjBasico);
        if (existing != null) return existing;
        Company c = new Company();
        c.cnpjBasico = cnpjBasico;
        return c;
    }

    /** Divide a linha CSV respeitando aspas. */
    private static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == SEPARATOR && !inQuotes) {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(trim(s).replace(",", "."));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String buildPhone(String ddd, String numero) {
        String d = trim(ddd);
        String n = trim(numero);
        if (d.isEmpty() && n.isEmpty()) return "";
        return d.isEmpty() ? n : "(" + d + ") " + n;
    }

    private static String parseSituacao(String code) {
        return switch (code) {
            case "01" -> "NULA";
            case "2"  -> "ATIVA";
            case "3"  -> "SUSPENSA";
            case "4"  -> "INAPTA";
            case "08" -> "BAIXADA";
            default   -> code;
        };
    }

    // ==================== CONTROLE DE ESTADO ====================

    private long calcNextSyncDelay() {
        LocalDate lastSync = loadLastSyncDate();
        if (lastSync == null) {
            return 0; // nunca sincronizou: começa imediatamente
        }
        LocalDate nextSync = lastSync.plusDays(SYNC_INTERVAL_DAYS);
        LocalDate today    = LocalDate.now();
        if (!today.isBefore(nextSync)) {
            return 0; // prazo já venceu
        }
        return TimeUnit.DAYS.toSeconds(today.until(nextSync).getDays());
    }

    private LocalDate loadLastSyncDate() {
        File f = new File(STATE_FILE);
        if (!f.exists()) return null;
        try (FileReader r = new FileReader(f)) {
            Properties p = new Properties();
            p.load(r);
            String val = p.getProperty("last_sync");
            if (val == null || val.isBlank()) return null;
            return LocalDate.parse(val, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveLastSyncDate() {
        try (FileWriter w = new FileWriter(STATE_FILE)) {
            Properties p = new Properties();
            p.setProperty("last_sync", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
            p.store(w, "BrazilianCompanySync - não edite manualmente");
        } catch (IOException e) {
            Console.warn("[BrazilianCompanySync] Não foi possível salvar o estado de sincronização: %s", e.getMessage());
        }
    }

    private void cleanTempDir() {
        try {
            if (Files.exists(tempDir)) {
                Files.walk(tempDir)
                     .sorted(Comparator.reverseOrder())
                     .map(Path::toFile)
                     .forEach(File::delete);
            }
        } catch (IOException ignored) {}
    }

    // ==================== WRAPPER InputStream (não fecha ZipInputStream) ====================

    /** Wrapper que delega tudo ao stream subjacente mas ignora chamadas a close(). */
    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream in) { super(in); }
        @Override public void close() { /* não fecha o ZipInputStream pai */ }
    }

    // ==================== CLASSE COMPANY ====================

    /**
     * Representa uma empresa brasileira conforme dados abertos da Receita Federal.
     *
     * <p>O ID desta entidade é o {@code cnpjBasico} (8 dígitos), que é a chave
     * primária utilizada para juntar os arquivos de Empresas, Estabelecimentos
     * e Simples da Receita Federal.</p>
     *
     * <p>Os dados são carregados de forma incremental: primeiro os campos de
     * Empresas são preenchidos, depois os de Estabelecimentos são mesclados
     * e, por fim, os do Simples Nacional.</p>
     */
    public static class Company extends Saveable {

        // --- Campos de Empresas ---
        /** CNPJ básico (8 primeiros dígitos) — chave primária. */
        private String cnpjBasico;
        /** Razão social / nome empresarial. */
        private String razaoSocial;
        /** Código da natureza jurídica (ex.: "2062" = Sociedade Empresária Limitada). */
        private String naturezaJuridica;
        /** Qualificação do responsável pela empresa. */
        private String qualificacaoResponsavel;
        /** Capital social declarado. */
        private double capitalSocial;
        /**
         * Porte da empresa.
         * Valores: {@code 00} = Não informado, {@code 01} = Micro Empresa,
         * {@code 03} = Empresa de Pequeno Porte, {@code 05} = Demais.
         */
        private String porte;
        /** Ente federativo responsável (preenchido apenas para natureza jurídica 1XXX). */
        private String enteFederativoResp;

        // --- Campos de Estabelecimentos (matriz) ---
        /** Ordem do estabelecimento (dígitos 9-12 do CNPJ). */
        private String cnpjOrdem;
        /** Dígito verificador do CNPJ. */
        private String cnpjDv;
        /** {@code MATRIZ} ou {@code FILIAL}. */
        private String matrizFilial;
        /** Nome fantasia. */
        private String nomeFantasia;
        /** Situação cadastral: ATIVA, SUSPENSA, INAPTA, BAIXADA, NULA. */
        private String situacaoCadastral;
        /** Data do evento de situação cadastral (formato YYYYMMDD). */
        private String dataSituacao;
        /** Código do motivo da situação cadastral. */
        private String motivoSituacao;
        /** Data de início da atividade (formato YYYYMMDD). */
        private String dataInicioAtiv;
        /** Código CNAE da atividade econômica principal. */
        private String cnaePrincipal;
        /** Código(s) CNAE de atividades secundárias, separados por vírgula. */
        private String cnaeSecundarias;
        /** Tipo de logradouro (RUA, AV, etc.). */
        private String tipoLogradouro;
        private String logradouro;
        private String numero;
        private String complemento;
        private String bairro;
        private String cep;
        /** Sigla da Unidade Federativa. */
        private String uf;
        /** Código do município (tabela de domínio da Receita Federal). */
        private String municipio;
        /** Telefone 1 no formato "(DDD) NÚMERO". */
        private String telefone1;
        /** Telefone 2 no formato "(DDD) NÚMERO". */
        private String telefone2;
        private String fax;
        private String email;

        // --- Campos do Simples Nacional / MEI ---
        /** S = Optante, N = Não optante. */
        private String opcaoSimples;
        private String dataOpcaoSimples;
        private String dataExclusaoSimp;
        /** S = MEI, N = Não MEI. */
        private String opcaoMei;
        private String dataOpcaoMei;
        private String dataExclusaoMei;

        /** Construtor padrão exigido pelo Gson / Saveable. */
        public Company() {}

        // ---- Saveable ----

        @Override
        public String getId() {
            return cnpjBasico;
        }

        // ---- Getters ----

        public String getCnpjBasico()              { return cnpjBasico; }
        public String getRazaoSocial()             { return razaoSocial; }
        public String getNomeFantasia()            { return nomeFantasia; }
        public String getNaturezaJuridica()        { return naturezaJuridica; }
        public String getQualificacaoResponsavel() { return qualificacaoResponsavel; }
        public double getCapitalSocial()           { return capitalSocial; }
        public String getPorte()                   { return porte; }
        public String getEnteFederativoResp()      { return enteFederativoResp; }
        public String getCnpjOrdem()               { return cnpjOrdem; }
        public String getCnpjDv()                  { return cnpjDv; }
        public String getMatrizFilial()            { return matrizFilial; }
        public String getSituacaoCadastral()       { return situacaoCadastral; }
        public String getDataSituacao()            { return dataSituacao; }
        public String getMotivoCadastral()         { return motivoSituacao; }
        public String getDataInicioAtividade()     { return dataInicioAtiv; }
        public String getCnaePrincipal()           { return cnaePrincipal; }
        public String getCnaeSecundarias()         { return cnaeSecundarias; }
        public String getTipoLogradouro()          { return tipoLogradouro; }
        public String getLogradouro()              { return logradouro; }
        public String getNumero()                  { return numero; }
        public String getComplemento()             { return complemento; }
        public String getBairro()                  { return bairro; }
        public String getCep()                     { return cep; }
        public String getUf()                      { return uf; }
        public String getMunicipio()               { return municipio; }
        public String getTelefone1()               { return telefone1; }
        public String getTelefone2()               { return telefone2; }
        public String getFax()                     { return fax; }
        public String getEmail()                   { return email; }
        public String getOpcaoSimples()            { return opcaoSimples; }
        public String getDataOpcaoSimples()        { return dataOpcaoSimples; }
        public String getDataExclusaoSimples()     { return dataExclusaoSimp; }
        public String getOpcaoMei()                { return opcaoMei; }
        public String getDataOpcaoMei()            { return dataOpcaoMei; }
        public String getDataExclusaoMei()         { return dataExclusaoMei; }

        /** Retorna o CNPJ completo formatado (XX.XXX.XXX/XXXX-XX) se todos os campos estiverem presentes. */
        public String getCnpjFormatado() {
            if (cnpjBasico == null || cnpjOrdem == null || cnpjDv == null) return cnpjBasico;
            String raw = cnpjBasico + cnpjOrdem + cnpjDv;
            if (raw.length() != 14) return raw;
            return raw.substring(0, 2) + "." + raw.substring(2, 5) + "." +
                   raw.substring(5, 8) + "/" + raw.substring(8, 12) + "-" + raw.substring(12);
        }

        /** Retorna {@code true} se a empresa está com situação cadastral ATIVA. */
        public boolean isAtiva() {
            return "ATIVA".equalsIgnoreCase(situacaoCadastral);
        }

        /** Retorna {@code true} se a empresa é optante pelo Simples Nacional. */
        public boolean isSimples() {
            return "S".equalsIgnoreCase(opcaoSimples);
        }

        /** Retorna {@code true} se a empresa é MEI. */
        public boolean isMei() {
            return "S".equalsIgnoreCase(opcaoMei);
        }

        @Override
        public String toString() {
            return "Company{cnpj=" + getCnpjFormatado() +
                   ", razaoSocial='" + razaoSocial + '\'' +
                   ", situacao=" + situacaoCadastral +
                   ", uf=" + uf + '}';
        }
    }
}