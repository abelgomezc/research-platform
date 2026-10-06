param($Port)
$max = 60
for ($i = 0; $i -lt $max; $i++) {
    try {
        $r = (New-Object net.webclient).DownloadString("http://localhost:$Port/actuator/health")
        if ($r -match 'UP') {
            Write-Host "    Backend respondiendo"
            exit 0
        }
    } catch {}
    Start-Sleep -Seconds 5
}
exit 1
