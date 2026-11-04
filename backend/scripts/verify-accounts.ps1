# 账号创建层级 + MySQL 改造的端到端验收（临时脚本，跑完即删）
$ErrorActionPreference = 'Continue'
$base = if ($env:CHAINSIP_BASE_URL) { $env:CHAINSIP_BASE_URL.TrimEnd('/') + '/api' } else { 'http://127.0.0.1:8080/api' }
$adminUser = if ($env:CHAINSIP_ADMIN_USERNAME) { $env:CHAINSIP_ADMIN_USERNAME } else { 'admin' }
$adminPass = if ($env:CHAINSIP_ADMIN_PASSWORD) { $env:CHAINSIP_ADMIN_PASSWORD } else { 'ChangeMe_123456' }
$bureauUser = if ($env:CHAINSIP_BUREAU_USERNAME) { $env:CHAINSIP_BUREAU_USERNAME } else { 'bureau_demo' }
$bureauPass = if ($env:CHAINSIP_BUREAU_PASSWORD) { $env:CHAINSIP_BUREAU_PASSWORD } else { 'ChangeMe_123456' }
$sponsorUserLogin = if ($env:CHAINSIP_SPONSOR_USERNAME) { $env:CHAINSIP_SPONSOR_USERNAME } else { 'sponsor_demo' }
$sponsorPass = if ($env:CHAINSIP_SPONSOR_PASSWORD) { $env:CHAINSIP_SPONSOR_PASSWORD } else { 'ChangeMe_123456' }
$newAccountPass = if ($env:CHAINSIP_TEST_PASSWORD) { $env:CHAINSIP_TEST_PASSWORD } else { 'ChangeMe_123456' }
$ok = 0; $fail = 0

function Step($name, $block) {
    Write-Output "`n### $name"
    try { & $block } catch {
        $script:fail++
        $msg = $_.Exception.Message
        if ($_.ErrorDetails) { $msg = $_.ErrorDetails.Message }
        Write-Output "    FAIL: $msg"
    }
}
function Login($u, $p) {
    $r = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType 'application/json' -Body (@{ username = $u; password = $p } | ConvertTo-Json)
    return $r.data.token
}
function PostJson($path, $token, $body) {
    $json = if ($null -eq $body) { '{}' } else { $body | ConvertTo-Json -Depth 6 }
    return Invoke-RestMethod -Uri "$base$path" -Method Post -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body $json
}
function GetJson($path, $token) {
    return Invoke-RestMethod -Uri "$base$path" -Headers @{ Authorization = "Bearer $token" }
}
function ExpectHttp($expected, $block) {
    try { & $block; Write-Output "    (未按预期失败)"; $script:fail++ }
    catch {
        $code = $_.Exception.Response.StatusCode.value__
        if ($code -eq $expected) { $script:ok++; Write-Output "    OK 返回 $code（符合预期）" }
        else { $script:fail++; Write-Output "    FAIL 期望 $expected 实际 $code ：$($_.Exception.Message)" }
    }
}

$admin = Login $adminUser $adminPass
$bureau = Login $bureauUser $bureauPass
$sponsor = Login $sponsorUserLogin $sponsorPass
Write-Output "### 三个角色登录成功"

Step '1) /api/chain/status 新字段（adminAddress / adminOnChainRole）' {
    $s = (GetJson '/chain/status' $admin).data
    Write-Output "    ready=$($s.ready) coreDeployed=$($s.coreDeployed) transferDeployed=$($s.transferDeployed)"
    Write-Output "    adminAddress=$($s.adminAddress)  adminOnChainRole=$($s.adminOnChainRole)"
    if ($s.adminAddress) { $script:ok++; Write-Output '    OK adminAddress 已返回' } else { $script:fail++; Write-Output '    FAIL 缺 adminAddress' }
    $script:ok++
}

$stamp = (Get-Date -Format 'HHmmss')
$sponsorUser = "sponsor_$stamp"
$employeeUser = "employee_$stamp"

Step "2) 社保局创建缴费单位账号（$sponsorUser）" {
    $r = PostJson '/bureau/accounts' $bureau @{ username = $sponsorUser; password = $newAccountPass; displayName = '乙公司'; name = '乙公司'; city = 'A市' }
    Write-Output "    code=$($r.code) 机构编码=$($r.data.institutionCode) 链上地址=$($r.data.chainAddress)"
    Write-Output "    hint=$($r.data.hint)"
    if ($r.code -eq 0) { $script:ok++ } else { $script:fail++ }
}

Step '3) 列表：社保局看到下辖缴费单位' {
    $list = (GetJson '/bureau/accounts' $bureau).data
    Write-Output "    共 $($list.Count) 个；含新账号: $([bool]($list | Where-Object { $_.username -eq $sponsorUser }))"
    if ($list.Count -ge 1) { $script:ok++ } else { $script:fail++ }
}

Step "4) 缴费单位创建职工账号（$employeeUser）" {
    $r = PostJson '/sponsor/accounts' $sponsor @{ username = $employeeUser; password = $newAccountPass; displayName = '李四' }
    Write-Output "    code=$($r.code) hint=$($r.data.hint)"
    if ($r.code -eq 0) { $script:ok++ } else { $script:fail++ }
}

Step '5) 列表：缴费单位看到本单位职工账号' {
    $list = (GetJson '/sponsor/accounts' $sponsor).data
    Write-Output "    共 $($list.Count) 个；含新账号: $([bool]($list | Where-Object { $_.username -eq $employeeUser }))"
    if ($list.Count -ge 1) { $script:ok++ } else { $script:fail++ }
}

Step '6) 越权：社保局调 ADMIN 专属接口（期望 403）' {
    ExpectHttp 403 { PostJson '/admin/accounts' $bureau @{ username = "x_$stamp"; password = $newAccountPass; role = 'ADMIN' } }
}

Step '7) 越权：缴费单位调社保局接口（期望 403）' {
    ExpectHttp 403 { PostJson '/bureau/accounts' $sponsor @{ username = "y_$stamp"; password = $newAccountPass; displayName = '越权' } }
}

Step '8) 用户名重复（期望 400）' {
    ExpectHttp 400 { PostJson '/bureau/accounts' $bureau @{ username = $sponsorUser; password = $newAccountPass; displayName = '重复' } }
}

Step '9) 管理员仍可创建任意角色（创建监管账号）' {
    $r = PostJson '/admin/accounts' $admin @{ username = "reg_$stamp"; password = $newAccountPass; role = 'REGULATOR'; displayName = '审计员' }
    Write-Output "    code=$($r.code) 机构编码=$($r.data.institutionCode) 链上地址=$($r.data.chainAddress)"
    if ($r.code -eq 0) { $script:ok++ } else { $script:fail++ }
}

Step '10) 新账号可登录' {
    $t = Login $sponsorUser $newAccountPass
    if ($t) { $script:ok++; Write-Output '    OK 新缴费单位账号登录成功' } else { $script:fail++ }
}

Write-Output "`n============ SUMMARY: ok=$ok fail=$fail ============"
