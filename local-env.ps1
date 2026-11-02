# Project-local environment for the backend repository.
# Reuses the pinned JDK 25 / Maven / Node installations kept in the
# ai-runtime-governance docs repository. Dot-source from this folder:
#   . .\local-env.ps1

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$sharedEnv = Join-Path $workspaceRoot 'ai-runtime-governance\local-env\env.ps1'
if (-not (Test-Path -LiteralPath $sharedEnv)) {
    throw "Shared environment not found: $sharedEnv"
}
. $sharedEnv
