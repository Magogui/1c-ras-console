# Сборка самостоятельного приложения (RasConsole.exe + встроенная Java) через jpackage и архивов для переноса.
# Сначала выполните build.ps1.
#
#   package.ps1          — сборка С библиотекой 1С (для себя и своей организации):
#                          dist\app\RasConsole\RasConsole.exe, dist\RasConsole-portable.zip, dist\RasConsole-linux.tar.gz
#   package.ps1 -Public  — публичная сборка БЕЗ библиотеки 1С (для GitHub Releases и т. п.):
#                          dist\public\RasConsole-<версия>-windows.zip, dist\public\RasConsole-<версия>-linux.tar.gz
#                          Библиотеку пользователь указывает при первом запуске (архив с ИТС).
param([switch]$Public)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

$jdk = $env:JAVA_HOME
if ($jdk -and (Split-Path $jdk -Leaf) -eq 'bin') { $jdk = Split-Path $jdk }
if (-not $jdk -or -not (Test-Path "$jdk\bin\jpackage.exe")) {
    $axiom = Get-ChildItem 'C:\Program Files\1C\1CE\components' -Directory -Filter 'axiom-jdk-full-*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $axiom) { throw 'Не найден JDK с jpackage. Укажите JAVA_HOME.' }
    $jdk = $axiom.FullName
}
if (-not (Test-Path 'dist\RasConsole\ras-console.jar')) { throw 'Сначала выполните build.ps1' }

$version = [regex]::Match((Get-Content 'src\ru\rasconsole\Version.java' -Raw), 'VERSION = "([\d.]+)"').Groups[1].Value
if (-not $version) { throw 'Не найдена версия в src\ru\rasconsole\Version.java' }
Write-Host "Версия: $version$(if ($Public) { ' (публичная сборка, без библиотеки 1С)' })"

if ($Public) {
    $outDir = Join-Path $root 'dist\public'
    # во вход jpackage — только jar консоли, без lib\ с библиотекой 1С
    $inputDir = Join-Path $root 'out\public-input'
    if (Test-Path $inputDir) { Remove-Item -Recurse -Force $inputDir }
    New-Item -ItemType Directory -Force $inputDir | Out-Null
    Copy-Item 'dist\RasConsole\ras-console.jar' $inputDir
} else {
    $outDir = Join-Path $root 'dist'
    $inputDir = Join-Path $root 'dist\RasConsole'
}
$appDir = Join-Path $outDir 'app'
if (Test-Path $appDir) { Remove-Item -Recurse -Force $appDir }
New-Item -ItemType Directory -Force $outDir | Out-Null

