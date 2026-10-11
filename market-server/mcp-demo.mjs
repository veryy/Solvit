/** Stateless read-only MCP endpoint for exercising real HTTPS connections. */
const versions = ['2025-11-25', '2025-06-18', '2025-03-26'];
const tools = [
  { name: 'echo', description: 'Repeat the supplied text; no external side effects.', inputSchema: { type: 'object', properties: { text: { type: 'string', maxLength: 4000 } }, required: ['text'], additionalProperties: false } },
  { name: 'server_time', description: 'Read the server current UTC time.', inputSchema: { type: 'object', properties: {}, additionalProperties: false } },
];
export async function handleMcpDemo(request, response) {
  const send = (status, value) => {
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(value === undefined ? undefined : JSON.stringify(value));
  };
  if (request.headers.origin && request.headers.origin !== 'https://solvit.207.57.126.73.sslip.io') { send(403, { error: 'Invalid origin' }); return; }
  if (request.headers['mcp-protocol-version'] && !versions.includes(request.headers['mcp-protocol-version'])) { send(400, { error: 'Unsupported protocol version' }); return; }
  if (request.method !== 'POST') { send(405, { error: 'Only POST is supported' }); return; }
  let rpc;
  try {
    const chunks = []; let size = 0;
    for await (const chunk of request) {
      size += chunk.length;
      if (size > 16 * 1024) { send(413, { error: 'Request too large' }); return; }
      chunks.push(chunk);
    }
    rpc = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch { send(400, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } }); return; }
  if (!rpc || rpc.jsonrpc !== '2.0' || typeof rpc.method !== 'string' || (rpc.id !== undefined && typeof rpc.id !== 'string' && typeof rpc.id !== 'number')) {
    send(400, { jsonrpc: '2.0', id: null, error: { code: -32600, message: 'Invalid request' } }); return;
  }
  if (rpc.id === undefined) { send(202); return; }
  const fail = (code, message) => send(200, { jsonrpc: '2.0', id: rpc.id, error: { code, message } });
  let result;
  switch (rpc.method) {
    case 'initialize': {
      const requested = rpc.params?.protocolVersion;
      if (!versions.includes(requested)) { fail(-32602, 'Unsupported protocol version'); return; }
      result = { protocolVersion: requested, capabilities: { tools: {} }, serverInfo: { name: 'Solvit read-only MCP demo', version: '1.0.0' } };
      break;
    }
    case 'ping': result = {}; break;
    case 'tools/list': result = { tools }; break;
    case 'tools/call': {
      const args = rpc.params?.arguments;
      if (!args || typeof args !== 'object' || Array.isArray(args)) { fail(-32602, 'Arguments must be an object'); return; }
      if (rpc.params.name === 'echo') {
        if (typeof args.text !== 'string' || args.text.length > 4000) { fail(-32602, 'Invalid echo text'); return; }
        result = { content: [{ type: 'text', text: args.text }] };
      } else if (rpc.params.name === 'server_time') result = { content: [{ type: 'text', text: new Date().toISOString() }] };
      else { fail(-32602, 'Unknown tool'); return; }
      break;
    }
    default: fail(-32601, 'Method not found'); return;
  }
  send(200, { jsonrpc: '2.0', id: rpc.id, result });
}
