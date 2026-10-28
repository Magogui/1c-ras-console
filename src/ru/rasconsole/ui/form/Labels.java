package ru.rasconsole.ui.form;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com._1c.v8.ibis.admin.IAssignmentRuleInfo;
import com._1c.v8.ibis.admin.IClusterInfo;
import com._1c.v8.ibis.admin.IClusterManagerInfo;
import com._1c.v8.ibis.admin.IInfoBaseInfo;
import com._1c.v8.ibis.admin.IRegUserInfo;
import com._1c.v8.ibis.admin.IResourceConsumptionCounter;
import com._1c.v8.ibis.admin.IResourceConsumptionLimit;
import com._1c.v8.ibis.admin.ISecurityProfile;
import com._1c.v8.ibis.admin.ISessionInfo;
import com._1c.v8.ibis.admin.IWorkingProcessInfo;
import com._1c.v8.ibis.admin.IWorkingServerInfo;

/** Русские подписи свойств, порядок полей в формах и значения перечислений. */
public final class Labels {

    private static final Map<String, String> LABELS = new HashMap<>();
    private static final Map<Class<?>, List<String>> ORDER = new HashMap<>();
    private static final Map<String, Map<Integer, String>> CHOICES = new HashMap<>();
    private static final Map<String, List<String>> STRING_CHOICES = new HashMap<>();
    /** Разделы формы: заголовок → свойства (в порядке показа). */
    private static final Map<Class<?>, LinkedHashMap<String, List<String>>> GROUPS = new HashMap<>();
    /** Пояснения под заголовками разделов. */
    private static final Map<String, String> GROUP_HINTS = new HashMap<>();

