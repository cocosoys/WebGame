Write-Output ("PSVersion: " + $PSVersionTable.PSVersion)
$x1 = New-Object byte[] 4
Write-Output ("x1 null? " + ($null -eq $x1))
$x2 = New-Object byte[] 4096
Write-Output ("x2 null? " + ($null -eq $x2))
$x3 = New-Object 'System.Byte[]' 4096
Write-Output ("x3 null? " + ($null -eq $x3))
$x4 = [byte[]]::new(4096)
Write-Output ("x4 null? " + ($null -eq $x4))
$s = New-Object System.IO.MemoryStream
$null = $s.Write([byte[]]@(1,2,3), 0, 3)
$buf = [byte[]]::new(16)
$n = $s.Read($buf, 0, $buf.Length)
Write-Output ("read n=" + $n)
