"""The async client: query logs and traces; cancelling the task raises the SDK's CancelledError."""

from __future__ import annotations

import asyncio

import grow_signoz as sdk


async def main() -> None:
    async with sdk.AsyncSignozClient.from_env() as client:
        print(await client.health())
        logs = client.log_client(service_name="example-py-async")
        logs.send(sdk.LogInput("hello from the async client", severity=sdk.Severity.WARN))
        await logs.flush(10)
        record = await client.logs.wait_for_log("'hello from the async client'", timeout_s=60)
        print(f"found: {record.body} ({record.severity_text})")
        print("services:", await client.traces.list_services())
        task = asyncio.create_task(client.ping())
        try:
            print(f"ping: {await asyncio.wait_for(task, timeout=5):.1f} ms")
        except asyncio.TimeoutError:
            print("ping took longer than 5 s and was cancelled")


if __name__ == "__main__":
    asyncio.run(main())
