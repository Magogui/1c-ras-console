package ru.rasconsole.ui.cluster;

import java.util.List;

import com._1c.v8.ibis.admin.IAssignmentRuleInfo;
import com._1c.v8.ibis.admin.IClusterInfo;
import com._1c.v8.ibis.admin.IClusterManagerInfo;
import com._1c.v8.ibis.admin.IClusterServiceInfo;
import com._1c.v8.ibis.admin.IInfoBaseConnectionShort;
import com._1c.v8.ibis.admin.IInfoBaseInfoShort;
import com._1c.v8.ibis.admin.IObjectLockInfo;
import com._1c.v8.ibis.admin.IRegUserInfo;
import com._1c.v8.ibis.admin.IResourceConsumptionCounter;
import com._1c.v8.ibis.admin.IResourceConsumptionCounterValue;
import com._1c.v8.ibis.admin.IResourceConsumptionLimit;
import com._1c.v8.ibis.admin.ISecurityProfile;
import com._1c.v8.ibis.admin.ISecurityProfileAddIn;
import com._1c.v8.ibis.admin.ISecurityProfileApplication;
import com._1c.v8.ibis.admin.ISecurityProfileCOMClass;
import com._1c.v8.ibis.admin.ISecurityProfileExternalModule;
import com._1c.v8.ibis.admin.ISecurityProfileInternetResource;
import com._1c.v8.ibis.admin.ISecurityProfileVirtualDirectory;
import com._1c.v8.ibis.admin.ISessionInfo;
import com._1c.v8.ibis.admin.IWorkingProcessInfo;
import com._1c.v8.ibis.admin.IWorkingServerInfo;

import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.form.Labels;
import ru.rasconsole.ui.table.Col;

/** Колонки таблиц — примерно как в стандартной консоли кластера. */
final class Columns {

    private Columns() {
    }

    static List<Col<IClusterInfo>> clusters() {
        return List.of(
            Col.<IClusterInfo>of("Имя", 220, IClusterInfo::getName),
            Col.of("Компьютер", 160, IClusterInfo::getHostName),
            Col.of("Порт", 70, IClusterInfo::getMainPort),
            Col.of("Уровень отказоустойчивости", IClusterInfo::getSessionFaultToleranceLevel),
            Col.<IClusterInfo>of("Распределение нагрузки", 200, IClusterInfo::getLoadBalancingMode)
                .text(v -> Labels.choiceText("loadBalancingMode", (Integer) v)),
            Col.of("ID", 260, IClusterInfo::getClusterId));
    }

    static List<Col<IRegUserInfo>> admins() {
        return List.of(
            Col.<IRegUserInfo>of("Имя", 180, IRegUserInfo::getName),
            Col.of("Описание", 220, IRegUserInfo::getDescr),
            Col.of("Аутентификация паролем", IRegUserInfo::isPasswordAuthAllowed),
            Col.of("Аутентификация ОС", IRegUserInfo::isSysAuthAllowed),
            Col.of("Пользователь ОС", 180, IRegUserInfo::getSysUserName));
    }

    static List<Col<IClusterManagerInfo>> managers() {
        return List.of(
            Col.<IClusterManagerInfo>of("Описание", 220, IClusterManagerInfo::getDescr),
            Col.of("Компьютер", 160, IClusterManagerInfo::getHostName),
            Col.of("Порт", 70, IClusterManagerInfo::getMainPort),
            Col.of("PID", 80, IClusterManagerInfo::getPid),
            Col.<IClusterManagerInfo>of("Главный", m -> m.getMainManager() != 0));
    }

    static List<Col<IClusterServiceInfo>> services() {
        return List.of(
            Col.<IClusterServiceInfo>of("Имя", 260, IClusterServiceInfo::getName),
            Col.of("Описание", 320, IClusterServiceInfo::getDescr),
            Col.<IClusterServiceInfo>of("Только главный", s -> s.getMainOnly() != 0),
            Col.<IClusterServiceInfo>of("Менеджеров", s -> s.getClusterManagerIds() == null ? 0 : s.getClusterManagerIds().size()));
    }

