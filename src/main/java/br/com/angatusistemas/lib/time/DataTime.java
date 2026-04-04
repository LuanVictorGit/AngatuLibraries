package br.com.angatusistemas.lib.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Date;
import java.util.TimeZone;

/**
 * [PT] Classe utilitária para operações comuns com datas, horas e fusos horários.
 * <p>
 * Utiliza a API {@code java.time} (Java 8+) para garantir imutabilidade, thread-safety
 * e maior clareza nos métodos.
 * </p>
 * 
 * [EN] Utility class for common date, time and timezone operations.
 * <p>
 * Uses the {@code java.time} API (Java 8+) to ensure immutability, thread-safety
 * and clearer method design.
 * </p>
 * 
 * @author [Sua equipe]
 * @see java.time.LocalDate
 * @see java.time.LocalDateTime
 * @see java.time.ZonedDateTime
 */
public final class DataTime {

    // Fuso horário padrão: Brasil (São Paulo)
    // Default timezone: Brazil (Sao Paulo)
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Sao_Paulo");

    // Formatadores comuns (thread-safe e reutilizáveis)
    // Common formatters (thread-safe and reusable)
    public static final DateTimeFormatter DATETIME_BR_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm");
    public static final DateTimeFormatter DATE_BR_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    public static final DateTimeFormatter TIME_BR_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static final DateTimeFormatter ISO_DATETIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    public static final DateTimeFormatter ISO_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private DataTime() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== MÉTODOS LEGADOS (COMPATIBILIDADE) ====================
    // ==================== LEGACY METHODS (COMPATIBILITY) ====================

    /**
     * [PT] Retorna a data e hora atual no formato "dd/MM/yyyy - HH:mm" com fuso de São Paulo.
     * <p>
     * Este método usa {@link java.util.Calendar} e {@link java.text.SimpleDateFormat}
     * (não thread-safe). Prefira {@link #getCurrentDateTime()} para código moderno.
     * </p>
     * 
     * [EN] Returns current date and time in "dd/MM/yyyy - HH:mm" format with Sao Paulo timezone.
     * <p>
     * This method uses {@link java.util.Calendar} and {@link java.text.SimpleDateFormat}
     * (not thread-safe). Prefer {@link #getCurrentDateTime()} for modern code.
     * </p>
     *
     * @return [PT] string formatada com data e hora atuais
     *         [EN] formatted string with current date and time
     */
    public static String getData() {
        // Mantido original para compatibilidade, mas não otimizado.
        // Legacy method kept for compatibility, but not optimized.
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy - HH:mm");
        sdf.setTimeZone(TimeZone.getTimeZone(DEFAULT_ZONE));
        return sdf.format(java.util.Calendar.getInstance().getTime());
    }

    // ==================== OBTENÇÃO DE DATA/HORA ATUAL ====================
    // ==================== GET CURRENT DATE/TIME ====================

    /**
     * [PT] Obtém a data atual no fuso horário padrão (São Paulo).
     * [EN] Gets current date in the default timezone (Sao Paulo).
     *
     * @return [PT] data atual
     *         [EN] current date
     */
    public static LocalDate getCurrentDate() {
        return LocalDate.now(DEFAULT_ZONE);
    }

    /**
     * [PT] Obtém a data e hora atual no fuso horário padrão (São Paulo).
     * [EN] Gets current date and time in the default timezone (Sao Paulo).
     *
     * @return [PT] data e hora atuais
     *         [EN] current date and time
     */
    public static LocalDateTime getCurrentDateTime() {
        return LocalDateTime.now(DEFAULT_ZONE);
    }

    /**
     * [PT] Obtém a data e hora atual com fuso horário completo (São Paulo).
     * [EN] Gets current date and time with full timezone (Sao Paulo).
     *
     * @return [PT] data, hora e fuso atuais
     *         [EN] current date, time and timezone
     */
    public static ZonedDateTime getCurrentZonedDateTime() {
        return ZonedDateTime.now(DEFAULT_ZONE);
    }