# второй запуск: RasCli.exe — командная строка с окном консоли
New-Item -ItemType Directory -Force 'out' | Out-Null
"main-class=ru.rasconsole.CliBoot`r`nwin-console=true`r`nicon=icon/app.ico`r`n" | Set-Content -NoNewline -Encoding ASCII 'out\rascli.properties'
& "$jdk\bin\jpackage.exe" --type app-image `
    --input $inputDir `
    --main-jar 'ras-console.jar' `
    --main-class 'ru.rasconsole.Boot' `
    --name 'RasConsole' `
    --app-version $version `
    --vendor 'RasConsole' `
    --add-modules 'java.base,java.desktop,java.logging,java.management,java.naming,jdk.crypto.ec,jdk.unsupported,javafx.controls,javafx.swing' `
    --java-options '--enable-native-access=javafx.graphics' `
    --icon 'icon/app.ico' `
    --add-launcher 'RasCli=out/rascli.properties' `
    --dest $appDir
if ($LASTEXITCODE -ne 0) { throw 'Ошибка jpackage' }

# Подсказка рядом с exe: запускатель работает только вместе с папками app и runtime
$note = @"
RasConsole.exe и RasCli.exe — запускатели. Рядом с ними должны лежать папки app и runtime.
Переносите папку RasConsole целиком, а не один exe.
Ярлык на рабочий стол: правой кнопкой по RasConsole.exe -> Отправить -> Рабочий стол (создать ярлык).
"@
if ($Public) {
    $note += @"

Библиотека 1С в эту сборку не входит. При первом запуске консоль попросит архив
«Программный Java-интерфейс для административного сервера» (скачайте его с ИТС:
https://its.1c.ru/db/content/metod8dev/src/admins/i8104985.htm , вариант для 8.3.11 и старше).
Можно заранее положить архив .zip рядом с RasConsole.exe — тогда консоль возьмёт его сама.
Командная строка: RasCli install-api <архив.zip>
"@
}
$note | Set-Content -Encoding UTF8 (Join-Path $appDir 'RasConsole\ПРОЧТИ.txt')

# Переносимый архив всей папки
$zip = if ($Public) { Join-Path $outDir "RasConsole-$version-windows.zip" } else { Join-Path $outDir 'RasConsole-portable.zip' }
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path (Join-Path $appDir 'RasConsole') -DestinationPath $zip -CompressionLevel Optimal
Write-Host "Готово: $appDir\RasConsole\RasConsole.exe"
Write-Host "Архив для Windows: $zip"

# Linux: jar (+ библиотеки 1С для внутренней сборки) + скрипты запуска; Java с JavaFX ставится отдельно
$stage = Join-Path $root 'out\linux\RasConsole'
if (Test-Path (Join-Path $root 'out\linux')) { Remove-Item -Recurse -Force (Join-Path $root 'out\linux') }
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item 'dist\RasConsole\ras-console.jar' $stage
if (-not $Public) {
    New-Item -ItemType Directory -Force "$stage\lib" | Out-Null
    Copy-Item 'dist\RasConsole\lib\*.jar' "$stage\lib"
}
foreach ($f in 'rasconsole.sh', 'rascli.sh', 'find-java.sh') {
    # скрипты — с переводами строк LF, иначе sh в Linux их не выполнит
    $text = [IO.File]::ReadAllText((Join-Path $root $f)) -replace "`r`n", "`n"
    [IO.File]::WriteAllText((Join-Path $stage $f), $text, (New-Object Text.UTF8Encoding $false))
}
$readme = @"
Консоль администрирования RAS $version для Linux.

Нужна Java 17+ с JavaFX: Axiom JDK Full или Liberica JDK Full (вариант Full).
Обычный OpenJDK без JavaFX не подойдёт для окна консоли (командной строке JavaFX не нужна).

Запуск окна:          ./rasconsole.sh
Командная строка:     ./rascli.sh help
Другая Java:          JAVA_HOME=<каталог JDK> ./rasconsole.sh

Настройки и список серверов: ~/.rasconsole
Пароли сохраняются, если установлен secret-tool (пакет libsecret-tools) и работает связка ключей (GNOME Keyring, KWallet).
"@
if ($Public) {
    $readme += @"

Библиотека 1С в эту сборку не входит. Скачайте с ИТС архив «Программный Java-интерфейс
для административного сервера» (вариант для 8.3.11 и старше):
https://its.1c.ru/db/content/metod8dev/src/admins/i8104985.htm
и установите его:   ./rascli.sh install-api <архив.zip>
(или положите архив рядом со скриптами — консоль возьмёт его сама при запуске).
"@
}
[IO.File]::WriteAllText((Join-Path $stage 'README-linux.txt'), ($readme -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding $false))
$tgzName = if ($Public) { "RasConsole-$version-linux.tar.gz" } else { 'RasConsole-linux.tar.gz' }
$tgz = Join-Path $outDir $tgzName
$tgzRel = $tgz.Substring($root.Length + 1) -replace '\\', '/'
& "$jdk\bin\java.exe" -cp 'out/classes' ru.rasconsole.tools.TarGz $tgzRel 'RasConsole' 'out/linux/RasConsole'
if ($LASTEXITCODE -ne 0) { throw 'Ошибка сборки архива для Linux' }
Write-Host "Архив для Linux: $tgz"

# Публичная сборка: проверить, что в ней нет ни одного файла библиотеки 1С
if ($Public) {
    $leak = @(Get-ChildItem $appDir -Recurse -File | Where-Object { $_.Name -match '^com\._1c\.|^netty-' })
    $leak += @(Get-ChildItem $stage -Recurse -File | Where-Object { $_.Name -match '^com\._1c\.|^netty-' })
    if ($leak.Count -gt 0) { throw "В публичную сборку попала библиотека 1С: $($leak.FullName -join ', ')" }
    Write-Host 'Проверено: библиотеки 1С в публичной сборке нет.'
}
