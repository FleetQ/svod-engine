# Use Svod from your agent framework

Svod has no separate client SDK, and it doesn't need one. Every framework below talks to the
engine's standard **MCP endpoint** (streamable HTTP) and authenticates with a bearer token from
your config's `agents` list. Each section is a minimal copy-paste setup: it connects, loads the
tools, makes one `search` call and one `remember` call, and hands the tools to an agent.

This page is test-checked: the tool names, their required arguments, the URL and the
auth header on this page are compared with the live engine
(`engine/src/test/kotlin/dev/svod/engine/mcp/AgentFrameworkDocsConsistencyTest.kt`). The test
can't check the frameworks' own APIs, so each section says which package version it was written
against. If an import or class name has moved, check that framework's MCP docs.

## Before you start

- Run `svod-engine init` (see the README [Quickstart](../README.md#quickstart)). It writes a config
  with an agent and prints that agent's token.
- The MCP URL is `http://127.0.0.1:7518/mcp` (the default `mcpPort`).
- The token **is** the agent's identity: its `agentId` becomes the git commit author and its `role`
  (`WRITE` / `READ_ONLY`) gates every call. The snippets read it from the `SVOD_TOKEN` environment
  variable. Never paste a real token into code you commit.

```sh
export SVOD_TOKEN='<token from your config agents[]>'
```

## Tools used

- `search`: hybrid (keyword + semantic) search; needs `query`.
- `remember`: store a durable typed memory (`fact`, `preference`, `policy`, `episode`); needs `content`.
- `context_pack`: a token-budgeted, cited context block for a query. Your agent will usually call
  this itself once it has the tools.

The agent gets every tool the engine advertises; the "The agent interface" section of the
[README](../README.md) lists them all.

## LangGraph / LangChain (Python)

Written against `langchain-mcp-adapters` 0.1 and `langchain` 1.0 (`pip install langchain-mcp-adapters "langchain[openai]"`).

```python
import asyncio, os
from langchain_mcp_adapters.client import MultiServerMCPClient
from langchain.agents import create_agent  # LangGraph's older langgraph.prebuilt.create_react_agent works too

async def main():
    client = MultiServerMCPClient({
        "svod": {
            "transport": "streamable_http",
            "url": "http://127.0.0.1:7518/mcp",
            "headers": {"Authorization": f"Bearer {os.environ['SVOD_TOKEN']}"},
        }
    })
    tools = await client.get_tools()
    tools_by_name = {t.name: t for t in tools}

    print(await tools_by_name["search"].ainvoke({"query": "release checklist"}))
    print(await tools_by_name["remember"].ainvoke({"content": "Releases ship on Tuesdays.", "type": "fact"}))

    agent = create_agent("openai:gpt-4.1-mini", tools)
    result = await agent.ainvoke({"messages": [{"role": "user", "content": "What do we know about releases?"}]})
    print(result["messages"][-1].content)

asyncio.run(main())
```

## OpenAI Agents SDK (Python)

Written against `openai-agents` 0.3 (`pip install openai-agents`).

```python
import asyncio, os
from agents import Agent, Runner
from agents.mcp import MCPServerStreamableHttp

async def main():
    async with MCPServerStreamableHttp(
        name="svod",
        params={
            "url": "http://127.0.0.1:7518/mcp",
            "headers": {"Authorization": f"Bearer {os.environ['SVOD_TOKEN']}"},
        },
    ) as server:
        print([t.name for t in await server.list_tools()])
        print(await server.call_tool("search", {"query": "release checklist"}))
        print(await server.call_tool("remember", {"content": "Releases ship on Tuesdays.", "type": "fact"}))

        agent = Agent(name="assistant", instructions="Use Svod as your long-term memory.", mcp_servers=[server])
        result = await Runner.run(agent, "What do we know about releases?")
        print(result.final_output)

asyncio.run(main())
```

## Vercel AI SDK (TypeScript)

Written against `ai` 6 and `@ai-sdk/mcp` 1 (`npm i ai @ai-sdk/mcp @ai-sdk/openai`). On AI SDK 5 the
same client is `experimental_createMCPClient` from `ai`.

```ts
import { createMCPClient } from '@ai-sdk/mcp';
import { generateText, stepCountIs } from 'ai';
import { openai } from '@ai-sdk/openai';

const client = await createMCPClient({
  transport: {
    type: 'http',
    url: 'http://127.0.0.1:7518/mcp',
    headers: { Authorization: `Bearer ${process.env.SVOD_TOKEN}` },
  },
});

try {
  const tools = await client.tools();
  const opts = { toolCallId: 'manual', messages: [] };
  console.log(await tools.search.execute({ query: 'release checklist' }, opts));
  console.log(await tools.remember.execute({ content: 'Releases ship on Tuesdays.', type: 'fact' }, opts));

  const { text } = await generateText({
    model: openai('gpt-4.1-mini'),
    tools,
    stopWhen: stepCountIs(5),
    prompt: 'What do we know about releases?',
  });
  console.log(text);
} finally {
  await client.close();
}
```

## Mastra (TypeScript)

Written against `@mastra/mcp` 0.13 and `@mastra/core` 0.20 (`npm i @mastra/mcp @mastra/core @ai-sdk/openai`).
Mastra prefixes each tool with the server name, so `search` is `svod_search`.

```ts
import { MCPClient } from '@mastra/mcp';
import { Agent } from '@mastra/core/agent';
import { openai } from '@ai-sdk/openai';

const mcp = new MCPClient({
  servers: {
    svod: {
      url: new URL('http://127.0.0.1:7518/mcp'),
      requestInit: { headers: { Authorization: `Bearer ${process.env.SVOD_TOKEN}` } },
    },
  },
});

const tools = await mcp.getTools();
console.log(await tools['svod_search'].execute({ context: { query: 'release checklist' } }));
console.log(await tools['svod_remember'].execute({ context: { content: 'Releases ship on Tuesdays.', type: 'fact' } }));

const agent = new Agent({
  name: 'assistant',
  instructions: 'Use Svod as your long-term memory.',
  model: openai('gpt-4.1-mini'),
  tools,
});
console.log((await agent.generate('What do we know about releases?')).text);
await mcp.disconnect();
```

## Notes

- **TLS:** plain HTTP is for loopback only. For any non-loopback access configure `mcpTls` and use
  `https://` with your own host name.
- **Roles:** a `READ_ONLY` token can search and read, but `remember` (and every other write) returns
  `denied`.
- **Errors:** if a tool returns `rate_limited` or `denied`, stop and report it; don't retry in a loop.
- **System prompt:** give the agent the README's
  [ready-to-paste system prompt](../README.md#ready-to-paste-system-prompt) so it recalls before
  answering and writes durable facts as it learns them.
