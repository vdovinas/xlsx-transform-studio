param([switch]$Portable)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$localCargo = "$PSScriptRoot/tools/rust/cargo/bin"
if (Test-Path -LiteralPath "$localCargo/cargo.exe") {
    $env:CARGO_HOME = "$PSScriptRoot/tools/rust/cargo"
    $env:RUSTUP_HOME = "$PSScriptRoot/tools/rust/rustup"
    $env:PATH = "$localCargo;$env:PATH"
}
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw 'Cargo not found. Install Rust or restore tools/rust before packaging.'
}
& mvn -B -ntp "-Dmaven.repo.local=$PSScriptRoot/tools/m2" spotless:check clean verify
if ($LASTEXITCODE -ne 0) { throw 'Java checks failed' }
$runtimePath = [IO.Path]::GetFullPath("$PSScriptRoot/tools/engine-runtime")
if (Test-Path -LiteralPath $runtimePath) {
    if ((Resolve-Path -LiteralPath $runtimePath).Path -ne $runtimePath) { throw 'Unexpected Java runtime path' }
    Remove-Item -LiteralPath $runtimePath -Recurse -Force
}
& jlink --add-modules java.se --strip-debug --compress=2 --no-header-files --no-man-pages --output $runtimePath
if ($LASTEXITCODE -ne 0) { throw 'Java runtime preparation failed' }
New-Item -ItemType Directory -Force tools/engine | Out-Null
Copy-Item -LiteralPath target/xlsx-desktop.jar -Destination tools/engine/xlsx-desktop.jar
$engineRuntimePath = [IO.Path]::GetFullPath("$PSScriptRoot/tools/engine/runtime")
$existingRuntime = Get-Item -LiteralPath $engineRuntimePath -Force -ErrorAction SilentlyContinue
if ($existingRuntime) {
    if ($existingRuntime.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        [IO.Directory]::Delete($engineRuntimePath)
    } else {
        if ((Resolve-Path -LiteralPath $engineRuntimePath).Path -ne $engineRuntimePath) { throw 'Unexpected engine runtime path' }
        Remove-Item -LiteralPath $engineRuntimePath -Recurse -Force
    }
}
Copy-Item -LiteralPath $runtimePath -Destination $engineRuntimePath -Recurse
if (-not (Test-Path -LiteralPath "$engineRuntimePath/bin/java.exe")) { throw 'Engine Java runtime is missing' }
& npm --cache ./tools/npm-cache ci --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { throw 'Desktop dependency installation failed' }
& npm test
if ($LASTEXITCODE -ne 0) { throw 'Desktop checks failed' }
& npm run test:ui
if ($LASTEXITCODE -ne 0) { throw 'Interface checks failed' }
& cargo test --manifest-path src-tauri/Cargo.toml
if ($LASTEXITCODE -ne 0) { throw 'Native checks failed' }
if ($Portable) { & npm run portable } else { & npm run package }
if ($LASTEXITCODE -ne 0) { throw 'Desktop packaging failed' }

New-Item -ItemType Directory -Force release | Out-Null
if ($Portable) {
    New-Item -ItemType Directory -Force release/portable | Out-Null
    Copy-Item src-tauri/target/release/xlsx-studio.exe release/portable/
    New-Item -ItemType Directory -Force release/portable/engine | Out-Null
    Copy-Item tools/engine/* release/portable/engine/ -Recurse -Force
    Copy-Item src/main/resources/profiles.json release/portable/
} else {
    Copy-Item src-tauri/target/release/bundle/nsis/*.exe release/
}