    static {
        // общие
        l("name", "Имя");
        l("descr", "Описание");
        l("description", "Описание");
        l("hostName", "Компьютер");
        l("host", "Компьютер");
        l("mainPort", "IP-порт");
        l("pid", "PID");
        l("password", "Пароль");
        l("securityLevel", "Уровень безопасности");

        // кластер
        l("clusterId", "ID кластера");
        l("expirationTimeout", "Принудительно завершать проблемные процессы через, с");
        l("lifeTimeLimit", "Интервал перезапуска, с");
        l("maxMemorySize", "Допустимый объём памяти, КБ");
        l("maxMemoryTimeLimit", "Интервал превышения допустимого объёма памяти, с");
        l("sessionFaultToleranceLevel", "Уровень отказоустойчивости");
        l("loadBalancingMode", "Режим распределения нагрузки");
        l("clusterRecyclingErrorsCountThreshold", "Допустимое отклонение количества ошибок сервера, %");
        l("clusterRecyclingKillProblemProcesses", "Принудительно завершать проблемные процессы");
        l("clusterRecyclingKillByMemoryWithDump", "Выключать процессы по превышению памяти с дампом");
        order(IClusterInfo.class, "name", "hostName", "mainPort", "securityLevel", "lifeTimeLimit",
            "maxMemorySize", "maxMemoryTimeLimit", "clusterRecyclingErrorsCountThreshold",
            "clusterRecyclingKillProblemProcesses", "clusterRecyclingKillByMemoryWithDump",
            "expirationTimeout", "sessionFaultToleranceLevel", "loadBalancingMode", "clusterId");

        // информационная база
        l("infoBaseId", "ID информационной базы");
        l("dbServerName", "Сервер баз данных");
        l("dbms", "Тип СУБД");
        l("dbName", "База данных");
        l("dbUser", "Пользователь сервера БД");
        l("dbPassword", "Пароль пользователя БД");
        l("dateOffset", "Смещение дат");
        l("locale", "Язык (страна)");
        l("licenseDistributionAllowed", "Выдача лицензий сервером 1С:Предприятия");
        l("sessionsDenied", "Блокировка начала сеансов включена");
        l("deniedFrom", "Начало блокировки");
        l("deniedTo", "Конец блокировки");
        l("deniedMessage", "Сообщение о блокировке");
        l("permissionCode", "Код разрешения");
        l("deniedParameter", "Параметр блокировки");
        l("scheduledJobsDenied", "Блокировка регламентных заданий включена");
        l("externalSessionManagerConnectionString", "Внешнее управление сеансами");
        l("externalSessionManagerRequired", "Обязательное использование внешнего управления сеансами");
        l("securityProfileName", "Профиль безопасности");
        l("safeModeSecurityProfileName", "Профиль безопасности безопасного режима");
        l("reserveWorkingProcesses", "Резервировать рабочие процессы");
        order(IInfoBaseInfo.class, "name", "descr", "securityLevel", "dbServerName", "dbms", "dbName",
            "dbUser", "dbPassword", "dateOffset", "locale", "licenseDistributionAllowed",
            "sessionsDenied", "deniedFrom", "deniedTo", "deniedMessage", "permissionCode",
            "deniedParameter", "scheduledJobsDenied", "externalSessionManagerConnectionString",
            "externalSessionManagerRequired", "securityProfileName", "safeModeSecurityProfileName",
            "reserveWorkingProcesses", "infoBaseId");

        group(IInfoBaseInfo.class, "Основное", null, "name", "descr", "securityLevel");
        group(IInfoBaseInfo.class, "База данных", "Где лежат данные базы. Пароль показывается пустым — заполните, только чтобы сменить.",
            "dbms", "dbServerName", "dbName", "dbUser", "dbPassword", "locale", "dateOffset");
        group(IInfoBaseInfo.class, "Блокировка начала сеансов",
            "В указанный период пользователи не смогут войти. Войти во время блокировки — с параметром /UC<код разрешения>.",
            "sessionsDenied", "deniedFrom", "deniedTo", "deniedMessage", "permissionCode", "deniedParameter");
        group(IInfoBaseInfo.class, "Регламентные задания и лицензии", null,
            "scheduledJobsDenied", "licenseDistributionAllowed", "reserveWorkingProcesses");
        group(IInfoBaseInfo.class, "Безопасность", null, "securityProfileName", "safeModeSecurityProfileName");
        group(IInfoBaseInfo.class, "Внешнее управление сеансами", null,
            "externalSessionManagerConnectionString", "externalSessionManagerRequired");
        group(IInfoBaseInfo.class, "Служебное", null, "infoBaseId");

        order(com._1c.v8.ibis.admin.IInfoBaseInfoShort.class, "name", "descr", "infoBaseId");

        // рабочий сервер
        l("workingServerId", "ID рабочего сервера");
        l("mainServer", "Центральный сервер");
        l("infoBasesPerWorkingProcessLimit", "Количество ИБ на процесс");
        l("connectionsPerWorkingProcessLimit", "Количество соединений на процесс");
        l("workingProcessMemoryLimit", "Максимальный объём памяти рабочих процессов, байт");
        l("safeWorkingProcessesMemoryLimit", "Безопасный расход памяти рабочих процессов, байт");
        l("safeCallMemoryLimit", "Безопасный расход памяти за один вызов, байт");
        l("criticalProcessesTotalMemory", "Критический объём памяти процессов, байт");
        l("temporaryAllowedProcessesTotalMemory", "Временно допустимый объём памяти процессов, байт");
        l("temporaryAllowedProcessesTotalMemoryTimeLimit", "Интервал превышения допустимого объёма памяти, с");
        l("clusterMainPort", "Порт главного менеджера кластера");
        l("dedicatedManagers", "Менеджер под каждый сервис");
        l("portRanges", "Диапазоны IP-портов");
        order(IWorkingServerInfo.class, "name", "hostName", "mainPort", "mainServer", "portRanges",
            "infoBasesPerWorkingProcessLimit", "connectionsPerWorkingProcessLimit",
            "workingProcessMemoryLimit", "safeWorkingProcessesMemoryLimit", "safeCallMemoryLimit",
            "criticalProcessesTotalMemory", "temporaryAllowedProcessesTotalMemory",
            "temporaryAllowedProcessesTotalMemoryTimeLimit", "dedicatedManagers", "clusterMainPort",
            "workingServerId");

        // рабочий процесс
        l("workingProcessId", "ID рабочего процесса");
        l("enable", "Включён");
        l("running", "Активен");
        l("use", "Использование");
        l("reserve", "Резервный");
        l("startedAt", "Время запуска");
        l("memorySize", "Занято памяти, КБ");
        l("memoryExcessTime", "Время превышения памяти, с");
        l("connections", "Соединений");
        l("capacity", "Производительность");
        l("availablePerfomance", "Доступная производительность");
        l("selectionSize", "Объём выборки");
        l("avgThreads", "Среднее количество потоков");
        l("avgCallTime", "Среднее время вызова, мс");
        l("avgServerCallTime", "Среднее время сервера, мс");
        l("avgDBCallTime", "Среднее время СУБД, мс");
        l("avgLockCallTime", "Среднее время менеджера блокировок, мс");
        l("avgBackCallTime", "Среднее время клиента, мс");
        l("license", "Лицензии");
        order(IWorkingProcessInfo.class, "hostName", "mainPort", "pid", "enable", "running", "use",
            "reserve", "startedAt", "memorySize", "memoryExcessTime", "connections", "capacity",
            "availablePerfomance", "selectionSize", "avgThreads", "avgCallTime", "avgServerCallTime",
            "avgDBCallTime", "avgLockCallTime", "avgBackCallTime", "license", "workingProcessId");

        // менеджер кластера
        l("clusterManagerId", "ID менеджера");
        l("mainManager", "Главный менеджер");
        order(IClusterManagerInfo.class, "descr", "hostName", "mainPort", "pid", "mainManager", "clusterManagerId");

        // администратор
        l("passwordAuthAllowed", "Аутентификация паролем");
        l("sysAuthAllowed", "Аутентификация ОС");
        l("sysUserName", "Пользователь ОС");
        order(IRegUserInfo.class, "name", "descr", "passwordAuthAllowed", "password", "sysAuthAllowed", "sysUserName");

        // сеанс
        l("sid", "ID сеанса");
        l("sessionId", "Номер сеанса");
        l("appId", "Приложение");
        l("userName", "Пользователь");
        l("lastActiveAt", "Последняя активность");
        l("hibernate", "Спящий");
        l("passiveSessionHibernateTime", "Засыпать пассивный сеанс через, с");
        l("hibernateSessionTerminationTime", "Завершать спящий сеанс через, с");
        l("blockedByDbms", "Заблокировано СУБД");
        l("blockedByLs", "Заблокировано упр.");
        l("connectionId", "ID соединения");
        l("dataSeparation", "Разделение данных");
        l("clientIPAddress", "IP-адрес клиента");
        l("currentServiceName", "Текущая служба");
        l("dbProcInfo", "Соединение с СУБД");
        l("dbProcTook", "Захвачено СУБД (текущее), мс");
        l("dbProcTookAt", "Время захвата СУБД");
        l("licenses", "Лицензии");
        l("bytesAll", "Объём данных (всего)");
        l("bytesLast5Min", "Объём данных (5 мин)");
        l("callsAll", "Вызовов (всего)");
        l("callsLast5Min", "Вызовов (5 мин)");
        l("dbmsBytesAll", "Данных СУБД (всего)");
        l("dbmsBytesLast5Min", "Данных СУБД (5 мин)");
        l("durationAll", "Время вызовов (всего), мс");
        l("durationAllDbms", "Время вызовов СУБД (всего), мс");
        l("durationAllService", "Время вызовов служб (всего), мс");
        l("durationCurrent", "Время вызова (текущее), мс");
        l("durationCurrentDbms", "Время вызова СУБД (текущее), мс");
        l("durationCurrentService", "Время вызова службы (текущее), мс");
        l("durationLast5Min", "Время вызовов (5 мин), мс");
        l("durationLast5MinDbms", "Время вызовов СУБД (5 мин), мс");
        l("durationLast5MinService", "Время вызовов служб (5 мин), мс");
        l("memoryCurrent", "Память (текущая)");
        l("memoryLast5Min", "Память (5 мин)");
        l("memoryTotal", "Память (всего)");
        l("readBytesCurrent", "Чтение (текущее)");
        l("readBytesLast5Min", "Чтение (5 мин)");
        l("readBytesTotal", "Чтение (всего)");
        l("writeBytesCurrent", "Запись (текущая)");
        l("writeBytesLast5Min", "Запись (5 мин)");
        l("writeBytesTotal", "Запись (всего)");
        l("cpuTimeCurrent", "Процессорное время (текущее), мс");
        l("cpuTimeLast5Min", "Процессорное время (5 мин), мс");
        l("cpuTimeAll", "Процессорное время (всего), мс");
        order(ISessionInfo.class, "sessionId", "userName", "appId", "host", "clientIPAddress", "startedAt",
            "lastActiveAt", "hibernate", "blockedByDbms", "blockedByLs", "dataSeparation", "locale",
            "currentServiceName", "dbProcInfo", "dbProcTook", "dbProcTookAt",
            "durationCurrent", "durationCurrentDbms", "durationCurrentService",
            "durationLast5Min", "durationLast5MinDbms", "durationLast5MinService",
            "durationAll", "durationAllDbms", "durationAllService",
            "cpuTimeCurrent", "cpuTimeLast5Min", "cpuTimeAll",
            "memoryCurrent", "memoryLast5Min", "memoryTotal",
            "readBytesCurrent", "readBytesLast5Min", "readBytesTotal",
            "writeBytesCurrent", "writeBytesLast5Min", "writeBytesTotal",
            "bytesAll", "bytesLast5Min", "callsAll", "callsLast5Min", "dbmsBytesAll", "dbmsBytesLast5Min",
            "passiveSessionHibernateTime", "hibernateSessionTerminationTime", "licenses",
            "sid", "infoBaseId", "connectionId", "workingProcessId");

        // соединение / блокировка
        l("infoBaseConnectionId", "ID соединения");
        l("connId", "Номер соединения");
        l("application", "Приложение");
        l("connectedAt", "Время начала");
        l("sessionNumber", "Сеанс");
        l("lockDescr", "Описание блокировки");
        l("lockedAt", "Время блокировки");
        l("object", "Объект");

        // профиль безопасности
        l("SPName", "Профиль безопасности");
        l("fileSystemFullAccess", "Полный доступ к файловой системе");
        l("comUseFullAccess", "Полный доступ к COM-объектам");
        l("addInUseFullAccess", "Полный доступ к внешним компонентам");
        l("externalAppFullAccess", "Полный доступ к приложениям");
        l("internetUseFullAccess", "Полный доступ к интернет-ресурсам");
        l("unsafeExternalModuleFullAccess", "Полный доступ к внешним модулям");
        l("safeModeProfile", "Может использоваться как профиль безопасного режима");
        l("privilegedModeInSafeModeAllowed", "Разрешён привилегированный режим в безопасном режиме");
        l("fullPrivilegedMode", "Полный доступ к привилегированному режиму");
        l("privilegedModeRoles", "Роли, доступные в привилегированном режиме");
        l("cryptographyAllowed", "Разрешено использование криптографии");
        l("rightExtension", "Доступ к расширению прав");
        l("rightExtensionDefinitionRoles", "Роли, определяющие расширение прав");
        l("allModulesExtension", "Расширение всех модулей");
        l("modulesAvailableForExtension", "Модули, доступные для расширения");
        l("modulesNotAvailableForExtension", "Модули, недоступные для расширения");
        order(ISecurityProfile.class, "SPName", "descr", "safeModeProfile", "privilegedModeInSafeModeAllowed",
            "fullPrivilegedMode", "privilegedModeRoles", "fileSystemFullAccess", "comUseFullAccess",
            "addInUseFullAccess", "unsafeExternalModuleFullAccess", "externalAppFullAccess",
            "internetUseFullAccess", "cryptographyAllowed", "rightExtension", "rightExtensionDefinitionRoles",
            "allModulesExtension", "modulesAvailableForExtension", "modulesNotAvailableForExtension");
        l("alias", "Логическое имя (URL)");
        l("physicalPath", "Физический путь");
        l("allowedRead", "Чтение разрешено");
        l("allowedWrite", "Запись разрешена");
        l("fileName", "Имя файла");
        l("objectUuid", "Идентификатор COM-объекта (CLSID)");
        l("computerName", "Компьютер");
        l("addInHash", "Контрольная сумма");
        l("moduleHash", "Контрольная сумма");
        l("commandWild", "Шаблон командной строки");
        l("address", "Адрес");
        l("port", "Порт");
        l("protocol", "Протокол");

        // требование назначения
        l("assignmentRuleId", "ID требования");
        l("objectType", "Объект требования");
        l("infoBaseName", "Имя информационной базы");
        l("ruleType", "Тип требования");
        l("applicationExt", "Значение дополнительного параметра");
        l("priority", "Приоритет");
        order(IAssignmentRuleInfo.class, "objectType", "ruleType", "infoBaseName", "applicationExt", "priority", "assignmentRuleId");

        // счётчик / ограничение ресурсов
        l("collectionTime", "Время накопления, с");
        l("group", "Группировка");
        l("filterType", "Тип отбора");
        l("filterValue", "Отбор");
        l("analyzeDuration", "Длительность вызовов");
        l("analyzeCpuTime", "Процессорное время");
        l("analyzeMemory", "Память");
        l("analyzeReadBytes", "Объём чтения");
        l("analyzeWriteBytes", "Объём записи");
        l("analyzeDurationDbms", "Длительность вызовов СУБД");
        l("analyzeDbmsBytes", "Данные СУБД");
        l("analyzeDurationService", "Длительность вызовов служб");
        l("analyzeCalls", "Количество вызовов");
        l("analyzeActiveSessionCount", "Количество активных сеансов");
        l("analyzeSessionCount", "Количество сеансов");
        order(IResourceConsumptionCounter.class, "name", "description", "collectionTime", "group", "filterType",
            "filterValue", "analyzeDuration", "analyzeCpuTime", "analyzeMemory", "analyzeReadBytes",
            "analyzeWriteBytes", "analyzeDurationDbms", "analyzeDbmsBytes", "analyzeDurationService",
            "analyzeCalls", "analyzeActiveSessionCount", "analyzeSessionCount");
        l("counter", "Счётчик");
        l("action", "Действие");
        l("message", "Сообщение");
        l("duration", "Длительность вызовов, мс");
        l("cpuTime", "Процессорное время, мс");
        l("memory", "Память, байт");
        l("readBytes", "Объём чтения, байт");
        l("writeBytes", "Объём записи, байт");
        l("durationDbms", "Длительность вызовов СУБД, мс");
        l("dbmsBytes", "Данные СУБД, байт");
        l("durationService", "Длительность вызовов служб, мс");
        l("calls", "Количество вызовов");
        l("activeSessionCount", "Количество активных сеансов");
        l("sessionCount", "Количество сеансов");
        l("time", "Время");
        order(IResourceConsumptionLimit.class, "name", "description", "counter", "action", "message",
            "duration", "cpuTime", "memory", "readBytes", "writeBytes", "durationDbms", "dbmsBytes",
            "durationService", "calls", "activeSessionCount", "sessionCount");

        // перечисления
        choice("securityLevel", 0, "Небезопасное соединение", 1, "Защищённое при подключении", 2, "Постоянно защищённое");
        choice("loadBalancingMode", 0, "Приоритет по производительности", 1, "Приоритет по памяти");
        choice("licenseDistributionAllowed", 0, "Запрещена", 1, "Разрешена");
        choice("ruleType", 0, "Не назначать", 1, "Авто", 2, "Назначать");
        choice("group", 0, "Пользователи", 1, "Разделение данных");
        choice("filterType", 0, "Выбранные", 1, "Все, кроме выбранных", 2, "Все");
        choice("action", 0, "Нет действия", 1, "Понизить приоритет потока", 2, "Прервать текущий серверный вызов", 3, "Завершить сеанс");
        STRING_CHOICES.put("dbms", List.of("MSSQLServer", "PostgreSQL", "IBMDB2", "OracleDatabase"));
        STRING_CHOICES.put("protocol", List.of("HTTP", "HTTPS", "FTP", "FTPS", "POP3", "POP3S", "SMTP", "SMTPS", "IMAP", "IMAPS"));
    }

