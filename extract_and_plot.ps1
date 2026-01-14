# TCP Reno CWND Visualization Script
# Usage: Run TestRun first, then execute this script

Write-Host "=== TCP Reno CWND Data Extraction and Visualization ===" -ForegroundColor Cyan

# Check if tcp_output.log exists
if (Test-Path "tcp_output.log") {
    Write-Host "[OK] Found log file: tcp_output.log" -ForegroundColor Green
    
    # Extract CWND data
    Write-Host "`nExtracting CWND data..." -ForegroundColor Yellow
    Select-String -Path tcp_output.log -Pattern "CWND_LOG" | ForEach-Object { $_.Line } | Out-File -FilePath cwnd_data.txt -Encoding UTF8
    
    # Check extraction result
    $lineCount = (Get-Content cwnd_data.txt).Count
    if ($lineCount -gt 0) {
        Write-Host "[OK] Successfully extracted $lineCount CWND records" -ForegroundColor Green
        
        # Show preview
        Write-Host "`nFirst 5 records:" -ForegroundColor Cyan
        Get-Content cwnd_data.txt | Select-Object -First 5
        
        # Run Python visualization
        Write-Host "`nGenerating visualization chart..." -ForegroundColor Yellow
        python plot_cwnd.py
        
        if ($LASTEXITCODE -eq 0) {
            Write-Host "`n[OK] Chart generated successfully!" -ForegroundColor Green
        } else {
            Write-Host "`n[ERROR] Chart generation failed, check Python environment" -ForegroundColor Red
        }
    } else {
        Write-Host "[ERROR] No CWND_LOG data found" -ForegroundColor Red
        Write-Host "Hint: Check if tcp_output.log contains CWND_LOG lines" -ForegroundColor Yellow
    }
} else {
    Write-Host "[ERROR] File not found: tcp_output.log" -ForegroundColor Red
    Write-Host "`nPlease run TestRun.java in IDE and redirect output to tcp_output.log" -ForegroundColor Yellow
    Write-Host "Or manually copy console output to tcp_output.log" -ForegroundColor Yellow
}

Write-Host "`n=== Done ===" -ForegroundColor Cyan