    static List<Col<IWorkingServerInfo>> servers() {
        return List.of(
            Col.<IWorkingServerInfo>of("Имя", 200, IWorkingServerInfo::getName),
            Col.of("Компьютер", 160, IWorkingServerInfo::getHostName),
            Col.of("Порт", 70, IWorkingServerInfo::getMainPort),
            Col.of("Центральный", IWorkingServerInfo::isMainServer),
            Col.of("Диапазоны портов", 160, s -> ClusterSections.portRangesText(s.getPortRanges())),
            Col.of("ИБ на процесс", IWorkingServerInfo::getInfoBasesPerWorkingProcessLimit),
            Col.of("Соединений на процесс", IWorkingServerInfo::getConnectionsPerWorkingProcessLimit),
            Col.bytes("Макс. память процессов", IWorkingServerInfo::getWorkingProcessMemoryLimit),
            Col.bytes("Безопасный расход за вызов", IWorkingServerInfo::getSafeCallMemoryLimit));
    }

    static List<Col<IWorkingProcessInfo>> processes() {
        return List.of(
            Col.<IWorkingProcessInfo>of("Компьютер", 160, IWorkingProcessInfo::getHostName),
            Col.of("Порт", 70, IWorkingProcessInfo::getMainPort),
            Col.of("PID", 80, IWorkingProcessInfo::getPid),
            Col.of("Включён", IWorkingProcessInfo::isEnable),
            Col.<IWorkingProcessInfo>of("Активен", p -> p.getRunning() != 0),
            Col.of("Использование", IWorkingProcessInfo::getUse),
            Col.of("Резервный", IWorkingProcessInfo::isReserve),
            Col.of("Запущен", 140, IWorkingProcessInfo::getStartedAt),
            Col.<IWorkingProcessInfo>bytes("Память", p -> (long) p.getMemorySize() * 1024),
            Col.of("Соединений", IWorkingProcessInfo::getConnections),
            Col.of("Доступная производительность", IWorkingProcessInfo::getAvailablePerfomance),
            Col.of("Ср. время вызова, мс", IWorkingProcessInfo::getAvgCallTime),
            Col.of("Ср. время сервера, мс", IWorkingProcessInfo::getAvgServerCallTime),
            Col.of("Ср. время СУБД, мс", IWorkingProcessInfo::getAvgDBCallTime),
            Col.of("Ср. время блокировок, мс", IWorkingProcessInfo::getAvgLockCallTime),
            Col.of("Ср. время клиента, мс", IWorkingProcessInfo::getAvgBackCallTime),
            Col.of("Ср. потоков", IWorkingProcessInfo::getAvgThreads));
    }

    static List<Col<IInfoBaseInfoShort>> infoBases() {
        return List.of(
            Col.<IInfoBaseInfoShort>of("Имя", 240, IInfoBaseInfoShort::getName),
            Col.of("Описание", 400, IInfoBaseInfoShort::getDescr));
    }

