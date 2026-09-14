$pong = [byte[]]@(0x04, 0x01, 0x00, 0x00, 0x00)
Write-Output ("pong null? " + ($null -eq $pong) + " len=" + $pong.Length)
try {
  $seg = [System.ArraySegment[byte]]::new($pong)
  Write-Output ("seg count=" + $seg.Count)
} catch { Write-Output ("ctor err: " + $_.Exception.Message) }
$buf = New-Object byte[] 4
Write-Output ("buf null? " + ($null -eq $buf) + " len=" + $buf.Length)
$seg2 = [System.ArraySegment[byte]]::new($buf)
Write-Output ("seg2 count=" + $seg2.Count)
