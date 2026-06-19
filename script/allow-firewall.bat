@echo off
REM ==============================================================================
REM SemiRisk -- Windows 防火墙放行脚本
REM ==============================================================================
REM 以管理员身份运行此脚本，放行 Docker 容器需要的端口
REM 让你的公网 IP (123.57.239.56) 可以被外部访问
REM ==============================================================================

echo ============================================
echo SemiRisk 防火墙端口放行
echo ============================================
echo.
echo 即将放行以下端口：
echo   80    - Nginx 前端
echo   8080  - API Gateway
echo   3306  - MySQL (不建议对外开放)
echo   6379  - Redis (不建议对外开放)
echo   8848  - Nacos 控制台
echo   9001  - MinIO 控制台
echo   9411  - Zipkin
echo   15672 - RabbitMQ 管理界面
echo.
echo 建议只开放 80 和 8080 端口给公网！
echo.
pause

REM 放行前端端口 80
netsh advfirewall firewall add rule name="SemiRisk-HTTP-80" dir=in action=allow protocol=TCP localport=80
if %errorlevel% equ 0 (echo [OK] 端口 80 (Nginx 前端) 已放行) else (echo [FAIL] 端口 80 放行失败)

REM 放行 Gateway 端口 8080
netsh advfirewall firewall add rule name="SemiRisk-Gateway-8080" dir=in action=allow protocol=TCP localport=8080
if %errorlevel% equ 0 (echo [OK] 端口 8080 (API Gateway) 已放行) else (echo [FAIL] 端口 8080 放行失败)

REM 放行 Nacos 控制台 8848（仅局域网）
netsh advfirewall firewall add rule name="SemiRisk-Nacos-8848" dir=in action=allow protocol=TCP localport=8848
if %errorlevel% equ 0 (echo [OK] 端口 8848 (Nacos) 已放行) else (echo [FAIL] 端口 8848 放行失败)

REM 放行 MinIO 控制台 9001（仅局域网）
netsh advfirewall firewall add rule name="SemiRisk-MinIO-9001" dir=in action=allow protocol=TCP localport=9001
if %errorlevel% equ 0 (echo [OK] 端口 9001 (MinIO) 已放行) else (echo [FAIL] 端口 9001 放行失败)

REM 放行 Zipkin 9411（仅局域网）
netsh advfirewall firewall add rule name="SemiRisk-Zipkin-9411" dir=in action=allow protocol=TCP localport=9411
if %errorlevel% equ 0 (echo [OK] 端口 9411 (Zipkin) 已放行) else (echo [FAIL] 端口 9411 放行失败)

REM 放行 RabbitMQ 15672（仅局域网）
netsh advfirewall firewall add rule name="SemiRisk-RabbitMQ-15672" dir=in action=allow protocol=TCP localport=15672
if %errorlevel% equ 0 (echo [OK] 端口 15672 (RabbitMQ) 已放行) else (echo [FAIL] 端口 15672 放行失败)

echo.
echo ============================================
echo 防火墙配置完成！
echo ============================================
echo.
echo 现在可以通过公网 IP 访问：
echo   前端:   http://123.57.239.56:80
echo   接口:   http://123.57.239.56:8080
echo   Swagger: http://123.57.239.56:8080/swagger-ui.html
echo.
echo 注意：请保持 SSH 隧道运行，否则 Docker 容器无法连接 VM 上的中间件！
echo.
pause