    private Labels() {
    }

    private static void l(String prop, String label) {
        LABELS.put(prop, label);
    }

    private static void order(Class<?> type, String... props) {
        ORDER.put(type, List.of(props));
    }

    private static void choice(String prop, Object... pairs) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((Integer) pairs[i], (String) pairs[i + 1]);
        }
        CHOICES.put(prop, m);
    }

    private static void group(Class<?> type, String title, String hint, String... props) {
        GROUPS.computeIfAbsent(type, k -> new LinkedHashMap<>()).put(title, List.of(props));
        if (hint != null) GROUP_HINTS.put(title, hint);
    }

    /** Разделы формы объекта или null, если форма без разделов. */
    public static Map<String, List<String>> groups(Class<?> type) {
        return GROUPS.get(type);
    }

    public static String groupHint(String title) {
        return GROUP_HINTS.get(title);
    }

    public static String label(String prop) {
        return LABELS.getOrDefault(prop, prop);
    }

    public static List<String> order(Class<?> type) {
        return ORDER.get(type);
    }

    public static Map<Integer, String> choices(String prop) {
        return CHOICES.get(prop);
    }

    public static List<String> stringChoices(String prop) {
        return STRING_CHOICES.get(prop);
    }

    /** Отображение значения перечисления, если оно известно. */
    public static String choiceText(String prop, int value) {
        Map<Integer, String> m = CHOICES.get(prop);
        if (m == null) return Integer.toString(value);
        return m.getOrDefault(value, Integer.toString(value));
    }
}
