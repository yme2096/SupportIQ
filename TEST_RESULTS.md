# SupportIQ test results

**Updated:** 2026-11-06

## Latest checks

| Status | Check | Result |
|---|---|---|
| PASS | Backend Compose integration suite | Latest `docker compose --profile test run --rm backend-tests`: 27 tests, 0 failures, 0 errors, 0 skipped; migrations applied in isolated `phase7_test`. |
| PASS | Backend suite after Gemini resilience coverage | 27 tests, 0 failures, 0 errors, 0 skipped; 7 authentication/tenant/realtime, 5 document ingestion, 7 API client resilience, 3 existing fallback, and 5 Gemini provider integration tests. The existing tenant retrieval test also verifies that a perfect-match vector tagged with a different embedding model cannot be retrieved or cited. The Gemini API tests are mocked and do not prove live provider access. |
| PASS | Surefire resolution | Maven Surefire 3.5.4 resolved; the backend test run completed successfully. |
| PASS | Clean Docker image builds | Backend, worker, and frontend were clean-built in the preceding readiness check. After Gemini hardening and provider/model-scoped retrieval, backend and worker rebuilt successfully with Maven `BUILD SUCCESS`; the latest backend image is running and healthy. |
| PASS | Frontend image and runtime | Current production frontend image is running; `/`, `/supportiq-widget.js`, and `/chat/shopsmart` returned HTTP 200. |
| PASS | Separate-origin widget | The company widget was exercised on the separate local test website; anonymous chat, tenant-specific routing, and human handoff were covered in isolated data. |
| PASS | Default Compose runtime | PostgreSQL healthy, rebuilt backend health reports database UP, frontend running on port 5173. Optional worker startup was verified and stopped idle. |
| PASS | Dedicated worker | Isolated API upload was consumed by the Compose worker; test document reached READY with one chunk and no processing error. The temporary worker was stopped and removed. |
| PASS | Retrieval, fallback, and isolation | Isolated document retrieval returned a source reference; unsupported questions used fallback without citations; a cross-company request could not see the other company's source. |
| PASS | Gemini API boundary (mocked) | Server-side key header, 768-dimensional embeddings, grounded prompt with untrusted document content, bounded retries/timeouts, invalid key, 429, 500, network, malformed/empty response, and fallback were verified with mocked HTTP responses. |
| PASS | Existing data and volume | Public counts remain at 2 companies, 3 documents, 5 conversations, 11 messages, 3 tickets, 2 customer orders, 2 demo orders, 0 refunds, 2 subscriptions, and 2 refund policies. Existing Docker volume remains mounted. |
| PASS | Frontend unit/build/audit | Latest checks: 5 frontend tests passed, production build completed, and high-severity npm audit found zero vulnerabilities. |
| PASS | Windows lifecycle scripts | `CHECK_SUPPORTIQ.bat` passed health and route checks; `STOP_SUPPORTIQ.bat` preserved the volume; `START_SUPPORTIQ.bat` rebuilt/restarted the stack and reported ready. |
| PASS | Application logs | Recent service logs had no ERROR/FATAL level entries; browser console errors were absent. |
| PASS | Signed-in company/agent/customer browser walkthrough | In an isolated test schema, company admin onboarding, overview, knowledge manager, and widget setup rendered. Anonymous customer chat requested human help. Agent inbox opened the assigned thread, sent a visible reply, and saved a private note. Browser console had no errors. |
| PASS | Signed-in Super Admin browser walkthrough | Isolated Super Admin session verified overview, companies, subscriptions/plans, and audit views. Browser console had no errors. |
| PASS | Super Admin access restriction | A signed-in agent navigating to `/platform` returned to the agent workspace. |
| FAIL | Initial Docker recovery attempt | Docker Desktop logged access denied while renaming a stale secrets-engine socket reparse point. The first uncached Maven fetch also failed. Docker recovered, subsequent image builds passed, and Surefire resolved; the exact Windows handle owner was not identified. |
| BLOCKED | Live Gemini provider checks | No Gemini API key is configured in process environment or local `.env`; actual remote embedding and generation calls were not made. |
| BLOCKED | GitHub readiness | The inherited git root is `C:\` and its remote is an unrelated DSA repository. No SupportIQ files were staged or pushed. |
| UNTESTED | External services | No live payment, email/SMS, or Render services were contacted. |

See [FINAL_LOCAL_READINESS_REPORT.md](FINAL_LOCAL_READINESS_REPORT.md) for evidence, startup instructions, and limits.

Test output: `.local-readiness/nov5-final-java-build.log` and `.local-readiness/nov5-compose-build.log`. Non-fatal Mockito dynamic-agent and PDFBox fallback-font warnings may appear in the test output.
