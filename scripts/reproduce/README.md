# Reproduction scripts

Start PostgreSQL and the application, then run:

```powershell
.\scripts\reproduce\phase1-slow-pg-lock.ps1
```

The script submits two simultaneous orders using a 10-second fake PG delay and samples
Hikari active/pending connections plus PostgreSQL lock waiters every 250 ms. Raw CSV
evidence is written under `build/phase1-evidence/`.

To deliberately exceed the five-connection pool and observe pending acquisition or
connection timeout, run six same-product requests with a shorter delay:

```powershell
.\scripts\reproduce\phase1-slow-pg-lock.ps1 -GatewayMode DELAY_3S -ConcurrentRequests 6
```

향후 실패 및 경쟁 조건을 반복 재현하는 스크립트를 둔다. 현재 단계에서는 비어 있다.
