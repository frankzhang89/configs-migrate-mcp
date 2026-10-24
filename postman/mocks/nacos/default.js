// Contract: Nacos 2.x /v1/auth/login and /v1/cs/configs HTTP APIs.
// State is isolated by the full namespace/group/dataId identity.
const http = require('http');
http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const input = req.method === 'POST' ? req.body : Object.fromEntries(url.searchParams);
  // interceptRequests parses form bodies; normalize strings for compatibility.
  const args = typeof input === 'string' ? Object.fromEntries(new URLSearchParams(input)) : (input || {});
  const reply = (status, body, json = false) => {
    res.writeHead(status, { 'Content-Type': (json ? 'application/json' : 'text/plain') + ';charset=UTF-8' });
    res.end(json ? JSON.stringify(body) : body);
  };
  // @endpoint POST /v1/auth/login
  if (req.method === 'POST' && url.pathname === '/v1/auth/login') {
    if (args.username !== 'user' || args.password !== 'password') return reply(403, 'invalid credentials');
    return reply(200, { accessToken: 'simulation-token', tokenTtl: 18000 }, true);
  }
  // @endpoint GET /v1/cs/configs
  // @endpoint POST /v1/cs/configs
  if (url.pathname === '/v1/cs/configs') {
    if (args.accessToken !== 'simulation-token') return reply(403, 'invalid token');
    if (!args.dataId || !args.group) return reply(400, 'dataId and group required');
    const key = 'nacos.config.' + JSON.stringify([args.tenant || '', args.group, args.dataId]);
    if (req.method === 'GET') {
      const value = await pm.state.get(key);
      return value == null ? reply(404, 'not found') : reply(200, value.content);
    }
    if (req.method === 'POST') {
      if (typeof args.content !== 'string' || !['properties', 'yaml'].includes(args.type)) return reply(400, 'content/type required');
      await pm.state.set(key, { content: args.content, type: args.type });
      return reply(200, 'true');
    }
  }
  return reply(501, 'Endpoint not modeled');
}).listen(process.env.PORT || 14902);
