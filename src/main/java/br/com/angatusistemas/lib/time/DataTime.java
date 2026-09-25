package br.com.angatusistemas.lib.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classe utilitária para operações com datas, horas e fusos horários.
 *
 * <p><strong>Propósito:</strong> operações de data/hora com a API
 * {@code java.time} (imutável e thread-safe), fuso padrão
 * {@code America/Sao_Paulo}, formatos brasileiros (dd/MM/yyyy) e nomes de mês e de dia da
 * semana em português do Brasil.</p>
 *
 * <p><strong>Quando usar:</strong> formatação, parsing, aritmética, diferenças,
 * extrações e conversões de data/hora em qualquer ponto da aplicação; também é
 * a base do timestamp usado pelo {@code Console}.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> para dados temporais que exigem fuso do
 * usuário (a classe fixa São Paulo) ou precisão de nanossegundos com
 * timezone-aware (use {@link java.time.ZonedDateTime} diretamente); para
 * serialização JSON, os TypeAdapters de {@code GsonAPI} já tratam datas.</p>
 *
 * <p><strong>Integração:</strong> usada pelo {@code Console} (timestamps dos
 * logs), {@code Saveable} (campos {@code long} epoch) e demais módulos; não
 * depende de bibliotecas externas.</p>
 *
 * <p><strong>Fluxo de utilização:</strong> métodos estáticos diretos. Formato
 * brasileiro ({@code formatDate}, {@code parseDate}), ISO
 * ({@code formatIso}), custom ({@code formatCustom}, {@code parseCustom}) e
 * timestamp epoch ({@code getCurrentTimestamp}, {@code fromTimestamp},
 * {@code toTimestamp}).</p>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * LocalDate hoje = DataTime.getCurrentDate();
 * String br = DataTime.formatDate(hoje);                 // "03/08/2026"
 * LocalDate parsed = DataTime.parseDate("25/12/2026");
 * boolean valida = DataTime.isValidDate("31/02/2026");   // false: fevereiro não tem 31
 * int idade = DataTime.calculateAge(LocalDate.of(1990, 5, 10));
 * </pre>
 *
 * <p><strong>Boas práticas:</strong> prefira os métodos desta classe ao invés
 * de {@code SimpleDateFormat} (não thread-safe); use
 * {@code isValidDate/isValidDateTime} para validar entrada do usuário — eles rejeitam datas
 * que não existem, como 31/02 ou 29/02 em ano não bissexto.</p>
 *
 * <p><strong>Limitações:</strong> fuso fixo em São Paulo e idioma fixo em português do Brasil
 * (independentes do fuso e do idioma da JVM — num contêiner em {@code en_US}, os nomes antes
 * saíam em inglês); métodos {@code custom} aceitam qualquer padrão — padrões inválidos lançam
 * {@link IllegalArgumentException} e textos fora do padrão lançam
 * {@link java.time.format.DateTimeParseException}.</p>
 *
 * <p><strong>Nome da classe e de {@link #getData()}:</strong> mantidos por compatibilidade;
 * renomeá-los quebraria todo projeto que já os usa.</p>
 *
 * <p><strong>Extensões futuras:</strong> suporte a fuso configurável e
 * duração humana (ex: "há 3 dias") são evoluções naturais sem quebrar a API.</p>
 *
 * @author Angatu Sistemas
 * @see java.time.LocalDate
 * @see java.time.LocalDateTime
 * @see java.time.ZonedDateTime
 */
public final class DataTime {

    /** Idioma dos nomes de mês e de dia da semana: português do Brasil, qualquer que seja o da JVM. */
    private static final Locale PT_BR = Locale.of("pt", "BR");

    /** Fuso horário padrão: Brasil (São Paulo). */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Sao_Paulo");

    /**
     * Formatador de saída de data e hora no padrão brasileiro {@code dd/MM/yyyy - HH:mm}.
     *
     * <p>Serve para <em>escrever</em>. Para ler texto digitado, use {@link #parseDateTime(String)}
     * ou {@link #isValidDateTime(String)}: este formatador resolve no modo SMART do JDK, que
     * aceita 31/02 e o transforma em 28/02.</p>
     */
    public static final DateTimeFormatter DATETIME_BR_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm", PT_BR);

    /**
     * Formatador de saída de data no padrão brasileiro {@code dd/MM/yyyy}.
     *
     * <p>Serve para <em>escrever</em>. Para ler texto digitado, use {@link #parseDate(String)} ou
     * {@link #isValidDate(String)}: este formatador resolve no modo SMART do JDK, que aceita
     * 31/02 e o transforma em 28/02.</p>
     */
    public static final DateTimeFormatter DATE_BR_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy", PT_BR);

    /** Formatador de hora no padrão {@code HH:mm:ss}. */
    public static final DateTimeFormatter TIME_BR_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss", PT_BR);

    /** Formatador ISO-8601 de data e hora local ({@code 2025-04-03T10:30:00}), com validação estrita. */
    public static final DateTimeFormatter ISO_DATETIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** Formatador ISO-8601 de data local ({@code 2025-04-03}), com validação estrita. */
    public static final DateTimeFormatter ISO_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Leitor estrito de {@code dd/MM/uuuu}: rejeita datas que não existem. Tem de ser
     * {@code uuuu} (ano proléptico): com {@code yyyy} (ano da era), o modo STRICT exige a era no
     * texto e rejeita toda data.
     */
    private static final DateTimeFormatter DATE_BR_PARSER =
            DateTimeFormatter.ofPattern("dd/MM/uuuu", PT_BR).withResolverStyle(ResolverStyle.STRICT);

    /** Leitor estrito de {@code dd/MM/uuuu - HH:mm}, pelo mesmo motivo de {@link #DATE_BR_PARSER}. */
    private static final DateTimeFormatter DATETIME_BR_PARSER =
            DateTimeFormatter.ofPattern("dd/MM/uuuu - HH:mm", PT_BR).withResolverStyle(ResolverStyle.STRICT);

    /**
     * Quantos padrões personalizados ficam guardados, no máximo. Um projeto usa poucos; o teto
     * existe para que padrões montados em tempo de execução não façam o mapa crescer sem fim.
     */
    private static final int MAX_CACHED_PATTERNS = 256;

    /** Formatadores já compilados para padrões personalizados (thread-safe, limitado). */
    private static final Map<String, DateTimeFormatter> CUSTOM_FORMATTER_CACHE = new ConcurrentHashMap<>();

    private DataTime() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== MÉTODOS LEGADOS (COMPATIBILIDADE) ====================

    /**
     * Retorna a data e hora atual no formato {@code "dd/MM/yyyy - HH:mm"} com fuso de São Paulo.
     *
     * <p>Método legado mantido para compatibilidade. O formatador é reutilizado (thread-safe);
     * cada chamada ainda cria a data atual e a String do resultado. Prefira
     * {@link #getCurrentDateTime()} para código novo.</p>
     *
     * @return String formatada com data e hora atuais
     */
    public static String getData() {
        return LocalDateTime.now(DEFAULT_ZONE).format(DATETIME_BR_FORMATTER);
    }

    // ==================== OBTENÇÃO DE DATA/HORA ATUAL ====================

    /**
     * Obtém a data atual no fuso horário padrão (São Paulo).
     *
     * @return Data atual
     */
    public static LocalDate getCurrentDate() {
        return LocalDate.now(DEFAULT_ZONE);
    }

    /**
     * Obtém a data e hora atual no fuso horário padrão (São Paulo).
     *
     * @return Data e hora atuais
     */
    public static LocalDateTime getCurrentDateTime() {
        return LocalDateTime.now(DEFAULT_ZONE);
    }

    /**
     * Obtém a data e hora atual com fuso horário completo (São Paulo).
     *
     * @return Data, hora e fuso atuais
     */
    public static ZonedDateTime getCurrentZonedDateTime() {
        return ZonedDateTime.now(DEFAULT_ZONE);
    }

    /**
     * Obtém o timestamp Unix (segundos desde 1970-01-01T00:00:00Z).
     *
     * @return Timestamp atual em segundos
     */
    public static long getCurrentTimestamp() {
        return Instant.now().getEpochSecond();
    }

    // ==================== FORMATAÇÃO ====================

    /**
     * Formata uma data no padrão brasileiro {@code "dd/MM/yyyy"}.
     *
     * @param date Data a ser formatada (não nula)
     * @return String formatada
     */
    public static String formatDate(LocalDate date) {
        return date.format(DATE_BR_FORMATTER);
    }

    /**
     * Formata uma data/hora no padrão brasileiro {@code "dd/MM/yyyy - HH:mm"}.
     *
     * @param dateTime Data/hora a ser formatada (não nula)
     * @return String formatada
     */
    public static String formatDateTime(LocalDateTime dateTime) {
        return dateTime.format(DATETIME_BR_FORMATTER);
    }

    /**
     * Formata uma data/hora com fuso horário no padrão ISO-8601.
     *
     * @param zonedDateTime Data/hora com fuso (não nula)
     * @return String no formato ISO (ex: {@code 2025-04-03T10:30:00-03:00})
     */
    public static String formatIso(ZonedDateTime zonedDateTime) {
        return zonedDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * Formata uma data/hora usando um padrão personalizado, com nomes de mês e de dia da semana
     * em português do Brasil ({@code "EEEE, dd 'de' MMMM"} → {@code "quarta-feira, 23 de setembro"}).
     *
     * @param dateTime Data/hora (não nula)
     * @param pattern  Padrão de formatação (ex: {@code "yyyy-MM-dd HH:mm:ss"})
     * @return String formatada
     * @throws IllegalArgumentException se o padrão for inválido
     */
    public static String formatCustom(LocalDateTime dateTime, String pattern) {
        return dateTime.format(formatterFor(pattern));
    }

    // ==================== PARSING (CONVERSÃO DE STRING PARA DATA) ====================

    /**
     * Converte uma string no formato {@code "dd/MM/yyyy"} para {@link LocalDate}.
     *
     * <p>Estrito: uma data que não existe (31/02, 29/02 em ano não bissexto, 31/04) é rejeitada.
     * Antes, "31/02/2025" virava 28/02/2025 em silêncio.</p>
     *
     * @param dateStr String da data (ex: {@code "25/12/2025"}; não nula)
     * @return {@link LocalDate} correspondente
     * @throws DateTimeParseException se o formato for inválido ou a data não existir
     */
    public static LocalDate parseDate(String dateStr) {
        return LocalDate.parse(dateStr, DATE_BR_PARSER);
    }

    /**
     * Converte uma string no formato {@code "dd/MM/yyyy - HH:mm"} para {@link LocalDateTime}.
     *
     * <p>Estrito como {@link #parseDate(String)}: data ou hora que não existe é rejeitada.</p>
     *
     * @param dateTimeStr String de data/hora (ex: {@code "25/12/2025 - 14:30"}; não nula)
     * @return {@link LocalDateTime} correspondente
     * @throws DateTimeParseException se o formato for inválido ou a data/hora não existir
     */
    public static LocalDateTime parseDateTime(String dateTimeStr) {
        return LocalDateTime.parse(dateTimeStr, DATETIME_BR_PARSER);
    }

    /**
     * Converte uma string para {@link LocalDateTime} usando um padrão personalizado.
     *
     * <p>Resolve no modo SMART do JDK, e não no estrito: um dia além do fim do mês (31/02) é
     * ajustado para o último dia válido. O estrito exigiria {@code uuuu} no lugar de
     * {@code yyyy} e rejeitaria todo padrão já escrito com {@code yyyy}. Para validar entrada do
     * usuário no formato brasileiro, use {@link #parseDateTime(String)} ou
     * {@link #isValidDateTime(String)}.</p>
     *
     * @param dateTimeStr String da data/hora
     * @param pattern     Padrão usado na string (ex: {@code "yyyy/MM/dd HH:mm"})
     * @return {@link LocalDateTime} correspondente
     * @throws DateTimeParseException se o texto não seguir o padrão
     * @throws IllegalArgumentException se o padrão for inválido
     */
    public static LocalDateTime parseCustom(String dateTimeStr, String pattern) {
        return LocalDateTime.parse(dateTimeStr, formatterFor(pattern));
    }

    // ==================== OPERAÇÕES ARITMÉTICAS COM DATAS ====================

    /**
     * Adiciona ou subtrai dias a uma data.
     *
     * @param date Data base
     * @param days Número de dias (positivo para adicionar, negativo para subtrair)
     * @return Nova data com os dias ajustados
     */
    public static LocalDate addDays(LocalDate date, long days) {
        return date.plusDays(days);
    }

    /**
     * Adiciona ou subtrai meses a uma data.
     *
     * @param date   Data base
     * @param months Número de meses (positivo para adicionar, negativo para subtrair)
     * @return Nova data com os meses ajustados
     */
    public static LocalDate addMonths(LocalDate date, long months) {
        return date.plusMonths(months);
    }

    /**
     * Adiciona ou subtrai anos a uma data.
     *
     * @param date  Data base
     * @param years Número de anos (positivo para adicionar, negativo para subtrair)
     * @return Nova data com os anos ajustados
     */
    public static LocalDate addYears(LocalDate date, long years) {
        return date.plusYears(years);
    }

    /**
     * Adiciona ou subtrai horas a uma data/hora.
     *
     * @param dateTime Data/hora base
     * @param hours    Número de horas (positivo para adicionar, negativo para subtrair)
     * @return Nova data/hora com as horas ajustadas
     */
    public static LocalDateTime addHours(LocalDateTime dateTime, long hours) {
        return dateTime.plusHours(hours);
    }

    /**
     * Adiciona ou subtrai minutos a uma data/hora.
     *
     * @param dateTime Data/hora base
     * @param minutes  Número de minutos (positivo para adicionar, negativo para subtrair)
     * @return Nova data/hora com os minutos ajustados
     */
    public static LocalDateTime addMinutes(LocalDateTime dateTime, long minutes) {
        return dateTime.plusMinutes(minutes);
    }

    /**
     * Adiciona ou subtrai segundos a uma data/hora.
     *
     * @param dateTime Data/hora base
     * @param seconds  Número de segundos (positivo para adicionar, negativo para subtrair)
     * @return Nova data/hora com os segundos ajustados
     */
    public static LocalDateTime addSeconds(LocalDateTime dateTime, long seconds) {
        return dateTime.plusSeconds(seconds);
    }

    // ==================== DIFERENÇA ENTRE DATAS ====================

    /**
     * Calcula a diferença em dias entre duas datas.
     *
     * @param start Data inicial
     * @param end   Data final
     * @return Número de dias entre as datas (pode ser negativo)
     */
    public static long diffDays(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end);
    }

    /**
     * Calcula a diferença em meses completos entre duas datas.
     *
     * @param start Data inicial
     * @param end   Data final
     * @return Número de meses entre as datas (pode ser negativo)
     */
    public static long diffMonths(LocalDate start, LocalDate end) {
        return ChronoUnit.MONTHS.between(start, end);
    }

    /**
     * Calcula a diferença em anos completos entre duas datas.
     *
     * @param start Data inicial
     * @param end   Data final
     * @return Número de anos entre as datas (pode ser negativo)
     */
    public static long diffYears(LocalDate start, LocalDate end) {
        return ChronoUnit.YEARS.between(start, end);
    }

    /**
     * Calcula a diferença em horas completas entre duas datas/horas.
     *
     * @param start Data/hora inicial
     * @param end   Data/hora final
     * @return Número de horas entre as datas (pode ser negativo)
     */
    public static long diffHours(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.HOURS.between(start, end);
    }

    /**
     * Calcula a diferença em minutos completos entre duas datas/horas.
     *
     * @param start Data/hora inicial
     * @param end   Data/hora final
     * @return Número de minutos entre as datas (pode ser negativo)
     */
    public static long diffMinutes(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.MINUTES.between(start, end);
    }

    /**
     * Calcula a diferença em segundos completos entre duas datas/horas.
     *
     * @param start Data/hora inicial
     * @param end   Data/hora final
     * @return Número de segundos entre as datas (pode ser negativo)
     */
    public static long diffSeconds(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.SECONDS.between(start, end);
    }

    // ==================== EXTRAÇÃO DE PARTES DA DATA ====================

    /**
     * Obtém o dia do mês de uma data.
     *
     * @param date Data (não nula)
     * @return Dia do mês, de 1 a 31
     */
    public static int getDay(LocalDate date) {
        return date.getDayOfMonth();
    }

    /**
     * Obtém o mês de uma data.
     *
     * @param date Data (não nula)
     * @return Mês, de 1 (janeiro) a 12 (dezembro)
     */
    public static int getMonth(LocalDate date) {
        return date.getMonthValue();
    }

    /**
     * Obtém o ano de uma data.
     *
     * @param date Data (não nula)
     * @return Ano (ex: 2026)
     */
    public static int getYear(LocalDate date) {
        return date.getYear();
    }

    /**
     * Obtém a hora de uma data/hora.
     *
     * @param dateTime Data/hora (não nula)
     * @return Hora, de 0 a 23
     */
    public static int getHour(LocalDateTime dateTime) {
        return dateTime.getHour();
    }

    /**
     * Obtém o minuto de uma data/hora.
     *
     * @param dateTime Data/hora (não nula)
     * @return Minuto, de 0 a 59
     */
    public static int getMinute(LocalDateTime dateTime) {
        return dateTime.getMinute();
    }

    /**
     * Obtém o segundo de uma data/hora.
     *
     * @param dateTime Data/hora (não nula)
     * @return Segundo, de 0 a 59
     */
    public static int getSecond(LocalDateTime dateTime) {
        return dateTime.getSecond();
    }

    /**
     * Obtém o dia da semana conforme a ISO-8601.
     *
     * @param date Data (não nula)
     * @return Dia da semana, de 1 (segunda-feira) a 7 (domingo)
     */
    public static int getDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue();
    }

    /**
     * Verifica se o ano da data é bissexto.
     *
     * @param date Data (não nula)
     * @return {@code true} se o ano for bissexto
     */
    public static boolean isLeapYear(LocalDate date) {
        return date.isLeapYear();
    }

    // ==================== AJUSTES DE DATA (INÍCIO/FIM) ====================

    /**
     * Retorna o início do dia (00:00:00) de uma data.
     *
     * @param date Data (não nula)
     * @return Data/hora à meia-noite do dia
     */
    public static LocalDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay();
    }

    /**
     * Retorna o fim do dia (23:59:59.999999999) de uma data.
     *
     * @param date Data (não nula)
     * @return Data/hora no último instante do dia
     */
    public static LocalDateTime endOfDay(LocalDate date) {
        return date.atTime(LocalTime.MAX);
    }

    /**
     * Retorna o primeiro dia do mês da data fornecida.
     *
     * @param date Data (não nula)
     * @return Dia 1 do mesmo mês
     */
    public static LocalDate firstDayOfMonth(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    /**
     * Retorna o último dia do mês da data fornecida.
     *
     * @param date Data (não nula)
     * @return Último dia do mesmo mês (28, 29, 30 ou 31)
     */
    public static LocalDate lastDayOfMonth(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfMonth());
    }

    /**
     * Retorna o primeiro dia do ano da data fornecida.
     *
     * @param date Data (não nula)
     * @return 1º de janeiro do mesmo ano
     */
    public static LocalDate firstDayOfYear(LocalDate date) {
        return date.withDayOfYear(1);
    }

    /**
     * Retorna o último dia do ano da data fornecida.
     *
     * @param date Data (não nula)
     * @return 31 de dezembro do mesmo ano
     */
    public static LocalDate lastDayOfYear(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfYear());
    }

    // ==================== COMPARAÇÕES E VALIDAÇÕES ====================

    /**
     * Verifica se uma data é anterior a outra.
     *
     * @param date1 Data a testar
     * @param date2 Data de referência
     * @return {@code true} se {@code date1} for anterior a {@code date2}
     */
    public static boolean isBefore(LocalDate date1, LocalDate date2) {
        return date1.isBefore(date2);
    }

    /**
     * Verifica se uma data é posterior a outra.
     *
     * @param date1 Data a testar
     * @param date2 Data de referência
     * @return {@code true} se {@code date1} for posterior a {@code date2}
     */
    public static boolean isAfter(LocalDate date1, LocalDate date2) {
        return date1.isAfter(date2);
    }

    /**
     * Verifica se uma data está dentro de um intervalo (inclusivo).
     *
     * @param date  Data a testar
     * @param start Início do intervalo
     * @param end   Fim do intervalo
     * @return {@code true} se {@code start <= date <= end}
     */
    public static boolean isBetween(LocalDate date, LocalDate start, LocalDate end) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    /**
     * Calcula a idade com base na data de nascimento, na data de hoje em São Paulo.
     *
     * @param birthDate Data de nascimento
     * @return Idade em anos completos
     */
    public static int calculateAge(LocalDate birthDate) {
        LocalDate today = getCurrentDate();
        return Period.between(birthDate, today).getYears();
    }

    // ==================== CONVERSÕES ENTRE TIPOS ====================

    /**
     * Converte {@link java.util.Date} legado para {@link LocalDateTime} no fuso padrão.
     *
     * @param date Data legada (não nula)
     * @return Data/hora local em São Paulo
     */
    public static LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(DEFAULT_ZONE).toLocalDateTime();
    }

    /**
     * Converte {@link java.util.Date} legado para {@link LocalDate} no fuso padrão.
     *
     * @param date Data legada (não nula)
     * @return Data local em São Paulo
     */
    public static LocalDate toLocalDate(Date date) {
        return toLocalDateTime(date).toLocalDate();
    }

    /**
     * Converte {@link LocalDateTime} (interpretado em São Paulo) para {@link java.util.Date}.
     *
     * @param localDateTime Data/hora local (não nula)
     * @return Data legada correspondente
     */
    public static Date toDate(LocalDateTime localDateTime) {
        return Date.from(localDateTime.atZone(DEFAULT_ZONE).toInstant());
    }

    /**
     * Converte {@link LocalDate} (meia-noite em São Paulo) para {@link java.util.Date}.
     *
     * @param localDate Data local (não nula)
     * @return Data legada correspondente
     */
    public static Date toDate(LocalDate localDate) {
        return toDate(localDate.atStartOfDay());
    }

    // ==================== TRABALHANDO COM TIMESTAMP ====================

    /**
     * Converte um timestamp (segundos desde epoch) para {@link LocalDateTime} no fuso padrão.
     *
     * @param timestamp Segundos desde 1970-01-01T00:00:00Z
     * @return Data/hora local em São Paulo
     */
    public static LocalDateTime fromTimestamp(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), DEFAULT_ZONE);
    }

    /**
     * Converte um {@link LocalDateTime} (interpretado em São Paulo) para timestamp em segundos.
     *
     * @param dateTime Data/hora local (não nula)
     * @return Segundos desde 1970-01-01T00:00:00Z
     */
    public static long toTimestamp(LocalDateTime dateTime) {
        return dateTime.atZone(DEFAULT_ZONE).toEpochSecond();
    }

    // ==================== VALIDAÇÃO DE STRINGS DE DATA ====================

    /**
     * Verifica se uma string está no formato {@code "dd/MM/yyyy"} e representa uma data que
     * existe no calendário.
     *
     * @param dateStr String a validar (pode ser nula)
     * @return {@code true} se válida; {@code false} se nula, fora do formato ou inexistente
     *         (ex.: {@code "31/02/2025"})
     */
    public static boolean isValidDate(String dateStr) {
        if (dateStr == null) {
            return false;
        }
        try {
            parseDate(dateStr);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Verifica se uma string está no formato {@code "dd/MM/yyyy - HH:mm"} e representa uma
     * data/hora que existe no calendário.
     *
     * @param dateTimeStr String a validar (pode ser nula)
     * @return {@code true} se válida; {@code false} se nula, fora do formato ou inexistente
     *         (ex.: {@code "30/02/2024 - 10:00"})
     */
    public static boolean isValidDateTime(String dateTimeStr) {
        if (dateTimeStr == null) {
            return false;
        }
        try {
            parseDateTime(dateTimeStr);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Obtém (ou cria e guarda) um {@link DateTimeFormatter} em português do Brasil para o padrão
     * informado. {@code DateTimeFormatter} é imutável e thread-safe; guardar evita recompilar o
     * padrão a cada chamada. Passado o teto de {@link #MAX_CACHED_PATTERNS}, padrões novos são
     * compilados a cada uso, sem entrar no mapa.
     *
     * @param pattern Padrão de formatação (ex: {@code "yyyy/MM/dd HH:mm"})
     * @return Formatador reutilizável para o padrão
     * @throws IllegalArgumentException se o padrão for inválido
     */
    private static DateTimeFormatter formatterFor(String pattern) {
        DateTimeFormatter cached = CUSTOM_FORMATTER_CACHE.get(pattern);
        if (cached != null) {
            return cached;
        }
        // Locale fixo: sem ele, os nomes seguiam o idioma da JVM e o contêiner (en_US)
        // imprimia "Wednesday, 23 de September".
        DateTimeFormatter created = DateTimeFormatter.ofPattern(pattern, PT_BR);
        if (CUSTOM_FORMATTER_CACHE.size() < MAX_CACHED_PATTERNS) {
            DateTimeFormatter previous = CUSTOM_FORMATTER_CACHE.putIfAbsent(pattern, created);
            return previous != null ? previous : created;
        }
        return created;
    }
}
