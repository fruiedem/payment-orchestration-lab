# Concurrency tests

`Phase1ConcurrentOrderIntegrationTest` starts two HTTP orders together for the same SKU.
With two units and `DELAY_3S`, one transaction holds the product row lock while calling
the fake PG, so the other request takes roughly two PG delays. With one unit, exactly
one order succeeds and the database stock constraint remains non-negative.

Run it with:

```powershell
docker compose --env-file .env.example --profile test run --build --rm test gradle test --tests '*Phase1ConcurrentOrderIntegrationTest' --no-daemon
```

향후 deterministic concurrency test와 반복 가능한 경쟁 조건 재현 시나리오를 둔다. 현재 단계에서는 비어 있다.