    static List<Col<ISessionInfo>> sessions(ClusterCtx ctx) {
        return List.of(
            Col.<ISessionInfo>of("Приложение", 140, s -> Fmt.app(s.getAppId())),
            Col.of("ИБ", 140, s -> ctx.ibName(s.getInfoBaseId())),
            Col.of("Сеанс", 60, ISessionInfo::getSessionId),
            Col.of("Пользователь", 160, ISessionInfo::getUserName),
            Col.of("Компьютер", 130, ISessionInfo::getHost),
            Col.of("Начало работы", 140, ISessionInfo::getStartedAt),
            Col.of("Последняя активность", 140, ISessionInfo::getLastActiveAt),
            Col.of("Соединение", 80, s -> ctx.connNumber(s.getConnectionId())),
            Col.of("Процесс", 130, s -> ctx.processName(s.getWorkingProcessId())),
            Col.of("Спящий", ISessionInfo::getHibernate),
            Col.of("Заблокировано СУБД", ISessionInfo::getBlockedByDbms),
            Col.of("Заблокировано упр.", ISessionInfo::getBlockedByLs),
            Col.of("Время вызова (тек.), мс", ISessionInfo::getDurationCurrent),
            Col.of("Захвачено СУБД (тек.), мс", ISessionInfo::getDbProcTook),
            Col.of("СУБД захвачено в", 140, ISessionInfo::getDbProcTookAt),
            Col.of("Вызовы (5 мин), мс", ISessionInfo::getDurationLast5Min),
            Col.of("Вызовы СУБД (тек.), мс", ISessionInfo::getDurationCurrentDbms),
            Col.of("CPU (тек.), мс", ISessionInfo::getCpuTimeCurrent),
            Col.of("CPU (5 мин), мс", ISessionInfo::getCpuTimeLast5Min),
            Col.bytes("Память (тек.)", ISessionInfo::getMemoryCurrent),
            Col.bytes("Память (5 мин)", ISessionInfo::getMemoryLast5Min),
            Col.bytes("Чтение (5 мин)", ISessionInfo::getReadBytesLast5Min),
            Col.bytes("Запись (5 мин)", ISessionInfo::getWriteBytesLast5Min),
            Col.bytes("Данные СУБД (5 мин)", ISessionInfo::getDbmsBytesLast5Min),
            Col.of("Соединение с СУБД", ISessionInfo::getDbProcInfo),
            Col.of("Текущая служба", ISessionInfo::getCurrentServiceName),
            Col.of("IP-адрес клиента", 120, ISessionInfo::getClientIPAddress),
            Col.of("Разделение данных", ISessionInfo::getDataSeparation));
    }

    /** Тяжёлые сеансы: сначала показатели нагрузки, потом кто это. */
    static List<Col<ISessionInfo>> heavySessions(ClusterCtx ctx) {
        return List.of(
            Col.<ISessionInfo>of("Сеанс", 60, ISessionInfo::getSessionId),
            Col.of("Пользователь", 150, ISessionInfo::getUserName),
            Col.of("ИБ", 130, s -> ctx.ibName(s.getInfoBaseId())),
            Col.of("CPU (5 мин), мс", 100, ISessionInfo::getCpuTimeLast5Min),
            Col.of("Время вызова (тек.), с", 90, s -> s.getDurationCurrent() / 1000),
            Col.of("Захвачено СУБД (тек.), с", 100, s -> s.getDbProcTook() / 1000),
            Col.of("CPU (тек.), мс", 90, ISessionInfo::getCpuTimeCurrent),
            Col.of("СУБД (тек.), мс", 90, ISessionInfo::getDurationCurrentDbms),
            Col.of("Вызовы (5 мин), мс", 110, ISessionInfo::getDurationLast5Min),
            Col.bytes("Память (тек.)", ISessionInfo::getMemoryCurrent),
            Col.bytes("Память (5 мин)", ISessionInfo::getMemoryLast5Min),
            Col.bytes("Данные СУБД (5 мин)", ISessionInfo::getDbmsBytesLast5Min),
            Col.bytes("Чтение (5 мин)", ISessionInfo::getReadBytesLast5Min),
            Col.bytes("Запись (5 мин)", ISessionInfo::getWriteBytesLast5Min),
            Col.of("Ждёт блокировку от сеанса", 110, s -> LockChains.waitsFor(s) == 0 ? null : LockChains.waitsFor(s)),
            Col.of("Текущая служба", 160, ISessionInfo::getCurrentServiceName),
            Col.of("Приложение", 130, s -> Fmt.app(s.getAppId())),
            Col.of("Компьютер", 130, ISessionInfo::getHost),
            Col.of("Процесс", 130, s -> ctx.processName(s.getWorkingProcessId())),
            Col.of("Соединение с СУБД", ISessionInfo::getDbProcInfo));
    }

    static List<Col<IInfoBaseConnectionShort>> connections(ClusterCtx ctx) {
        return List.of(
            Col.<IInfoBaseConnectionShort>of("Приложение", 140, c -> Fmt.app(c.getApplication())),
            Col.of("Соединение", 80, IInfoBaseConnectionShort::getConnId),
            Col.of("Компьютер", 140, IInfoBaseConnectionShort::getHost),
            Col.of("ИБ", 140, c -> ctx.ibName(c.getInfoBaseId())),
            Col.of("Процесс", 140, c -> ctx.processName(c.getWorkingProcessId())),
            Col.of("Время начала", 140, IInfoBaseConnectionShort::getConnectedAt),
            Col.of("Сеанс", 60, IInfoBaseConnectionShort::getSessionNumber),
            Col.of("Заблокировано упр.", IInfoBaseConnectionShort::getBlockedByLs));
    }