    /**
     * [PT] Obtém o timestamp Unix (segundos desde 1970-01-01T00:00:00Z).
     * [EN] Gets the Unix timestamp (seconds since 1970-01-01T00:00:00Z).
     *
     * @return [PT] timestamp atual em segundos
     *         [EN] current timestamp in seconds
     */
    public static long getCurrentTimestamp() {
        return Instant.now().getEpochSecond();
    }

    // ==================== FORMATAÇÃO ====================
    // ==================== FORMATTING ====================

    /**
     * [PT] Formata uma data no padrão brasileiro "dd/MM/yyyy".
     * [EN] Formats a date in Brazilian pattern "dd/MM/yyyy".
     *
     * @param date [PT] data a ser formatada (não nula)
     *            [EN] date to format (non-null)
     * @return [PT] string formatada
     *         [EN] formatted string
     */
    public static String formatDate(LocalDate date) {
        return date.format(DATE_BR_FORMATTER);
    }

    /**
     * [PT] Formata uma data/hora no padrão brasileiro "dd/MM/yyyy - HH:mm".
     * [EN] Formats a date/time in Brazilian pattern "dd/MM/yyyy - HH:mm".
     *
     * @param dateTime [PT] data/hora a ser formatada (não nula)
     *                 [EN] date/time to format (non-null)
     * @return [PT] string formatada
     *         [EN] formatted string
     */
    public static String formatDateTime(LocalDateTime dateTime) {
        return dateTime.format(DATETIME_BR_FORMATTER);
    }

    /**
     * [PT] Formata uma data/hora com fuso horário no padrão ISO.
     * [EN] Formats a zoned date/time in ISO pattern.
     *
     * @param zonedDateTime [PT] data/hora com fuso (não nula)
     *                      [EN] zoned date/time (non-null)
     * @return [PT] string no formato ISO (ex: 2025-04-03T10:30:00-03:00)
     *         [EN] string in ISO format (e.g., 2025-04-03T10:30:00-03:00)
     */
    public static String formatIso(ZonedDateTime zonedDateTime) {
        return zonedDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * [PT] Formata uma data/hora usando um padrão personalizado.
     * [EN] Formats a date/time using a custom pattern.
     *
     * @param dateTime [PT] data/hora (não nula)
     *                 [EN] date/time (non-null)
     * @param pattern  [PT] padrão de formatação (ex: "yyyy-MM-dd HH:mm:ss")
     *                 [EN] formatting pattern (e.g., "yyyy-MM-dd HH:mm:ss")
     * @return [PT] string formatada
     *         [EN] formatted string
     */
    public static String formatCustom(LocalDateTime dateTime, String pattern) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        return dateTime.format(formatter);
    }

    // ==================== PARSING (CONVERSÃO DE STRING PARA DATA) ====================
    // ==================== PARSING (STRING TO DATE) ====================

    /**
     * [PT] Converte uma string no formato "dd/MM/yyyy" para LocalDate.
     * [EN] Parses a string in "dd/MM/yyyy" format to LocalDate.
     *
     * @param dateStr [PT] string da data (ex: "25/12/2025")
     *                [EN] date string (e.g., "25/12/2025")
     * @return [PT] LocalDate correspondente
     *         [EN] corresponding LocalDate
     * @throws java.time.format.DateTimeParseException se o formato for inválido
     */
    public static LocalDate parseDate(String dateStr) {
        return LocalDate.parse(dateStr, DATE_BR_FORMATTER);
    }

    /**
     * [PT] Converte uma string no formato "dd/MM/yyyy - HH:mm" para LocalDateTime.
     * [EN] Parses a string in "dd/MM/yyyy - HH:mm" format to LocalDateTime.
     *
     * @param dateTimeStr [PT] string de data/hora (ex: "25/12/2025 - 14:30")
     *                    [EN] date/time string (e.g., "25/12/2025 - 14:30")
     * @return [PT] LocalDateTime correspondente
     *         [EN] corresponding LocalDateTime
     */
    public static LocalDateTime parseDateTime(String dateTimeStr) {
        return LocalDateTime.parse(dateTimeStr, DATETIME_BR_FORMATTER);
    }

