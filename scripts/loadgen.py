"""Minimal closed-loop load generator: N concurrent clients hammer one URL for D seconds.
Usage: python3 loadgen.py URL [concurrency] [seconds] [header=value ...]
Prints throughput and latency percentiles. Not a substitute for k6/Gatling in CI, but enough for
before/after comparisons on one machine (client and server share the CPU, so absolute numbers are pessimistic)."""
import asyncio, sys, time
import aiohttp


async def worker(session, url, headers, deadline, lat, codes):
    while time.perf_counter() < deadline:
        t0 = time.perf_counter()
        try:
            async with session.get(url, headers=headers) as r:
                await r.read()
                codes[r.status] = codes.get(r.status, 0) + 1
        except Exception as e:
            codes[type(e).__name__] = codes.get(type(e).__name__, 0) + 1
        lat.append((time.perf_counter() - t0) * 1000)


async def main(url, conc, secs, headers):
    lat, codes = [], {}
    conn = aiohttp.TCPConnector(limit=conc, force_close=False)
    async with aiohttp.ClientSession(connector=conn) as s:
        # warm-up: JIT, connection pools, caches
        warm = time.perf_counter() + 3
        await asyncio.gather(*(worker(s, url, headers, warm, [], {}) for _ in range(conc)))
        deadline = time.perf_counter() + secs
        await asyncio.gather(*(worker(s, url, headers, deadline, lat, codes) for _ in range(conc)))
    lat.sort()
    pct = lambda p: lat[min(len(lat) - 1, int(len(lat) * p))]
    print(f"{len(lat) / secs:8.0f} req/s | p50 {pct(.5):6.1f} ms | p95 {pct(.95):6.1f} ms | p99 {pct(.99):6.1f} ms | codes {codes}")


if __name__ == "__main__":
    u = sys.argv[1]
    c = int(sys.argv[2]) if len(sys.argv) > 2 else 64
    d = int(sys.argv[3]) if len(sys.argv) > 3 else 10
    hdrs = dict(a.split("=", 1) for a in sys.argv[4:])
    asyncio.run(main(u, c, d, hdrs))