    static List<Col<IObjectLockInfo>> locks(ClusterCtx ctx) {
        return List.of(
            Col.<IObjectLockInfo>of("Описание", 420, IObjectLockInfo::getLockDescr),
            Col.of("Время", 140, IObjectLockInfo::getLockedAt),
            Col.of("Сеанс", 70, l -> ctx.sessionNumber(l.getSid())),
            Col.of("Соединение", 80, l -> ctx.connNumber(l.getConnectionId())),
            Col.of("Объект", 260, IObjectLockInfo::getObject));
    }

    static List<Col<IAssignmentRuleInfo>> assignmentRules() {
        return List.of(
            Col.<IAssignmentRuleInfo>of("Объект требования", 200, IAssignmentRuleInfo::getObjectType),
            Col.<IAssignmentRuleInfo>of("Тип требования", 120, IAssignmentRuleInfo::getRuleType)
                .text(v -> Labels.choiceText("ruleType", (Integer) v)),
            Col.of("Имя ИБ", 160, IAssignmentRuleInfo::getInfoBaseName),
            Col.of("Доп. параметр", 160, IAssignmentRuleInfo::getApplicationExt),
            Col.of("Приоритет", IAssignmentRuleInfo::getPriority));
    }

    static List<Col<ISecurityProfile>> securityProfiles() {
        return List.of(
            Col.<ISecurityProfile>of("Имя", 200, ISecurityProfile::getSPName),
            Col.of("Описание", 260, ISecurityProfile::getDescr),
            Col.of("Профиль безопасного режима", ISecurityProfile::getSafeModeProfile),
            Col.of("Полный привилегированный режим", ISecurityProfile::getFullPrivilegedMode),
            Col.of("Файловая система", ISecurityProfile::getFileSystemFullAccess),
            Col.of("COM", ISecurityProfile::getComUseFullAccess),
            Col.of("Внешние компоненты", ISecurityProfile::getAddInUseFullAccess),
            Col.of("Внешние модули", ISecurityProfile::getUnsafeExternalModuleFullAccess),
            Col.of("Приложения", ISecurityProfile::getExternalAppFullAccess),
            Col.of("Интернет", ISecurityProfile::getInternetUseFullAccess));
    }

    static List<Col<ISecurityProfileVirtualDirectory>> virtualDirectories() {
        return List.of(
            Col.<ISecurityProfileVirtualDirectory>of("Логическое имя", 200, ISecurityProfileVirtualDirectory::getAlias),
            Col.of("Физический путь", 260, ISecurityProfileVirtualDirectory::getPhysicalPath),
            Col.of("Чтение", ISecurityProfileVirtualDirectory::getAllowedRead),
            Col.of("Запись", ISecurityProfileVirtualDirectory::getAllowedWrite),
            Col.of("Описание", 220, ISecurityProfileVirtualDirectory::getDescr));
    }

    static List<Col<ISecurityProfileCOMClass>> comClasses() {
        return List.of(
            Col.<ISecurityProfileCOMClass>of("Имя", 200, ISecurityProfileCOMClass::getName),
            Col.of("Файл", 200, ISecurityProfileCOMClass::getFileName),
            Col.of("CLSID", 260, ISecurityProfileCOMClass::getObjectUuid),
            Col.of("Компьютер", 140, ISecurityProfileCOMClass::getComputerName),
            Col.of("Описание", 200, ISecurityProfileCOMClass::getDescr));
    }

    static List<Col<ISecurityProfileAddIn>> addIns() {
        return List.of(
            Col.<ISecurityProfileAddIn>of("Имя", 200, ISecurityProfileAddIn::getName),
            Col.of("Контрольная сумма", 300, ISecurityProfileAddIn::getAddInHash),
            Col.of("Описание", 200, ISecurityProfileAddIn::getDescr));
    }

