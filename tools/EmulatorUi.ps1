param(
    [Parameter(Mandatory)][string]$Serial,
    [ValidateSet('Capture', 'Tap', 'Scroll', 'Dump')][string]$Action = 'Dump',
    [string]$Text,
    [string]$Name = 'runtime',
    [int]$Width = 454,
    [int]$Height = 454,
    [int]$Count = 1
)
$ErrorActionPreference = 'Stop'
$adb = 'F:\Android\SDK\platform-tools\adb.exe'
$outputDir = Join-Path $PSScriptRoot '..\screenshots'
New-Item -ItemType Directory -Force $outputDir | Out-Null
function Dump-Ui {
    for ($attempt=0; $attempt -lt 3; $attempt++) {
        $result = & $adb -s $Serial shell uiautomator dump /data/local/tmp/wearfiles-ui.xml 2>&1
        if ($result -match 'dumped to') { break }
        if ($attempt -eq 2) { throw '界面导出失败，未使用过期 XML' }
        Start-Sleep -Milliseconds 500
    }
    $xmlPath = Join-Path $outputDir "$Serial-current.xml"
    & $adb -s $Serial pull /data/local/tmp/wearfiles-ui.xml $xmlPath 2>$null | Out-Null
    [xml]$document = Get-Content -LiteralPath $xmlPath
    return $document
}
switch ($Action) {
    'Tap' {
        $doc = Dump-Ui
        $node = $doc.SelectNodes('//node') | Where-Object { $_.text -eq $Text -or $_.'content-desc' -eq $Text } | Select-Object -First 1
        if (!$node) { throw "找不到可见项目：$Text" }
        $bounds = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        $x = [int]( ([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2 )
        $y = [int]( ([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2 )
        if ($y -lt 1 -or $y -ge $Height) { throw "项目不在可点击区域：$Text" }
        & $adb -s $Serial shell input tap $x $y
        Write-Output "已点击 $Text ($x,$y)"
    }
    'Scroll' {
        for ($i=0; $i -lt $Count; $i++) {
            & $adb -s $Serial shell input swipe ([int]($Width * 0.5)) ([int]($Height * 0.76)) ([int]($Width * 0.5)) ([int]($Height * 0.3)) 600
            Start-Sleep -Milliseconds 350
        }
    }
    'Capture' {
        # 先等待 UI Automator 的空闲检测，避免截到上一帧或尚未结束的滚动动画。
        $doc = Dump-Ui
        & $adb -s $Serial shell screencap -p /data/local/tmp/wearfiles-capture.png
        & $adb -s $Serial pull /data/local/tmp/wearfiles-capture.png (Join-Path $outputDir "$Name.png")
        Copy-Item -LiteralPath (Join-Path $outputDir "$Serial-current.xml") -Destination (Join-Path $outputDir "$Name.xml")
    }
    'Dump' { $doc = Dump-Ui }
}
if ($doc) {
    $doc.SelectNodes('//node') | Where-Object { $_.text -or $_.'content-desc' } | ForEach-Object {
        if ($_.text) { " $($_.text) $($_.bounds)" } else { " $($_.'content-desc') $($_.bounds)" }
    }
}
