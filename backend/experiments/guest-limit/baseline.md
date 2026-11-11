# Guest quota: before-change HTTP measurement

Measured at: 2026-10-02 01:41:06 UTC (10:41:06 Asia/Seoul).

## Scope

- Production `GuestReviewController` and `GuestReviewService`, served by embedded Tomcat on a random loopback port.
- Actual HTTP requests and actual Redis at `127.0.0.1:16379`.
- One shared guest cookie, with a unique token for this run.
- AI is an in-process controlled fake. Both models fail for one request. Two other requests wait until measurement ends, then succeed.
- Database repositories and prompt lookup are mocks. Security filters, the full application, external AI HTTP and load/performance testing are outside this experiment.
- No production source was changed. This is one deterministic defect reproduction, not a failure-rate estimate.

## Measured results

| Step / metric | Result |
|---|---:|
| Initial requests waiting in AI | 3 |
| Initial Redis counter | 3 |
| Excess requests | 10 |
| Excess requests rejected with HTTP 403 | 10 |
| Redis counter after excess requests | 13 |
| AI invocations after excess requests | 3 |
| Failed original request HTTP status (both models failed) | 502 |
| Redis counter after its refund | 12 |
| New request HTTP status | 403 |
| Redis counter after new request | 13 |
| Total AI invocations (3 primary + 1 fallback) | 4 |
| Remaining original requests completed with HTTP 200 | 2 |

The new request should have been allowed after one of the three reservations failed. It was rejected because the ten previously rejected requests had also incremented the counter.

## Verification after the change

This table preserves a measurement of the old implementation. The original baseline test is not part of the current tree, so the old behavior cannot be rerun from this commit.

With Java 25 and the dedicated Redis and MariaDB test containers available on ports 16379 and 13307, run from `backend`:

```powershell
./gradlew.bat test --tests 'idu.sba.backend.domain.guest.service.GuestReviewHttpTest' --rerun-tasks
```

The current test repeats the HTTP sequence with the same guest cookie, ten excess requests and a controlled AI failure against both quota stores. It checks that the counter stays at 3 after rejections, falls to 2 after the failed request and allows the next request with HTTP 200. This verifies the defect fix; it is not an API latency or throughput measurement.