    static List<Col<ISecurityProfileExternalModule>> externalModules() {
        return List.of(
            Col.<ISecurityProfileExternalModule>of("Имя", 200, ISecurityProfileExternalModule::getName),
            Col.of("Контрольная сумма", 300, ISecurityProfileExternalModule::getModuleHash),
            Col.of("Описание", 200, ISecurityProfileExternalModule::getDescr));
    }

    static List<Col<ISecurityProfileApplication>> applications() {
        return List.of(
            Col.<ISecurityProfileApplication>of("Имя", 200, ISecurityProfileApplication::getName),
            Col.of("Шаблон командной строки", 300, ISecurityProfileApplication::getCommandWild),
            Col.of("Описание", 200, ISecurityProfileApplication::getDescr));
    }

    static List<Col<ISecurityProfileInternetResource>> internetResources() {
        return List.of(
            Col.<ISecurityProfileInternetResource>of("Имя", 200, ISecurityProfileInternetResource::getName),
            Col.of("Протокол", 90, ISecurityProfileInternetResource::getProtocol),
            Col.of("Адрес", 220, ISecurityProfileInternetResource::getAddress),
            Col.of("Порт", 70, ISecurityProfileInternetResource::getPort),
            Col.of("Описание", 200, ISecurityProfileInternetResource::getDescr));
    }

    static List<Col<IResourceConsumptionCounter>> counters() {
        return List.of(
            Col.<IResourceConsumptionCounter>of("Имя", 200, IResourceConsumptionCounter::getName),
            Col.of("Описание", 220, IResourceConsumptionCounter::getDescription),
            Col.of("Время накопления, с", IResourceConsumptionCounter::getCollectionTime),
            Col.<IResourceConsumptionCounter>of("Группировка", 140, IResourceConsumptionCounter::getGroup)
                .text(v -> Labels.choiceText("group", (Integer) v)),
            Col.<IResourceConsumptionCounter>of("Тип отбора", 160, IResourceConsumptionCounter::getFilterType)
                .text(v -> Labels.choiceText("filterType", (Integer) v)),
            Col.of("Отбор", 200, IResourceConsumptionCounter::getFilterValue));
    }

    static List<Col<IResourceConsumptionCounterValue>> counterValues() {
        return List.of(
            Col.<IResourceConsumptionCounterValue>of("Объект", 200, IResourceConsumptionCounterValue::getObject),
            Col.of("Время", 140, IResourceConsumptionCounterValue::getTime),
            Col.of("Длительность, мс", IResourceConsumptionCounterValue::getDuration),
            Col.of("CPU, мс", IResourceConsumptionCounterValue::getCpuTime),
            Col.bytes("Память", IResourceConsumptionCounterValue::getMemory),
            Col.bytes("Чтение", IResourceConsumptionCounterValue::getReadBytes),
            Col.bytes("Запись", IResourceConsumptionCounterValue::getWriteBytes),
            Col.of("Длительность СУБД, мс", IResourceConsumptionCounterValue::getDurationDbms),
            Col.bytes("Данные СУБД", IResourceConsumptionCounterValue::getDbmsBytes),
            Col.of("Длительность служб, мс", IResourceConsumptionCounterValue::getDurationService),
            Col.of("Вызовов", IResourceConsumptionCounterValue::getCalls),
            Col.of("Активных сеансов", IResourceConsumptionCounterValue::getActiveSessionCount),
            Col.of("Сеансов", IResourceConsumptionCounterValue::getSessionCount));
    }

    static List<Col<IResourceConsumptionLimit>> limits() {
        return List.of(
            Col.<IResourceConsumptionLimit>of("Имя", 200, IResourceConsumptionLimit::getName),
            Col.of("Счётчик", 180, IResourceConsumptionLimit::getCounter),
            Col.<IResourceConsumptionLimit>of("Действие", 220, IResourceConsumptionLimit::getAction)
                .text(v -> Labels.choiceText("action", (Integer) v)),
            Col.of("Сообщение", 220, IResourceConsumptionLimit::getMessage),
            Col.of("Описание", 220, IResourceConsumptionLimit::getDescription));
    }
}
