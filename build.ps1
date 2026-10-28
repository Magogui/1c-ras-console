# Сборка консоли администрирования RAS.
# Требуется JDK 17+ с JavaFX (например, Axiom JDK Full из комплекта 1С).
# Использование: powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

function Find-Jdk {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { return $env:JAVA_HOME }
    $axiom = Get-ChildItem 'C:\Program Files\1C\1CE\components' -Directory -Filter 'axiom-jdk-full-*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($axiom) { return $axiom.FullName }
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { return (Split-Path (Split-Path $javac.Source)) }
    throw 'JDK не найден. Укажите JAVA_HOME.'
}

$jdk = Find-Jdk
Write-Host "JDK: $jdk"

# 1. Библиотеки RAS API: извлекаем из архива 1С, если папки lib ещё нет
$lib = Join-Path $root 'lib'
if (-not (Test-Path "$lib\com._1c.v8.ibis.admin-*.jar")) {
    $zip = Get-ChildItem $root -Filter '1cits_exe_java-api-*.zip' | Select-Object -First 1
    if (-not $zip) { throw 'Нет папки lib и архива 1cits_exe_java-api-*.zip' }
    Write-Host "Извлечение библиотек из $($zip.Name)"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $outer = [IO.Compression.ZipFile]::OpenRead($zip.FullName)
    try {
        $mid = New-Object IO.Compression.ZipArchive(($outer.Entries | Where-Object { $_.Name -like 'java-api-*.zip' }).Open())
        $inner = New-Object IO.Compression.ZipArchive(($mid.Entries | Where-Object { $_.Name -like 'com._1c.v8.ibis.admin-*.zip' }).Open())
        New-Item -ItemType Directory -Force $lib | Out-Null
        foreach ($e in $inner.Entries) {
            if ($e.FullName -match '/lib/[^/]+\.jar$') {
                [IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $lib $e.Name), $true)
            }
        }
    } finally { $outer.Dispose() }
}

# 2. Компиляция
$out = Join-Path $root 'out'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\classes" | Out-Null
# Путь проекта может содержать кириллицу: в @-файл javac пишем только относительные (ASCII) пути
$sources = Get-ChildItem "$root\src" -Recurse -Filter *.java |
    ForEach-Object { $_.FullName.Substring($root.Length + 1) -replace '\\', '/' }
$sources | Set-Content -Encoding ASCII 'out\sources.txt'
# Классы — для Java 17. С --release javac видит только стандартные модули, а JavaFX доступна, лишь
# когда версия JDK и --release совпадают; на более новом JDK собираем через --source/--target.
$javacVersion = (& "$jdk\bin\javac.exe" -version 2>&1 | Out-String)
$major = [int]([regex]::Match($javacVersion, 'javac (\d+)').Groups[1].Value)
$target = if ($major -eq 17) { @('--release', '17') } else { @('--source', '17', '--target', '17', '-Xlint:-options') }
& "$jdk\bin\javac.exe" @target -encoding UTF-8 -cp 'lib/*' -d 'out/classes' '@out/sources.txt'
if ($LASTEXITCODE -ne 0) { throw 'Ошибка компиляции' }

# Ресурсы (всё, кроме .java)
Get-ChildItem "$root\src" -Recurse -File | Where-Object { $_.Extension -ne '.java' } | ForEach-Object {
    $rel = $_.FullName.Substring("$root\src\".Length)
    $dst = Join-Path "$out\classes" $rel
    New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
    Copy-Item $_.FullName $dst
}

# 3. Jar с манифестом
$dist = Join-Path $root 'dist\RasConsole'
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force "$dist\lib" | Out-Null
Copy-Item "$lib\*.jar" "$dist\lib"
$cp = (Get-ChildItem "$dist\lib" -Filter *.jar | ForEach-Object { "lib/$($_.Name)" }) -join ' '
$manifest = Join-Path $out 'MANIFEST.MF'
# Строки манифеста не длиннее 72 байт: переносим с пробелом в начале продолжения
$cpLine = "Class-Path: $cp"
$lines = @('Manifest-Version: 1.0', 'Main-Class: ru.rasconsole.Boot')
$first = $true
while ($cpLine.Length -gt 0) {
    $n = if ($first) { 70 } else { 69 }
    $chunk = $cpLine.Substring(0, [Math]::Min($n, $cpLine.Length))
    $lines += $(if ($first) { $chunk } else { ' ' + $chunk })
    $cpLine = $cpLine.Substring($chunk.Length)
    $first = $false
}
($lines -join "`r`n") + "`r`n" | Set-Content -NoNewline -Encoding ASCII $manifest
& "$jdk\bin\jar.exe" --create --file "$dist\ras-console.jar" --manifest $manifest -C "$out\classes" .
if ($LASTEXITCODE -ne 0) { throw 'Ошибка сборки jar' }

Copy-Item "$root\run.cmd" $dist
Copy-Item "$root\rascli.cmd" $dist
Write-Host "Готово: $dist"