    /**
     * [PT] Converte uma string para LocalDateTime usando um padrão personalizado.
     * [EN] Parses a string to LocalDateTime using a custom pattern.
     *
     * @param dateTimeStr [PT] string da data/hora
     *                    [EN] date/time string
     * @param pattern     [PT] padrão usado na string (ex: "yyyy/MM/dd HH:mm")
     *                    [EN] pattern used in the string (e.g., "yyyy/MM/dd HH:mm")
     * @return [PT] LocalDateTime correspondente
     *         [EN] corresponding LocalDateTime
     */
    public static LocalDateTime parseCustom(String dateTimeStr, String pattern) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        return LocalDateTime.parse(dateTimeStr, formatter);
    }

    // ==================== OPERAÇÕES ARITMÉTICAS COM DATAS ====================
    // ==================== DATE ARITHMETIC ====================

    /**
     * [PT] Adiciona ou subtrai dias a uma data.
     * [EN] Adds or subtracts days from a date.
     *
     * @param date [PT] data base
     *             [EN] base date
     * @param days [PT] número de dias (positivo para adicionar, negativo para subtrair)
     *             [EN] number of days (positive to add, negative to subtract)
     * @return [PT] nova data com os dias ajustados
     *         [EN] new date with days adjusted
     */
    public static LocalDate addDays(LocalDate date, long days) {
        return date.plusDays(days);
    }

    /**
     * [PT] Adiciona ou subtrai meses a uma data.
     * [EN] Adds or subtracts months from a date.
     *
     * @param date   [PT] data base
     *               [EN] base date
     * @param months [PT] número de meses (positivo para adicionar, negativo para subtrair)
     *               [EN] number of months (positive to add, negative to subtract)
     * @return [PT] nova data com os meses ajustados
     *         [EN] new date with months adjusted
     */
    public static LocalDate addMonths(LocalDate date, long months) {
        return date.plusMonths(months);
    }

    /**
     * [PT] Adiciona ou subtrai anos a uma data.
     * [EN] Adds or subtracts years from a date.
     *
     * @param date  [PT] data base
     *              [EN] base date
     * @param years [PT] número de anos (positivo para adicionar, negativo para subtrair)
     *              [EN] number of years (positive to add, negative to subtract)
     * @return [PT] nova data com os anos ajustados
     *         [EN] new date with years adjusted
     */
    public static LocalDate addYears(LocalDate date, long years) {
        return date.plusYears(years);
    }

    /**
     * [PT] Adiciona ou subtrai horas a uma data/hora.
     * [EN] Adds or subtracts hours from a date/time.
     *
     * @param dateTime [PT] data/hora base
     *                 [EN] base date/time
     * @param hours    [PT] número de horas (positivo para adicionar, negativo para subtrair)
     *                 [EN] number of hours (positive to add, negative to subtract)
     * @return [PT] nova data/hora com as horas ajustadas
     *         [EN] new date/time with hours adjusted
     */
    public static LocalDateTime addHours(LocalDateTime dateTime, long hours) {
        return dateTime.plusHours(hours);
    }

    /**
     * [PT] Adiciona ou subtrai minutos a uma data/hora.
     * [EN] Adds or subtracts minutes from a date/time.
     *
     * @param dateTime [PT] data/hora base
     *                 [EN] base date/time
     * @param minutes  [PT] número de minutos (positivo para adicionar, negativo para subtrair)
     *                 [EN] number of minutes (positive to add, negative to subtract)
     * @return [PT] nova data/hora com os minutos ajustados
     *         [EN] new date/time with minutes adjusted
     */
    public static LocalDateTime addMinutes(LocalDateTime dateTime, long minutes) {
        return dateTime.plusMinutes(minutes);
    }

    /**
     * [PT] Adiciona ou subtrai segundos a uma data/hora.
     * [EN] Adds or subtracts seconds from a date/time.
     *
     * @param dateTime [PT] data/hora base
     *                 [EN] base date/time
     * @param seconds  [PT] número de segundos (positivo para adicionar, negativo para subtrair)
     *                 [EN] number of seconds (positive to add, negative to subtract)
     * @return [PT] nova data/hora com os segundos ajustados
     *         [EN] new date/time with seconds adjusted
     */
    public static LocalDateTime addSeconds(LocalDateTime dateTime, long seconds) {
        return dateTime.plusSeconds(seconds);
    }

    // ==================== DIFERENÇA ENTRE DATAS ====================
    // ==================== DATE DIFFERENCE ====================

    /**
     * [PT] Calcula a diferença em dias entre duas datas.
     * [EN] Calculates the difference in days between two dates.
     *
     * @param start [PT] data inicial
     *              [EN] start date
     * @param end   [PT] data final
     *              [EN] end date
     * @return [PT] número de dias entre as datas (pode ser negativo)
     *         [EN] number of days between the dates (may be negative)
     */
    public static long diffDays(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end);
    }

    /**
     * [PT] Calcula a diferença em meses entre duas datas.
     * [EN] Calculates the difference in months between two dates.
     *
     * @param start [PT] data inicial
     *              [EN] start date
     * @param end   [PT] data final
     *              [EN] end date
     * @return [PT] número de meses entre as datas (pode ser negativo)
     *         [EN] number of months between the dates (may be negative)
     */
    public static long diffMonths(LocalDate start, LocalDate end) {
        return ChronoUnit.MONTHS.between(start, end);
    }

    /**
     * [PT] Calcula a diferença em anos entre duas datas.
     * [EN] Calculates the difference in years between two dates.
     *
     * @param start [PT] data inicial
     *              [EN] start date
     * @param end   [PT] data final
     *              [EN] end date
     * @return [PT] número de anos entre as datas (pode ser negativo)
     *         [EN] number of years between the dates (may be negative)
     */
    public static long diffYears(LocalDate start, LocalDate end) {
        return ChronoUnit.YEARS.between(start, end);
    }

    /**
     * [PT] Calcula a diferença em horas entre duas datas/horas.
     * [EN] Calculates the difference in hours between two date/times.
     *
     * @param start [PT] data/hora inicial
     *              [EN] start date/time
     * @param end   [PT] data/hora final
     *              [EN] end date/time
     * @return [PT] número de horas entre as datas (pode ser negativo)
     *         [EN] number of hours between the dates (may be negative)
     */
    public static long diffHours(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.HOURS.between(start, end);
    }

    /**
     * [PT] Calcula a diferença em minutos entre duas datas/horas.
     * [EN] Calculates the difference in minutes between two date/times.
     *
     * @param start [PT] data/hora inicial
     *              [EN] start date/time
     * @param end   [PT] data/hora final
     *              [EN] end date/time
     * @return [PT] número de minutos entre as datas (pode ser negativo)
     *         [EN] number of minutes between the dates (may be negative)
     */
    public static long diffMinutes(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.MINUTES.between(start, end);
    }

    /**
     * [PT] Calcula a diferença em segundos entre duas datas/horas.
     * [EN] Calculates the difference in seconds between two date/times.
     *
     * @param start [PT] data/hora inicial
     *              [EN] start date/time
     * @param end   [PT] data/hora final
     *              [EN] end date/time
     * @return [PT] número de segundos entre as datas (pode ser negativo)
     *         [EN] number of seconds between the dates (may be negative)
     */
    public static long diffSeconds(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.SECONDS.between(start, end);
    }

    // ==================== EXTRAÇÃO DE PARTES DA DATA ====================
    // ==================== DATE PART EXTRACTION ====================

    /**
     * [PT] Obtém o dia do mês (1-31) de uma data.
     * [EN] Gets the day of month (1-31) from a date.
     */
    public static int getDay(LocalDate date) {
        return date.getDayOfMonth();
    }

    /**
     * [PT] Obtém o mês (1-12) de uma data.
     * [EN] Gets the month (1-12) from a date.
     */
    public static int getMonth(LocalDate date) {
        return date.getMonthValue();
    }

    /**
     * [PT] Obtém o ano de uma data.
     * [EN] Gets the year from a date.
     */
    public static int getYear(LocalDate date) {
        return date.getYear();
    }

    /**
     * [PT] Obtém a hora (0-23) de uma data/hora.
     * [EN] Gets the hour (0-23) from a date/time.
     */
    public static int getHour(LocalDateTime dateTime) {
        return dateTime.getHour();
    }

    /**
     * [PT] Obtém o minuto (0-59) de uma data/hora.
     * [EN] Gets the minute (0-59) from a date/time.
     */
    public static int getMinute(LocalDateTime dateTime) {
        return dateTime.getMinute();
    }

    /**
     * [PT] Obtém o segundo (0-59) de uma data/hora.
     * [EN] Gets the second (0-59) from a date/time.
     */
    public static int getSecond(LocalDateTime dateTime) {
        return dateTime.getSecond();
    }

    /**
     * [PT] Obtém o dia da semana (segunda=1 a domingo=7, conforme ISO).
     * [EN] Gets the day of week (Monday=1 to Sunday=7, ISO standard).
     */
    public static int getDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue();
    }

    /**
     * [PT] Verifica se o ano é bissexto.
     * [EN] Checks if the year is leap.
     */
    public static boolean isLeapYear(LocalDate date) {
        return date.isLeapYear();
    }

    // ==================== AJUSTES DE DATA (INÍCIO/FIM) ====================
    // ==================== DATE ADJUSTMENTS (START/END) ====================

    /**
     * [PT] Retorna o início do dia (00:00:00) de uma data.
     * [EN] Returns the start of the day (00:00:00) of a date.
     */
    public static LocalDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay();
    }

    /**
     * [PT] Retorna o fim do dia (23:59:59.999999999) de uma data.
     * [EN] Returns the end of the day (23:59:59.999999999) of a date.
     */
    public static LocalDateTime endOfDay(LocalDate date) {
        return date.atTime(LocalTime.MAX);
    }

    /**
     * [PT] Retorna o primeiro dia do mês da data fornecida.
     * [EN] Returns the first day of the month of the given date.
     */
    public static LocalDate firstDayOfMonth(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    /**
     * [PT] Retorna o último dia do mês da data fornecida.
     * [EN] Returns the last day of the month of the given date.
     */
    public static LocalDate lastDayOfMonth(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfMonth());
    }

    /**
     * [PT] Retorna o primeiro dia do ano da data fornecida.
     * [EN] Returns the first day of the year of the given date.
     */
    public static LocalDate firstDayOfYear(LocalDate date) {
        return date.withDayOfYear(1);
    }

    /**
     * [PT] Retorna o último dia do ano da data fornecida.
     * [EN] Returns the last day of the year of the given date.
     */
    public static LocalDate lastDayOfYear(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfYear());
    }

    // ==================== COMPARAÇÕES E VALIDAÇÕES ====================
    // ==================== COMPARISONS AND VALIDATIONS ====================

    /**
     * [PT] Verifica se uma data é anterior a outra.
     * [EN] Checks if a date is before another.
     */
    public static boolean isBefore(LocalDate date1, LocalDate date2) {
        return date1.isBefore(date2);
    }

    /**
     * [PT] Verifica se uma data é posterior a outra.
     * [EN] Checks if a date is after another.
     */
    public static boolean isAfter(LocalDate date1, LocalDate date2) {
        return date1.isAfter(date2);
    }

    /**
     * [PT] Verifica se uma data está dentro de um intervalo (inclusivo).
     * [EN] Checks if a date is within an interval (inclusive).
     *
     * @param date     [PT] data a testar
     *                 [EN] date to test
     * @param start    [PT] início do intervalo
     *                 [EN] start of interval
     * @param end      [PT] fim do intervalo
     *                 [EN] end of interval
     * @return [PT] true se start <= date <= end
     *         [EN] true if start <= date <= end
     */
    public static boolean isBetween(LocalDate date, LocalDate start, LocalDate end) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    /**
     * [PT] Calcula a idade com base na data de nascimento.
     * [EN] Calculates age based on birth date.
     *
     * @param birthDate [PT] data de nascimento
     *                  [EN] birth date
     * @return [PT] idade em anos
     *         [EN] age in years
     */
    public static int calculateAge(LocalDate birthDate) {
        LocalDate today = getCurrentDate();
        return Period.between(birthDate, today).getYears();
    }

    // ==================== CONVERSÕES ENTRE TIPOS ====================
    // ==================== TYPE CONVERSIONS ====================

    /**
     * [PT] Converte {@link java.util.Date} legado para {@link LocalDateTime} no fuso padrão.
     * [EN] Converts legacy {@link java.util.Date} to {@link LocalDateTime} in default timezone.
     */
    public static LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(DEFAULT_ZONE).toLocalDateTime();
    }

    /**
     * [PT] Converte {@link java.util.Date} legado para {@link LocalDate}.
     * [EN] Converts legacy {@link java.util.Date} to {@link LocalDate}.
     */
    public static LocalDate toLocalDate(Date date) {
        return toLocalDateTime(date).toLocalDate();
    }

    /**
     * [PT] Converte {@link LocalDateTime} para {@link java.util.Date}.
     * [EN] Converts {@link LocalDateTime} to {@link java.util.Date}.
     */
    public static Date toDate(LocalDateTime localDateTime) {
        return Date.from(localDateTime.atZone(DEFAULT_ZONE).toInstant());
    }

    /**
     * [PT] Converte {@link LocalDate} para {@link java.util.Date}.
     * [EN] Converts {@link LocalDate} to {@link java.util.Date}.
     */
    public static Date toDate(LocalDate localDate) {
        return toDate(localDate.atStartOfDay());
    }

    // ==================== TRABALHANDO COM TIMESTAMP ====================
    // ==================== TIMESTAMP HANDLING ====================

    /**
     * [PT] Converte um timestamp (segundos desde epoch) para LocalDateTime no fuso padrão.
     * [EN] Converts a timestamp (seconds since epoch) to LocalDateTime in default timezone.
     */
    public static LocalDateTime fromTimestamp(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), DEFAULT_ZONE);
    }

    /**
     * [PT] Converte um LocalDateTime para timestamp em segundos.
     * [EN] Converts a LocalDateTime to timestamp in seconds.
     */
    public static long toTimestamp(LocalDateTime dateTime) {
        return dateTime.atZone(DEFAULT_ZONE).toEpochSecond();
    }

    // ==================== VALIDAÇÃO DE STRINGS DE DATA ====================
    // ==================== DATE STRING VALIDATION ====================

    /**
     * [PT] Verifica se uma string está no formato "dd/MM/yyyy" e representa uma data válida.
     * [EN] Checks if a string is in "dd/MM/yyyy" format and represents a valid date.
     */
    public static boolean isValidDate(String dateStr) {
        try {
            parseDate(dateStr);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * [PT] Verifica se uma string está no formato "dd/MM/yyyy - HH:mm" e representa uma data/hora válida.
     * [EN] Checks if a string is in "dd/MM/yyyy - HH:mm" format and represents a valid date/time.
     */
    public static boolean isValidDateTime(String dateTimeStr) {
        try {
            parseDateTime(dateTimeStr);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}